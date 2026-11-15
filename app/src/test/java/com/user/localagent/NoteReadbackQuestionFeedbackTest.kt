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
class NoteReadbackQuestionFeedbackTest {
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
        WrittenAnswer("{\"type\":\"clarification\",\"question\":\"계속할까요?\"}",null,
            GenerationSummary("review",reason,GenerationEvidence.HOST,truncated,768,visibleChars=10),paused,toolEnvelope=true,opaqueOutput=opaque)
    private fun feedback(f:Fixture,q:QueuedChatRequest=request,current:RequiredReadbackObligations.Context=context,
        answer:WrittenAnswer=written(),used:Boolean=false,allowed:Set<String> = required,rounds:Int=3,task:TaskGoal=goal)=
        NoteReadbackQuestionFeedback.feedback(q,task,f.steps(),f.outcomes,f.state(),current,allowed,f.host.deliveredKeys(),answer,used,rounds,false)
    private suspend fun partial(ack:Boolean=true):Fixture=Fixture().also{f->f.patch();val page=f.execute(f.read(0));f.deliver(listOf(page),ack)}
    private suspend fun complete():Fixture=Fixture().also{f->f.patch();val a=f.execute(f.read(0));val b=f.execute(f.read(900));f.deliver(listOf(a,b),true)}
    @Test fun actualPartialAfterReceiptAndNormalInputAckPermitOnlyProtocolFeedback()=runBlocking {
        val f=partial();val beforeSteps=f.steps();val source=f.db.note;val result=checkNotNull(feedback(f))
        assertTrue(result.contains("nextOffset=900"));assertFalse(result.contains("\"type\":\"tool_call\""))
        assertEquals(beforeSteps.map{it.arguments},f.steps().map{it.arguments});assertEquals(source,f.db.note);assertEquals(1,f.db.receipts.size)
        assertFalse(RequiredReadbackObligations.assess(f.state(),context,f.steps(),0,required,14,3,true).readbackSatisfied)
        val original="원래 query와 현재 원문 페이지";assertTrue((original+"\n"+result).startsWith(original))
    }
    @Test fun unacknowledgedAbsentOrAlreadyWholeAfterPagesCannotTriggerFeedback()=runBlocking {
        assertNull(feedback(partial(false)));val f=Fixture();f.patch();assertNull(feedback(f));assertNull(feedback(complete()))
    }
    @Test fun questionWordsDoNotClassifyAuthorityAndAreNotCopiedIntoFeedback()=runBlocking {
        val f=partial();val a=checkNotNull(feedback(f))
        val b=feedback(f,answer=written().copy(text="{\"type\":\"clarification\",\"question\":\"다른 필수 선택이 있나요?\"}"))
        assertEquals(a,b);assertFalse(a.contains("계속할까요"));assertFalse(a.contains("다른 필수 선택"))
    }
    @Test fun secondQuestionUnknownStopOpaquePausedTruncatedOrMalformedQuestionKeepsOriginalPause()=runBlocking {
        val f=partial();assertNull(feedback(f,used=true));assertNull(feedback(f,rounds=0));assertNull(feedback(f,rounds=1))
        for(w in listOf(written(GenerationStopReason.SDK_DONE_UNKNOWN),written(paused=true),written(opaque=true),written(truncated=true),
            written().copy(messageId="stored"),written().copy(toolEnvelope=false),written().copy(text="{\"type\":\"clarification\",\"question\":\"\"}"),
            written().copy(text="{\"type\":\"clarification\",\"question\":\"x\",\"permission\":true}")))assertNull(feedback(f,answer=w))
    }
    @Test fun currentRunThreadProjectBranchScopeAndWorkspaceRemainMandatory()=runBlocking {
        val f=partial()
        for(c in listOf(context.copy(runId="other"),context.copy(threadId="other"),context.copy(projectId="other"),context.copy(scopeKey="other"),context.copy(workspaceBinding="other")))assertNull(feedback(f,current=c))
        assertNull(feedback(f,q=request.copy(branchId="other")))
    }
    @Test fun readPermissionOldRequestMixedTaskAndGoalAreNotRestored()=runBlocking {
        val f=partial();assertNull(feedback(f,allowed=emptySet()));assertNull(feedback(f,q=request.copy(allowTools=false)))
        assertNull(feedback(f,q=request.copy(previousSteps=f.steps())));assertNull(feedback(f,q=request.copy(readbackObligations=f.state())))
        assertNull(feedback(f,q=request.copy(attachments=listOf(ChatAttachment("a","a.txt","text/plain",1)))))
        assertNull(feedback(f,q=request.copy(goal=GoalDefinition("goal","thread","project","영속 목표",listOf(GoalCriterion("c","검증"))))))
        assertNull(feedback(f,task=TaskGoal(owner,listOf("혼합 작업"),requiredTools=required+"create_reminder")))
        assertNotNull(feedback(f,q=request.copy(pcImageRecoveryAllowed=true)))
    }
    @Test fun malformedNextOffsetDifferentHashOrVersionAndUnknownEffectNeverReceiveFeedback()=runBlocking {
        for(field in listOf("nextOffset","bodySha256","expectedUpdatedAt","hasMore","id")){
            val f=partial();val old=f.outcomes.last();val json=Json.parseToJsonElement(old.result.content).jsonObject
            val value:JsonElement=when(field){"nextOffset"->JsonPrimitive(901);"bodySha256"->JsonPrimitive("b".repeat(64));"expectedUpdatedAt"->JsonPrimitive("99");"hasMore"->JsonPrimitive(false);else->JsonPrimitive("other")}
            f.outcomes[f.outcomes.lastIndex]=old.copy(result=old.result.copy(content=JsonObject(json+(field to value)).toString()))
            assertNull(feedback(f))
        }
        val f=partial();val last=f.outcomes.last();f.outcomes[f.outcomes.lastIndex]=last.copy(result=last.result.copy(verification=VerificationState.UNVERIFIED));assertNull(feedback(f))
    }
}
