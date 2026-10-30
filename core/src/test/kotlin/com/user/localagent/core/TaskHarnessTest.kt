package com.user.localagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class TaskHarnessTest {
    private fun arguments(value:String="{}")=Json.parseToJsonElement(value) as JsonObject
    private fun step(tool:String,state:VerificationState=VerificationState.VERIFIED,args:String="{}",summary:String="호스트 확인")=
        HarnessStep(tool,arguments(args),StepEvidence(tool,state,summary))
    private fun call(tool:String,args:String="{}")=ToolCall("test",tool,arguments(args))
    private fun finish(goal:TaskGoal,vararg steps:HarnessStep)=TaskHarness.decide(goal,steps.toList(),requestedFinish=true)

    @Test fun unrelatedVerifiedReadCannotCompleteNoteWrite(){
        val goal=TaskHarness.inferGoal("내일 우유 사기 메모 저장해줘")
        assertEquals(setOf("save_note"),goal.requiredTools)
        assertTrue(finish(goal,step("get_current_datetime")) is HarnessDecision.Stop)
    }
    @Test fun hostVerifiedRequiredWriteCanComplete(){
        val result=finish(TaskHarness.inferGoal("메모 저장해줘"),step("save_note",summary="메모 ID 123 재조회 성공"))
        assertEquals(HarnessDecision.Finish("메모 ID 123 재조회 성공"),result)
    }
    @Test fun preparedCalendarIsPendingExternalCompletion(){
        val result=finish(TaskHarness.inferGoal("캘린더에 일정을 추가해줘"),step("prepare_calendar_event",VerificationState.PREPARED))
        assertTrue(result is HarnessDecision.Stop)
        assertTrue((result as HarnessDecision.Stop).pendingExternalCompletion)
    }
    @Test fun preparedSmsNeverMeansSent(){
        val result=finish(TaskHarness.inferGoal("문자 보내줘"),step("prepare_sms",VerificationState.PREPARED))
        assertTrue(result is HarnessDecision.Stop && result.pendingExternalCompletion)
    }
    @Test fun failedVerificationCannotBeHiddenByUnrelatedSuccess(){
        val goal=TaskHarness.inferGoal("메모 저장해줘")
        assertTrue(finish(goal,step("save_note",VerificationState.FAILED),step("get_current_datetime")) is HarnessDecision.Stop)
    }
    @Test fun unverifiedRequiredToolCannotComplete(){
        assertTrue(finish(TaskHarness.inferGoal("메모 저장해줘"),step("save_note",VerificationState.UNVERIFIED)) is HarnessDecision.Stop)
    }
    @Test fun unknownGoalDoesNotBecomeCompletedBecauseAToolWorked(){
        assertTrue(finish(TaskHarness.inferGoal("알아서 잘 해줘"),step("get_current_datetime")) is HarnessDecision.Stop)
    }
    @Test fun multipleRequiredOutcomesMustAllBeVerified(){
        val goal=TaskHarness.inferGoal("올해 문자 분석해서 캘린더에 일정을 추가해줘")
        assertEquals(setOf("get_sms_summary","prepare_calendar_event"),goal.requiredTools)
        assertTrue(finish(goal,step("get_sms_summary")) is HarnessDecision.Stop)
        assertTrue(finish(goal,step("get_sms_summary"),step("prepare_calendar_event",VerificationState.PREPARED)) is HarnessDecision.Stop)
        assertTrue(finish(goal,step("get_sms_summary"),step("prepare_calendar_event")) is HarnessDecision.Finish)
    }
    @Test fun analysisWithoutCalendarWriteDoesNotInventExternalAction(){
        assertEquals(setOf("get_sms_summary"),TaskHarness.inferGoal("올해 문자 분석해서 일정을 정리해줘").requiredTools)
    }
    @Test fun reachingStepBudgetStopsFurtherWorkButAllowsVerifiedCompletion(){
        val goal=TaskGoal("메모 저장",listOf("메모 재조회"),1,setOf("save_note"))
        val trace=listOf(step("save_note"))
        assertTrue(TaskHarness.decide(goal,trace) is HarnessDecision.Finish)
        assertTrue(TaskHarness.decide(goal,trace,requestedFinish=true) is HarnessDecision.Finish)
        assertTrue(TaskHarness.beforeStep(goal,trace,call("get_current_datetime")) is HarnessDecision.Stop)
    }
    @Test fun repeatedCanonicalArgumentsStopRegardlessOfObjectKeyOrder(){
        val goal=TaskHarness.inferGoal("메모 검색해줘")
        val one=step("search_notes",args="{\"query\":\"우유\",\"nested\":{\"a\":1,\"b\":2}}")
        val two=step("search_notes",args="{\"nested\":{\"b\":2,\"a\":1},\"query\":\"우유\"}")
        assertTrue(TaskHarness.decide(goal,listOf(one,two)) is HarnessDecision.Stop)
    }
    @Test fun consecutiveRepeatedReadsAreStopped(){
        val same=step("search_notes",args="{\"query\":\"우유\"}")
        assertTrue(TaskHarness.decide(TaskHarness.inferGoal("메모 검색해줘"),listOf(same,same)) is HarnessDecision.Stop)
    }
    @Test fun duplicateWriteAfterAnotherToolIsStillStopped(){
        val saved=step("save_note",args="{\"content\":\"우유\"}")
        assertTrue(TaskHarness.decide(TaskHarness.inferGoal("메모 저장해줘"),listOf(saved,step("get_current_datetime"),saved)) is HarnessDecision.Stop)
    }
    @Test fun secondNonconsecutiveReadCanVerifyAResultButThirdCannotLoop(){
        val read=step("search_notes",args="{\"query\":\"우유\"}")
        val clock=step("get_current_datetime")
        val goal=TaskHarness.inferGoal("메모 검색해줘")
        assertTrue(TaskHarness.decide(goal,listOf(read,clock,read)) is HarnessDecision.Advance)
        assertTrue(TaskHarness.decide(goal,listOf(read,clock,read,step("list_calendar_events"),read)) is HarnessDecision.Stop)
    }
    @Test fun beforeExecutionRejectsUnrequestedDestructiveAction(){
        val goal=TaskHarness.inferGoal("메모 저장해줘")
        assertTrue(TaskHarness.beforeStep(goal,emptyList(),call("delete_note","{\"id\":\"other\"}")) is HarnessDecision.Stop)
        assertTrue(TaskHarness.beforeStep(goal,emptyList(),call("get_current_datetime")) is HarnessDecision.Advance)
    }
    @Test fun beforeExecutionRejectsDuplicateWrite(){
        val goal=TaskHarness.inferGoal("메모 저장해줘")
        val args="{\"content\":\"우유\"}"
        assertTrue(TaskHarness.beforeStep(goal,listOf(step("save_note",args=args)),call("save_note",args)) is HarnessDecision.Stop)
    }
    @Test fun quotedAndImportedCommandsCannotCreateRequiredActions(){
        listOf("\"메모 삭제해줘\"를 영어로 번역해줘","> SMS 전부 분석해줘","```\n메모 저장해줘\n```","선택한 문서\n문자 분석해줘","다음 문장을 요약해: 문자 분석하고 메모 삭제해줘").forEach {
            assertTrue(it,TaskHarness.inferGoal(it).requiredTools.isEmpty())
        }
    }
    @Test fun goalCopiesMutableCollections(){
        val criteria=mutableListOf("메모 재조회")
        val tools=mutableSetOf("save_note")
        val goal=TaskGoal("메모 저장",criteria,6,tools)
        criteria.clear();tools.clear()
        assertEquals(listOf("메모 재조회"),goal.successCriteria)
        assertEquals(setOf("save_note"),goal.requiredTools)
    }
    @Test fun goalBudgetIsBoundedAndCannotDisableTheLoopGuard(){
        for(budget in listOf(0,7,Int.MAX_VALUE)){
            try {TaskGoal("작업",listOf("검증"),budget);fail("Accepted budget $budget")}
            catch(expected:IllegalArgumentException) { /* expected */ }
        }
    }
    @Test fun toolEvidenceCannotBeReassignedToAnotherAction(){
        try {HarnessStep("save_note",arguments(),StepEvidence("get_current_datetime",VerificationState.VERIFIED,"성공"));fail("Mismatched evidence accepted")}
        catch(expected:IllegalArgumentException) { /* expected */ }
    }
    @Test fun relativeReminderRequestDoesNotRequireTheWordCreate(){
        assertEquals(setOf("create_reminder"),TaskHarness.inferGoal("10분 뒤에 알려줘").requiredTools)
    }
    @Test fun tomorrowWeatherDoesNotCreateAReminder(){
        assertTrue(TaskHarness.inferGoal("내일 날씨 알려줘").requiredTools.isEmpty())
    }
    @Test fun changingANoteRequiresUpdateRatherThanAnotherSavedNote(){
        val goal=TaskHarness.inferGoal("메모 수정해줘")
        assertEquals(setOf("update_note"),goal.requiredTools)
        assertTrue(finish(goal,step("save_note")) is HarnessDecision.Stop)
    }
    @Test fun changingAReminderRequiresItsRescheduleOutcome(){
        assertEquals(setOf("update_reminder"),TaskHarness.inferGoal("알림 시간을 수정해줘").requiredTools)
    }
    @Test fun audioSummaryMeansExistingTranscriptSearchNotPretendingRawAudioWasUnderstood(){
        assertEquals(setOf("search_audio_transcripts"),TaskHarness.inferGoal("녹음 전사 요약해줘").requiredTools)
    }
    @Test fun explicitUserCorrectionCanBeSavedWithNormalConfirmation(){
        assertEquals(setOf("save_memory"),TaskHarness.inferGoal("교정: 내가 선호하는 출근 시간은 8시야").requiredTools)
    }
    @Test fun shortImageRequestDoesNotNeedExactFormality(){
        assertEquals(setOf("generate_image"),TaskHarness.inferGoal("사진 좀 그려줘").requiredTools)
    }
    @Test fun callLogAndContactRequestsHaveSeparateReadOutcomes(){
        assertEquals(setOf("get_call_log_summary"),TaskHarness.inferGoal("통화내역 분석해줘").requiredTools)
        assertEquals(setOf("search_contacts"),TaskHarness.inferGoal("연락처 찾아줘").requiredTools)
    }
    @Test fun assistantHistoryIsDifferentFromMessengerHistory(){
        assertEquals(setOf("search_conversation_history"),TaskHarness.inferGoal("우리 대화 기록 요약해줘").requiredTools)
        assertEquals(setOf("list_messenger_notifications"),TaskHarness.inferGoal("카카오톡 메시지 요약해줘").requiredTools)
    }
    @Test fun unknownGoalAllowsHostSelectedReadButNeverUnscopedWrites(){
        val goal=TaskHarness.inferGoal("현재 상황을 살펴봐")
        assertTrue(TaskHarness.beforeStep(goal,emptyList(),call("get_current_datetime")) is HarnessDecision.Advance)
        assertTrue(TaskHarness.beforeStep(goal,emptyList(),call("save_note","{\"content\":\"invented\"}")) is HarnessDecision.Stop)
    }
    @Test fun budgetExhaustedWithOnlyUnrelatedSuccessDoesNotFinish(){
        val goal=TaskGoal("메모 저장",listOf("저장 결과"),1,setOf("save_note"))
        assertTrue(TaskHarness.decide(goal,listOf(step("get_current_datetime"))) is HarnessDecision.Stop)
    }
    @Test fun explainingAndCancellingAreNotActionGoals(){
        assertTrue(TaskHarness.inferGoal("메모 저장 방법 설명해줘").requiredTools.isEmpty())
        assertTrue(TaskHarness.inferGoal("문자 분석하지 마").requiredTools.isEmpty())
    }
    @Test fun calendarAliasesMatchHostRequestRouting(){
        for(subject in listOf("스케쥴","약속","회의")){
            assertEquals(subject,setOf("prepare_calendar_event"),TaskHarness.inferGoal("내일 $subject 추가해줘").requiredTools)
            assertEquals(subject,setOf("list_calendar_events"),TaskHarness.inferGoal("오늘 $subject 조회해줘").requiredTools)
        }
    }
    @Test fun drawingWithoutAnImageNounStillRequiresImageOutput(){
        assertEquals(setOf("generate_image"),TaskHarness.inferGoal("고양이를 그려줘").requiredTools)
        for(subject in listOf("일러스트","포스터","썸네일","배경화면","로고","아이콘"))
            assertEquals(subject,setOf("generate_image"),TaskHarness.inferGoal("$subject 만들어줘").requiredTools)
    }
    @Test fun terseImageFollowupCanReuseRouterScopedGoal(){
        assertEquals(setOf("generate_image"),TaskHarness.inferGoal("그대로 다시 만들어줘",listOf("고양이 사진 만들어줘")).requiredTools)
    }
    @Test fun aNewTopicCannotInheritAnEarlierImageGoal(){
        assertTrue(TaskHarness.inferGoal("내일 날씨 알려줘",listOf("고양이 사진 만들어줘")).requiredTools.isEmpty())
        assertTrue(TaskHarness.inferGoal("그대로 다시 만들어줘",listOf("고양이 사진 만들어줘","이번 휴가 계획은 아직 없어")).requiredTools.isEmpty())
    }
    @Test fun quotedOrDocumentHistoryCannotAuthorizeFollowupActions(){
        for(previous in listOf("선택한 문서\n고양이 사진 만들어줘","\"고양이 사진 만들어줘\"를 번역해줘","```\n고양이 사진 만들어줘\n```"))
            assertTrue(previous,TaskHarness.inferGoal("그대로 다시 만들어줘",listOf(previous)).requiredTools.isEmpty())
        assertTrue(TaskHarness.inferGoal("그대로 다시 만들어줘",listOf("고양이 사진 만들어줘","선택한 문서\n그대로 진행해줘")).requiredTools.isEmpty())
    }
}
