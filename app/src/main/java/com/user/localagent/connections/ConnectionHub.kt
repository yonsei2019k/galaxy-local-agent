package com.user.localagent.connections

import android.content.Context
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import com.user.localagent.core.connections.*
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ConnectionHub private constructor(private val context:Context,
    private val calendarAuthorizedSender:(suspend(ConnectedService,ApiRequest,ConnectionAccess)->ApiResponse)?=null) {
    val store=ConnectionStore(context)
    val google by lazy {check(calendarAuthorizedSender==null){"isolated_calendar_has_no_google_auth"};GoogleConnectionAuth(context,store)}
    val pc by lazy {check(calendarAuthorizedSender==null){"isolated_calendar_has_no_pc_client"};PcToolClient(context,store)}
    private val runtimes=java.util.concurrent.ConcurrentHashMap<ConnectedService,ConnectionRuntime>()
    fun clearCache(){runtimes.values.forEach {it.clearCache()}}
    fun authorizedServiceIds(writeOnly:Boolean=false):Set<String> = ConnectedService.entries.filter {store.connection(it)?.let {value->!writeOnly||value.writeAllowed}==true}.map {it.id}.toSet()+if(!writeOnly&&pc.authorized())setOf("pc-tools")else emptySet()
    fun verifiedServiceIds():Set<String> = ConnectedService.entries.filter {store.connection(it)?.verifiedAt?.let {at->at>0}==true}.map {it.id}.toSet()+if(pc.verified())setOf("pc-tools")else emptySet()
    fun disconnect(service:ConnectedService){store.disconnect(service);clearCache()}
    private fun runtime(service:ConnectedService)=runtimes.getOrPut(service){ConnectionRuntime(object:ConnectionHost {
        override suspend fun access(service:ConnectedService):ConnectionAccess? {
            val value=store.connection(service)?:return null
            return ConnectionAccess(value.principal,value.generation,true,value.writeAllowed)
        }
        override suspend fun send(service:ConnectedService,request:ApiRequest):ApiResponse {
            val state=store.connection(service)?:return ApiResponse(401,"{}")
            return sendAuthorized(service,request,ConnectionAccess(state.principal,state.generation,true,state.writeAllowed))
        }
        override suspend fun sendAuthorized(service:ConnectedService,request:ApiRequest,expectedAccess:ConnectionAccess):ApiResponse {
            val state=store.connection(service)?:return ApiResponse(401,"{}")
            if(ConnectionAccess(state.principal,state.generation,true,state.writeAllowed)!=expectedAccess)return ApiResponse(401,"{}")
            calendarAuthorizedSender?.let {sender->
                check(service==ConnectedService.CALENDAR){"isolated_calendar_only"}
                currentCoroutineContext().ensureActive()
                return sender(service,request,expectedAccess).also {currentCoroutineContext().ensureActive()}
            }
            val token=try {if(service.google)google.token(service,state.writeAllowed,expectedAccess)else store.read("token.${service.id}")?:return ApiResponse(401,"{}")}
                catch(cancel:CancellationException){throw cancel}catch(_:Exception){return ApiResponse(401,"{}")}
            val current=store.connection(service)?:return ApiResponse(401,"{}")
            if(current.principal!=state.principal||current.generation!=state.generation||current.writeAllowed!=state.writeAllowed)return ApiResponse(401,"{}")
            return transport(service,request,token)
        }
        override fun mutation(key:String)=store.mutation(service,key)
        override fun saveMutation(key:String,record:MutationRecord)=store.saveMutation(service,key,record)
        override fun reserveRequest(service:ConnectedService)=store.reserve(service)
    })}
    /** Local encrypted connection metadata only. No OAuth, API or model request is started to prepare this review. */
    suspend fun calendarTargetForApproval(call:ToolCall,hostRunId:String,verifyAccess:suspend()->Unit):CalendarTargetReview=withContext(Dispatchers.IO){
        verifyAccess()
        val state=checkNotNull(store.connection(ConnectedService.CALENDAR)){"calendar_connection_required"}
        val access=ConnectionAccess(state.principal,state.generation,true,state.writeAllowed)
        val review=CalendarTargetIntegrity.review(ConnectionExecutionScope.bind(call,hostRunId),access,state.accountName)
        verifyAccess();check(store.connection(ConnectedService.CALENDAR)?.let{ConnectionAccess(it.principal,it.generation,true,it.writeAllowed)}==access){"calendar_target_changed_before_preview"}
        review
    }
    suspend fun execute(call:ToolCall,hostRunId:String?=null,approveGmailSend:(suspend(GmailSendPreview)->Boolean)?=null):ToolResult=
        executeBound(call,hostRunId,null,null,{},approveGmailSend)
    suspend fun executeInThread(call:ToolCall,hostRunId:String,hostThreadId:String,pcDataBinding:String?,verifyAccess:suspend()->Unit,
        approveGmailSend:(suspend(GmailSendPreview)->Boolean)?=null):ToolResult {
        require(hostThreadId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        verifyAccess()
        return executeBound(call,hostRunId,hostThreadId,pcDataBinding,verifyAccess,approveGmailSend)
    }
    suspend fun executeApprovedCalendarInThread(call:ToolCall,hostRunId:String,hostThreadId:String,pcDataBinding:String?,verifyAccess:suspend()->Unit,
        approval:ApprovedCalendarTarget):ToolResult {
        require(hostThreadId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        verifyAccess();return executeBound(call,hostRunId,hostThreadId,pcDataBinding,verifyAccess,null,approval)
    }
    private suspend fun executeBound(call:ToolCall,hostRunId:String?,hostThreadId:String?,pcDataBinding:String?,verifyAccess:suspend()->Unit,
        approveGmailSend:(suspend(GmailSendPreview)->Boolean)?,calendarApproval:ApprovedCalendarTarget?=null):ToolResult=withContext(Dispatchers.IO) {
        if(calendarAuthorizedSender!=null&&ConnectionCatalog.tools[call.name]?.service!=ConnectedService.CALENDAR)
            return@withContext ToolResult(call.id,false,"격리된 Calendar 검증 범위 밖의 요청입니다.",VerificationState.FAILED,"isolated_calendar_only")
        if(call.name in PcToolCatalog.names)return@withContext pc.execute(call,hostThreadId,pcDataBinding,verifyAccess,computeOwner=hostRunId)
        val service=ConnectionCatalog.tools[call.name]?.service?:return@withContext ToolResult(call.id,false,"지원하지 않는 연결 도구",VerificationState.FAILED)
        try {
            val initialAccess=store.connection(service)?.let {ConnectionAccess(it.principal,it.generation,true,it.writeAllowed)}
            val scopedCall=ConnectionExecutionScope.bind(call,hostRunId)
            val outcome=if(calendarApproval!=null)runtime(service).executeApprovedCalendar(scopedCall,calendarApproval)else runtime(service).execute(scopedCall,approveGmailSend)
            if(outcome.success&&initialAccess!=null)store.markVerified(service,initialAccess)
            ToolResult(call.id,outcome.success,outcome.data,outcome.state,outcome.code)
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){ToolResult(call.id,false,"연결 인자 또는 계정 설정을 확인해주세요.",VerificationState.FAILED,"connection_failed")}
    }
    suspend fun configureToken(service:ConnectedService,token:String,allowedChat:String?=null)=withContext(Dispatchers.IO) {
        require(!service.google&&token.length in 20..500&&token.none {it.isWhitespace()||it=='\u0000'})
        if(service==ConnectedService.TELEGRAM){require(token.matches(Regex("[0-9]{5,20}:[A-Za-z0-9_-]{20,200}")));require(allowedChat?.matches(Regex("-?[0-9]{1,20}"))==true)}
        val request=when(service){
            ConnectedService.TELEGRAM->ApiRequest("https://api.telegram.org/getMe")
            ConnectedService.NOTION->ApiRequest("https://api.notion.com/v1/users/me",extraHeaders=mapOf("Notion-Version" to "2026-03-11"))
            ConnectedService.TODOIST->ApiRequest("https://api.todoist.com/api/v1/tasks?limit=1")
            else->error("지원하지 않는 연결")
        }
        val response=transport(service,request,token,testingToken=true)
        check(response.status in 200..299){"서비스가 연결 정보를 수락하지 않았습니다."}
        if(service==ConnectedService.TELEGRAM)check(Json.parseToJsonElement(response.body).jsonObject["ok"]?.jsonPrimitive?.boolean==true)
        val principal=MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString(""){"%02x".format(it)}
        store.write("token.${service.id}",token);store.write("allowedChat.${service.id}",allowedChat)
        store.connect(service,LocalConnection(principal,null,false,System.currentTimeMillis(),System.currentTimeMillis()));clearCache()
    }
    suspend fun transport(service:ConnectedService,request:ApiRequest,token:String,testingToken:Boolean=false):ApiResponse=withContext(Dispatchers.IO) {
        require(token.isNotBlank()&&token.none {it=='\r'||it=='\n'})
        if(service==ConnectedService.GMAIL&&request.method=="POST"&&request.url=="https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
            return@withContext GmailSendTransport.send(request,token)
        val url=if(service==ConnectedService.TELEGRAM){require(request.url in setOf("https://api.telegram.org/getUpdates","https://api.telegram.org/getMe"));URL("https://api.telegram.org/bot$token/"+request.url.substringAfterLast('/'))}else URL(request.url)
        val connection=url.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects=false;connection.requestMethod=request.method
            connection.connectTimeout=15_000;connection.readTimeout=20_000
            connection.setRequestProperty("Accept","application/json, text/plain;q=0.9")
            if(service!=ConnectedService.TELEGRAM)connection.setRequestProperty("Authorization","Bearer $token")
            request.extraHeaders.forEach { (key,value)->connection.setRequestProperty(key,value)}
            request.body?.let {body->connection.doOutput=true;connection.setRequestProperty("Content-Type",request.contentType);connection.outputStream.use {it.write(body.toByteArray(Charsets.UTF_8))}}
            val status=connection.responseCode
            if(status in 300..399&&status!=304)return@withContext ApiResponse(status,"{}")
            if(status !in 200..299&&status!=304)return@withContext ApiResponse(status,"{}")
            val bytes=java.io.ByteArrayOutputStream()
            if(status!=304)connection.inputStream.use {input->val buffer=ByteArray(8192);while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;require(bytes.size()+count<=2*1024*1024){"응답 크기 제한"};bytes.write(buffer,0,count)}}
            val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            var body=decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            if(service==ConnectedService.TELEGRAM&&!testingToken&&status in 200..299){
                val allowed=store.read("allowedChat.telegram")?:error("허용한 텔레그램 대화가 없습니다.")
                val data=Json.parseToJsonElement(body).jsonObject
                check(data["ok"]?.jsonPrimitive?.boolean==true)
                val filtered=(data["result"] as? JsonArray).orEmpty().filter {update->
                    val obj=update.jsonObject;listOf("message","edited_message","channel_post","edited_channel_post").any {key->(obj[key] as? JsonObject)?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.content==allowed}
                }.take(20)
                body=buildJsonObject {put("ok",true);put("result",JsonArray(filtered));put("allowedChatOnly",true);put("historyComplete",false)}.toString()
            }
            ApiResponse(status,body,connection.contentType?:"application/json",connection.getHeaderField("ETag"))
        }finally {connection.disconnect()}
    }
    suspend fun createPhotoSelection():String=withContext(Dispatchers.IO) {
        val service=ConnectedService.PHOTOS;val token=google.token(service,false)
        check(store.reserve(service)){"오늘의 사진 선택 요청 한도를 사용했습니다."}
        val response=transport(service,ApiRequest("https://photospicker.googleapis.com/v1/sessions","POST","{}"),token)
        check(response.status in 200..299){"사진 선택 세션을 만들지 못했습니다."}
        val data=Json.parseToJsonElement(response.body).jsonObject
        val id=data.getValue("id").jsonPrimitive.content;ConnectionRequests.key(id)
        val uri=data.getValue("pickerUri").jsonPrimitive.content
        val parsed=java.net.URI(uri);require(parsed.scheme=="https"&&parsed.host=="photos.google.com"&&parsed.userInfo==null)
        store.write("picker.photos",id);uri
    }
    suspend fun selectedPhotos():String=withContext(Dispatchers.IO) {
        val service=ConnectedService.PHOTOS;val token=google.token(service,false);val id=store.read("picker.photos")?:error("먼저 사진을 선택해주세요.")
        check(store.reserve(service));val session=transport(service,ApiRequest("https://photospicker.googleapis.com/v1/sessions/${ConnectionRequests.key(id)}"),token)
        check(session.status in 200..299&&Json.parseToJsonElement(session.body).jsonObject["mediaItemsSet"]?.jsonPrimitive?.boolean==true){"사진 선택을 완료한 뒤 다시 확인해주세요."}
        check(store.reserve(service));val result=transport(service,ApiRequest("https://photospicker.googleapis.com/v1/mediaItems?sessionId=${ConnectionRequests.encode(id)}&pageSize=20"),token)
        check(result.status in 200..299)
        val data=Json.parseToJsonElement(result.body).jsonObject
        buildJsonObject {put("selectedOnly",true);put("hasMore",data.containsKey("nextPageToken"));put("items",buildJsonArray {(data["mediaItems"] as? JsonArray).orEmpty().forEach {item->val value=item.jsonObject;add(buildJsonObject {put("id",value["id"]?:JsonNull);put("type",value["type"]?:JsonNull);put("filename",value["mediaFile"]?.jsonObject?.get("filename")?:JsonNull)})}})}.toString()
    }
    companion object {
        @Volatile private var instance:ConnectionHub?=null
        fun get(context:Context):ConnectionHub=instance?:synchronized(this){instance?:ConnectionHub(context.applicationContext).also {instance=it}}
        /** Trusted instrumentation dependency only; never registered as a tool or installed as the global singleton. */
        internal fun isolatedCalendarForValidation(context:Context,sender:suspend(ConnectedService,ApiRequest,ConnectionAccess)->ApiResponse):ConnectionHub=
            ConnectionHub(context.applicationContext,sender)
    }
}
