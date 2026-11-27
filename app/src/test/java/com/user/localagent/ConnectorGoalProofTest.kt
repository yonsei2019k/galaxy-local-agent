package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.tools.CertificateReadback
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConnectorGoalProofTest {
    @Test fun certificateRequiresExplicitCompletionDateAndSingleCourse(){
        val body="교육 이수증\n과정명: 시험 과정\n수료일자: 2025.11.24\n발급일자: 2026.01.12"
        assertEquals("시험 과정" to 2025,CertificateReadback.courseAndYear(body))
        assertNull(CertificateReadback.courseAndYear(body.replace("수료일자:","갱신일자:")))
        assertNull(CertificateReadback.courseAndYear(body+"\n과정명: 다른 과정"))
        assertNull(CertificateReadback.courseAndYear(body.replace("11.24","02.31")))
    }
    @Test fun sentReceiptCannotCloseAnAttachmentOrDeliveryCriterion(){
        val call=ToolCall("x","send_gmail_message",buildJsonObject {put("to","team@example.org");put("subject","교육");put("body","자료")})
        val data=buildJsonObject {put("service","gmail");put("verified",true);put("sent",true);put("remoteId","a");put("mimeSha256","a".repeat(64))}
        val proof=checkNotNull(connectorGoalProof(call,data))
        assertTrue(proof.matchesOwnerConnectorFields("team@example.org에게 교육 자료 보내줘"))
        assertFalse(proof.matchesOwnerConnectorFields("my-team@example.org에게 교육 자료 보내줘"))
        assertFalse(proof.matchesOwnerConnectorFields("team@example.org.au로 교육 자료 보내줘"))
        val step=HarnessStep(call.name,call.arguments,StepEvidence(call.name,VerificationState.VERIFIED,"",outcomeProof=goalOutcomeDigest(proof)))
        assertTrue(goalCriterionMatches(GoalCriterion(text=proof.connectorCriterion(),proofTarget=proof),step))
        assertFalse(goalCriterionMatches(GoalCriterion(text="첨부한 파일 전달 확인",proofTarget=proof),step))
        assertNull(connectorGoalProof(call,JsonObject(data+("sent" to JsonPrimitive(false)))))
    }
    @Test fun calendarReceiptMustMatchTheExactCalendarAndBothInstants(){
        val args=buildJsonObject {put("calendarId","work");put("title","체크인");put("start","2026-11-28T15:00:00+09:00");put("end","2026-11-29T11:00:00+09:00")}
        val call=ToolCall("e","create_google_calendar_event",args)
        val event=buildJsonObject {put("status","confirmed");put("id","a");put("title","체크인");put("start","2026-11-28T06:00:00Z");put("end","2026-11-29T02:00:00Z")}
        val data=buildJsonObject {put("verified",true);put("calendarSelector","work");put("accountBindingSha256","c".repeat(64));put("eventActive",true);put("remoteId","a");put("event",event)}
        assertNotNull(connectorGoalProof(call,data))
        assertNull(connectorGoalProof(call,JsonObject(data+("calendarSelector" to JsonPrimitive("home")))))
        assertNull(connectorGoalProof(call,JsonObject(data+("event" to JsonObject(event+("end" to event.getValue("start")))))))
    }
}
