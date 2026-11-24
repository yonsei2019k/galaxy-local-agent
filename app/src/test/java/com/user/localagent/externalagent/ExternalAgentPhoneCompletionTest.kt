package com.user.localagent.externalagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExternalAgentPhoneCompletionTest {
    private val sid="10000000-0000-4000-8000-000000000009"
    private val capture=ExternalAgentCapture("10000000-0000-4000-8000-000000000001","thread","branch","project","binding",3,
        "a".repeat(64),setOf("get_current_datetime"),true,pcWorkspaceProfile="phone-only",requirePlan=true)
    private val plan=ExternalAgentPlan(AgentPlanState.ACCEPTED,
        ExternalAgentContract.planDigest(capture,"기기 시각 확인",listOf("get_current_datetime"),emptyList()),
        capture.requestDigest,"기기 시각 확인",listOf("get_current_datetime"),emptyList())
    private val proposal=ExternalAgentProposal("10000000-0000-4000-8000-000000000002",1,"get_current_datetime",buildJsonObject{},
        capture.requestDigest,capture.projectBinding,capture.authorityRevision)
    private fun actual(result:ToolResult=ToolResult(proposal.callId,true,"synthetic clock",VerificationState.VERIFIED),withheld:Boolean=false)=
        PhoneAgentExecution(capture.parentRunId,capture.projectBinding,capture.authorityRevision,proposal,result,withheld)
    private fun evidence(plan:ExternalAgentPlan=this.plan,proposal:ExternalAgentProposal=this.proposal):JsonObject=buildJsonObject {
        put("sessionId",sid);put("parentRunId",capture.parentRunId);put("revision",4);put("state","ACTIVE")
        put("projectBinding",capture.projectBinding);put("authorityRevision",3);put("requestDigest",capture.requestDigest)
        put("acceptedPlan",buildJsonObject {
            put("state","ACCEPTED");put("planDigest",plan.planDigest);put("requestDigest",plan.requestDigest);put("goal",plan.goal)
            put("requiredTools",buildJsonArray{plan.requiredTools.forEach { add(it) }});put("constraints",buildJsonArray{})
        })
        put("requiredTools",buildJsonArray{plan.requiredTools.forEach { add(it) }})
        put("phoneToolsRequiringLocalEvidence",buildJsonArray{plan.requiredTools.forEach { add(it) }})
        listOf("verifiedPcTools","pcCalls","unresolvedCalls","preparedCalls").forEach{put(it,buildJsonArray{})}
        put("phoneCalls",buildJsonArray{add(buildJsonObject {
            put("callId",proposal.callId);put("sequence",1);put("tool",proposal.tool);put("state","COMPLETED")
        })})
        put("phoneEvidenceImported",false);put("restartPolicy","RAM_ONLY_RESTART_REVOKES")
    }
    @Test fun matchesOnlyThisSessionsActualLocalCallback(){
        assertTrue(ExternalAgentPhoneCompletion.matches(capture,plan,evidence(),listOf(actual()),sid))
        assertFalse(ExternalAgentPhoneCompletion.matches(capture,plan,evidence(),emptyList(),sid))
        assertFalse(ExternalAgentPhoneCompletion.matches(capture,plan,evidence(),listOf(actual()),proposal.callId))
    }
    @Test fun settledBrowserReadIsOnlyProgressWhenThePlannedSaveNeverRan(){
        val tools=listOf("read_app_browser_page","save_browser_pdf")
        val scope=ExternalAgentCapture(capture.parentRunId,"thread","branch","project","binding",3,capture.requestDigest,
            tools.toSet(),true,pcWorkspaceProfile="phone-only",requirePlan=true)
        val plan=ExternalAgentPlan(AgentPlanState.ACCEPTED,ExternalAgentContract.planDigest(scope,"문서 확인",tools,emptyList()),
            scope.requestDigest,"문서 확인",tools,emptyList())
        val read=ExternalAgentProposal(proposal.callId,1,tools.first(),buildJsonObject {},scope.requestDigest,scope.projectBinding,3)
        val result=ToolResult(read.callId,true,"{}",VerificationState.VERIFIED)
        val actual=listOf(PhoneAgentExecution(scope.parentRunId,scope.projectBinding,3,read,result))
        val evidence=evidence(plan,read)
        assertTrue(ExternalAgentPhoneCompletion.browserReadsMatch(scope,plan,evidence,actual,sid))
        assertFalse(ExternalAgentPhoneCompletion.matches(scope,plan,evidence,actual,sid))
        assertFalse(ExternalAgentPhoneCompletion.browserReadsMatch(scope,plan,evidence,actual,proposal.callId))
        val pending=JsonObject(evidence+("preparedCalls" to buildJsonArray{add("unknown")}))
        assertFalse(ExternalAgentPhoneCompletion.browserReadsMatch(scope,plan,pending,actual,sid))
        val hidden=listOf(PhoneAgentExecution(scope.parentRunId,scope.projectBinding,3,read,result,true))
        assertFalse(ExternalAgentPhoneCompletion.browserReadsMatch(scope,plan,evidence,hidden,sid))
    }

    @Test fun privateLocalObservationCannotProveCloudCompletion(){
        assertFalse(ExternalAgentPhoneCompletion.matches(capture,plan,evidence(),listOf(actual(withheld=true)),sid))
    }
    @Test fun serverCompletedCannotReplaceFailedPreparedOrUnverifiedPhoneEffect(){
        listOf(VerificationState.FAILED,VerificationState.PREPARED,VerificationState.UNVERIFIED).forEach{state->
            assertFalse(ExternalAgentPhoneCompletion.matches(capture,plan,evidence(),listOf(actual(ToolResult(proposal.callId,true,"untrusted",state))),sid))
        }
    }
    @Test fun noCallsOrUnresolvedCallsOrImportedPhoneEvidenceCannotComplete(){
        listOf(
            evidence()+mapOf("phoneCalls" to buildJsonArray{}),
            evidence()+mapOf("unresolvedCalls" to buildJsonArray{add("synthetic")}),
            evidence()+mapOf("phoneEvidenceImported" to JsonPrimitive(true)),
            evidence()+mapOf("parentRunId" to JsonPrimitive(proposal.callId)),
            evidence()+mapOf("pcCalls" to buildJsonArray{add("synthetic")})
        ).forEach{assertFalse(ExternalAgentPhoneCompletion.matches(capture,plan,JsonObject(it),listOf(actual()),sid))}
    }
}
