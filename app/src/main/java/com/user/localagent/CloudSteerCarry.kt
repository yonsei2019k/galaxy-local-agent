package com.user.localagent

import com.user.localagent.core.HarnessStep
import com.user.localagent.core.ToolReadPolicy
import com.user.localagent.core.VerificationState

/** Decides which earlier steps a steered cloud run carries; the cloud path refuses any non-empty carry. */
internal object CloudSteerCarry {
    private fun effectFreeRead(step:HarnessStep):Boolean =
        ToolReadPolicy.isReadOnly(step.tool)&&step.evidence.continuationId==null&&
            (step.evidence.state==VerificationState.VERIFIED||
                (step.evidence.state==VerificationState.FAILED&&step.evidence.effectNotApplied))

    fun carriedSteps(allowExternalAgent:Boolean,steps:List<HarnessStep>):List<HarnessStep> =
        if(allowExternalAgent&&steps.all(::effectFreeRead))emptyList() else steps
}
