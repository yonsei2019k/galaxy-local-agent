package com.user.localagent.externalagent

import kotlinx.serialization.json.put
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
    @Test fun readOnlyAnswersKeepTheModelSynthesisButChangesUseTheHostReport(){
        assertFalse(CloudAgentFinals.needsHostReport(listOf("get_current_datetime","list_calendar_events")))
        assertFalse(CloudAgentFinals.needsHostReport(listOf("search_contacts")))
        assertTrue(CloudAgentFinals.needsHostReport(listOf("get_current_datetime","save_note")))
        assertTrue(CloudAgentFinals.needsHostReport(listOf("prepare_sms")))
    }
    @Test fun malformedArgumentsReachRunToolInsteadOfAbortingButUnknownToolsDoNot(){
        val capture=ExternalAgentCapture("10000000-0000-4000-8000-000000000001","thread","branch","project","binding",3,
            "a".repeat(64),setOf("search_contacts"),true,pcWorkspaceProfile="phone-only",requirePlan=true)
        fun proposal(tool:String,args:kotlinx.serialization.json.JsonObject)=ExternalAgentProposal("10000000-0000-4000-8000-000000000002",1,tool,args,
            capture.requestDigest,capture.projectBinding,capture.authorityRevision)
        val bad=proposal("search_contacts",kotlinx.serialization.json.buildJsonObject{put("query","   ")})
        assertEquals("search_contacts",bad.checkedCall(capture).name)
        assertThrows(IllegalArgumentException::class.java){com.user.localagent.core.ToolRegistry.validate(bad.checkedCall(capture))}
        assertThrows(IllegalArgumentException::class.java){proposal("save_note",kotlinx.serialization.json.buildJsonObject{}).checkedCall(capture)}
    }
    @Test fun plainAnswerIsLabelledAsUnverified(){
        val text=CloudAgentFinals.toolFreeAnswer("  2026년 11월 16일입니다. ")
        assertTrue(text.startsWith("휴대폰 도구로 확인하지 않고"))
        assertTrue(text.endsWith("2026년 11월 16일입니다."))
        assertTrue(CloudAgentFinals.PREPARED_PAUSE.contains("완료는 확인하지 않았습니다"))
    }
}
