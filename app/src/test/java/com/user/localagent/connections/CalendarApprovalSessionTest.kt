package com.user.localagent.connections

import com.user.localagent.core.*
import com.user.localagent.core.connections.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CalendarApprovalSessionTest {
    private companion object {
        fun call()=ToolCall("public-calendar-op",CalendarTargetIntegrity.CREATE,buildJsonObject{
            put("title","Synthetic meeting");put("start","2026-11-15T09:00:00+09:00");put("end","2026-11-15T10:00:00+09:00")
        })
    }
    private class Host(source:ToolCall):ConnectionHost {
        var identity:ConnectionAccess?=ConnectionAccess("public-account-a",1,true,true);var posts=0
        private val expected=checkNotNull(ConnectionRequests.build(source).knownId)
        private val records=mutableMapOf<String,MutationRecord>()
        override suspend fun access(service:ConnectedService)=identity
        override fun mutation(key:String)=records[key]
        override fun saveMutation(key:String,record:MutationRecord){records[key]=record}
        override fun reserveRequest(service:ConnectedService)=true
        override suspend fun send(service:ConnectedService,request:ApiRequest):ApiResponse {
            if(request.method=="POST"){posts++;return ApiResponse(200,buildJsonObject{put("id",expected)}.toString())}
            assertTrue(request.url.endsWith("/events/$expected"))
            return ApiResponse(200,buildJsonObject{
                put("id",expected);put("status","confirmed");put("summary","Synthetic meeting")
                put("start",buildJsonObject{put("dateTime","2026-11-15T00:00:00Z")})
                put("end",buildJsonObject{put("dateTime","2026-11-15T01:00:00Z")})
            }.toString())
        }
    }
    private class Fixture {
        var live=true;var scope=true;var captures=0;var executes=0;var generic=0
        val host=Host(ConnectionExecutionScope.bind(call(),"public-run"));val runtime=ConnectionRuntime(host)
        val connection=RequestConnectionExecutor("public-run",{live},approve={_,_,_->error("Not a Gmail action")},
            execute={_,_,_->generic++;error("Calendar must carry its original target approval")},verifyAccess={check(scope)},
            captureCalendar={c,run->captures++;CalendarTargetIntegrity.review(ConnectionExecutionScope.bind(c,run),checkNotNull(host.identity),"owner-a@example.invalid")},
            executeCalendar={c,run,approved->
                executes++;val result=runtime.executeApprovedCalendar(ConnectionExecutionScope.bind(c,run),approved)
                ToolResult(c.id,result.success,result.data,result.state,result.code)
            })
    }
    private suspend fun rejected(block:suspend()->Unit){try{block();fail()}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun actualSnapshotIsTakenBeforeOneOwnerReviewAndNotRecapturedAtExecute()=runBlocking {
        val f=Fixture();val c=call();val text=checkNotNull(f.connection.reviewForAction(c));assertTrue(text.contains("owner-a@example.invalid"))
        f.connection.confirmCalendarOwner(c,text);val result=f.connection.execute(c)
        assertTrue(result.content,result.success);assertEquals(1,f.host.posts)
        assertEquals(1,f.captures);assertEquals(1,f.executes);assertEquals(0,f.generic)
        assertFalse(result.content.contains("owner-a@example.invalid"))
    }
    @Test fun changedAccountAfterExactOwnerReviewIsRejectedWithoutAnyPost()=runBlocking {
        val f=Fixture();val c=call();val text=f.connection.reviewForAction(c);f.connection.confirmCalendarOwner(c,text)
        f.host.identity=ConnectionAccess("public-account-b",2,true,true)
        assertFalse(f.connection.execute(c).success);assertEquals(0,f.host.posts);assertEquals(1,f.captures)
    }
    @Test fun reviewedButUnconfirmedOrWrongDisplayedPreviewCannotExecute()=runBlocking {
        val f=Fixture();val c=call();val text=checkNotNull(f.connection.reviewForAction(c))
        rejected{f.connection.execute(c)};rejected{f.connection.confirmCalendarOwner(c,text+" changed")};assertEquals(0,f.executes)
    }
    @Test fun exactApprovalCannotMoveToAnotherCallOrPayload()=runBlocking {
        val f=Fixture();val c=call();f.connection.confirmCalendarOwner(c,f.connection.reviewForAction(c))
        rejected{f.connection.execute(c.copy(id="public-different-call"))}
        rejected{f.connection.execute(c.copy(arguments=JsonObject(c.arguments+("title" to JsonPrimitive("Other")))))}
        assertEquals(0,f.executes)
    }
    @Test fun stoppedRunAndRevokedChatScopeCannotReachTheConnector()=runBlocking {
        for(revokeScope in listOf(false,true)){
            val f=Fixture();val c=call();f.connection.confirmCalendarOwner(c,f.connection.reviewForAction(c))
            if(revokeScope)f.scope=false else f.live=false
            rejected{f.connection.execute(c)};assertEquals(0,f.executes)
        }
    }
    @Test fun markerIsSingleUseAndFreshRequestCannotBorrowIt()=runBlocking {
        val c=call();val f=Fixture();val text=checkNotNull(f.connection.reviewForAction(c));f.connection.confirmCalendarOwner(c,text)
        f.host.identity=ConnectionAccess("public-account-b",2,true,true);assertFalse(f.connection.execute(c).success)
        rejected{f.connection.execute(c)}
        val fresh=Fixture();rejected{fresh.connection.execute(c)};assertEquals(0,fresh.executes)
    }
    @Test fun snapshotDoesNotTrustModelAccountFieldsAndKeepsActiveDistinctFromAttendeeConfirmation(){
        val c=call();val extra=c.copy(arguments=JsonObject(c.arguments+("account" to JsonPrimitive("public-account-b"))))
        try{CalendarTargetIntegrity.review(extra,ConnectionAccess("public-account-a",1,true,true),"Owner");fail()}catch(_:IllegalArgumentException){}
        val p=CalendarTargetIntegrity.review(c,ConnectionAccess("public-account-a",1,true,true),"Owner")
        assertTrue(p.approvalText().contains("참석자 수신·수락 확인은 포함하지 않습니다."));assertFalse(p.toString().contains("Owner"))
    }
}
