package com.user.localagent.core.connections

import com.user.localagent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The same expectations run against baseline and candidate; adapters only reflect their execute signatures. */
class CalendarTargetRegressionTest {
    companion object {
        fun call()=ToolCall("public-calendar-op",CalendarTargetIntegrity.CREATE,buildJsonObject{
            put("title","Synthetic meeting");put("start","2026-11-15T09:00:00+09:00");put("end","2026-11-15T10:00:00+09:00")
        })
    }
    class Host(sourceCall:ToolCall=call()):ConnectionHost {
        var identity:ConnectionAccess?=ConnectionAccess("public-account-a",1,true,true)
        var postId:String?="expected";var getId:String?="expected";var status:String?="confirmed";var title="Synthetic meeting"
        var posts=0;var gets=0;var afterPost:(()->Unit)?=null
        var visibility:String?=null
        var attendees:JsonArray?=null
        var htmlLink:String?=null
        private val records=mutableMapOf<String,MutationRecord>()
        val expectedId=checkNotNull(ConnectionRequests.build(sourceCall).knownId)
        override suspend fun access(service:ConnectedService)=identity
        override fun mutation(key:String)=records[key]
        override fun saveMutation(key:String,record:MutationRecord){records[key]=record}
        override fun reserveRequest(service:ConnectedService)=true
        override suspend fun send(service:ConnectedService,request:ApiRequest):ApiResponse {
            if(request.method=="POST"){
                posts++;afterPost?.invoke()
                return ApiResponse(200,buildJsonObject{postId?.let{put("id",if(it=="expected")expectedId else it)}}.toString())
            }
            gets++
            assertTrue(request.url.endsWith("/events/$expectedId"))
            return ApiResponse(200,buildJsonObject{
                getId?.let{put("id",if(it=="expected")expectedId else it)};status?.let{put("status",it)};put("summary",title)
                put("start",buildJsonObject{put("dateTime","2026-11-15T00:00:00Z")})
                put("end",buildJsonObject{put("dateTime","2026-11-15T01:00:00Z")})
                visibility?.let{put("visibility",it)}
                attendees?.let{put("attendees",it)}
                htmlLink?.let{put("htmlLink",it)}
            }.toString())
        }
    }
    private fun review(h:Host)=CalendarTargetIntegrity.review(call(),checkNotNull(h.identity),"Owner A public fixture")
    @Test fun accountChangedAfterPreviewMustNotReceiveTheApprovedWrite()=runBlocking {
        val h=Host();val approvedA=review(h);h.identity=ConnectionAccess("public-account-b",2,true,true)
        val r=calendarTestExecute(ConnectionRuntime(h),call(),approvedA)
        assertFalse("Account B must not inherit account A's approval",r.success);assertEquals(0,h.posts);assertEquals(0,h.gets)
    }
    @Test fun generationChangedAfterPreviewMustNotInheritApproval()=runBlocking {
        val h=Host();val old=review(h);h.identity=h.identity!!.copy(generation=2)
        assertFalse(calendarTestExecute(ConnectionRuntime(h),call(),old).success);assertEquals(0,h.posts)
    }
    @Test fun noActualApprovalMustStartNoWrite()=runBlocking {
        val h=Host();assertFalse(calendarTestExecute(ConnectionRuntime(h),call(),null).success);assertEquals(0,h.posts)
    }
    @Test fun wrongPostCreatedIdMustRemainUnverifiedWithoutAutoRepost()=runBlocking {
        for(id in listOf("public-other-event",null)){
            val h=Host().also{it.postId=id};val runtime=ConnectionRuntime(h);val approved=review(h)
            val r=calendarTestExecute(runtime,call(),approved)
            assertFalse("POST id=$id",r.success);assertNotEquals(VerificationState.VERIFIED,r.state);assertEquals(1,h.posts)
            calendarTestExecute(runtime,call(),approved);assertEquals("Never repeat the uncertain POST",1,h.posts)
        }
    }
    @Test fun wrongGetEventIdMustNotVerifyEvenWhenTitleAndTimesMatch()=runBlocking {
        for(id in listOf("public-other-event",null)){
            val h=Host().also{it.getId=id};val r=calendarTestExecute(ConnectionRuntime(h),call(),review(h))
            assertFalse("GET id=$id",r.success);assertNotEquals(VerificationState.VERIFIED,r.state)
        }
    }
    @Test fun cancelledMissingAndUnknownStatusMustNotVerify()=runBlocking {
        for(status in listOf("cancelled",null,"public-unknown")){
            val h=Host().also{it.status=status};val r=calendarTestExecute(ConnectionRuntime(h),call(),review(h))
            assertFalse("status=$status",r.success);assertNotEquals(VerificationState.VERIFIED,r.state)
        }
    }
    @Test fun sameApprovedAccountExactActiveEventCanVerify()=runBlocking {
        for(status in listOf("confirmed","tentative")){
            val h=Host().also{it.status=status};val r=calendarTestExecute(ConnectionRuntime(h),call(),review(h))
            assertTrue(r.data,r.success);assertEquals(VerificationState.VERIFIED,r.state);assertEquals(1,h.posts);assertEquals(1,h.gets)
        }
    }
    @Test fun titleMismatchAndDuringExecutionAuthorityChangeStayUnverified()=runBlocking {
        val mismatch=Host().also{it.title="Other title"};assertFalse(calendarTestExecute(ConnectionRuntime(mismatch),call(),review(mismatch)).success)
        val revoked=Host();val approved=review(revoked);revoked.afterPost={revoked.identity=revoked.identity!!.copy(generation=2)}
        assertFalse(calendarTestExecute(ConnectionRuntime(revoked),call(),approved).success);assertEquals(1,revoked.posts);assertEquals(0,revoked.gets)
    }
}
