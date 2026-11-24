package com.user.localagent

import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.HarnessStep
import com.user.localagent.core.ToolReadPolicy
import com.user.localagent.core.VerificationState
import java.util.UUID

internal object BrowserAuthContinuation {
    fun childId(parent:String):String = UUID.nameUUIDFromBytes("ziphap-browser-auth:$parent".toByteArray(Charsets.UTF_8)).toString()

    fun safeSteps(steps:List<HarnessStep>):Boolean {
        val auth=steps.lastOrNull() ?: return false
        if(auth.tool !in setOf("open_app_browser_page","read_app_browser_page","open_pc_browser_page") ||
            auth.evidence.state!=VerificationState.FAILED || !auth.evidence.effectNotApplied ||
            auth.evidence.summary!=BrowserAgentTools.PHONE_AUTH_REQUIRED)return false
        return steps.dropLast(1).all { step ->
            (ToolReadPolicy.isReadOnly(step.tool)||step.tool in setOf("open_app_browser_page","open_pc_browser_page")) &&
                (step.evidence.state==VerificationState.VERIFIED || step.evidence.effectNotApplied)
        }
    }
}
