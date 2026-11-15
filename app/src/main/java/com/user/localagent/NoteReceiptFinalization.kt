package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.notes.NotePatchReceipt
import com.user.localagent.notes.NoteReadDeliveryCompletion
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Optional publication of the SAME existing host report, after a model completion decision.
 * Never selects an action, changes a receipt, grants permission, or replaces stored model text.
 * The caller must still check live cancellation/steering/run/branch/scope/permission before writing.
 */
internal object NoteReceiptFinalization {
    private val tools=setOf("search_notes","read_note","patch_note")
    fun report(
        request:QueuedChatRequest,plan:SemanticRequestPlan?,goal:TaskGoal,
        steps:List<HarnessStep>,outcomes:List<ObservedToolOutcome>,
        state:RequiredReadbackObligations.State?,current:RequiredReadbackObligations.Context,
        allowed:Set<String>,hasGuidance:Boolean,written:WrittenAnswer,
        review:AutonomousReviewPolicy.Decision?=null,direct:DirectNextActionProtocol.Decision?=null,
    ):String?=runCatching {
        val reviewed=review is AutonomousReviewPolicy.Decision.Complete&&direct==null&&plan!=null
        val answered=direct is DirectNextActionProtocol.Decision.Answer&&review==null&&plan==null
        if(!reviewed&&!answered)return null
        if(!NoteReadDeliveryCompletion.canAcknowledge(written.paused,written.opaqueOutput,written.text.isNotBlank(),written.summary)||
            written.messageId!=null||written.toolTruncated)return null
        if(!request.allowTools||request.allowCloudText||request.mode!="act"||request.goal!=null||request.parentGoalStartRunId!=null||
            request.attachments.isNotEmpty()||request.previousSteps.isNotEmpty()||request.continuation!=null||request.readbackObligations!=null||
            request.pcDataBinding!=null||request.kakaoReadBinding!=null||hasGuidance)return null
        if(plan!=null&&(plan.action!=SemanticAction.TOOL_TASK||plan.requiredTools!=goal.requiredTools))return null
        if(plan==null&&goal.ownerRequest!=request.text)return null
        if(!goal.requiredTools.containsAll(setOf("patch_note","read_note"))||goal.requiredTools.any{it !in tools}||
            goal.requiredArtifactFormats.isNotEmpty()||!allowed.containsAll(goal.requiredTools))return null
        if(current.runId!=request.runId||current.threadId!=request.threadId||current.projectId!=request.projectId||
            current.workspaceBinding!=request.workspaceBinding)return null
        val proof=state?:return null
        if(proof.context!=current||proof.originRunId!=request.runId||proof.selected!=setOf(RequiredReadbackObligations.Pair.NOTE_PATCH)||
            proof.applied.size!=1||proof.invalidHostProof)return null
        if(steps.isEmpty()||steps.size!=outcomes.size||outcomes.map{it.call.id}.distinct().size!=outcomes.size)return null
        for((step,observed)in steps.zip(outcomes)){
            val call=observed.call;val result=observed.result
            if(call.name !in tools||call.name !in allowed||step.tool!=call.name||step.arguments!=call.arguments||
                step.evidence.state!=VerificationState.VERIFIED||step.evidence.effectNotApplied||
                !result.success||result.id!=call.id||result.verification!=VerificationState.VERIFIED||result.effectNotApplied)return null
            ToolRegistry.validateForRequest(call,allowed)
        }
        val patch=outcomes.singleOrNull{it.call.name=="patch_note"}?:return null
        val literal=NoteReadPatchToolCatalog.patchRequest(patch.call) // References must already be host-resolved.
        val receipt=NotePatchReceipt.parse(patch.result.content)
        if(receipt.identity.id!=literal.id||receipt.identity.collection!=literal.scope||receipt.beforeUpdatedAt!=literal.expectedUpdatedAt)return null
        if(proof.applied.single().after!=NoteReadbackPages.snapshot(literal.id,literal.scope,receipt.updatedAt.toString(),receipt.afterSha256))return null
        val canonical=buildJsonObject{
            put("runId",request.runId);put("threadId",request.threadId);put("branchId",request.branchId);put("scopeKey",current.scopeKey)
            put("tool",patch.call.name);put("arguments",JsonObject(patch.call.arguments.toSortedMap()))
        }
        // Same immutable identity as NoteReadPatchHost; prevents a receipt from another run/branch/scope.
        val digest=MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))
            .joinToString(""){"%02x".format(it.toInt()and 255)}
        if(receipt.identity.argumentsSha256!=digest||receipt.identity.operationId!="note-patch-"+digest)return null
        if(outcomes.filter{it.call.name=="read_note"}.any{
                it.call.arguments["id"]?.jsonPrimitive?.content!=literal.id||it.call.arguments["scope"]?.jsonPrimitive?.content!=literal.scope
            })return null
        if(TaskHarness.decide(goal,steps,requestedFinish=true) !is HarnessDecision.Finish)return null
        val readback=RequiredReadbackObligations.assess(proof,current,steps,0,allowed,
            (goal.stepBudget-steps.size).coerceAtLeast(0),1,requestedFinish=true)
        if(!readback.applicable||!readback.readbackSatisfied)return null
        ActionCompletionReport.render(goal.requiredTools,outcomes) // No new summary, clipping, or whole-body reconstruction.
    }.getOrNull()
}
