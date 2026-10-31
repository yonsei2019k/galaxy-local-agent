package com.user.localagent.core

import java.time.ZonedDateTime
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic owner requests exercise the shared intent from planning through completion evidence. */
class PublicResearchRoutingRegressionTest {
    private val now = ZonedDateTime.parse("2026-10-30T22:00:00+09:00[Asia/Seoul]")
    private val directConnectionResearch = "카카오톡에 직접 연결할 수 있는 방법을 찾아봐"
    private val privateTools = setOf(
        "get_sms_summary", "get_call_log_summary", "search_contacts",
        "list_messenger_notifications", "list_imported_conversations", "search_conversation_imports",
        "search_conversation_history", "prepare_sms", "prepare_email", "reply_messenger_notification",
        "search_notes", "search_memories", "search_audio_transcripts", "list_audio_recordings",
        "list_calendar_events"
    )
    private val privateSources = setOf(
        IntentSource.SMS, IntentSource.KAKAO_IMPORT, IntentSource.KAKAO_NOTIFICATIONS,
        IntentSource.TELEGRAM_IMPORT, IntentSource.TELEGRAM_NOTIFICATIONS,
        IntentSource.MEDITONG_IMPORT, IntentSource.MEDITONG_NOTIFICATIONS,
        IntentSource.ASSISTANT_HISTORY, IntentSource.CALENDAR, IntentSource.NOTES,
        IntentSource.MEMORIES, IntentSource.AUDIO_TRANSCRIPTS
    )

    private fun assertPublicResearch(text: String, subject: String, history: List<String> = emptyList()) {
        val web = WebTaskPlanner.plan(text, history)
        assertTrue("A method investigation must search public sources: $text", web is WebTaskPlan.WebResearch)
        web as WebTaskPlan.WebResearch
        assertTrue("The public product must remain the search topic: ${web.query}", web.query.contains(subject, ignoreCase = true))
        assertFalse(web.shopping)

        assertEquals("Public methods are not private message analysis", AssistantTaskPlan.NoPlan,
            AssistantTaskPlanner.plan(text, history, now))
        val execution = RequestExecutionPlanner.plan(text, history, now)
        assertTrue("Host execution must consume the public-research choice", execution is RequestExecutionPlan.PublicResearch)
        assertEquals(web, (execution as RequestExecutionPlan.PublicResearch).plan)

        val intent = IntentPlanning.plan(text, history)
        assertEquals("web", intent.domain)
        assertTrue(intent.recommendedNextTools.contains("search_web"))
        assertTrue(intent.sources.contains(IntentSource.WEB))
        assertTrue("App names used as topics do not authorize private sources", intent.sources.none { it in privateSources })
        assertTrue((intent.recommendedNextTools + intent.deferredTools).none { it in privateTools })
        assertTrue(intent.missingEssentialSlots.isEmpty())

        val routed = RequestToolRouter.select(text, history).toSet()
        assertTrue(routed.contains("search_web"))
        assertTrue("Private tools must not accompany public research: $routed", routed.none { it in privateTools })
        assertEquals("Completion must require evidence for the same selected task", setOf("search_web"),
            TaskHarness.inferGoal(text, history).requiredTools)
    }

    @Test fun directConnectionResearchUsesPublicSourcesAcrossEveryPlanner() {
        assertPublicResearch(directConnectionResearch, "카카오톡")
    }

    @Test fun publicMethodResearchDoesNotRequireTheUserToSayWebOrInternet() {
        listOf(
            "텔레그램에 직접 연결하는 방법을 조사해줘" to "텔레그램",
            "Gmail 계정을 연동하는 방법 찾아줘" to "Gmail",
            "카카오톡 알림 접근 설정 방법 찾아봐" to "카카오톡"
        ).forEach { (text, subject) -> assertPublicResearch(text, subject) }
    }

    @Test fun englishPublicConnectionInvestigationsUseTheSameExecutionContract() {
        assertPublicResearch("Find a way to connect KakaoTalk directly", "KakaoTalk")
        assertPublicResearch("Research how to connect Telegram", "Telegram")
    }

    @Test fun replacingThePublicAppTopicDoesNotSearchTheRejectedApp() {
        val text = "카카오톡 말고 텔레그램 연결 방법 찾아줘"
        assertPublicResearch(text, "텔레그램", listOf(directConnectionResearch))
        val query = (WebTaskPlanner.plan(text, listOf(directConnectionResearch)) as WebTaskPlan.WebResearch).query
        assertFalse("An excluded app must not remain a research subject", query.contains("카카오톡"))
    }

