package com.user.localagent.externalagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
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
    @Test fun verifiedPageStaysVisibleAfterManySearchOnlyResults(){
        val id="10000000-0000-4000-8000-000000000002"
        fun observed(tool:String,body:String,withheld:Boolean=false):PhoneAgentExecution {
            val proposal=ExternalAgentProposal(id,1,tool,kotlinx.serialization.json.JsonObject(emptyMap()),
                "a".repeat(64),"binding",3)
            val result=com.user.localagent.core.ToolResult(id,true,body,com.user.localagent.core.VerificationState.VERIFIED)
            return PhoneAgentExecution(id,"binding",3,proposal,result,withheld)
        }
        val rows=(1..5).joinToString(","){"""{"url":"https://example.org/result$it","title":"Source $it"}"""}
        val search=observed("search_web","""{"success":true,"shopping":false,"privateSourcesRead":false,"untrustedSourceData":true,"items":[$rows]}""")
        val read=observed("read_webpage","""{"url":"https://example.org/official","untrustedSourceData":true}""")
        val hidden=observed("read_webpage","""{"url":"https://example.org/hidden","untrustedSourceData":true}""",withheld=true)
        val links=CloudPublicSearchProjection.partialSources(listOf(search,read,read,hidden))
        assertEquals(5,links.size)
        assertEquals("- <https://example.org/official> · 본문을 읽었어요",links.first())
        assertEquals(1,links.count{it.contains("/official")})
        assertTrue(links.none{it.contains("/hidden")})
    }

    @Test fun partialBrowserReportUsesOnlyHostCountsAndRecordedSteps(){
        fun observation(id:Int,name:String,body:String):PhoneAgentExecution {
            val callId="10000000-0000-4000-8000-"+id.toString().padStart(12,'0')
            val proposal=ExternalAgentProposal(callId,id,name,buildJsonObject{},"a".repeat(64),"binding",1)
            return PhoneAgentExecution("10000000-0000-4000-8000-000000000009","binding",1,proposal,
                ToolResult(callId,true,body,VerificationState.VERIFIED))
        }
        val screen=observation(2,"read_screen","private-screen")
        fun progress(count:String,askedToSave:Boolean):String?{
            val page=observation(1,"read_app_browser_page","""{"sameSessionPdfCount":$count,"text":"private-page"}""")
            val reads=listOf(page,screen)
            val required=reads.map{it.proposal.tool}.toSet() + if(askedToSave)setOf("save_browser_pdf") else emptySet()
            val steps=reads.map{HarnessStep(it.proposal.tool,it.proposal.arguments,StepEvidence(it.proposal.tool,VerificationState.VERIFIED,""))}
            assertNull(CloudAgentFinals.browserReadProgress(required,reads,steps.reversed()))
            return CloudAgentFinals.browserReadProgress(required,reads,steps)
        }
        val saved=progress("2",true)!!
        assertTrue(saved.contains("2개"));assertTrue(saved.contains("저장은 실행하지 않았어요"))
        assertFalse(saved.contains("private"))
        assertTrue(progress("0",true)!!.contains("마지막으로 읽은 화면"))
        assertFalse(progress("1",false)!!.contains("PDF"))
        assertNull(progress("\"1\"",true));assertNull(progress("121",true))
    }

    @Test fun plainAndPreviouslySavedAnswersDoNotShowTheRepeatedNotice(){
        val legacy="휴대폰 도구로 확인하지 않고 클라우드 모델이 생성한 답변입니다. 기기 자료나 현재 시각을 조회한 근거는 없습니다."
        val body="안녕하세요. 집합의 김호영 과장입니다."
        assertEquals(body,CloudAgentFinals.toolFreeAnswer("  $body "))
        assertEquals(body,CloudAgentFinals.visibleAnswer("$legacy\n\n$legacy\n\n$body"))
        assertEquals("  $body ",CloudAgentFinals.visibleAnswer("  $body "))
        assertTrue(CloudAgentFinals.PREPARED_PAUSE.contains("아직 확인하지 못했어요"))
    }
}
