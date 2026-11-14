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
        val text=ActionCompletionReport.render(setOf(o.call.name),listOf(o))
        assertTrue(text.contains("일정 생성 후 실제 ID·활성 상태"));assertTrue(text.contains("참석자 수신·수락은 확인하지 않았습니다."))
        assertFalse(text.contains("전체 요청의 완료는 확인되지 않았습니다."))
        val receipt=Json.parseToJsonElement(o.result.content).jsonObject
        assertEquals(false,receipt["attendeeAcceptanceConfirmed"]!!.jsonPrimitive.boolean)
        assertFalse(o.result.content.contains("Owner"))
    }
    @Test fun malformedOrOtherTargetVerifiedFlagAloneCannotBecomeHostCompletion()=runBlocking {
        val good=observed();val original=Json.parseToJsonElement(good.result.content).jsonObject
        for(bad in listOf(buildJsonObject{put("verified",true)},JsonObject(original+("calendarSelector" to JsonPrimitive("other-calendar"))),
            JsonObject(original+("event" to JsonObject(original["event"]!!.jsonObject+("id" to JsonPrimitive("other-event"))))))){
            val outcome=good.copy(result=good.result.copy(content=bad.toString()))
            val text=ActionCompletionReport.render(setOf(good.call.name),listOf(outcome))
            assertFalse(text.contains("생성 후 실제 ID"));assertTrue(text.contains("저장 근거를 확인하지 못했습니다."))
        }
    }
    @Test fun unverifiedCalendarEffectCannotBeReportedAsCreated()=runBlocking {
        val o=observed("cancelled");assertFalse(o.result.success)
        val text=ActionCompletionReport.render(setOf(o.call.name),listOf(o))
        assertFalse(text.contains("생성 후 실제 ID"));assertTrue(text.contains("실행 결과 미확인"))
    }
}
