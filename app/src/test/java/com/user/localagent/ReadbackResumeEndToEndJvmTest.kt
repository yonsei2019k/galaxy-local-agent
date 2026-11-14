package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.core.RequiredReadbackObligations as L
import com.user.localagent.core.RequiredReadbackPolicy as P
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Crosses actual collector/signature/strict codec/ledger APIs with synthetic host receipts only. */
class ReadbackResumeEndToEndJvmTest {
    private val first=L.Context("qa-first","qa-thread",null,"qa-scope","qa-workspace")
    private val resumed=first.copy(runId="qa-resumed")
    private val pair=setOf("save_note","search_notes")
    private val readOnly=setOf("search_notes")
    private fun note(id:String="qa-note",version:Int=1,clipped:Boolean=false)=buildJsonObject{
        put("id",id);put("title","QA title");put("collection","qa");put("text",if(clipped)"clip"else"QA body");put("updatedAt",version);put("truncated",clipped)
    }
    private fun out(tool:String,args:JsonObject,body:JsonObject)=ObservedToolOutcome(ToolCall("qa-${body.hashCode()}",tool,args),ToolResult("qa-${body.hashCode()}",true,body.toString(),VerificationState.VERIFIED))
    private fun save()=out("save_note",buildJsonObject{put("title","QA title");put("content","QA body")},buildJsonObject{put("status","ok");put("id","qa-note");put("storedAndReadBack",true);put("note",note())})
    private fun search(rows:List<JsonObject>,query:String="QA title",explicitDefaults:Boolean=false)=out("search_notes",buildJsonObject{
        if(explicitDefaults){put("scope","");put("limit",5)};put("query",query)
    },buildJsonObject{put("status","ok");put("details",buildJsonObject{put("notes",JsonArray(rows))})})
    private fun steps(outcomes:List<ObservedToolOutcome>)=outcomes.map{HarnessStep(it.call,StepEvidence(it.call.name,VerificationState.VERIFIED,"QA host"))}
    private fun capture(state:L.State?,context:L.Context,steps:List<HarnessStep>,outcomes:List<ObservedToolOutcome>):L.State {
        val selected=L.select(state,context,if(state==null)pair else readOnly)!!
        val collected=ChatReadbackEvidence.collect(context.proofBinding(),selected.selectedTools(),steps,outcomes)
        val signatures=collected.reads.associate{it.stepIndex to checkNotNull(ChatReadbackEvidence.querySignature(it.tool,steps[it.stepIndex].arguments))}
        return checkNotNull(L.capture(selected,context,steps,collected.mutations,collected.reads,collected.aligned,signatures))
    }
    private fun checkpoint(state:L.State)=ChatReadbackObligationCodec.decode(ChatReadbackObligationCodec.encode(state))
    private fun carry(state:L.State,steps:List<HarnessStep>)=L.carry(checkpoint(state),first,resumed,steps,steps,steps.indices.associateWith{it})
    private fun assess(state:L.State,steps:List<HarnessStep>,inherited:Int,allowed:Set<String> = readOnly)=L.assess(state,resumed,steps,inherited,allowed,18-steps.size,3,requestedFinish=true)
    @Test fun fullProofThenResumeEquivalentDefaultEmptyReadInvalidatesIt(){
        val old=listOf(save(),search(listOf(note())));val previous=steps(old)
        val state=carry(capture(null,first,previous,old),previous)
        val current=listOf(search(emptyList(),explicitDefaults=true));val all=previous+steps(current)
        val restored=checkpoint(capture(state,resumed,all,current))
        assertEquals(P.Reason.TARGET_NOT_RETURNED,assess(restored,all,previous.size).pending.single().reason)
        assertFalse(assess(restored,all,previous.size).readbackSatisfied)
    }
    @Test fun originalTargetCanBeProvedAfterPauseWithoutRepeatingOrAuthorizingWrite(){
        val old=listOf(save());val previous=steps(old);val state=carry(capture(null,first,previous,old),previous)
        val wrong=listOf(search(listOf(note("qa-other"))));val wrongSteps=previous+steps(wrong)
        assertFalse(assess(capture(state,resumed,wrongSteps,wrong),wrongSteps,1).readbackSatisfied)
        val exact=listOf(search(listOf(note())));val exactSteps=previous+steps(exact)
        assertTrue(assess(capture(state,resumed,exactSteps,exact),exactSteps,1).readbackSatisfied)
        assertEquals(readOnly,TaskGoal("QA remaining read",listOf("QA exact target"),requiredTools=readOnly).requiredTools)
        assertEquals(1,exactSteps.count{it.tool=="save_note"})
    }
    @Test fun bothPriorQueriesSurviveStrictCheckpointWhenOnlyOlderOneBecomesEmpty(){
        val old=listOf(save(),search(listOf(note())),search(listOf(note()),query="second exact literal"));val previous=steps(old)
        val state=carry(capture(null,first,previous,old),previous)
        val current=listOf(search(emptyList(),explicitDefaults=true));val all=previous+steps(current)
        assertEquals(P.Reason.TARGET_NOT_RETURNED,assess(checkpoint(capture(state,resumed,all,current)),all,previous.size).pending.single().reason)
    }
    @Test fun resumedPartialAndRevokedReadAuthorityCannotFinish(){
        val old=listOf(save());val previous=steps(old);val state=carry(capture(null,first,previous,old),previous)
        val current=listOf(search(listOf(note(version=2,clipped=true))));val all=previous+steps(current)
        val blocked=assess(capture(state,resumed,all,current),all,1,emptySet())
        assertEquals(P.Reason.INCOMPLETE_READ,blocked.pending.single().reason)
        assertTrue(P.Blocker.REQUEST_TOOL_UNAVAILABLE in blocked.blockers)
    }
    @Test fun malformedLinkedLedgerCannotEraseEarlierWriteFromSameGoal(){
        val old=listOf(save());val previous=steps(old);val state=capture(null,first,previous,old)
        val goal=GoalDefinition("qa-goal",first.threadId,null,"QA saved target",listOf(GoalCriterion("qa-criterion","QA exact read")),status=GoalStatus.ACTIVE,linkedRunIds=listOf(first.runId))
        val request=QueuedChatRequest(first.runId,first.threadId,"qa-branch",null,"QA request","qa-model",false,emptyList(),"qa-user",previousSteps=previous,workspaceBinding=first.workspaceBinding,goal=goal,readbackObligations=state)
        val malformed=JsonObject(Json.parseToJsonElement(request.toJson()).jsonObject+("readbackObligations" to JsonPrimitive("bad")))
        val source=GoalRunEvidenceSource(first.runId,first.threadId,null,"paused",malformed.toString())
        assertTrue(runCatching{inheritVerifiedGoalEvidence(goal,listOf(source),first.workspaceBinding!!)}.isFailure)
    }
    @Test fun ordinaryFreshRequestGetsNoPreviousCriterionOrMetadata(){
        val fresh=QueuedChatRequest("qa-new",first.threadId,"qa-branch",null,"QA unrelated new request","qa-model",false,emptyList(),"qa-new-user")
        val restored=QueuedChatRequest.fromJson(fresh.toJson())
        assertNull(restored.readbackObligations);assertTrue(restored.previousSteps.isEmpty())
        assertFalse(L.assess(null,resumed,emptyList(),0,emptySet(),18,3,requestedFinish=true).applicable)
    }
}
