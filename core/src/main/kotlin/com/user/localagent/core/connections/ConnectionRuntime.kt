package com.user.localagent.core.connections

import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest

data class ConnectionAccess(val principal:String,val generation:Long,val readAllowed:Boolean,val writeAllowed:Boolean)
data class ApiResponse(val status:Int,val body:String,val contentType:String="application/json",val etag:String?=null)
data class MutationRecord(val requestHash:String,val remoteId:String?,val phase:String,val requestedAtMillis:Long?=null)
data class ConnectionOutcome(val success:Boolean,val state:VerificationState,val data:String,val code:String)

interface ConnectionHost {
    suspend fun access(service:ConnectedService):ConnectionAccess?
    suspend fun send(service:ConnectedService,request:ApiRequest):ApiResponse
    /** Production OAuth hosts must bind token selection to this exact account/generation, not reselect a current account. */
    suspend fun sendAuthorized(service:ConnectedService,request:ApiRequest,expectedAccess:ConnectionAccess):ApiResponse {
        check(access(service)==expectedAccess){"connection_changed"}
        return send(service,request)
    }
    fun mutation(key:String):MutationRecord?
    fun saveMutation(key:String,record:MutationRecord)
    fun reserveRequest(service:ConnectedService):Boolean
    fun now():Long=System.currentTimeMillis()
    suspend fun approveGmailSend(preview:GmailSendPreview):Boolean=false
}

