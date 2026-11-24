package com.user.localagent.core.connections

import com.user.localagent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionContractsTest {
    private fun call(name:String,vararg fields:Pair<String,String>,id:String="operation1")=ToolCall(id,name,buildJsonObject {fields.forEach {(k,v)->put(k,v)}})
    private class Host:ConnectionHost {
        var identity:ConnectionAccess?=ConnectionAccess("owner1",1,true,true)
        val sent=mutableListOf<ApiRequest>();val responses=ArrayDeque<ApiResponse>();val records=mutableMapOf<String,MutationRecord>()
        var allowed=true;var clock=1000L;var afterSend:(()->Unit)?=null
        override suspend fun access(service:ConnectedService)=identity
        override suspend fun send(service:ConnectedService,request:ApiRequest):ApiResponse {sent+=request;afterSend?.invoke();return responses.removeFirst()}
        override fun mutation(key:String)=records[key]
        override fun saveMutation(key:String,record:MutationRecord){records[key]=record}
        override fun reserveRequest(service:ConnectedService)=allowed
        override fun now()=clock
    }
    @Test fun excludedServicesAndExtraSendingPermissionsRemainAbsentWhileMailSendsAreHighRisk() {
        for(service in ConnectedService.entries){assertFalse(service.id in ConnectionCatalog.excludedServices);assertFalse(service.scopes(true).any {it.contains("gmail.send")})}
        assertEquals(setOf("send_gmail_message"),ConnectionCatalog.tools.keys.filter {it.contains("send")}.toSet())
        assertFalse(ConnectionCatalog.tools.keys.any {it.contains("delete")})
        assertTrue(GmailSendContract.names.all {ConnectionCatalog.tools.getValue(it).writes&&ConnectionCatalog.tools.getValue(it).definition.risk==Risk.HIGH})
        assertEquals(28,ConnectionCatalog.tools.size)
    }
    @Test fun broaderGoogleGrantCoversItsReadScopeButNotOtherServices() {
        assertTrue(ConnectedService.DOCS.coveredBy(listOf("https://www.googleapis.com/auth/documents"),true))
        assertFalse(ConnectedService.GMAIL.coveredBy(listOf("https://www.googleapis.com/auth/documents"),false))
        assertFalse(ConnectedService.DRIVE.coveredBy(listOf("https://www.googleapis.com/auth/drive.file"),false))
    }
    @Test fun hostRunScopeSeparatesReusableModelCallIds() {
        val original=call("create_google_task","title" to "할 일",id="1")
        val first=ConnectionExecutionScope.bind(original,"run-a")
        assertEquals(first,ConnectionExecutionScope.bind(original,"run-a"))
        assertNotEquals(first.id,ConnectionExecutionScope.bind(original,"run-b").id)
        assertEquals("1",original.id)
    }
    @Test fun writesCannotExecuteWithoutHostScope() {
        try{ConnectionExecutionScope.bind(call("create_google_task","title" to "할 일"),null);fail()}catch(_:IllegalArgumentException){}
        assertEquals("operation1",ConnectionExecutionScope.bind(call("list_google_tasks"),null).id)
    }
    @Test fun idsCannotInjectPathsOrAuthorities() {
        for(id in listOf("../a","a/b","a?alt=media","a#fragment","https://evil.test","a\\b","..","a\r\nHeader:x")) {
            try{ConnectionRequests.build(call("read_google_document","id" to id));fail(id)}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun onlyFixedHttpsHostsAndHeadersAreAccepted() {
        for(url in listOf("http://www.googleapis.com/a","https://evil.test/a","https://user@www.googleapis.com/a","https://www.googleapis.com:444/a"))try{ApiRequest(url);fail(url)}catch(_:IllegalArgumentException){}
        try{ApiRequest("https://www.googleapis.com/a",extraHeaders=mapOf("Authorization" to "secret"));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun publicQueryAndDriveOperatorsAreEscaped() {
        val request=ConnectionRequests.build(call("search_drive_files","query" to "a' or trashed=true or name='한글")).first
        assertFalse(request.url.contains(" "));assertFalse(request.url.contains("한글"));assertTrue(request.url.contains("%5C%27"))
    }
    @Test fun sheetsUseRawValuesAndBoundDimensions() {
        val plan=ConnectionRequests.build(call("create_google_sheet","title" to "표","rowsJson" to "[[\"=IMPORTXML(x)\",\"한글\"]]"))
        assertTrue(plan.populate!!.invoke("sheet1").url.endsWith("valueInputOption=RAW"))
        for(raw in listOf("{}","[]","[[1]]","[[true]]"))try{ConnectionRequests.build(call("create_google_sheet","title" to "표","rowsJson" to raw));fail(raw)}catch(_:IllegalArgumentException){}
    }
    @Test fun draftHeaderInjectionIsRejectedAndDraftEndpointRemainsUnsent() {
        try{ConnectionRequests.build(call("create_gmail_draft","to" to "a@example.com","subject" to "hello\r\nBcc: x@example.com","body" to "text"));fail()}catch(_:IllegalArgumentException){}
        val plan=ConnectionRequests.build(call("create_gmail_draft","to" to "a@example.com","subject" to "제목","body" to "본문"))
        assertTrue(plan.first.url.endsWith("/drafts"));assertFalse(plan.first.url.endsWith("/send"))
    }
    @Test fun calendarUsesExplicitOffsetsAndStableEventId() {
        val call=call("create_google_calendar_event","title" to "회의","start" to "2026-10-31T09:00:00+09:00","end" to "2026-10-31T10:00:00+09:00")
        assertEquals(ConnectionRequests.build(call).knownId,ConnectionRequests.build(call).knownId)
        assertTrue(ConnectionRequests.build(call).knownId!!.matches(Regex("[0-9a-f]{64}")))
        try{ConnectionRequests.build(call.copy(arguments=JsonObject(call.arguments+("end" to JsonPrimitive("2026-10-31T08:00:00+09:00")))));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun tasksRejectInvalidDueDatesAndFormsRequireStringQuestions() {
        try{ConnectionRequests.build(call("create_google_task","title" to "test","dueDate" to "2026-02-30"));fail()}catch(_:Exception){}
        try{ConnectionRequests.build(call("create_google_form","title" to "form","questionsJson" to "[{}]"));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun missingConnectionAndReadOnlyGrantNeverExecuteWrites()=runBlocking {
        val h=Host();h.identity=null;val runtime=ConnectionRuntime(h)
        assertFalse(runtime.execute(call("list_google_tasks")).success)
        h.identity=ConnectionAccess("owner",1,true,false)
        assertFalse(runtime.execute(call("create_google_task","title" to "할 일")).success)
        assertTrue(h.sent.isEmpty())
    }
    @Test fun writesNeedActualReadbackAndDoNotReplayCreates()=runBlocking {
        val h=Host();h.responses.addAll(listOf(ApiResponse(200,"{\"id\":\"task1\"}"),ApiResponse(200,"{\"id\":\"task1\",\"title\":\"할 일\"}"),ApiResponse(200,"{\"id\":\"task1\",\"title\":\"할 일\"}")))
        val r=ConnectionRuntime(h);val c=call("create_google_task","title" to "할 일")
        assertEquals(VerificationState.VERIFIED,r.execute(c).state);assertTrue(r.execute(c).success)
        assertEquals(listOf("POST","GET","GET"),h.sent.map {it.method})
    }
    @Test fun missingIdsAndUnknownWritesAreNeverReissued()=runBlocking {
        val h=Host();h.responses.add(ApiResponse(200,"{}"));val r=ConnectionRuntime(h);val c=call("create_google_task","title" to "할 일")
        assertFalse(r.execute(c).success);assertFalse(r.execute(c).success);assertEquals(1,h.sent.size)
    }
    @Test fun reauthorizationDoesNotEraseAnUncertainWriteIdentity()=runBlocking {
        val h=Host();h.responses.add(ApiResponse(200,"{}"));val r=ConnectionRuntime(h);val c=call("create_google_task","title" to "할 일")
        assertFalse(r.execute(c).success);h.identity=h.identity!!.copy(generation=999)
        assertFalse(r.execute(c).success);assertEquals(1,h.sent.size)
    }
    @Test fun populatedDocumentRequiresItsActualText()=runBlocking {
        val h=Host();h.responses.addAll(listOf(ApiResponse(200,"{\"documentId\":\"doc1\"}"),ApiResponse(200,"{}"),ApiResponse(200,"{\"documentId\":\"doc1\",\"body\":{\"content\":[]}}")))
        val outcome=ConnectionRuntime(h).execute(call("create_google_document","title" to "제목","content" to "본문"))
        assertFalse(outcome.success);assertEquals(VerificationState.UNVERIFIED,outcome.state);assertEquals(listOf("POST","POST","GET"),h.sent.map {it.method})
    }
    @Test fun changedAuthorityStopsMultiRequestWrite()=runBlocking {
        val h=Host();h.responses.add(ApiResponse(200,"{\"documentId\":\"doc1\"}"));h.afterSend={h.identity=h.identity!!.copy(generation=2)}
        assertFalse(ConnectionRuntime(h).execute(call("create_google_document","title" to "제목","content" to "본문")).success)
        assertEquals(1,h.sent.size)
    }
    @Test fun cacheIsRevalidatedAndScopedToCurrentAccount()=runBlocking {
        val h=Host();h.responses.addAll(listOf(ApiResponse(200,"{\"items\":[]}",etag="v1"),ApiResponse(304,""),ApiResponse(200,"{\"items\":[]}")))
        val r=ConnectionRuntime(h);val c=call("list_google_tasks")
        assertTrue(r.execute(c).success);assertTrue(r.execute(c).data.contains("\"cached\":true"));assertEquals("v1",h.sent[1].extraHeaders["If-None-Match"])
        h.identity=h.identity!!.copy(principal="other-owner");r.execute(c);assertFalse("If-None-Match" in h.sent[2].extraHeaders)
    }
    @Test fun unknownVersionsAreNotServedFromAnUnverifiedTtlCache()=runBlocking {
        val h=Host();h.responses.addAll(listOf(ApiResponse(200,"{}"),ApiResponse(200,"{}")));val r=ConnectionRuntime(h)
        r.execute(call("list_google_tasks"));r.execute(call("list_google_tasks"));assertEquals(2,h.sent.size)
    }
    @Test fun quotaErrorsDoNotRetryOrEnablePaidFeatures()=runBlocking {
        val h=Host();h.responses.add(ApiResponse(429,"private error"));val result=ConnectionRuntime(h).execute(call("list_google_tasks"))
        assertFalse(result.success);assertEquals("quota_exhausted_wait_no_paid_upgrade",result.code);assertFalse(result.data.contains("private error"));assertEquals(1,h.sent.size)
    }
    @Test fun paginationAndLargeResultsRemainExplicitlyPartial()=runBlocking {
        val h=Host();h.responses.add(ApiResponse(200,buildJsonObject {put("nextPageToken","next");put("data","x".repeat(14000))}.toString()))
        val result=ConnectionRuntime(h).execute(call("list_google_tasks"));assertTrue(result.data.contains("\"hasMore\":true"));assertTrue(result.data.contains("\"truncated\":true"));assertTrue(result.data.contains("\"completeDataset\":false"))
    }
    @Test fun gmailTextIsDecodedButAttachmentsAndHtmlAreNotExecuted()=runBlocking {
        val h=Host();val encoded=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("본문".toByteArray())
        h.responses.add(ApiResponse(200,"{\"id\":\"m1\",\"payload\":{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"$encoded\"}}}"))
        val result=ConnectionRuntime(h).execute(call("read_gmail_message","id" to "m1"));assertTrue(result.data.contains("본문"));assertTrue(result.data.contains("\"htmlExecuted\":false"))
    }
    @Test fun repeatedMessagesArePreservedAndWholeHistoryNeverClaimed() {
        val input="채팅 기록\n--------------- 2026년 10월 30일 금요일 ---------------\n[친구] [오전 9:05] 안녕\n[친구] [오전 9:05] 안녕\n두 번째 메시지의 다음 줄"
        val parsed=KakaoExportParser.parse(input);assertEquals(2,parsed.messages.size);assertEquals("2026-10-30",parsed.messages[0].date);assertTrue(parsed.messages[1].text.contains("다음 줄"));assertFalse(parsed.completeHistory)
    }
    @Test fun mobileExportAndBoundsAreHandled() {
        val result=KakaoExportParser.parse("2026년 10월 30일 오전 9:05, 친구 : 안녕\n2026년 10월 30일 오전 9:06, 나 : 답장",1)
        assertEquals(1,result.messages.size);assertTrue(result.truncated)
        try{KakaoExportParser.parse("\u0000");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun calendarRequestsKeepSecondsForExactMinuteTimestamps(){
        val start="2026-11-25T00:00:00+09:00"
        val end="2026-11-26T00:00:00+09:00"
        val list=ConnectionRequests.build(call("list_google_calendar_events","start" to start,"end" to end))
        val query=java.net.URI(list.first.url).query
        assertTrue(query.contains("timeMin=$start&timeMax=$end"))
        val create=ConnectionRequests.build(call("create_google_calendar_event","title" to "일정","start" to start,"end" to end))
        val body=Json.parseToJsonElement(checkNotNull(create.first.body)).jsonObject
        assertEquals(start,body.getValue("start").jsonObject.getValue("dateTime").jsonPrimitive.content)
        assertEquals(end,body.getValue("end").jsonObject.getValue("dateTime").jsonPrimitive.content)
    }
}