    @Test fun connectionResearchOverridesAPreviousPrivateConversationAnalysis() {
        assertPublicResearch(directConnectionResearch, "카카오톡", listOf("카카오톡 대화를 분석해줘"))
    }

    @Test fun rejectingAnImportGuidePreservesThePublicAppTopic() {
        assertPublicResearch("파일 가져오기 말고 다른 연결 방법 조사해", "카카오톡", listOf(directConnectionResearch))
    }

    @Test fun correctingAStatusRequestReplacesItsActionWithResearch() {
        assertPublicResearch("상태 말고 직접 연결 방법을 찾아", "카카오톡", listOf("카카오톡 연결 상태 보여줘"))
    }

    @Test fun aSecondMethodCorrectionKeepsTheTopicAcrossBoundedHistory() {
        assertPublicResearch("파일 가져오기 말고 다른 연결 방법 조사해", "카카오톡",
            listOf(directConnectionResearch, "상태 말고 직접 연결 방법을 찾아"))
    }

    @Test fun aNewDocumentationSubjectDoesNotInheritThePreviousApp() {
        for ((text, subject) in listOf("블루투스 연결 방법 찾아줘" to "블루투스", "엑셀 사용법 찾아줘" to "엑셀")) {
            assertPublicResearch(text, subject, listOf(directConnectionResearch))
            val query = (WebTaskPlanner.plan(text, listOf(directConnectionResearch)) as WebTaskPlan.WebResearch).query
            assertFalse("A new public topic replaces the previous app: $query", query.contains("카카오톡"))
        }
    }

    @Test fun publicMethodSearchDoesNotReusePrivateMessageContentsAsItsQuery() {
        val marker = "SYNTHETIC_PRIVATE_NOTE_42"
        val history = listOf("카카오톡 대화에서 $marker 내용을 분석해줘")
        assertPublicResearch(directConnectionResearch, "카카오톡", history)
        assertFalse((WebTaskPlanner.plan(directConnectionResearch, history) as WebTaskPlan.WebResearch).query.contains(marker))
    }

    @Test fun connectionStatusExecutionAndExplanationCannotTriggerMessageAnalysis() {
        for (text in listOf("카카오톡 연결 상태 보여줘", "카카오톡에 직접 연결해줘", "카카오톡 연결 방법을 설명해줘")) {
            assertEquals(text, WebTaskPlan.NoPlan, WebTaskPlanner.plan(text))
            assertEquals(text, AssistantTaskPlan.NoPlan, AssistantTaskPlanner.plan(text, now = now))
            assertEquals(text, RequestExecutionPlan.Model, RequestExecutionPlanner.plan(text, emptyList(), now))
            assertTrue(text, RequestToolRouter.select(text).none { it in privateTools })
            val intent = IntentPlanning.plan(text)
            assertTrue(text, intent.sources.none { it in privateSources })
            assertTrue(text, (intent.recommendedNextTools + intent.deferredTools).none { it in privateTools })
        }
    }

    @Test fun anActualMessageAnalysisKeepsItsLocalSourceAndNativePlan() {
        val text = "카카오톡 대화 전부 분석해줘"
        assertEquals(WebTaskPlan.NoPlan, WebTaskPlanner.plan(text))
        val execution = RequestExecutionPlanner.plan(text, emptyList(), now)
        assertTrue(execution is RequestExecutionPlan.NativeAnalysis)
        assertTrue((execution as RequestExecutionPlan.NativeAnalysis).plan is AssistantTaskPlan.MessengerAnalysis)
        assertEquals("messages", IntentPlanning.plan(text).domain)
        assertFalse(RequestToolRouter.select(text).contains("search_web"))
    }

