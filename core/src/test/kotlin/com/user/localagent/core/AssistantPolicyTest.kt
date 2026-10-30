package com.user.localagent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPolicyTest {
    @Test fun noToolsMeansDirectTextOnlyAndStillHelpsWithDrafts() {
        val prompt=AssistantPolicy.build(emptySet(),"2026-11-01T08:30:00+09:00")
        assertTrue(prompt.contains("글쓰기"))
        assertTrue(prompt.contains("이메일 초안"))
        assertTrue(prompt.contains("자연어로 답하고"))
        assertFalse(prompt.contains("tool_call"))
        assertFalse(prompt.contains("send_email"))
        assertTrue(prompt.length<=2500)
    }

    @Test fun onlySelectedActionsArePresented() {
        val prompt=AssistantPolicy.build(setOf("get_current_datetime","fake_tool"),"2026-11-01T08:30:00+09:00")
        assertTrue(prompt.contains("get_current_datetime"))
        assertFalse(prompt.contains("fake_tool"))
        assertFalse(prompt.contains("prepare_sms"))
        assertFalse(prompt.contains("reply_messenger_notification"))
        assertTrue(prompt.contains("tool_call"))
    }

    @Test fun messengerPackageMappingAppearsOnlyWhenReadToolIsAllowed() {
        val unavailable=AssistantPolicy.build(setOf("reply_messenger_notification"),"2026-11-01T08:30:00+09:00")
        assertFalse(unavailable.contains("카카오톡=com.kakao.talk"))
        val available=AssistantPolicy.build(setOf("list_messenger_notifications"),"2026-11-01T08:30:00+09:00")
        ToolRegistry.supportedMessengerPackages.forEach {assertTrue(available.contains(it))}
        assertTrue(available.contains("카카오톡=com.kakao.talk"))
    }

    @Test fun calendarUsesSeoulTodayAndNoFalseCompletion() {
        val prompt=AssistantPolicy.build(setOf("list_calendar_events"),"2026-11-01T08:30:00+09:00")
        assertTrue(prompt.contains("Asia/Seoul"))
        assertTrue(prompt.contains("다음 날 00:00"))
        assertTrue(prompt.contains("실행하지 않은 행동을 완료했다고 말하지 않는다"))
        assertTrue(prompt.length<=2500)
    }
}
