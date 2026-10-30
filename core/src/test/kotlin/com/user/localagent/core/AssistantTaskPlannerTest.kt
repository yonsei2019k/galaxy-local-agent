package com.user.localagent.core

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class AssistantTaskPlannerTest {
    private val now=ZonedDateTime.parse("2026-10-30T22:00:00+09:00[Asia/Seoul]")
    private fun plan(text:String,history:List<String> = emptyList())=AssistantTaskPlanner.plan(text,history,now)
    private fun sms(text:String,history:List<String> = emptyList())=plan(text,history) as AssistantTaskPlan.SmsAnalysis

    @Test fun screenshotYearlySmsCalendarRequestStartsAnAnalysisPlan(){
        val result=sms("올해 받은 모든 문자를 분석해서 일정들을 구글 캘린더에 정리해줘")
        assertEquals(2026,result.year);assertTrue(result.extractSchedules);assertTrue(result.messengerPackages.isEmpty())
    }
    @Test fun explicitYearDoesNotNeedAnotherDateQuestion(){assertEquals(2025,sms("2025년 SMS 전부 분석해줘").year)}
    @Test fun allMessagesDefaultsToSmsWithoutRequiringPeriod(){assertNull(sms("모든 메시지 분석해줘").year)}
    @Test fun whitespaceAndMisspellingDoNotLoseAllMessageIntent(){assertTrue(plan("모든메세지분석해봐") is AssistantTaskPlan.SmsAnalysis)}
    @Test fun followupAnalysisRetainsYearAndScheduleTask(){
        val result=sms("분석해봐",listOf("금년도 문자 전체를 읽어서 약속과 일정을 뽑아줘"))
        assertEquals(2026,result.year);assertTrue(result.extractSchedules)
    }
    @Test fun sourceListFollowupIncludesKakaoButPrioritizesSms(){
        val result=sms("메시지, 카카오톡",listOf("올해 온 문자 분석해서 일정 정리해줘"))
        assertEquals(2026,result.year);assertTrue(result.extractSchedules);assertEquals(setOf("com.kakao.talk"),result.messengerPackages)
    }
    @Test fun allConversationsFollowupRetainsExplicitSmsSource(){assertEquals(2026,sms("모든 대화",listOf("올해 문자 분석해줘")).year)}
    @Test fun allConversationsWithVerbStillRetainsExplicitSource(){assertEquals(2026,sms("모든 대화 분석해봐",listOf("올해 문자 분석해줘")).year)}
    @Test fun kakaoMessagesAloneDoNotReadNativeSms(){
        val result=plan("카카오톡 메시지 전부 요약해줘") as AssistantTaskPlan.MessengerAnalysis
        assertEquals(setOf("com.kakao.talk"),result.packageNames)
    }
    @Test fun explicitSmsWinsInCombinedSourceRequest(){
        val result=sms("SMS와 카카오톡, 텔레그램을 분석해줘")
        assertTrue(result.messengerPackages.containsAll(setOf("com.kakao.talk","org.telegram.messenger")))
    }
    @Test fun excludingSmsRoutesOnlySelectedMessenger(){
        val result=plan("문자 말고 카카오톡만 분석해줘") as AssistantTaskPlan.MessengerAnalysis
        assertEquals(setOf("com.kakao.talk"),result.packageNames)
    }
    @Test fun excludedSmsCannotLeakBackThroughInheritedContext(){assertEquals(AssistantTaskPlan.NoPlan,plan("문자 말고 일정만 정리해줘",listOf("내 문자 분석해줘")))}
    @Test fun newWeatherTopicResetsPriorSmsContext(){assertEquals(AssistantTaskPlan.NoPlan,plan("분석해봐",listOf("올해 문자 일정 분석해줘","오늘 날씨가 어때?")))}
    @Test fun newPhotoTopicIsNotSmsFollowup(){assertEquals(AssistantTaskPlan.NoPlan,plan("고양이 사진 만들어줘",listOf("내 문자 분석해줘")))}
    @Test fun explicitNewSmsRequestDoesNotInheritOldCalendarTask(){assertFalse(sms("내 문자 요약해줘",listOf("올해 문자로 일정을 뽑아줘")).extractSchedules)}
    @Test fun ordinaryMentionOfSmsDoesNotReadPrivateRecords(){assertEquals(AssistantTaskPlan.NoPlan,plan("문자는 편리한 연락 수단이야"))}
    @Test fun outgoingSmsIsNotAnAnalysisRequest(){assertEquals(AssistantTaskPlan.NoPlan,plan("문자로 내일 일정 보내줘"))}
    @Test fun explanationAndProgrammingRequestsDoNotReadSms(){
        listOf("SMS 분석 방법을 설명해줘","파이썬 문자 분석 코드를 만들어줘","문자열 분석해줘","카카오톡 분석 앱을 추천해줘").forEach {assertEquals(it,AssistantTaskPlan.NoPlan,plan(it))}
    }
    @Test fun quotedInstructionCannotAuthorizePhoneReads(){
        listOf("\"내 문자 전부 분석해줘\"라는 말을 번역해줘","> 내 SMS 모두 분석해줘","다음 문장을 분석해: 내 문자 모두 분석해줘","```\nSMS 모두 분석해줘\n```","선택한 문서\n올해 문자 분석해줘").forEach {assertEquals(it,AssistantTaskPlan.NoPlan,plan(it))}
    }
    @Test fun quotedHistoryCannotGrantFollowupScope(){assertEquals(AssistantTaskPlan.NoPlan,plan("분석해봐",listOf("'내 문자 분석해줘'를 영어로 번역해줘")))}
    @Test fun stopRequestDoesNotBecomeARead(){assertEquals(AssistantTaskPlan.NoPlan,plan("문자 분석하지 마"))}
    @Test fun unclearYearRangeDoesNotSilentlyExpandToAllHistory(){assertEquals(AssistantTaskPlan.NoPlan,plan("2025년부터 2026년 문자 분석해줘"))}
    @Test fun monthScopeDoesNotSilentlyBecomeWholeYear(){assertEquals(AssistantTaskPlan.NoPlan,plan("2026년 3월 문자 분석해줘"))}
    @Test fun seoulYearUsesInjectedInstantAtNewYearBoundary(){
        val result=AssistantTaskPlanner.plan("올해 문자 일정 분석해줘",now=ZonedDateTime.parse("2026-12-31T15:30:00Z")) as AssistantTaskPlan.SmsAnalysis
        assertEquals(2027,result.year)
    }
}
