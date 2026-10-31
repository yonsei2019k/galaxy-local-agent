package com.user.localagent.core

import kotlinx.serialization.json.*

/** The host binds partial/full analysis receipts, never a model-issued completion flag. */
object AnalysisCompletionEvidence {
    fun fromResult(call:ToolCall,result:ToolResult):StepEvidence {
        if(call.name !in PrivateAnalysisToolCatalog.analysisNames||!result.success)return StepEvidence(call.name,result.verification,result.verificationSummary)
        val report=Json.parseToJsonElement(result.content).jsonObject
        val id=report.getValue("analysisId").jsonPrimitive.content
        require(UUID.matches(id))
        val complete=report.getValue("analysisCompleteWithinSelectedText").jsonPrimitive.boolean
        require(result.verification==if(complete)VerificationState.VERIFIED else VerificationState.UNVERIFIED)
        return StepEvidence(call.name,result.verification,result.verificationSummary,id)
    }
    fun resolved(steps:List<HarnessStep>,index:Int):Boolean {
        val step=steps[index]
        val id=step.evidence.continuationId?:return false
        if(step.tool !in PrivateAnalysisToolCatalog.analysisNames)return false
        return steps.drop(index+1).any {it.tool==step.tool&&it.evidence.continuationId==id&&it.evidence.state==VerificationState.VERIFIED}
    }
    private val UUID=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
}
