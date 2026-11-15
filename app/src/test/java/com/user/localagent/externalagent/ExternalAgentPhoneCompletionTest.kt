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
    private fun actual(result:ToolResult=ToolResult(proposal.callId,true,"synthetic clock",VerificationState.VERIFIED))=
        PhoneAgentExecution(capture.parentRunId,capture.projectBinding,capture.authorityRevision,proposal,result)
    private fun evidence():JsonObject=buildJsonObject {
        put("sessionId",sid);put("parentRunId",capture.parentRunId);put("revision",4);put("state","ACTIVE")
        put("projectBinding",capture.projectBinding);put("authorityRevision",3);put("requestDigest",capture.requestDigest)
        put("acceptedPlan",buildJsonObject {
            put("state","ACCEPTED");put("planDigest",plan.planDigest);put("requestDigest",plan.requestDigest);put("goal",plan.goal)
            put("requiredTools",buildJsonArray{add("get_current_datetime")});put("constraints",buildJsonArray{})
        })
        put("requiredTools",buildJsonArray{add("get_current_datetime")})
        put("phoneToolsRequiringLocalEvidence",buildJsonArray{add("get_current_datetime")})
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
