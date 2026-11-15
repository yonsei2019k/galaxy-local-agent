package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.core.RequiredReadbackObligations as L
import com.user.localagent.notes.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotePatchReadbackIntegrationTest {
    private val binding=RequiredReadbackPolicy.Binding("run-demo","scope-demo",null)
    private val context=L.Context("run-demo","thread-demo",null,"scope-demo",null)
    private val required=setOf("patch_note","read_note")
    private fun mutation(body:String="changed "+"x".repeat(1300)):ObservedToolOutcome {
        val call=ToolCall("patch-demo","patch_note",buildJsonObject{put("id","note-demo");put("scope","team");put("expectedUpdatedAt","7");put("oldText","needle");put("newText","changed")})
        val receipt=NotePatchReceipt(NotePatchIdentity("note-patch-"+"a".repeat(64),"a".repeat(64),"note-demo","team"),"title-demo",7,8,noteSha("needle "+"x".repeat(1300)),noteSha(body),true)
        return ObservedToolOutcome(call,ToolResult(call.id,true,receipt.json().toString(),VerificationState.VERIFIED,"host"))
    }
    private fun read(offset:Int=0,body:String="changed "+"x".repeat(1300),version:Long=8):ObservedToolOutcome {
        val note=NoteSource("note-demo","scope-demo","team","title-demo",body,version)
        val call=ToolCall("read-demo-$offset","read_note",buildJsonObject{put("id",note.id);put("scope",note.collection);put("expectedUpdatedAt",version.toString());put("offset",offset);put("limit",900)})
        val page=NoteReadPatchSession(NoteReadBinding("run-demo","thread-demo","branch-demo","scope-demo")).read(note,NoteReadPatchToolCatalog.readRequest(call))
        return ObservedToolOutcome(call,ToolResult(call.id,true,page.json().toString(),VerificationState.VERIFIED,"host"))
    }
    private fun steps(outcomes:List<ObservedToolOutcome>)=outcomes.map{HarnessStep(it.call,StepEvidence(it.call.name,it.result.verification,"host"))}
    // Synthetic host-delivery fixture only; production keys come from the request ledger after model return.
    private fun delivered(outcomes:List<ObservedToolOutcome>)=outcomes.filter{it.call.name=="read_note"}.map{NoteReadbackPages.deliveryKey(it.call,it.result.content)}.toSet()
    private fun assess(vararg outcomes:ObservedToolOutcome)=ChatReadbackEvidence.assess(binding,required,required,steps(outcomes.toList()),outcomes.toList(),3,deliveredNoteReadKeys=delivered(outcomes.toList()))
    @Test fun actualMutationReceiptPlusPartialPageCannotFinish(){assertFalse(assess(mutation(),read()).readbackSatisfied)}
    @Test fun wholePageSetMustMatchActualMutationVersionAndBody(){
        assertTrue(assess(mutation(),read(),read(900)).readbackSatisfied)
        assertFalse(assess(mutation(),read(version=9),read(900,version=9)).readbackSatisfied)
        assertFalse(assess(mutation(),read(body="wrong"),read(body="wrong")).readbackSatisfied)
    }
    @Test fun completionReportDistinguishesCompletePageSetFromOnePartialPage(){
        val partial=ActionCompletionReport.render(required,listOf(mutation(),read()))
        assertTrue(partial.contains("전체 요청의 완료는 확인되지 않았습니다."))
        val full=ActionCompletionReport.render(required,listOf(read(version=7),mutation(),read(),read(900)))
        assertFalse(full.contains("전체 요청의 완료는 확인되지 않았습니다."))
        assertTrue(full.contains("전체 저장 재조회를 확인"))
    }
    @Test fun beforeMutationPageCannotCompleteAnAfterPartialRead(){assertFalse(assess(read(),mutation(),read(900)).readbackSatisfied)}
    @Test fun privateDurableObligationSurvivesNarrowedReadOnlyResumeWithoutAddingWrite(){
        val old=listOf(mutation());val oldSteps=steps(old);val collected=ChatReadbackEvidence.collect(binding,required,oldSteps,old)
        val selected=checkNotNull(L.select(null,context,required))
        val captured=checkNotNull(L.capture(selected,context,oldSteps,collected.mutations,collected.reads,collected.aligned))
        assertEquals(setOf(L.Pair.NOTE_PATCH),captured.selected)
        assertEquals(captured,ChatReadbackObligationCodec.decode(ChatReadbackObligationCodec.encode(captured)))
        val resumed=context.copy(runId="resumed-demo")
        val carried=L.carry(captured,context,resumed,oldSteps,oldSteps,mapOf(0 to 0))
        val narrowed=L.select(carried,resumed,setOf("read_note"))
        assertEquals(setOf(L.Pair.NOTE_PATCH),narrowed?.selected)
        assertFalse(L.assess(narrowed,resumed,oldSteps,1,setOf("read_note"),17,3).readbackSatisfied)
        val actual=old+read()+read(900);val actualSteps=steps(actual)
        val evidence=ChatReadbackEvidence.collect(resumed.proofBinding(),required,actualSteps,actual,delivered(actual))
        val finished=checkNotNull(L.capture(narrowed,resumed,actualSteps,emptyList(),evidence.reads,true,
            actual.withIndex().filter{it.value.call.name=="read_note"}.associate{it.index to checkNotNull(ChatReadbackEvidence.querySignature(it.value.call.name,it.value.call.arguments))}))
        assertTrue(L.assess(finished,resumed,actualSteps,1,setOf("read_note"),15,3).readbackSatisfied)
        assertEquals(1,actual.count{it.call.name=="patch_note"})
    }
    @Test fun partialAndMalformedLaterReadCannotKeepAnOldWholeProof(){
        val malformed=read().let{it.copy(result=it.result.copy(content="{}"))}
        assertFalse(assess(mutation(),read(),read(900),malformed).readbackSatisfied)
    }
    @Test fun oldNotePairSnapshotContractRemainsDistinct(){
        assertEquals("note",L.Pair.NOTE_SAVE.kind);assertEquals("note",L.Pair.NOTE_UPDATE.kind)
        assertEquals("note_body_v1",L.Pair.NOTE_PATCH.kind)
        assertNotEquals(RequiredReadbackPolicy.Resource("note","note-demo"),RequiredReadbackPolicy.Resource("note_body_v1","note-demo"))
    }
}
