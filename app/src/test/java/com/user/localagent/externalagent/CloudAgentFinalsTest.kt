package com.user.localagent.externalagent

import org.junit.Assert.*
import org.junit.Test

class CloudAgentFinalsTest {
    @Test fun onlyAPlanlessCallFreeDoneReportIsAPlainAnswer(){
        assertTrue(CloudAgentFinals.isToolFreeAnswer("reported_done",false,0,0))
        assertFalse(CloudAgentFinals.isToolFreeAnswer("reported_done",true,0,0))
        assertFalse(CloudAgentFinals.isToolFreeAnswer("reported_done",false,1,0))
        assertFalse(CloudAgentFinals.isToolFreeAnswer("reported_done",false,0,1))
        listOf("partial","failed","needs_input").forEach{assertFalse(CloudAgentFinals.isToolFreeAnswer(it,false,0,0))}
    }
    @Test fun plainAnswerIsLabelledAsUnverified(){
        val text=CloudAgentFinals.toolFreeAnswer("  2026년 11월 16일입니다. ")
        assertTrue(text.startsWith("휴대폰 도구로 확인하지 않고"))
        assertTrue(text.endsWith("2026년 11월 16일입니다."))
        assertTrue(CloudAgentFinals.PREPARED_PAUSE.contains("완료는 확인하지 않았습니다"))
    }
}
