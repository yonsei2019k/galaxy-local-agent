package com.user.localagent.core.connections

import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Runs inside ConnectionRuntime's same-account lane. A durable started record always precedes POST. */
internal object GmailSendRuntime {
    private val journalClaimLock=Any()
    suspend fun execute(host:ConnectionHost,call:ToolCall,access:ConnectionAccess,operation:String,
        send:suspend(ApiRequest)->ApiResponse,approve:suspend(GmailSendPreview)->Boolean):ConnectionOutcome {
        val prior=host.mutation(operation)
        // There is no Gmail send idempotency key: unknown completion MUST NOT retry POST.
        val requestedAt:Long=if(prior==null)host.now()else {
            prior.requestedAtMillis?:return uncertain("previous_send_timestamp_unknown",prior.remoteId)
        }
        var remoteId=prior?.remoteId
        try {
            val profile=send(GmailSendContract.profileRequest())
            if(profile.status !in 200..299)return unavailable(prior,"sender_account_unverified")
            val original=if(call.name=="reply_gmail_message") {
                val response=send(GmailSendContract.originalRequest(call.arguments.getValue("messageId").jsonPrimitive.content))
                if(response.status !in 200..299)return unavailable(prior,"reply_source_unverified")
                response.body
            }else null
            val prepared=GmailSendContract.prepare(call,access.principal,operation,profile.body,original,requestedAt)
            if(prior!=null&&prior.requestHash!=prepared.preview.requestFingerprint)return uncertain("operation_key_conflict",remoteId)
            if(prior==null){
                if(!approve(prepared.preview))return ConnectionOutcome(false,VerificationState.FAILED,"수신자·제목·본문 전체의 별도 발송 승인이 필요합니다.","send_not_approved")
                currentCoroutineContext().ensureActive()
                check(host.access(ConnectedService.GMAIL)==access){"connection_changed"}
                val started=MutationRecord(prepared.preview.requestFingerprint,null,"request_started",requestedAt)
                val claimed=synchronized(journalClaimLock) {
                    if(host.mutation(operation)!=null)false else {
                        host.saveMutation(operation,started)
                        check(host.mutation(operation)==started){"send_journal_not_durable"};true
                    }
                }
                if(!claimed)return uncertain("concurrent_send_requires_reconciliation",host.mutation(operation)?.remoteId)
                currentCoroutineContext().ensureActive()
                val posted=try {send(prepared.request)}
                    catch(cancel:CancellationException){throw cancel}
                    catch(_:Exception){null}
                remoteId=posted?.takeIf {it.status in 200..299}?.let {
                    runCatching {Json.parseToJsonElement(it.body).jsonObject["id"]?.jsonPrimitive?.content?.also(ConnectionRequests::key)}.getOrNull()
                }
                if(remoteId!=null){
                    val created=MutationRecord(prepared.preview.requestFingerprint,remoteId,"created",requestedAt)
                    host.saveMutation(operation,created);check(host.mutation(operation)==created)
                }
            }
            if(remoteId==null){
                // Gmail may have committed the POST before the client received a response.
                val query=ConnectionRequests.encode("rfc822msgid:"+prepared.preview.rfcMessageId.removeSurrounding("<",">"))
                val found=send(ApiRequest("https://gmail.googleapis.com/gmail/v1/users/me/messages?labelIds=SENT&maxResults=2&q=$query"))
                if(found.status !in 200..299)return uncertain("sent_search_unavailable")
                val matches=Json.parseToJsonElement(found.body).jsonObject
                if(matches["nextPageToken"]!=null)return uncertain("sent_search_ambiguous")
                remoteId=(matches["messages"] as? JsonArray)?.singleOrNull()?.jsonObject
                    ?.get("id")?.jsonPrimitive?.content?.also(ConnectionRequests::key)
                    ?:return uncertain("previous_send_outcome_unknown")
            }
            val readbackId = checkNotNull(remoteId)
            val observed = send(GmailSendContract.readbackRequest(readbackId))
            if (observed.status !in 200..299) return uncertain("send_readback_failed", readbackId)
            if (!GmailSendContract.verify(prepared, readbackId, observed.body)) return uncertain("send_readback_mismatch", readbackId)
            if (host.access(ConnectedService.GMAIL) != access) return uncertain("sender_account_changed", readbackId)
            val verified=MutationRecord(prepared.preview.requestFingerprint,remoteId,"verified",requestedAt)
            host.saveMutation(operation,verified);check(host.mutation(operation)==verified)
            return ConnectionOutcome(true,VerificationState.VERIFIED,buildJsonObject {
                put("service","gmail");put("remoteId",remoteId);put("verified",true);put("sent",true)
                put("sentToOthers",!prepared.preview.to.equals(prepared.preview.from,true));put("deliveryConfirmed",false)
                put("threadId",Json.parseToJsonElement(observed.body).jsonObject.getValue("threadId"))
                put("mimeSha256",prepared.preview.mimeSha256)
                put("verification","SENT 상태·발신/수신 주소·제목·본문·Message-ID·답장 헤더 실제 재조회 일치")
            }.toString(),"sent_verified")
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){return uncertain("send_requires_reconciliation",remoteId)}
    }
    private fun unavailable(prior:MutationRecord?,code:String)=if(prior==null)
        ConnectionOutcome(false,VerificationState.FAILED,"발신 계정 또는 답장 원본을 확인하지 못했습니다.",code)else uncertain(code,prior.remoteId)
    private fun uncertain(code:String,id:String?=null)=ConnectionOutcome(false,VerificationState.UNVERIFIED,buildJsonObject {
        put("code",code);put("remoteId",id);put("doNotRepeatWrite",true);put("sent",JsonNull);put("sentToOthers",JsonNull)
        put("deliveryStatus","unknown");put("deliveryConfirmed",false)
    }.toString(),code)
}
