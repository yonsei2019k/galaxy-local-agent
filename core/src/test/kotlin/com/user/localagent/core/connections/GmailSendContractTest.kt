package com.user.localagent.core.connections

import com.user.localagent.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Fake HTTP only. No credentials, network, actual mailbox or real message submission. */
class GmailSendContractTest {
    private fun call(reply:Boolean=false,id:String="operation-1",to:String="recipient@example.com",body:String="확인 후 자료를 보내드립니다.")=
        ToolCall(id,if(reply)"reply_gmail_message"else"send_gmail_message",buildJsonObject {
            put("to",to);put("body",body);if(reply)put("messageId","original-1")else put("subject","회의 자료")})
    private fun originalMessage(headers:List<Pair<String,String>> = listOf("From" to "동료 <recipient@example.com>",
        "Subject" to "원래 제목", "Message-ID" to "<original@example.com>","References" to "<older@example.com>"),
        id:String="original-1",thread:String="original-thread")=buildJsonObject {
        put("id",id);put("threadId",thread);put("payload",buildJsonObject {put("headers",buildJsonArray {
            headers.forEach {(name,value)->add(buildJsonObject {put("name",name);put("value",value)})}})})
    }.toString()
    private class Host:ConnectionHost {
        var identity:ConnectionAccess?=ConnectionAccess("synthetic-owner",1,true,true)
        var approved=false;var persist=true;var postMode="ok";var readbackMode="ok"
        var clock=java.time.Instant.parse("2026-10-31T08:30:00Z").toEpochMilli()
        var swapAccountAtAuthorizedPost=false
        var original=GmailSendContractTest().originalMessage()
        val sent=mutableListOf<ApiRequest>();val previews=mutableListOf<GmailSendPreview>();val records=mutableMapOf<String,MutationRecord>()
        val expectedAccesses=mutableListOf<ConnectionAccess>()
        var afterApproval:(()->Unit)?=null;var postedRaw:String?=null;var postedThread:String?=null
        override suspend fun access(service:ConnectedService)=identity
        override fun mutation(key:String)=records[key]
        override fun saveMutation(key:String,record:MutationRecord){if(persist)records[key]=record}
        override fun reserveRequest(service:ConnectedService)=true
        override fun now()=clock
        override suspend fun sendAuthorized(service:ConnectedService,request:ApiRequest,expectedAccess:ConnectionAccess):ApiResponse {
            expectedAccesses+=expectedAccess
            if(swapAccountAtAuthorizedPost&&request.method=="POST")identity=identity!!.copy(principal="different-owner",generation=2)
            check(identity==expectedAccess)
            return send(service,request)
        }
        override suspend fun approveGmailSend(preview:GmailSendPreview):Boolean {previews+=preview;afterApproval?.invoke();return approved}
        override suspend fun send(service:ConnectedService,request:ApiRequest):ApiResponse {
            sent+=request
            return when {
                request.url.endsWith("/profile")->ApiResponse(200,"{\"emailAddress\":\"owner@example.com\"}")
                request.url.contains("original-1?format=metadata")->ApiResponse(200,original)
                request.url.endsWith("/messages/send")->{
                    check(records.values.any {it.phase=="request_started"&&it.remoteId==null})
                    val body=Json.parseToJsonElement(checkNotNull(request.body)).jsonObject
                    postedRaw=body.getValue("raw").jsonPrimitive.content
                    postedThread=body["threadId"]?.jsonPrimitive?.content
                    when(postMode){"throw"->throw java.io.IOException("synthetic transport break")
                        "cancel"->throw CancellationException("synthetic caller cancellation")
                        "missing"->ApiResponse(200,"{}")
                        "500"->ApiResponse(500,"{}")
                        else->ApiResponse(200,"{\"id\":\"sent-1\",\"threadId\":\"${postedThread?:"new-thread"}\"}")}
                }
                request.url.contains("sent-1?format=raw")->{
                    var raw=String(Base64.getUrlDecoder().decode(checkNotNull(postedRaw)),Charsets.UTF_8)
                    raw=when(readbackMode){
                        "to"->raw.replace("To: recipient@example.com","To: other@example.com")
                        "extra-to"->raw.replace("To: recipient@example.com","To: hidden@example.com, <recipient@example.com>")
                        "quoted-extra-to"->raw.replace("To: recipient@example.com","To: \"one\" hidden@example.com, \"two\" <recipient@example.com>")
                        "from"->raw.replace("From: owner@example.com","From: other@example.com")
                        "subject"->raw.replace(Regex("Subject: [^\\r\\n]+"),"Subject: other")
                        "date"->raw.replace(Regex("Date: [^\\r\\n]+"),"Date: Fri, 30 Oct 2026 08:30:00 +0000")
                        "body"->raw.substringBefore("\r\n\r\n")+"\r\n\r\n"+Base64.getEncoder().encodeToString("different".toByteArray())
                        "message-id"->raw.replace(Regex("Message-ID: [^\\r\\n]+"),"Message-ID: <other@example.com>")
                        "references"->raw.replace("References: <older@example.com>\r\n <original@example.com>","References: <other@example.com>")
                        "cc"->"Cc: unexpected@example.com\r\n"+raw
                        else->raw
                    }
                    ApiResponse(200,buildJsonObject {
                        put("id",if(readbackMode=="id")"other-id"else"sent-1")
                        put("threadId",if(readbackMode=="thread")"other-thread"else postedThread?:"new-thread")
                        put("labelIds",buildJsonArray {add(if(readbackMode=="label")"DRAFT"else"SENT");if(readbackMode=="both-labels")add("DRAFT")})
                        put("raw",Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray()))
                    }.toString())
                }
                else->error("unexpected fake HTTP request")
            }
        }
        fun posts()=sent.count {it.method=="POST"}
    }

    @Test fun bothMailToolsAreAlwaysHighRiskAndCannotBePreapproved() {
        for(name in GmailSendContract.names){
            assertEquals(Risk.HIGH,ToolRegistry.definitions.getValue(name).risk)
            assertTrue(ConnectionCatalog.tools.getValue(name).writes)
            assertEquals("messages",IntentPlanning.domainForTool(name))
            assertFalse(CapabilityPolicy.canPreapprove(name))
            assertTrue(CapabilityPolicy.requiresConfirmation(call(name=="reply_gmail_message"),"builtin","hash",emptyList()))
        }
        assertTrue(ConnectedService.GMAIL.scopes(true).contains("https://www.googleapis.com/auth/gmail.compose"))
        assertFalse(ConnectedService.GMAIL.scopes(true).any {it.endsWith("/gmail.send")})
    }
    @Test fun catalogCapabilityAndHarnessNeverTreatDraftOrUnknownAsSent() {
        val env=CapabilityEnvironment(grantedPermissions=setOf("connector:gmail"),validatedConnectors=setOf("gmail"))
        for(name in GmailSendContract.names){
            val c=ToolCapabilityCatalog.snapshot(env).first {it.id==name}
            assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,c.availability);assertFalse(c.offline);assertTrue(c.requiresConfirmation)
            val goal=TaskGoal("합성 메일 발송",listOf("실제 발송 재조회"),requiredTools=setOf(name))
            val step=HarnessStep(call(name=="reply_gmail_message"),StepEvidence(name,VerificationState.UNVERIFIED,"결과 미확인"))
            assertTrue(TaskHarness.decide(goal,listOf(step),true) is HarnessDecision.Stop)
        }
    }
    @Test fun noExactApprovalMeansNoPostAndNoStartedJournal()=runBlocking {
        val h=Host();val outcome=ConnectionRuntime(h).execute(call())
        assertFalse(outcome.success);assertEquals("send_not_approved",outcome.code)
        assertEquals(0,h.posts());assertTrue(h.records.isEmpty());assertEquals(1,h.previews.size)
    }
    @Test fun approvedSubmissionVerifiesActualSentContentAndNeverReplaysPost()=runBlocking {
        val h=Host().apply {approved=true};val runtime=ConnectionRuntime(h);val c=call()
        val first=runtime.execute(c);assertTrue(first.success);assertEquals(VerificationState.VERIFIED,first.state)
        assertTrue(Json.parseToJsonElement(first.data).jsonObject.getValue("sentToOthers").jsonPrimitive.boolean)
        assertFalse(Json.parseToJsonElement(first.data).jsonObject.getValue("deliveryConfirmed").jsonPrimitive.boolean)
        assertTrue(ConnectionRuntime(h).execute(c).success)
        assertEquals(1,h.posts());assertEquals(1,h.previews.size);assertEquals("verified",h.records.values.single().phase)
        assertTrue(h.records.values.single().requestHash.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun perExecutionCallbackOverridesDefaultAndExactPreviewIncludesFullUnchangedBody()=runBlocking {
        val h=Host();val text="줄1\r\nBcc: merely body text\n"+"가".repeat(1000)+"SYNTHETIC-TAIL"
        val c=call(body=text);var preview:GmailSendPreview?=null
        val outcome=ConnectionRuntime(h).execute(c){p->preview=p;true}
        assertTrue(outcome.success);assertEquals(text,preview!!.body);assertTrue(preview!!.approvalText().contains(text))
        assertEquals(c.id,preview!!.callId);assertEquals("owner@example.com",preview!!.from)
        assertEquals("recipient@example.com",preview!!.to);assertEquals("synthetic-owner",preview!!.accountPrincipal)
        assertFalse(preview.toString().contains("SYNTHETIC-TAIL"));assertEquals(1,h.posts())
    }
    @Test fun replyUsesFreshVerifiedOriginalThreadAndRfcHeadersWithCopiedSubject()=runBlocking {
        val h=Host().apply {approved=true};val outcome=ConnectionRuntime(h).execute(call(reply=true))
        assertTrue(outcome.success)
        val p=h.previews.single();assertEquals("original-thread",p.threadId);assertEquals("original-1",p.originalMessageId)
        assertEquals("원래 제목",p.subject);assertEquals("<original@example.com>",p.inReplyTo)
        assertEquals(listOf("<older@example.com>","<original@example.com>"),p.references)
        assertEquals("original-thread",h.postedThread)
    }
    @Test fun mismatchedSourceReplyRecipientDuplicateHeaderAndInvalidRfcIdNeverSend()=runBlocking {
        val bad=listOf(
            originalMessage(id="wrong"),
            originalMessage(headers=listOf("From" to "other@example.com","Subject" to "제목","Message-ID" to "<original@example.com>")),
            originalMessage(headers=listOf("From" to "recipient@example.com","Subject" to "하나","Subject" to "둘","Message-ID" to "<original@example.com>")),
            originalMessage(headers=listOf("From" to "recipient@example.com","Subject" to "제목","Message-ID" to "not-rfc-id")),
            originalMessage(headers=listOf("From" to "recipient@example.com","Subject" to "제목","Message-ID" to "<original@example.com>","References" to "<valid@example.com> injection")))
        for(metadata in bad){val h=Host().apply {approved=true;original=metadata};assertFalse(ConnectionRuntime(h).execute(call(reply=true)).success)
            assertEquals(0,h.posts());assertTrue(h.previews.isEmpty())}
    }
    @Test fun replyToHeaderIsUsedInsteadOfGuessingFromDisplayName()=runBlocking {
        val h=Host().apply {approved=true;original=originalMessage(headers=listOf("From" to "recipient@example.com",
            "Reply-To" to "担当 <reply@example.com>","Subject" to "제목","Message-ID" to "<original@example.com>"))}
        assertTrue(ConnectionRuntime(h).execute(call(reply=true,to="reply@example.com")).success)
        assertEquals("reply@example.com",h.previews.single().to)
    }
    @Test fun injectionAndExtraRecipientsAreRejectedBeforeAnyNetwork()=runBlocking {
        for(c in listOf(call(to="a@example.com,b@example.com"),call(to="a@example.com\r\nBcc:b@example.com"),
            call().let {it.copy(arguments=JsonObject(it.arguments+("subject" to JsonPrimitive("hello\r\nBcc:x@example.com"))))},
            call().let {it.copy(arguments=JsonObject(it.arguments+("bcc" to JsonPrimitive("x@example.com"))))})) {
            val h=Host().apply {approved=true};assertTrue(runCatching {ConnectionRuntime(h).execute(c)}.isFailure)
            assertTrue(h.sent.isEmpty());assertTrue(h.records.isEmpty())
        }
    }
    @Test fun journalMustBeDurableBeforePostAndAccountChangeAfterApprovalBlocksSend()=runBlocking {
        val lost=Host().apply {approved=true;persist=false}
        assertFalse(ConnectionRuntime(lost).execute(call()).success);assertEquals(0,lost.posts())
        val changed=Host().apply {approved=true;afterApproval={identity=identity!!.copy(generation=2)}}
        assertFalse(ConnectionRuntime(changed).execute(call()).success);assertEquals(0,changed.posts())
    }
    @Test fun approvedAccountIsPassedThroughFinalAuthorizedSendEvenIfAccountSwapsAtBoundary()=runBlocking {
        val h=Host().apply {approved=true;swapAccountAtAuthorizedPost=true};val captured=h.identity!!
        val outcome=ConnectionRuntime(h).execute(call())
        assertFalse(outcome.success);assertEquals(0,h.posts())
        assertTrue(h.expectedAccesses.isNotEmpty());assertTrue(h.expectedAccesses.all {it==captured})
        assertEquals("request_started",h.records.values.single().phase)
        h.identity=captured;h.swapAccountAtAuthorizedPost=false
        assertFalse(ConnectionRuntime(h).execute(call()).success);assertEquals(0,h.posts())
    }
    @Test fun longKoreanSubjectIsFoldedWithoutLosingCodePointsOrPreviewText()=runBlocking {
        val h=Host().apply {approved=true};val subject="한글😀".repeat(50)
        val c=call().let {it.copy(arguments=JsonObject(it.arguments+("subject" to JsonPrimitive(subject))))}
        assertTrue(ConnectionRuntime(h).execute(c).success);assertEquals(subject,h.previews.single().subject)
        val raw=String(Base64.getUrlDecoder().decode(h.postedRaw!!),Charsets.UTF_8)
        val encodedWords=Regex("=\\?UTF-8\\?B\\?[^?]*\\?=").findAll(raw).toList()
        assertTrue(encodedWords.size>1);assertTrue(encodedWords.all {it.value.length<=75})
    }
    @Test fun unknownPostOutcomesNeverRetryEvenInNewRuntime()=runBlocking {
        for(mode in listOf("throw","missing","500")) {
            val h=Host().apply {approved=true;postMode=mode};val c=call()
            val first=ConnectionRuntime(h).execute(c);assertFalse("post mode=$mode",first.success);assertEquals(VerificationState.UNVERIFIED,first.state)
            assertFalse(ConnectionRuntime(h).execute(c).success);assertEquals(1,h.posts())
            assertEquals("request_started",h.records.values.single().phase)
            assertTrue(Json.parseToJsonElement(first.data).jsonObject.getValue("doNotRepeatWrite").jsonPrimitive.boolean)
        }
    }
    @Test fun callerCancellationAfterStartedPreservesNoRetryJournal()=runBlocking {
        val h=Host().apply {approved=true;postMode="cancel"};val c=call()
        assertTrue(runCatching {ConnectionRuntime(h).execute(c)}.exceptionOrNull() is CancellationException)
        h.postMode="ok";assertFalse(ConnectionRuntime(h).execute(c).success);assertEquals(1,h.posts())
    }
    @Test fun concurrentRuntimeInstancesCannotClaimSameDurableSubmissionTwice()=runBlocking {
        val h=Host();val c=call();val ready=CompletableDeferred<Unit>();val arrivals=java.util.concurrent.atomic.AtomicInteger()
        val approve:suspend(GmailSendPreview)->Boolean={if(arrivals.incrementAndGet()==2)ready.complete(Unit);ready.await();true}
        val a=async {ConnectionRuntime(h).execute(c,approve)};val b=async {ConnectionRuntime(h).execute(c,approve)}
        val outcomes=listOf(a.await(),b.await())
        assertEquals(1,h.posts());assertEquals(1,outcomes.count {it.success})
        assertEquals("verified",h.records.values.single().phase)
    }
    @Test fun actualReadbackMismatchNeverClaimsSentAndOnlyReconcilesByGet()=runBlocking {
        for(mode in listOf("to","extra-to","quoted-extra-to","from","subject","date","body","message-id","references","thread","label","both-labels","id","cc")) {
            val h=Host().apply {approved=true;readbackMode=mode};val c=call(reply=true)
            val first=ConnectionRuntime(h).execute(c);assertFalse("readback mode=$mode",first.success);assertEquals(VerificationState.UNVERIFIED,first.state)
            assertEquals("readback mode=$mode","send_readback_mismatch",first.code)
            assertEquals(JsonNull,Json.parseToJsonElement(first.data).jsonObject.getValue("sentToOthers"))
            h.readbackMode="ok";assertTrue(ConnectionRuntime(h).execute(c).success);assertEquals(1,h.posts())
        }
    }
    @Test fun rfcDateUsesPersistedHostTimeAndNeverChangesDuringReadbackOnlyReconciliation()=runBlocking {
        val h=Host().apply {approved=true};val firstTime=h.clock;val c=call();val runtime=ConnectionRuntime(h)
        assertTrue(runtime.execute(c).success)
        val raw=String(Base64.getUrlDecoder().decode(h.postedRaw!!),Charsets.UTF_8)
        assertTrue(raw.contains("Date: Sat, 31 Oct 2026 08:30:00 +0000\r\n"))
        assertEquals(firstTime,h.records.values.single().requestedAtMillis)
        val hash=h.records.values.single().requestHash
        h.clock+=7L*24*60*60*1000
        assertTrue(ConnectionRuntime(h).execute(c).success)
        assertEquals(1,h.posts());assertEquals(hash,h.records.values.single().requestHash)
        assertEquals(firstTime,h.records.values.single().requestedAtMillis)
        assertEquals(firstTime,h.previews.single().requestedAtMillis)
    }
    @Test fun legacyKnownIdWithoutFrozenTimestampNeverRepostsOrInventsDate()=runBlocking {
        val h=Host().apply {approved=true};val c=call()
        assertTrue(ConnectionRuntime(h).execute(c).success)
        val key=h.records.keys.single();h.records[key]=h.records.getValue(key).copy(requestedAtMillis=null)
        val outcome=ConnectionRuntime(h).execute(c)
        assertFalse(outcome.success);assertEquals("previous_send_timestamp_unknown",outcome.code)
        assertEquals(1,h.posts());assertEquals(1,h.previews.size)
    }
    @Test fun originalDraftBehaviorStillUsesDraftEndpointAndNeverHasSendSemantics() {
        val draft=ToolCall("draft","create_gmail_draft",buildJsonObject {put("to","recipient@example.com");put("subject","제목");put("body","본문")})
        val p=ConnectionRequests.build(draft);assertTrue(p.first.url.endsWith("/drafts"));assertFalse(draft.name in GmailSendContract.names)
        assertEquals(Risk.MEDIUM,ToolRegistry.definitions.getValue("create_gmail_draft").risk)
    }
}
