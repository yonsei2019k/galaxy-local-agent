package com.user.localagent.core.connections

import com.user.localagent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CalendarCompletionReportTest {
    private suspend fun observed(status:String="confirmed"):ObservedToolOutcome {
        val c=CalendarTargetRegressionTest.call();val h=CalendarTargetRegressionTest.Host().also{it.status=status}
        val r=calendarTestExecute(ConnectionRuntime(h),c,CalendarTargetIntegrity.review(c,checkNotNull(h.identity),"Owner"))
        return ObservedToolOutcome(c,ToolResult(c.id,r.success,r.data,r.state,r.code))
    }
    @Test fun actualActiveReceiptRendersCreationAndKeepsAttendeeConfirmationUnknown()=runBlocking {
        val o=observed("tentative");assertTrue(ActionCompletionReport.manages(setOf(o.call.name),listOf(o)))
        val report=ActionCompletionReport.compose(setOf(o.call.name),listOf(o))
        val card=report.receipts.single()
        assertEquals(ReceiptStatus.VERIFIED,card.status);assertTrue(card.verified);assertTrue(report.complete)
        assertEquals("받았는지·수락했는지 모름",card.fields.single{it.label=="초대"}.value)
        assertEquals(listOf("일정","시작","끝","초대"),card.fields.map{it.label})
        assertFalse(ActionCompletionReport.render(setOf(o.call.name),listOf(o)).contains("부탁한 일이 다 끝났는지는 아직 모름"))
        val receipt=Json.parseToJsonElement(o.result.content).jsonObject
        assertEquals(false,receipt["attendeeAcceptanceConfirmed"]!!.jsonPrimitive.boolean)
        assertFalse(o.result.content.contains("Owner"))
    }
    @Test fun malformedOrOtherTargetVerifiedFlagAloneCannotBecomeHostCompletion()=runBlocking {
        val good=observed();val original=Json.parseToJsonElement(good.result.content).jsonObject
        for(bad in listOf(buildJsonObject{put("verified",true)},JsonObject(original+("calendarSelector" to JsonPrimitive("other-calendar"))),
            JsonObject(original+("event" to JsonObject(original["event"]!!.jsonObject+("id" to JsonPrimitive("other-event"))))))){
            val outcome=good.copy(result=good.result.copy(content=bad.toString()))
            val report=ActionCompletionReport.compose(setOf(good.call.name),listOf(outcome))
            assertEquals(ReceiptStatus.UNVERIFIED,report.receipts.single().status);assertFalse(report.receipts.single().verified)
            assertFalse(report.complete);assertNull(report.receipts.single().link)
        }
    }
    @Test fun unverifiedCalendarEffectCannotBeReportedAsCreated()=runBlocking {
        val o=observed("cancelled");assertFalse(o.result.success)
        val report=ActionCompletionReport.compose(setOf(o.call.name),listOf(o))
        assertEquals(ReceiptStatus.FAILED,report.receipts.single().status);assertFalse(report.complete)
        assertFalse(report.receipts.single().fields.any{it.label=="일정"})
    }

    @Test fun privateEventRequiresVerifiedVisibilityAndHasAnOfficialEventLinkOnly()=runBlocking {
        val original=CalendarTargetRegressionTest.call()
        val call=original.copy(arguments=JsonObject(original.arguments+("private" to JsonPrimitive(true))))
        val plan=ConnectionRequests.build(call)
        val body=Json.parseToJsonElement(checkNotNull(plan.first.body)).jsonObject
        assertEquals(JsonPrimitive("private"),body["visibility"])
        assertFalse("attendees" in body)
        assertTrue(plan.first.url.endsWith("?sendUpdates=none"))
        val official="https://www.google.com/calendar/event?eid=public_fixture"
        val verifiedHost=CalendarTargetRegressionTest.Host(call).also{it.visibility="private";it.htmlLink=official}
        suspend fun result(host:CalendarTargetRegressionTest.Host)=calendarTestExecute(
            ConnectionRuntime(host),call,CalendarTargetIntegrity.review(call,checkNotNull(host.identity),"Owner"))
        val approved=CalendarTargetIntegrity.review(call,checkNotNull(verifiedHost.identity),"Owner")
        assertTrue(approved.approvalText().contains("비공개"))
        assertFalse(CalendarTargetIntegrity.matches(
            CalendarTargetIntegrity.ownerConfirmed(approved,approved.approvalText()),original,verifiedHost.identity!!))
        val observed=result(verifiedHost)
        assertTrue(observed.success)
        fun report(value:ConnectionOutcome)=ActionCompletionReport.compose(setOf(call.name),listOf(ObservedToolOutcome(
            call,ToolResult(call.id,value.success,value.data,value.state,value.code)))).receipts.single()
        assertEquals("비공개 · 초대 없음",report(observed).fields.single{it.label=="초대"}.value)
        assertEquals(official,report(observed).link)
        assertFalse(result(CalendarTargetRegressionTest.Host(call)).success)
        assertFalse(result(CalendarTargetRegressionTest.Host(call).also{
            it.visibility="private";it.attendees=buildJsonArray {add(buildJsonObject {put("email","fixture@example.org")})}
        }).success)
        val untrusted=result(CalendarTargetRegressionTest.Host(call).also{
            it.visibility="private";it.htmlLink="https://www.google.com/url?url=https://example.org"
        })
        assertTrue(untrusted.success)
        assertNull(report(untrusted).link)
        assertFalse(report(untrusted).fields.any{it.value.contains("https://")})
    }
}
