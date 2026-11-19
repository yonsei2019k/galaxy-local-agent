package com.user.localagent

import com.user.localagent.data.ChatRunRecord
import com.user.localagent.core.*
import com.user.localagent.externalagent.ExternalAgentContract
import com.user.localagent.externalagent.ExternalAgentSettings
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic native wire checkpoints. No JNI, model, account, provider or phone is invoked. */
class GoalNativeCheckpointFenceTest {
    private val request=QueuedChatRequest("10000000-0000-4000-8000-000000000001","thread","branch","project","QA only",
        ExternalAgentSettings.MODEL_ID,false,emptyList(),"owner-message",workspaceBinding="workspace",capturedPcRevision=3,
        allowExternalAgent=true,externalAgentRevision=7)
    private fun event(sequence:Int,kind:JsonObject)=buildJsonObject {
        put("sequence",sequence);put("timestampMs",1000L+sequence-1);put("kind",kind)
    }
    private fun checkpoint(tool:String?="search_notes",state:String="verified",closed:Boolean=true)=buildJsonObject {
        put("binding",buildJsonObject {
            put("runId",request.runId);put("threadId",request.threadId);put("branchId",request.branchId);put("projectId",request.projectId)
            put("scopeId",ExternalAgentContract.sha(listOf(request.runId,request.threadId,request.branchId,request.projectId.orEmpty(),"scope",request.workspaceBinding.orEmpty()).joinToString("\n")))
            put("authorizationRevision",7)
        })
        put("calls",buildJsonArray {if(tool!=null)add("call-1")})
        put("inFlight",JsonNull);put("inFlightTool",JsonNull);put("uncertain",false);put("closed",closed)
        put("budget",18);put("startedAtMs",1000);put("deadlineAtMs",331000)
        put("events",buildJsonArray {
            add(event(1,buildJsonObject {put("type","created")}));add(event(2,buildJsonObject {put("type","started")}))
            if(tool!=null) {
                add(event(3,buildJsonObject {put("type","tool_started");put("effect_id","call-1");put("tool",tool)}))
                add(event(4,buildJsonObject {put("type","tool_finished");put("effect_id","call-1");put("state",state)}))
            }
        })
    }
    private fun row(native:JsonElement?=checkpoint(),captured:QueuedChatRequest=request):ChatRunRecord {
        val original=Json.parseToJsonElement(captured.toJson()).jsonObject
        val context=if(native==null)original else JsonObject(original+("sharedEngineState" to native))
        return ChatRunRecord(id=captured.runId,threadId=captured.threadId,branchId=captured.branchId,userMessageId=captured.userMessageId,
            projectId=captured.projectId,modelId=captured.modelId,thinking=captured.thinking,status="completed",contextJson=context.toString())
    }
    private fun unresolved(native:JsonObject=checkpoint(),scope:String="scope",closed:Boolean=true)=goalNativeEffectsUnresolved(row(native),scope,closed)

