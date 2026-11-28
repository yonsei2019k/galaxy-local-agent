package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.inference.*
import com.user.localagent.notes.*
import com.user.localagent.workspace.ChatAttachment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real host Session/approval/commit receipt/delivery ledger + readback collector; synthetic storage, no Android/model execution. */
class NoteReceiptFinalizationTest {
    private val required=setOf("patch_note","read_note")
    private val binding=NoteReadBinding("run","thread","branch","scope")
    private val context=RequiredReadbackObligations.Context("run","thread","project","scope","workspace")
    private val owner="메모의 'needle'만 'changed'로 고치고 전체를 다시 확인해줘."
    private val request=QueuedChatRequest("run","thread","branch","project",owner,"synthetic",false,emptyList(),"user",workspaceBinding="workspace")
    private val plan=SemanticRequestPlan(SemanticAction.TOOL_TASK,"현재 메모 수정과 재조회","","",SemanticSourceKind.NONE,required,
        listOf("다른 문구와 줄바꿈 보존"),true,"",SemanticTargetType.CONTENT)
    private val goal=SemanticRequestPlanning.toTaskGoal(plan)
    private class Database(var note:NoteSource):NotePatchTransactions {
        val receipts=mutableMapOf<String,String>()
        override suspend fun <T> transaction(requireRunning:Boolean,block:suspend NotePatchTransaction.()->T):T=block(object:NotePatchTransaction{
            override suspend fun note(id:String)=this@Database.note.takeIf{it.id==id}
            override suspend fun putPreservingMetadata(before:NoteSource,body:String):NoteSource {
                check(before==this@Database.note);return this@Database.note.copy(body=body,updatedAt=this@Database.note.updatedAt+1).also{this@Database.note=it}
            }
            override suspend fun receipt(operationId:String)=receipts[operationId]
            override suspend fun putReceipt(operationId:String,json:String){check(operationId !in receipts);receipts[operationId]=json}
        })
    }
    private inner class Fixture {
        val before="needle\r\n"+"보존 구간  원문 😀 e\u0301\r\n".repeat(75)+"마지막 줄은 유지"
        val db=Database(NoteSource("note",binding.scopeKey,"team","제목",before,7))
        val host=NoteReadPatchHost(binding,NotePatchStore(binding,db)){}
        val outcomes=mutableListOf<ObservedToolOutcome>()
        fun read(offset:Int)=ToolCall("read-${outcomes.size}","read_note",buildJsonObject{
            put("id","note");put("scope","team");put("expectedUpdatedAt",db.note.updatedAt.toString());put("offset",offset);put("limit",900)
        })
        suspend fun execute(call:ToolCall):ObservedToolOutcome=ObservedToolOutcome(call,host.execute(call)).also{outcomes+=it}
        fun entry(o:ObservedToolOutcome)=buildJsonObject{
            put("tool",o.call.name);put("success",o.result.success);put("verification",o.result.verification.name)
            put("result",Json.parseToJsonElement(NoteReadModelDelivery.modelResult(o.result.content)))
        }
        fun deliver(reads:List<ObservedToolOutcome>,ack:Boolean){
            val entries=reads.map(::entry);val batch=host.accept(entries,JsonArray(entries).toString())
            if(ack)host.acknowledge(batch)
        }
        suspend fun patch(){
            val beforeRead=execute(read(0));deliver(listOf(beforeRead),true)
            val patch=ToolCall("patch","patch_note",buildJsonObject{
                put("id","note");put("scope","team");put("expectedUpdatedAt","7");put("oldText","needle");put("newText","changed")
            })
            assertNotNull(host.review(patch));host.ownerConfirmed(patch);assertTrue(execute(patch).result.success)
        }
        fun steps()=outcomes.map{HarnessStep(it.call,StepEvidence(it.call.name,it.result.verification,"Host outcome",effectNotApplied=it.result.effectNotApplied))}
        fun state():RequiredReadbackObligations.State {
            val steps=steps();val collected=ChatReadbackEvidence.collect(context.proofBinding(),required,steps,outcomes,host.deliveredKeys())
            return checkNotNull(RequiredReadbackObligations.capture(RequiredReadbackObligations.select(null,context,required),context,steps,
                collected.mutations,collected.reads,collected.aligned,collected.reads.associate{it.stepIndex to
                    checkNotNull(ChatReadbackEvidence.querySignature(steps[it.stepIndex].tool,steps[it.stepIndex].arguments))}))
        }
    }
    private fun written(reason:GenerationStopReason=GenerationStopReason.EOG,paused:Boolean=false,opaque:Boolean=false,truncated:Boolean=false)=
        WrittenAnswer("{\"type\":\"review_complete\",\"summary\":\"완료\"}",null,
            GenerationSummary("review",reason,GenerationEvidence.HOST,truncated,768,visibleChars=10),paused,opaqueOutput=opaque)
    private fun report(f:Fixture,q:QueuedChatRequest=request,current:RequiredReadbackObligations.Context=context,
        answer:WrittenAnswer=written(),review:AutonomousReviewPolicy.Decision=AutonomousReviewPolicy.Decision.Complete("모델 판단"),
        allowed:Set<String> = required,task:TaskGoal=goal,guidance:Boolean=false)=
        NoteReceiptFinalization.report(q,plan,task,f.steps(),f.outcomes,f.state(),current,allowed,guidance,answer,review=review)
    private suspend fun complete():Fixture=Fixture().also{f->f.patch();val a=f.execute(f.read(0));val b=f.execute(f.read(900));f.deliver(listOf(a,b),true)}
    @Test fun afterRealSessionCommitAndAcknowledgedWholePagesReportIsByteIdenticalAndContainsActualPages()=runBlocking {
        val f=complete();val result=checkNotNull(report(f))
        assertEquals(ActionCompletionReport.render(required,f.outcomes),result)
        val actualReads=f.outcomes.filter{it.call.name=="read_note"}.map{Json.parseToJsonElement(it.result.content).jsonObject.getValue("text").jsonPrimitive.content}
        actualReads.forEach{assertTrue(result.contains(it))}
        assertEquals(f.db.note.body,actualReads.takeLast(2).joinToString(""))
        assertFalse(result.contains("부탁한 일이 다 끝났는지는 아직 모름"))
        assertEquals(1,f.db.receipts.size)
    }
    @Test fun directAnswerAfterTheSameProofUsesTheSameCanonicalBytes()=runBlocking {
        val f=complete();val directGoal=TaskGoal(owner,listOf("정확한 수정과 후속 확인"),requiredTools=required)
        val result=NoteReceiptFinalization.report(request,null,directGoal,f.steps(),f.outcomes,f.state(),context,required,false,written(),
            direct=DirectNextActionProtocol.Decision.Answer)
        assertEquals(ActionCompletionReport.render(required,f.outcomes),result)
    }
    @Test fun providerCoverageWithoutNormalInputAckAndMissingAfterPageCannotSkipTheWriter()=runBlocking {
        val f=Fixture();f.patch();val a=f.execute(f.read(0));assertNull(report(f))
        val b=f.execute(f.read(900));f.deliver(listOf(a,b),false);assertNull(report(f))
        f.deliver(listOf(a,b),true);assertNotNull(report(f))
    }
    @Test fun staleFailedOrMalformedLatestReadInvalidatesTheCurrentProof()=runBlocking {
        for(kind in listOf("failed","malformed","stale")){
            val f=complete();val read=f.read(0)
            val result=when(kind){
                "failed"->ToolResult(read.id,false,"failure",VerificationState.FAILED,"failure")
                "malformed"->ToolResult(read.id,true,"{}",VerificationState.VERIFIED,"malformed")
                else->{f.db.note=f.db.note.copy(updatedAt=9);f.host.execute(f.read(0)).copy(id=read.id)}
            }
            f.outcomes+=ObservedToolOutcome(read,result);assertNull(report(f))
        }
    }
    @Test fun otherRunThreadProjectBranchScopeOrWorkspaceCannotBorrowCurrentReceipt()=runBlocking {
        val f=complete()
        for(other in listOf(context.copy(runId="other"),context.copy(threadId="other"),context.copy(projectId="other"),
            context.copy(scopeKey="other"),context.copy(workspaceBinding="other")))assertNull(report(f,current=other))
        assertNull(report(f,q=request.copy(branchId="other")))
        assertNull(report(f,q=request.copy(runId="other")))
    }
    @Test fun pausedOpaqueTruncatedUnknownOrNonCompleteModelDecisionKeepsTheExistingPath()=runBlocking {
        val f=complete()
        for(answer in listOf(written(paused=true),written(opaque=true),written(truncated=true),written(GenerationStopReason.SDK_DONE_UNKNOWN),
            written().copy(messageId="already-stored"),written().copy(toolTruncated=true),written().copy(text="")))assertNull(report(f,answer=answer))
        assertNull(report(f,review=AutonomousReviewPolicy.Decision.Unavailable()))
    }
    @Test fun oldStepsAttachmentsReadOnlyDeniedToolsMixedRequirementsAndGuidanceKeepTheExistingPath()=runBlocking {
        val f=complete()
        assertNull(report(f,q=request.copy(previousSteps=f.steps())))
        assertNull(report(f,q=request.copy(readbackObligations=f.state())))
        assertNull(report(f,q=request.copy(attachments=listOf(ChatAttachment("a","synthetic.txt","text/plain",1)))))
        assertNull(report(f,q=request.copy(allowTools=false)))
        assertNull(report(f,q=request.copy(mode="plan")))
        assertNull(report(f,q=request.copy(goal=GoalDefinition("goal","thread","project","원래 영속 목표",
            listOf(GoalCriterion("criterion","검증된 결과")),status=GoalStatus.ACTIVE,linkedRunIds=listOf("run")))))
        assertNull(report(f,allowed=setOf("read_note")))
        assertNull(report(f,task=TaskGoal(owner,listOf("다른 업무 포함"),requiredTools=required+"calculate")))
        assertNull(report(f,guidance=true))
    }
    @Test fun changedReceiptOrAdditionalMutationCannotReuseTheCompletedCheckpoint()=runBlocking {
        val f=complete();val index=f.outcomes.indexOfFirst{it.call.name=="patch_note"};val original=f.outcomes[index]
        val receipt=NotePatchReceipt.parse(original.result.content)
        f.outcomes[index]=original.copy(result=original.result.copy(content=receipt.copy(afterSha256="b".repeat(64)).json().toString()))
        assertNull(report(f))
        f.outcomes[index]=original
        f.outcomes+=original.copy(call=original.call.copy(id="second"),result=original.result.copy(id="second"))
        assertNull(report(f))
    }
    @Test fun normalSendImageRecoveryFlagRemainsCapturedWithoutBlockingOrAuthorizingNoteFinalization()=runBlocking {
        val f=complete();val expected=checkNotNull(report(f))
        val captured=QueuedChatRequest.fromJson(request.copy(pcImageRecoveryAllowed=true).toJson())
        assertTrue(captured.pcImageRecoveryAllowed)
        assertEquals(expected,report(f,q=captured))
        assertEquals(ActionCompletionReport.render(required,f.outcomes),report(f,q=captured))
        assertTrue(captured.pcImageRecoveryAllowed)
        assertTrue(f.outcomes.all{it.call.name in setOf("patch_note","read_note")})
        val partial=Fixture();partial.patch();partial.execute(partial.read(0));assertNull(report(partial,q=captured))
        assertNull(report(f,q=captured.copy(allowTools=false)))
        assertNull(report(f,q=captured.copy(previousSteps=f.steps())))
        assertNull(report(f,q=captured.copy(readbackObligations=f.state())))
        assertNull(report(f,q=captured.copy(pcDataBinding="a".repeat(64))))
        assertNull(report(f,q=captured.copy(kakaoReadBinding="00000000-0000-0000-0000-000000000001")))
        assertNull(report(f,q=captured,task=TaskGoal(owner,listOf("이미지 작업도 필요"),requiredTools=required+"generate_image")))
    }
}
