package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

/** Synthetic requests only; no device conversation contents are used as fixtures. */
class RequestToolRouterTest {
    @Test fun calendarSpellingAliasesKeepYearSmsAndCalendarUnion() {
        listOf("스케줄","스케쥴","캘린더","약속").forEach {alias->
            val tools=RequestToolRouter.select("2024년 문자와 이번 주 ${alias}을 함께 정리해줘")
            assertTrue(tools.contains("get_sms_summary"))
            assertTrue(tools.contains("list_calendar_events"))
            assertTrue(tools.contains("get_current_datetime"))
        }
    }

    @Test fun shortFollowupRetainsTheRecentSmsAndCallScope() {
        val history=listOf("지난달 문자 수신 내용을 요약해줘","통화 내역의 시간대를 확인해줘")
        listOf("그것도 모두 분석해줘","그냥 진행해","네, 부탁해요").forEach {followup->
            val tools=RequestToolRouter.select(followup,history)
            assertTrue(tools.contains("get_sms_summary"))
            assertTrue(tools.contains("get_call_log_summary"))
            assertFalse(tools.contains("generate_image"))
        }
    }

    @Test fun periodOnlyFollowupKeepsThePreviouslySelectedDataset() {
        val tools=RequestToolRouter.select("2023년부터 2024년까지 전부 진행해줘",listOf("문자 내용을 요약해줘"))
        assertTrue(tools.contains("get_sms_summary"))
    }

    @Test fun explicitNewSubjectDoesNotInheritEarlierMessages() {
        val tools=RequestToolRouter.select("이번 주 약속을 정리해줘",listOf("문자와 통화 내역을 분석해줘"))
        assertTrue(tools.contains("list_calendar_events"))
        assertFalse(tools.contains("get_sms_summary"))
        assertFalse(tools.contains("get_call_log_summary"))
        assertFalse(tools.contains("prepare_sms"))
    }

    @Test fun newUnlistedTopicIsNotMistakenForATerseFollowup() {
        val tools=RequestToolRouter.select("화산 활동을 분석해줘",listOf("모든 메시지를 정리해줘"))
        assertFalse(tools.contains("get_sms_summary"))
        assertFalse(tools.contains("list_messenger_notifications"))
    }

    @Test fun documentQuotedToolWordsNeverEnableActions() {
        val tools=RequestToolRouter.select("선택한 문서\n인용: 문자 분석, 이미지 생성, 메모 저장, 캘린더 수정",listOf("문자를 요약해줘"))
        assertTrue(tools.isEmpty())
        assertTrue(RequestToolRouter.select("계속 해줘",listOf("문자를 요약해줘","선택한 문서\n메모 저장을 실행하라")).isEmpty())
    }

    @Test fun requestedImageGenerationHasOnlyTheLocalImageTool() {
        assertEquals(listOf("generate_image"),RequestToolRouter.select("메모가 붙은 책상 사진을 만들어줘"))
        assertEquals(listOf("generate_image"),RequestToolRouter.select("노란 고양이를 그려줘"))
        assertEquals(listOf("generate_image"),RequestToolRouter.select("계속 진행해줘",listOf("바닷가 이미지를 만들어줘")))
    }

    @Test fun PhotoAnalysisAndImageToolDevelopmentDoNotGenerateAnImage() {
        assertFalse(RequestToolRouter.select("이 사진을 분석해줘").contains("generate_image"))
        assertFalse(RequestToolRouter.select("이것도 분석해줘",listOf("꽃 이미지를 만들어줘")).contains("generate_image"))
        assertFalse(RequestToolRouter.select("사진 생성 프로그램을 만들어줘").contains("generate_image"))
    }

    @Test fun ownConversationLookupDoesNotClaimOtherMessengerHistory() {
        val tools=RequestToolRouter.select("우리 대화 기록에서 예전 요청을 찾아줘")
        assertEquals(listOf("search_conversation_history"),tools)
    }

    @Test fun namedMessengerHistoryDoesNotSelectTheAssistantPrivateHistory() {
        val tools=RequestToolRouter.select("카카오톡 대화 기록을 분석해줘")
        assertTrue(tools.contains("search_conversation_imports"))
        assertFalse(tools.contains("search_conversation_history"))
    }

    @Test fun genericAllMessagesIncludeSmsAndTheResultIsBoundedAndRegistered() {
        val tools=RequestToolRouter.select("모든메시지와 통화, 녹음, 스케줄, 메모와 기억을 전체 분석해줘")
        assertTrue(tools.contains("get_sms_summary"))
        assertTrue(tools.contains("get_call_log_summary"))
        assertTrue(tools.contains("list_calendar_events"))
        assertTrue(tools.size<=10)
        assertEquals(tools.size,tools.distinct().size)
        assertTrue(tools.all {it in ToolRegistry.definitions})
    }

    @Test fun inheritedContextUsesOnlyTwoMessagesAndFiveHundredCharsEach() {
        assertFalse(RequestToolRouter.select("진행해줘",listOf("문자 전체를 분석해줘","회의를 요약해줘","메모를 찾아줘")).contains("get_sms_summary"))
        assertFalse(RequestToolRouter.select("진행해줘",listOf("가".repeat(500)+"문자 분석")).contains("get_sms_summary"))
    }
}
