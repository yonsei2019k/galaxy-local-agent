package com.user.localagent.core.connections

import com.user.localagent.core.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OutlookConnectionTest {
    private val challenge=buildJsonObject {
        put("device_code","test-only-private");put("user_code","TEST123");put("expires_in",120);put("interval",5)
    }
    private fun status(error:String)=buildJsonObject {put("error",error)}

    @Test fun pollingSlowsDownAndKeepsDeviceCodePrivate()=runBlocking {
        var time=0L
        val delays=mutableListOf<Long>()
        val responses=ArrayDeque(listOf(status("authorization_pending"),status("slow_down"),buildJsonObject { put("access_token","test") }))
        val result=OutlookDeviceFlow.awaitToken(challenge,{time},{delays+=it;time+=it},{true},{assertEquals("TEST123",it)}){code->
            assertEquals("test-only-private",code);responses.removeFirst()
        }
        assertEquals(listOf(5000L,5000L,10000L),delays)
        assertEquals("test",result.getValue("access_token").jsonPrimitive.content)
    }
    @Test fun cancelledOrExpiredChallengeCannotFinishLogin()=runBlocking {
        var time=0L
        var active=true
        val expiry=runCatching { OutlookDeviceFlow.awaitToken(challenge,{time},{time=121000},{true},{}){error("unexpected poll")} }.exceptionOrNull()
        assertEquals("로그인 코드가 만료됐어요.",expiry?.message)
        val switched=runCatching { OutlookDeviceFlow.awaitToken(challenge,{0},{},{active},{}) {
            active=false;buildJsonObject {put("access_token","test")}
        } }.exceptionOrNull()
        assertNotNull(switched)
    }
    @Test fun ownerDeclineStopsAfterOnePoll()=runBlocking {
        var polls=0
        assertTrue(runCatching { OutlookDeviceFlow.awaitToken(challenge,{0},{},{true},{}) {
            polls++;status("authorization_declined")
        } }.isFailure)
        assertEquals(1,polls)
    }
    @Test fun graphReadsUseFixedTargetsAndOffsetTimes(){
        fun plan(name:String, args:JsonObject)=ConnectionRequests.build(ToolCall("test",name,args))
        val inbox=plan("list_outlook_inbox",buildJsonObject {})
        assertEquals(ConnectedService.OUTLOOK_MAIL,inbox.service)
        assertTrue(inbox.first.url.startsWith("https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?"))
        assertFalse(inbox.writes);assertFalse(inbox.first.url.contains("body"))
        fun calendar(end:String)=plan("list_outlook_calendar",buildJsonObject { put("start","2027-01-01T09:00:00+09:00");put("end",end) })
        val range=calendar("2027-01-01T10:00:00+09:00")
        assertEquals(ConnectedService.OUTLOOK_CALENDAR,range.service)
        assertTrue(range.first.url.contains("%2B09%3A00"))
        assertThrows(IllegalArgumentException::class.java){calendar("2027-01-01T08:00:00+09:00")}
    }
}
