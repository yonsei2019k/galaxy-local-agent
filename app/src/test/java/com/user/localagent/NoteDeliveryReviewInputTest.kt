package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.notes.*
import com.user.localagent.inference.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real page/session/ack/host-readback policies, synthetic provider receipts. No model or Android execution. */
class NoteDeliveryReviewInputTest {
    private val binding=RequiredReadbackPolicy.Binding("run","scope",null)
    private val readBinding=NoteReadBinding("run","thread","branch","scope")
    private val required=setOf("patch_note","read_note")
    private val body="changed\r\n"+"x".repeat(1300)+" 😀 tail"
    private val note=NoteSource("note","scope","team","title",body,8)
    private fun patch():ObservedToolOutcome {
        val call=ToolCall("patch","patch_note",buildJsonObject{put("id","note");put("scope","team");put("expectedUpdatedAt","7");put("oldText","needle");put("newText","changed")})
        val receipt=NotePatchReceipt(NotePatchIdentity("note-patch-"+"a".repeat(64),"a".repeat(64),"note","team"),"title",7,8,noteSha("before"),noteSha(body),true)
        return ObservedToolOutcome(call,ToolResult(call.id,true,receipt.json().toString(),VerificationState.VERIFIED,"Synthetic committed receipt"))
    }
    private fun page(session:NoteReadPatchSession,offset:Int,version:Long=8):Pair<ObservedToolOutcome,NoteReadPage> {
        val source=note.copy(updatedAt=version)
        val call=ToolCall("read-$offset-$version","read_note",buildJsonObject{put("id","note");put("scope","team");put("expectedUpdatedAt",version.toString());put("offset",offset);put("limit",900)})
        val page=session.read(source,NoteReadPatchToolCatalog.readRequest(call))
        return ObservedToolOutcome(call,ToolResult(call.id,true,page.json().toString(),VerificationState.VERIFIED,"Synthetic provider page")) to page
    }
    private fun entry(observed:ObservedToolOutcome)=buildJsonObject {
        put("tool",observed.call.name);put("success",true);put("verification","VERIFIED")
        put("result",Json.parseToJsonElement(if(observed.call.name=="read_note")NoteReadModelDelivery.modelResult(observed.result.content)
            else compactJsonData(observed.result.content,1100)))
    }
    private fun assess(outcomes:List<ObservedToolOutcome>,keys:Set<String>)=ChatReadbackEvidence.assess(binding,required,required,
        outcomes.map{HarnessStep(it.call,StepEvidence(it.call.name,it.result.verification,"Host provider"))},outcomes,3,deliveredNoteReadKeys=keys)
    private fun summary(reason:GenerationStopReason=GenerationStopReason.EOG,truncated:Boolean=false)=
        GenerationSummary("review",reason,GenerationEvidence.HOST,truncated,768,visibleChars=10)
    @Test fun exactTwoAfterPagesReachReviewAndOnlyNormalReturnAcknowledgesWholeReadback(){
        val session=NoteReadPatchSession(readBinding);val ledger=NoteReadDeliveryLedger()
        val first=page(session,0);val last=page(session,900)
        ledger.issued(first.first.call,first.second);ledger.issued(last.first.call,last.second)
        val mutation=patch();val evidence=listOf(entry(mutation),entry(first.first),entry(last.first))
        val full="원래 수정 조건\n도구 실행 결과(인용 자료): ${JsonArray(evidence)}"
        assertTrue(evidence.filter{it.getValue("tool").jsonPrimitive.content=="read_note"}.all{it.getValue("result").jsonObject.getValue("sourceScopePartial").jsonPrimitive.boolean})
        val outcomes=listOf(mutation,first.first,last.first)
        val steps=outcomes.map{HarnessStep(it.call,StepEvidence(it.call.name,it.result.verification,"Host provider"))}
        val plan=SemanticRequestPlan(SemanticAction.TOOL_TASK,"원래 메모 수정과 후속 원문 확인","","",SemanticSourceKind.NONE,required,
            listOf("다른 줄과 현재 메모 버전 유지"),true,"",SemanticTargetType.CONTENT)
        val goal=SemanticRequestPlanning.toTaskGoal(plan)
        assertTrue(TaskHarness.decide(goal,steps,true) is HarnessDecision.Finish)
        assertFalse(FinalSynthesisPolicy.canUse(plan,goal,steps,evidence))
        assertTrue(NoteReadbackPages.canReviewDelivery(assess(outcomes,ledger.keys()).pending,outcomes))
        session.acceptDelivered(evidence,full)
        val batch=ledger.accept(session.acceptedPageTokens());assertTrue(batch.hasPages)
        assertFalse(assess(outcomes,ledger.keys()).readbackSatisfied)
        assertTrue(NoteReadDeliveryCompletion.canAcknowledge(false,false,true,summary()))
        ledger.acknowledge(batch)
        assertEquals(2,ledger.keys().size);assertTrue(assess(outcomes,ledger.keys()).readbackSatisfied)
    }
    @Test fun wholeProviderCoverageWithoutBothPagesInCurrentInputCannotComplete(){
        val session=NoteReadPatchSession(readBinding);val ledger=NoteReadDeliveryLedger()
        val first=page(session,0);val last=page(session,900)
        ledger.issued(first.first.call,first.second);ledger.issued(last.first.call,last.second)
        val evidence=listOf(entry(first.first),entry(last.first));val outcomes=listOf(patch(),first.first,last.first)
        assertTrue(NoteReadbackPages.canReviewDelivery(assess(outcomes,emptySet()).pending,outcomes))
        session.acceptDelivered(evidence,entry(last.first).toString()) // First raw page absent from this actual input.
        val batch=ledger.accept(session.acceptedPageTokens());ledger.acknowledge(batch)
        assertEquals(1,ledger.keys().size);assertFalse(assess(outcomes,ledger.keys()).readbackSatisfied)
    }
    @Test fun missingOrStaleAfterPageStillCannotComplete(){
        val session=NoteReadPatchSession(readBinding);val first=page(session,0);val stale=page(session,900,9)
        val completeKeys=setOf(NoteReadbackPages.deliveryKey(first.first.call,first.first.result.content),NoteReadbackPages.deliveryKey(stale.first.call,stale.first.result.content))
        assertFalse(assess(listOf(patch(),first.first),completeKeys).readbackSatisfied)
        assertFalse(assess(listOf(patch(),first.first,stale.first),completeKeys).readbackSatisfied)
    }
    @Test fun pausedOpaqueTruncatedOrUnknownReviewCannotAcknowledgePages(){
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(true,false,true,summary()))
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,true,true,summary()))
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,false,true,summary(truncated=true)))
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,false,true,summary(GenerationStopReason.SDK_DONE_UNKNOWN)))
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,false,false,summary()))
    }
}
