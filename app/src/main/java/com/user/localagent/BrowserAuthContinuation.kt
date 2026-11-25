package com.user.localagent

import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.HarnessStep
import com.user.localagent.core.ToolReadPolicy
import com.user.localagent.core.VerificationState
import java.util.UUID
import java.net.URI
import kotlinx.serialization.json.JsonPrimitive

internal object BrowserAuthContinuation {
    val reopenablePauses=setOf("browser_auth_on_phone","waiting_browser_auth","browser_auth_resume_expired")
    fun childId(parent:String):String = UUID.nameUUIDFromBytes("ziphap-browser-auth:$parent".toByteArray(Charsets.UTF_8)).toString()

    fun ownerLoginPage(steps:List<HarnessStep>):String? {
        if(!safeSteps(steps))return null
        val open=steps.last().takeIf {it.tool=="open_app_browser_page"}?:return null
        val raw=(open.arguments["url"] as? JsonPrimitive)?.content?:return null
        if(!com.user.localagent.core.WebAgentPolicy.urlAllowed(raw))return null
        val uri=runCatching{URI(raw)}.getOrNull()?:return null
        if(!uri.scheme.equals("https",true)||uri.port !in setOf(-1,443))return null
        if(uri.host.equals("hallymedu.co.kr",true))return "https://hallymedu.co.kr/new_home/My/login/"
        return URI("https",null,uri.host,-1,"/",null,null).toASCIIString()
    }

    fun canPauseAfterStatusTimeout(stage:String, pendingCallId:String?, lastObservedId:String?, steps:List<HarnessStep>):Boolean =
        stage=="PC_BRIDGE_TIMEOUT_GET_SESSION_STATUS" && lastObservedId!=null &&
            pendingCallId==lastObservedId && safeSteps(steps)

    fun safeSteps(steps:List<HarnessStep>):Boolean {
        val auth=steps.lastOrNull() ?: return false
        if(auth.tool !in setOf("open_app_browser_page","read_app_browser_page","open_pc_browser_page") ||
            auth.evidence.state!=VerificationState.FAILED || !auth.evidence.effectNotApplied ||
            auth.evidence.summary!=BrowserAgentTools.PHONE_AUTH_REQUIRED)return false
        return safeBeforeLogin(steps.dropLast(1))
    }

    fun safeBeforeLogin(steps:List<HarnessStep>)=steps.all { step ->
            (ToolReadPolicy.isReadOnly(step.tool)||step.tool in setOf("open_app_browser_page","open_pc_browser_page")) &&
                (step.evidence.state==VerificationState.VERIFIED || step.evidence.effectNotApplied)
    }
}