    @Test fun exactClosedVerifiedCheckpointAndEmptyClosedSessionAreSafe() {
        assertFalse(unresolved());assertFalse(unresolved(checkpoint(tool=null)))
    }
    @Test fun actualKnownNotAppliedReadRemainsSafeWithoutBecomingVerified() {
        val native=checkpoint(state="not_applied")
        assertFalse(unresolved(native))
        assertEquals("not_applied",native.getValue("events").jsonArray.last().jsonObject.getValue("kind").jsonObject.getValue("state").jsonPrimitive.content)
    }
    @Test fun unknownEventCannotBeHiddenByAFalseUncertaintyFlag() {
        assertTrue(unresolved(checkpoint(state="unknown")))
        val native=checkpoint()
        val events=native.getValue("events").jsonArray+event(5,buildJsonObject {put("type","uncertain")})
        assertTrue(unresolved(JsonObject(native+("events" to JsonArray(events)))))
    }
    @Test fun inFlightUncertaintyOrMissingFieldsFailClosed() {
        val native=checkpoint()
        for((key,value) in listOf("uncertain" to JsonPrimitive(true),"inFlight" to JsonPrimitive("call-1"),
            "inFlightTool" to JsonPrimitive("save_note"),"uncertain" to JsonPrimitive("false")))
            assertTrue(unresolved(JsonObject(native+(key to value))))
        for(key in listOf("closed","uncertain","inFlight","inFlightTool","events","binding"))assertTrue(unresolved(JsonObject(native-key)))
    }
    @Test fun unfinishedSessionIsNeverAClosedParentAndLegacySnakeCaseIsRejected() {
        assertTrue(unresolved(checkpoint(closed=false)))
        assertFalse(unresolved(checkpoint(closed=false),closed=false))
        val native=checkpoint()
        assertTrue(unresolved(JsonObject((native-"inFlight")+("in_flight" to JsonNull))))
    }
    @Test fun exactNativeBindingCannotCrossScopeRunBranchOrAuthorizationRevision() {
        assertTrue(unresolved(scope="other-scope"))
        val native=checkpoint();val binding=native.getValue("binding").jsonObject
        for((key,value) in listOf("runId" to JsonPrimitive("another-run"),"branchId" to JsonPrimitive("other-branch"),
            "threadId" to JsonPrimitive("other-thread"),"projectId" to JsonNull,"authorizationRevision" to JsonPrimitive(8)))
            assertTrue(unresolved(JsonObject(native+("binding" to JsonObject(binding+(key to value))))))
    }
    @Test fun preparedExternalEffectBlocksWhileScreenOpeningRemainsPrepared() {
        assertTrue(unresolved(checkpoint("prepare_sms","handed_off")))
        assertFalse(unresolved(checkpoint("open_url","handed_off")))
        assertTrue(unresolved(checkpoint("read_unregistered","not_applied")))
    }
    @Test fun duplicatedMissingOrReorderedEventsCannotEraseAnInFlightCall() {
        val native=checkpoint();val events=native.getValue("events").jsonArray
        assertTrue(unresolved(JsonObject(native+("events" to JsonArray(events.dropLast(1))))))
        assertTrue(unresolved(JsonObject(native+("events" to JsonArray(events+events.last())))))
        assertTrue(unresolved(JsonObject(native+("calls" to buildJsonArray {add("another-call")}))))
        assertTrue(unresolved(JsonObject(native+("events" to JsonArray(events.reversed())))))
    }
    @Test fun anyProviderWithoutItsNativeCheckpointRemainsUnknown() {
        assertTrue(goalNativeEffectsUnresolved(row(null),"scope",true))
        val local=request.copy(modelId="local",allowExternalAgent=false,externalAgentRevision=null)
        assertTrue(goalNativeEffectsUnresolved(row(null,local),"scope",true))
        assertTrue(goalNativeEffectsUnresolved(row(JsonPrimitive("not a checkpoint")),"scope",true))
    }
    @Test fun cleanVisibleStepsCannotLaunderAnInFlightNativeSourceThroughExplicitReentry() {
        val goal=GoalDefinition("goal",request.threadId,request.projectId,"QA only",listOf(GoalCriterion("criterion","Actual QA result")),
            status=GoalStatus.ACTIVE,linkedRunIds=listOf(request.runId),updatedAt=10)
        val binding=GoalSegmentBinding(goal.id,10,goalWorkDigest(goal),"scope",0,false,true,request.runId,null,null,1,1000,0,0)
        val captured=request.copy(goal=goal,goalSegment=binding)
        val safe=row(checkpoint(),captured)
        assertTrue(captured.previousSteps.isEmpty());assertFalse(goalSourceNativeEffectsUnresolved(safe))
        val native=checkpoint()
        val inFlight=JsonObject(native+mapOf("inFlight" to JsonPrimitive("call-1"),"inFlightTool" to JsonPrimitive("search_notes")))
        assertTrue(goalSourceNativeEffectsUnresolved(row(inFlight,captured)))
        val visibleVerified=captured.copy(previousSteps=listOf(HarnessStep("search_notes",buildJsonObject {put("query","QA")},StepEvidence("search_notes",VerificationState.VERIFIED,"Visible read only"))))
        assertTrue(goalSourceNativeEffectsUnresolved(row(inFlight,visibleVerified)))
        assertTrue(goalSourceNativeEffectsUnresolved(row(null,captured)))
        assertTrue(goalNativeEffectsUnresolved(safe,"today-has-a-different-scope",true))
        assertFalse(goalSourceNativeEffectsUnresolved(safe)) // Its captured original scope is used.
    }
    @Test fun aCloudNativeSourceWithoutCapturedScopeIsNotReboundToTodaysScope() {
        assertTrue(goalSourceNativeEffectsUnresolved(row(checkpoint())))
    }
    @Test fun localVisibleReadStepsCannotProveThatNoUnrecordedMutationStarted() {
        val local=request.copy(modelId="pc-text",previousSteps=listOf(HarnessStep("search_notes",
            buildJsonObject {put("query","QA")},StepEvidence("search_notes",VerificationState.VERIFIED,"Visible read only"))))
        assertTrue(goalSourceNativeEffectsUnresolved(row(null,local)))
        assertTrue(goalNativeEffectsUnresolved(row(null,local),"scope",true))
    }
}