/** One bounded execution lane. Network permissions are rechecked before every request and cache hit. */
class ConnectionRuntime(private val host:ConnectionHost) {
    private val lane=Mutex()
    private data class Cached(val at:Long,val body:String,val etag:String)
    private val cache=linkedMapOf<String,Cached>()
    fun clearCache(){synchronized(cache){cache.clear()}}
    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    suspend fun execute(call:ToolCall,approveGmailSend:(suspend(GmailSendPreview)->Boolean)?=null):ConnectionOutcome=executeBound(call,approveGmailSend,null)
    suspend fun executeApprovedCalendar(call:ToolCall,approval:ApprovedCalendarTarget):ConnectionOutcome {
        require(call.name==CalendarTargetIntegrity.CREATE);return executeBound(call,null,approval)
    }
    private suspend fun executeBound(call:ToolCall,approveGmailSend:(suspend(GmailSendPreview)->Boolean)?,calendarApproval:ApprovedCalendarTarget?):ConnectionOutcome=lane.withLock {
        val plan=ConnectionRequests.build(call)
        val access=host.access(plan.service)?:return@withLock fail("connection_required")
        if(!access.readAllowed || (plan.writes&&!access.writeAllowed))return@withLock fail("scope_required")
        if(call.name==CalendarTargetIntegrity.CREATE){
            if(calendarApproval==null)return@withLock fail("calendar_target_approval_required")
            if(!CalendarTargetIntegrity.matches(calendarApproval,call,access))return@withLock fail("calendar_target_changed_since_approval")
        }
        val principalKey="${plan.service.id}:${access.principal}:${access.generation}"
        val fingerprint=digest(call.name+"\n"+JsonObject(call.arguments.toSortedMap()).toString())
        // Revocation/re-authorization changes cache authority, never the identity of an uncertain write.
        val operation=digest(plan.service.id+":"+access.principal+"\n"+call.id)
        suspend fun send(request:ApiRequest):ApiResponse {
            val current=host.access(plan.service)?:error("connection_revoked")
            check(current==access && current.readAllowed && (!plan.writes||current.writeAllowed)){"connection_changed"}
            check(host.reserveRequest(plan.service)){"daily_budget_exhausted"}
            return host.sendAuthorized(plan.service,request,access).also {require(it.body.toByteArray().size<=2*1024*1024){"response_too_large"}}
        }
        try {
            if(call.name in GmailSendContract.names)return@withLock GmailSendRuntime.execute(host,call,access,operation,::send,
                approveGmailSend?:{preview->host.approveGmailSend(preview)}).also {clearCache()}
            if(!plan.writes) {
                val cacheKey=digest(principalKey+fingerprint)
                val cached=synchronized(cache){cache.remove(cacheKey)?.also {cache[cacheKey]=it}}
                val candidate=cached?.takeIf {host.now()-it.at in 0..30_000 && plan.first.method=="GET"}
                val request=if(candidate!=null)plan.first.copy(extraHeaders=plan.first.extraHeaders+("If-None-Match" to candidate.etag))else plan.first
                val response=send(request)
                if(host.access(plan.service)!=access)return@withLock fail("connection_changed")
                if(response.status==304&&candidate!=null)return@withLock readOutcome(call,candidate.body,true)
                if(response.status !in 200..299)return@withLock httpFailure(response.status,false)
                if(call.name=="read_drive_text_file")require(response.contentType.startsWith("text/")||response.contentType.contains("json")){"not_a_text_file"}
                // Bot updates are an externally changing stream; never cache or acknowledge them.
                if(plan.service!=ConnectedService.TELEGRAM && plan.first.method=="GET" && !response.etag.isNullOrBlank() && response.body.toByteArray().size<=256*1024)synchronized(cache){
                    cache[cacheKey]=Cached(host.now(),response.body,response.etag)
                    while(cache.size>64||cache.values.sumOf {it.body.toByteArray().size}>1024*1024)cache.remove(cache.keys.first())
                }
                return@withLock readOutcome(call,response.body,false)
            }
            val prior=host.mutation(operation)
            if(prior!=null && prior.requestHash!=fingerprint)return@withLock fail("operation_key_conflict")
            var remoteId=prior?.remoteId
            if(prior==null) {
                host.saveMutation(operation,MutationRecord(fingerprint,plan.knownId,"request_started"))
                val response=send(plan.first)
                if(response.status !in 200..299) {
                    if(response.status==409&&plan.knownId!=null)remoteId=plan.knownId
                    else return@withLock httpFailure(response.status,true)
                }else {
                    val returned=plan.createdIdField?.let {Json.parseToJsonElement(response.body).jsonObject[it]}
                    if(plan.knownId!=null&&plan.createdIdField!=null&&(returned !is JsonPrimitive||!returned.isString||returned.content!=plan.knownId))return@withLock uncertain("created_id_mismatch",plan.knownId)
                    remoteId=plan.knownId?:returned?.jsonPrimitive?.content
                }
                if(remoteId.isNullOrBlank())return@withLock uncertain("missing_remote_id")
                ConnectionRequests.key(remoteId!!)
                host.saveMutation(operation,MutationRecord(fingerprint,remoteId,"created"))
                if(plan.populate!=null) {
                    host.saveMutation(operation,MutationRecord(fingerprint,remoteId,"population_started"))
                    val populated=send(plan.populate.invoke(remoteId!!))
                    if(populated.status !in 200..299)return@withLock uncertain("created_but_content_unverified",remoteId)
                    host.saveMutation(operation,MutationRecord(fingerprint,remoteId,"populated"))
                }
            }else if(remoteId==null)return@withLock uncertain("previous_write_outcome_unknown")
            clearCache()
            val observed=send(checkNotNull(plan.readback).invoke(remoteId!!))
            if(observed.status !in 200..299)return@withLock uncertain("readback_failed",remoteId)
            val proof=checkNotNull(plan.verify).invoke(observed.body)
            if(!proof.verified)return@withLock uncertain("readback_mismatch",remoteId)
            host.saveMutation(operation,MutationRecord(fingerprint,remoteId,"verified"))
            ConnectionOutcome(true,VerificationState.VERIFIED,buildJsonObject {put("service",plan.service.id);put("remoteId",remoteId);put("verified",true);put("verification",proof.summary);put("sentToOthers",false)
                if(call.name==CalendarTargetIntegrity.CREATE){
                    put("calendarSelector",checkNotNull(calendarApproval).review.calendarSelector)
                    put("accountBindingSha256",digest(access.principal+"\n"+access.generation))
                    put("eventActive",true);put("attendeeDeliveryConfirmed",false);put("attendeeAcceptanceConfirmed",false)
                    val event=Json.parseToJsonElement(observed.body).jsonObject
                    put("event",buildJsonObject{
                        put("id",event.getValue("id"));put("status",event.getValue("status"));put("title",event.getValue("summary"))
                        put("start",event.getValue("start").jsonObject.getValue("dateTime"));put("end",event.getValue("end").jsonObject.getValue("dateTime"))
                        if(call.arguments["private"]==JsonPrimitive(true))put("visibility","private")
                        CalendarTargetIntegrity.eventLink((event["htmlLink"] as? JsonPrimitive)?.takeIf {it.isString}?.content)
                            ?.let {put("htmlLink",it)}
                    })
                }}.toString(),"verified")
        }catch(cancel:CancellationException){clearCache();throw cancel}
        catch(_:Exception){clearCache();if(plan.writes)uncertain("write_requires_reconciliation")else fail("read_failed_or_access_changed")}
    }
    private fun readOutcome(call:ToolCall,body:String,cached:Boolean):ConnectionOutcome {
        val parsed=runCatching {Json.parseToJsonElement(body)}.getOrNull()
        val nextPage=if(parsed is JsonObject)listOf("nextPageToken","next_cursor","nextCursor","has_more","@odata.nextLink").any {parsed[it]?.let {v->v !is JsonNull && v.toString()!="false" && v.toString()!="\"\""}==true}else false
        val display=when(call.name){
            "read_gmail_message" -> if(parsed is JsonObject)gmailPreview(parsed).toString() else body
            "list_x_bookmarks" -> {
                val page=parsed as? JsonObject ?: return fail("x_response_invalid")
                val meta=page["meta"] as? JsonObject ?: return fail("x_response_invalid")
                val count=meta["result_count"]?.jsonPrimitive?.intOrNull ?: return fail("x_response_invalid")
                val posts=(page["data"] as? JsonArray)?:JsonArray(emptyList())
                if(count!=posts.size || count !in 0..10 || "errors" in page)return fail("x_response_invalid")
                JsonArray(posts.map {
                    val post=it.jsonObject
                    require(post.getValue("id").jsonPrimitive.content.matches(Regex("[0-9]{1,22}")))
                    require(post.getValue("text").jsonPrimitive.isString)
                    JsonObject(post.filterKeys {k->k in setOf("id","text")})
                }).toString()
            }
            "search_notion_pages" -> {
                val block=call.arguments["blockId"]?.jsonPrimitive?.content
                val offset=call.arguments["offset"]?.jsonPrimitive?.content
                when {
                    block==null -> body
                    offset==null -> NotionBlocks.children(parsed).toString()
                    else -> NotionBlocks.single(parsed,block,offset).toString()
                }
            }
            "list_youtube_playlists" -> {
                val page=parsed as? JsonObject ?: return fail("youtube_response_invalid")
                if(page["items"] !is JsonArray)return fail("youtube_response_invalid")
                body
            }
            "list_outlook_inbox","list_outlook_calendar" -> {
                val rows=(parsed as? JsonObject)?.get("value") as? JsonArray ?: return fail("outlook_response_invalid")
                val keys=if(call.name=="list_outlook_inbox")setOf("id","subject","from","receivedDateTime") else setOf("id","subject","start","end")
                JsonArray(rows.map { row ->
                    val item=row as? JsonObject ?: return fail("outlook_response_invalid")
                    if((item["id"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank())return fail("outlook_response_invalid")
                    JsonObject(item.filterKeys { it in keys })
                }).toString()
            }
            "list_github_repository_issues" -> {
                val issues=parsed as? JsonArray ?: return fail("github_response_invalid")
                val rows=issues.map { it as? JsonObject ?: return fail("github_response_invalid") }
                    .filterNot { "pull_request" in it }
                if(rows.any { (it["number"] as? JsonPrimitive)?.longOrNull == null ||
                        (it["title"] as? JsonPrimitive)?.isString != true })return fail("github_response_invalid")
                JsonArray(rows.map { issue -> JsonObject(issue.filterKeys { it in setOf("number","title","state","html_url","updated_at") }) }).toString()
            }
            "read_slack_channel_history" -> {
                val channel=parsed as? JsonObject ?: return fail("slack_response_invalid")
                if(channel["ok"]?.jsonPrimitive?.booleanOrNull!=true)return fail("slack_request_rejected")
                val messages=channel["messages"] as? JsonArray ?: return fail("slack_response_invalid")
                JsonArray(messages.map { message ->
                    val entry=message as? JsonObject ?: return fail("slack_response_invalid")
                    if((entry["ts"] as? JsonPrimitive)?.isString!=true)return fail("slack_response_invalid")
                    JsonObject(entry.filterKeys { it in setOf("ts","user","bot_id","text","thread_ts") })
                }).toString()
            }
            else -> body
        }
        val limited=display.length>12000
        val report=buildJsonObject {put("service",ConnectionCatalog.tools.getValue(call.name).service.id);put("untrustedSourceData",true);put("completeDataset",false);put("cached",cached);put("sourceRevalidated",true);put("observedAt",host.now());if(call.name=="list_x_bookmarks"){
                val token=parsed?.jsonObject?.get("meta")?.jsonObject?.get("next_token")?.jsonPrimitive?.contentOrNull
                put("hasMore",!token.isNullOrBlank());if(!token.isNullOrBlank())put("pageToken",token)
            }else if(call.name!="list_github_repository_issues")put("hasMore",nextPage);put("truncated",limited)
            if(call.name=="search_notion_pages")
                (parsed as? JsonObject)?.get("next_cursor")?.jsonPrimitive?.contentOrNull?.let { put("cursor",it) }
            if(call.name=="list_youtube_playlists")
                (parsed as? JsonObject)?.get("nextPageToken")?.jsonPrimitive?.contentOrNull?.let { put("pageToken",it) }
            if(!limited && call.name!="read_drive_text_file" && runCatching {Json.parseToJsonElement(display)}.isSuccess)put("data",Json.parseToJsonElement(display))else put("preview",clip(display,12000))}
        return ConnectionOutcome(true,VerificationState.VERIFIED,report.toString(),"read_verified")
    }
    private fun gmailPreview(message:JsonObject):JsonObject {
        val payload=message["payload"] as? JsonObject?:buildJsonObject {}
        val headers=(payload["headers"] as? JsonArray).orEmpty().filter {it.jsonObject["name"]?.jsonPrimitive?.content?.lowercase() in setOf("from","to","subject","date")}
        fun text(part:JsonObject):List<String> {
            val own=if(part["mimeType"]?.jsonPrimitive?.content=="text/plain")part["body"]?.jsonObject?.get("data")?.jsonPrimitive?.content?.let {runCatching {String(java.util.Base64.getUrlDecoder().decode(it),Charsets.UTF_8)}.getOrNull()}else null
            return listOfNotNull(own)+(part["parts"] as? JsonArray).orEmpty().flatMap {text(it.jsonObject)}
        }
        val plain=text(payload).joinToString("\n")
        return buildJsonObject {put("id",message["id"]?:JsonNull);put("headers",JsonArray(headers));put("plainText",clip(plain,10000));put("bodyTruncated",plain.length>10000);put("plainTextAvailable",plain.isNotEmpty());put("attachmentsLoaded",false);put("htmlExecuted",false)}
    }
    private fun clip(text:String,max:Int):String {var end=minOf(max,text.length);if(end<text.length&&end>0&&text[end-1].isHighSurrogate()&&text[end].isLowSurrogate())end--;return text.substring(0,end)}
    private fun fail(code:String)=ConnectionOutcome(false,VerificationState.FAILED,"연결 또는 권한·사용량 상태를 확인해주세요.",code)
    private fun uncertain(code:String,id:String?=null)=ConnectionOutcome(false,VerificationState.UNVERIFIED,buildJsonObject {put("code",code);put("remoteId",id);put("doNotRepeatWrite",true)}.toString(),code)
    private fun httpFailure(status:Int,write:Boolean)=when(status){401,403->fail("authentication_or_scope_required");429->fail("quota_exhausted_wait_no_paid_upgrade");else->if(write)uncertain("remote_write_not_verified")else fail("remote_read_failed")}
}
