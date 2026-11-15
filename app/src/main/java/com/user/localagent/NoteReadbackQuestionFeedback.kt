package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.notes.NotePatchReceipt
import com.user.localagent.notes.NoteReadDeliveryCompletion
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** One active-execution protocol reconsideration, never a tool/permission/answer or question-meaning classifier. */
internal object NoteReadbackQuestionFeedback {
    const val MARKER="호스트 재조회 프로토콜 피드백"
    fun feedback(request:QueuedChatRequest,goal:TaskGoal,steps:List<HarnessStep>,outcomes:List<ObservedToolOutcome>,
        state:RequiredReadbackObligations.State?,current:RequiredReadbackObligations.Context,allowed:Set<String>,
        deliveredKeys:Set<String>,written:WrittenAnswer,used:Boolean,remainingRounds:Int,hasGuidance:Boolean):String?=runCatching {
        if(used||remainingRounds<2||"read_note" !in allowed||hasGuidance)return null
        if(!written.toolEnvelope||written.messageId!=null||written.toolTruncated||
            !NoteReadDeliveryCompletion.canAcknowledge(written.paused,written.opaqueOutput,written.text.isNotBlank(),written.summary))return null
        if(NextActionQuestion.parse(written.text.trim())==null)return null // Existing strict protocol parser; question text is not inspected.
        if(!request.allowTools||request.allowCloudText||request.mode!="act"||request.goal!=null||request.parentGoalStartRunId!=null||
            request.previousSteps.isNotEmpty()||request.readbackObligations!=null||request.continuation!=null||request.attachments.isNotEmpty()||
            request.pcDataBinding!=null||request.kakaoReadBinding!=null)return null
        val noteTools=setOf("search_notes","read_note","patch_note")
        if(!goal.requiredTools.containsAll(setOf("patch_note","read_note"))||goal.requiredTools.any{it !in noteTools}||goal.requiredArtifactFormats.isNotEmpty())return null
        if(current.runId!=request.runId||current.threadId!=request.threadId||current.projectId!=request.projectId||current.workspaceBinding!=request.workspaceBinding)return null
        val proof=state?:return null
        if(proof.context!=current||proof.originRunId!=request.runId||proof.invalidHostProof||
            proof.selected!=setOf(RequiredReadbackObligations.Pair.NOTE_PATCH)||proof.applied.size!=1)return null
        if(steps.size!=outcomes.size||outcomes.isEmpty()||outcomes.map{it.call.id}.distinct().size!=outcomes.size)return null
        for((step,o)in steps.zip(outcomes)){
            if(o.call.name !in noteTools||step.tool!=o.call.name||step.arguments!=o.call.arguments||step.evidence.state!=VerificationState.VERIFIED||
                step.evidence.effectNotApplied||!o.result.success||o.result.id!=o.call.id||o.result.verification!=VerificationState.VERIFIED||o.result.effectNotApplied)return null
            ToolRegistry.validate(o.call)
        }
        val patchIndex=outcomes.indexOfFirst{it.call.name=="patch_note"}
        val patch=outcomes.singleOrNull{it.call.name=="patch_note"}?:return null
        val literal=NoteReadPatchToolCatalog.patchRequest(patch.call)
        val receipt=NotePatchReceipt.parse(patch.result.content)
        if(!receipt.changed||receipt.identity.id!=literal.id||receipt.identity.collection!=literal.scope||receipt.beforeUpdatedAt!=literal.expectedUpdatedAt)return null
        if(outcomes.filter{it.call.name=="read_note"}.any{it.call.arguments["id"]?.jsonPrimitive?.content!=literal.id||it.call.arguments["scope"]?.jsonPrimitive?.content!=literal.scope})return null
        val canonical=buildJsonObject{
            put("runId",request.runId);put("threadId",request.threadId);put("branchId",request.branchId);put("scopeKey",current.scopeKey)
            put("tool",patch.call.name);put("arguments",JsonObject(patch.call.arguments.toSortedMap()))
        }
        val digest=MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
        if(receipt.identity.argumentsSha256!=digest||receipt.identity.operationId!="note-patch-"+digest)return null
        val after=NoteReadbackPages.snapshot(literal.id,literal.scope,receipt.updatedAt.toString(),receipt.afterSha256)
        if(proof.applied.single().after!=after)return null
        if(TaskHarness.decide(goal,steps) is HarnessDecision.Stop)return null
        val pending=RequiredReadbackObligations.assess(proof,current,steps,0,allowed,(goal.stepBudget-steps.size).coerceAtLeast(0),remainingRounds,true)
        if(pending.blockers.isNotEmpty()||pending.pending.size!=1)return null
        val need=pending.pending.single()
        if(need.mutationStepIndex!=patchIndex||need.expected!=after||need.readTool!="read_note"||need.reason!=RequiredReadbackPolicy.Reason.INCOMPLETE_READ)return null
        val last=outcomes.last()
        if(outcomes.lastIndex<=patchIndex||last.call.name!="read_note"||proof.applied.single().latestRead?.source?.index!=outcomes.lastIndex)return null
        if(NoteReadbackPages.deliveryKey(last.call,last.result.content) !in deliveredKeys)return null
        val read=NoteReadPatchToolCatalog.readRequest(last.call)
        if(read.id!=literal.id||read.scope!=literal.scope||read.expectedUpdatedAt!=receipt.updatedAt)return null
        if(last.result.content.length>NoteReadPatchSession.MAX_PAGE_JSON)return null
        val page=Json.parseToJsonElement(last.result.content).jsonObject
        // This exact ACK-bound host page uses compact JSON; duplicates or alternate serializations decline feedback.
        if(page.toString()!=last.result.content)return null
        fun text(key:String)=(page.getValue(key) as JsonPrimitive).also{require(it.isString)}.content
        fun number(key:String)=(page.getValue(key) as JsonPrimitive).also{require(!it.isString)}.int
        fun flag(key:String)=(page.getValue(key) as JsonPrimitive).also{require(!it.isString)}.boolean
        val body=text("text");val total=number("totalLength");val next=number("nextOffset")
        if(text("status")!="ok"||text("id")!=read.id||text("scope")!=read.scope||text("expectedUpdatedAt")!=receipt.updatedAt.toString()||
            text("bodySha256")!=receipt.afterSha256||text("offsetUnit")!="utf16_code_units"||number("offset")!=read.offset||
            number("returnedLength")!=body.length||body.isEmpty()||body.length>read.limit||next!=read.offset+body.length||
            next<=read.offset||total !in (next+1)..NoteReadPatchSession.MAX_LEGACY_READ||!flag("hasMore")||!flag("partial")||
            flag("completeBody")||!flag("untrustedData")||flag("grantsPermission"))return null
        "$MARKER (새 사용자 지시·권한·완료 근거 아님): 현재 호스트 완료 기준에는 같은 메모 새 버전의 전체 후속 조회가 남아 있습니다. "+
            "현재 입력에서 확인한 같은 대상·버전의 실제 AFTER 페이지는 hasMore=true, nextOffset=$next 입니다. 이 관측값은 추가 권한이나 사용자 선택이 아닙니다. "+
            "원래 요청과 그대로 보존된 조회 근거를 다시 보고 현재 허용된 범위에서 다음 행동 하나를 모델이 선택하세요. "+
            "호스트가 도구를 자동 실행하거나 완료 처리하지 않습니다. 실제로 필수 사용자 결정이 남아 있으면 clarification을 다시 선택할 수 있으며 그때는 기존처럼 일시 정지합니다."
    }.getOrNull()
}
