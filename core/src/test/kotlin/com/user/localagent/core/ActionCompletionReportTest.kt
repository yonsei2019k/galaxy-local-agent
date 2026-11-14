package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActionCompletionReportTest {
    private fun observed(tool:String,state:VerificationState,success:Boolean=true,args:JsonObject=buildJsonObject{},body:String="실제 결과")=
        ObservedToolOutcome(ToolCall("fixture",tool,args),ToolResult("fixture",success,body,state))
    @Test fun noEvidenceCannotBecomeCompletion(){
        val text=ActionCompletionReport.render(setOf("save_note"),emptyList())
        assertTrue(text.contains("완료를 확인한 작업이 없습니다"));assertTrue(text.contains("완료는 확인되지"))
    }
    @Test fun exactVerifiedStoredPayloadIsDisplayedWithoutModelRewriting(){
        val body="B2 기둥 17번\n두  칸 😀"
        val text=ActionCompletionReport.render(setOf("save_note"),listOf(observed("save_note",VerificationState.VERIFIED,args=buildJsonObject{put("content",body);put("title","원문")})))
        assertTrue(text.endsWith(body));assertFalse(text.contains("17 번"))
    }
    @Test fun intentReceiptCannotClaimScreenOrSendCompletion(){
        val text=ActionCompletionReport.render(setOf("prepare_sms"),listOf(observed("prepare_sms",VerificationState.PREPARED,args=buildJsonObject{put("phoneNumber","00000000002");put("message","안내")})))
        assertTrue(text.contains("실제 화면 표시·저장·발송·길 안내 시작은 확인되지 않았습니다"));assertTrue(text.contains("00000000002"));assertFalse(text.contains("발송했습니다"))
    }
    @Test fun failedAndUnverifiedWorkRetainsItsActualReason(){
        listOf(VerificationState.FAILED,VerificationState.UNVERIFIED).forEach{state->
            val text=ActionCompletionReport.render(setOf("search_contacts"),listOf(observed("search_contacts",state,false,body="연락처 권한 거부")))
            assertTrue(text.contains("권한 거부"));assertTrue(text.contains("결과 미확인"))
        }
    }
}