    @Test fun findingMethodInstructionsInsidePrivateMessagesRemainsALocalLookup() {
        for (text in listOf(
            "카카오톡 대화 중 연결 방법을 알려준 메시지를 찾아줘",
            "카카오톡의 대화 전체를 읽고 연결 방법을 찾아줘"
        )) {
            assertEquals(text, WebTaskPlan.NoPlan, WebTaskPlanner.plan(text))
            val execution = RequestExecutionPlanner.plan(text, emptyList(), now)
            assertTrue(text, execution is RequestExecutionPlan.NativeAnalysis)
            assertTrue(text, (execution as RequestExecutionPlan.NativeAnalysis).plan is AssistantTaskPlan.MessengerAnalysis)
            assertFalse(text, RequestToolRouter.select(text).contains("search_web"))
            assertEquals(text, "messages", IntentPlanning.plan(text).domain)
        }
    }

    @Test fun methodWordsCannotAuthorizeUploadingPersonalRecords() {
        for (text in listOf(
            "내 카카오톡 대화 내용을 참고해서 연결 방법을 웹에서 찾아줘",
            "내 메일 내용 기반으로 공식 문서에서 연동 방법을 검색해줘",
            "카카오톡 대화에서 필요한 기능을 분석해서 인터넷 검색해줘",
            "Search my Gmail messages for how to connect Telegram",
            "Find the message in my KakaoTalk chat that explains how to connect"
        )) {
            assertEquals(text, WebTaskPlan.NoPlan, WebTaskPlanner.plan(text))
            assertFalse(text, RequestExecutionPlanner.plan(text, emptyList(), now) is RequestExecutionPlan.PublicResearch)
            assertFalse(text, RequestToolRouter.select(text).contains("search_web"))
        }
    }

    @Test fun quotedOrImportedMethodInstructionsDoNotStartResearch() {
        for (text in listOf(
            "\"$directConnectionResearch\"라는 문장을 번역해줘",
            "> $directConnectionResearch",
            "```\n$directConnectionResearch\n```",
            "선택한 문서\n$directConnectionResearch"
        )) {
            assertEquals(text, WebTaskPlan.NoPlan, WebTaskPlanner.plan(text))
            assertEquals(text, AssistantTaskPlan.NoPlan, AssistantTaskPlanner.plan(text, now = now))
            assertFalse(text, RequestExecutionPlanner.plan(text, emptyList(), now) is RequestExecutionPlan.PublicResearch)
            assertTrue("Quoted app names cannot grant private tools: $text", RequestToolRouter.select(text).none { it in privateTools })
        }
    }

    @Test fun cancellingMethodResearchOrRequestingOfflineExplanationDoesNotSearch() {
        for (text in listOf(
            "카카오톡 연결 방법 검색하지 마",
            "카카오톡 연결 방법 찾아보지 마",
            "오프라인으로 카카오톡 연결 방법을 설명해줘",
            "Do not search for KakaoTalk connection methods",
            "Don't research how to connect Telegram"
        )) {
            assertEquals(text, WebTaskPlan.NoPlan, WebTaskPlanner.plan(text, listOf(directConnectionResearch)))
            assertFalse(text, RequestExecutionPlanner.plan(text, listOf(directConnectionResearch), now) is RequestExecutionPlan.PublicResearch)
            assertFalse(text, RequestToolRouter.select(text, listOf(directConnectionResearch)).contains("search_web"))
        }
    }

    @Test fun aSourceCountCannotCompleteConnectionMethodResearch() {
        val goal = TaskHarness.inferGoal(directConnectionResearch)
        val sourceCount = HarnessStep("list_messenger_notifications", buildJsonObject {},
            StepEvidence("list_messenger_notifications", VerificationState.VERIFIED, "합성 알림 0건 조회"))
        assertTrue(TaskHarness.decide(goal, listOf(sourceCount), requestedFinish = true) is HarnessDecision.Stop)
        val publicEvidence = HarnessStep("search_web", buildJsonObject {},
            StepEvidence("search_web", VerificationState.VERIFIED, "공개 문서의 연결 방법 확인"))
        assertTrue(TaskHarness.decide(goal, listOf(publicEvidence), requestedFinish = true) is HarnessDecision.Finish)
    }

    @Test fun methodWordsInsideAnExplicitNoteBodyDoNotReplaceTheSaveAction() {
        for (text in listOf("메모 저장: 카카오톡 연결 방법을 확인", "메모 저장: 이 방법이 좋다")) {
            assertEquals(text, WebTaskPlan.NoPlan, WebTaskPlanner.plan(text))
            assertTrue(text, RequestToolRouter.select(text).contains("save_note"))
            assertEquals(text, setOf("save_note"), TaskHarness.inferGoal(text).requiredTools)
        }
    }
}
