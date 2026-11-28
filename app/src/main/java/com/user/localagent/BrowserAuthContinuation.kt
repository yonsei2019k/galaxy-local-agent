package com.user.localagent

import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.HarnessStep
import com.user.localagent.core.ToolReadPolicy
import com.user.localagent.core.VerificationState
import com.user.localagent.core.ToolResult
import com.user.localagent.data.ChatRunRecord
import com.user.localagent.integrations.BrowserLoginRoutes
import java.util.UUID
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal object BrowserAuthContinuation {
    const val PENDING_SOURCE="hostPendingBrowserAuthSource"
    const val OWNER_CANCELLED="browser_auth_cancelled"

    fun matchesHandoff(current:String?,expected:String,confirmedInCurrentWork:Boolean):Boolean =
        current==expected||current==null&&confirmedInCurrentWork

    /** A retry keeps the original host handoff even if its immediate parent only saw a timeout. */
    suspend fun source(request:QueuedChatRequest,load:suspend(String)->ChatRunRecord?):ChatRunRecord? {
        val direct=request.browserAuthParentRunId
        val id=direct?:request.goalSegment?.parentRunId?:return null
        fun matches(row:ChatRunRecord)=row.threadId==request.threadId&&row.branchId==request.branchId&&row.projectId==request.projectId
        val parent=checkNotNull(load(id))
        check(matches(parent))
        val parentJson=Json.parseToJsonElement(parent.contextJson).jsonObject
        val wake=(parentJson["hostGoalWake"] as? kotlinx.serialization.json.JsonObject)?.get("event")
        val source=when {
            direct!=null||wake==JsonPrimitive("OWNER_AUTH_DONE")->parent
            else->{
                val inherited=(parentJson[PENDING_SOURCE] as? JsonPrimitive)?.content?:return null
                checkNotNull(load(inherited))
            }
        }
        val original=QueuedChatRequest.fromJson(source.contextJson)
        check(matches(source)&&source.id==original.runId&&pcBrowserWorkKey(original)==pcBrowserWorkKey(request)&&
            original.capturedPcRevision==request.capturedPcRevision&&original.externalAgentRevision==request.externalAgentRevision)
        check(readAfterAuth(original.previousSteps.lastOrNull())!=null)
        return source
    }
    fun readAfterAuth(step:HarnessStep?):String? {
        if(step==null||step.evidence.state!=VerificationState.FAILED||!step.evidence.effectNotApplied||
            step.evidence.summary!=BrowserAgentTools.PHONE_AUTH_REQUIRED)return null
        return when(step.tool){
            "open_pc_browser_page","read_pc_browser_page"->"read_pc_browser_page"
            "open_app_browser_page","read_app_browser_page"->"read_app_browser_page"
            else->null
        }
    }

    fun verifiedPage(result:ToolResult):Boolean {
        if(!result.success||result.verification!=VerificationState.VERIFIED)return false
        val data=runCatching{Json.parseToJsonElement(result.content).jsonObject}.getOrNull()?:return false
        return verifiedPage(data)
    }

    fun verifiedPage(data:kotlinx.serialization.json.JsonObject):Boolean {
        val snapshot=data["snapshotId"] as? JsonPrimitive ?:return false
        return snapshot.isString&&snapshot.content.isNotBlank()&&data["loginRequired"]==JsonPrimitive(false)&&
            data["blocked"]!=JsonPrimitive(true)&&data["externalHandoffBlocked"]!=JsonPrimitive(true)
    }

    fun readFailureNotice(result:ToolResult):String {
        val code=runCatching{(Json.parseToJsonElement(result.content).jsonObject["error"] as? JsonPrimitive)?.content}.getOrNull()
        return when(code){
            "TLS_ERROR"->"로그인 뒤 사이트에 다시 연결하는 중 보안 연결이 끊겼어요."
            "PAGE_TIMEOUT","PAGE_UNREACHABLE","HTTP_ERROR"->"로그인 뒤 원래 페이지를 다시 열었지만 사이트에서 응답을 받지 못했어요."
            "OWNER_LOGIN_PAGE_CHANGED","BROWSER_WORK_SESSION_GONE"->"로그인했던 브라우저 탭이 닫혔거나 다른 사이트로 바뀌어 원래 페이지를 확인하지 못했어요."
            else->"로그인 뒤 같은 브라우저의 페이지를 다시 읽었지만 원래 작업을 이어갈 화면을 확인하지 못했어요."
        }
    }
    val reopenablePauses=setOf("browser_auth_on_phone","waiting_browser_auth","browser_auth_resume_expired",
        "browser_auth_resume_unavailable")
    fun childId(parent:String):String = UUID.nameUUIDFromBytes("ziphap-browser-auth:$parent".toByteArray(Charsets.UTF_8)).toString()

    fun allowsOrdinaryHandoff(request:QueuedChatRequest):Boolean = request.goal==null&&request.officeStage==null&&
        request.scheduledReadTool==null&&request.continuation==null&&request.attachments.isEmpty()&&
        request.allowExternalAgent&&request.mode=="act"

    fun child(original:QueuedChatRequest,queueOrder:Long):QueuedChatRequest = original.copy(
        runId=childId(original.runId),queueOrder=queueOrder,previousSteps=emptyList(),readbackObligations=null,
        browserAuthParentRunId=original.runId,
        browserWorkRootRunId=original.browserWorkRootRunId?:original.browserAuthParentRunId)

    const val NAVER_RETURN_PAGE_KEY = "hostNaverAuthReturnPage"
    const val PC_LOGIN_ORIGIN_KEY = "hostPairedBrowserLoginOrigin"
    const val PC_LOGIN_TASK_PAGE_KEY = "hostPairedBrowserTaskPage"
    data class ReopenPage(val url:String,val naver:Boolean,val taskUrl:String?=null)

    fun reopenPage(contextJson:String):ReopenPage? {
        val steps=QueuedChatRequest.fromJson(contextJson).previousSteps
        if(!safeSteps(steps))return null
        val action=steps.last()
        val originalUrl=(action.arguments["url"] as? JsonPrimitive)?.content
        val saved=(Json.parseToJsonElement(contextJson).jsonObject[NAVER_RETURN_PAGE_KEY] as? JsonPrimitive)?.content
        val taskUrl=when(action.tool){
            "open_app_browser_page"->BrowserLoginRoutes.naverTaskPage(originalUrl)
            "read_app_browser_page"->BrowserLoginRoutes.naverTaskPage(saved)
            else->null
        }
        val loginStart=BrowserLoginRoutes.naverReturnPage(taskUrl)
        if(loginStart!=null)return ReopenPage(loginStart,true,taskUrl?.takeUnless{it==loginStart})
        return ownerLoginPage(steps)?.let { ReopenPage(it,false) }
    }

    fun canReopenLogin(contextJson:String):Boolean =
        (safeSteps(QueuedChatRequest.fromJson(contextJson).previousSteps)&&pcLoginOrigin(contextJson)!=null)||reopenPage(contextJson)!=null

    fun pcLoginOrigin(contextJson:String):String? {
        val steps=QueuedChatRequest.fromJson(contextJson).previousSteps
        val last=steps.lastOrNull()?:return null
        if(last.tool !in setOf("open_pc_browser_page","read_pc_browser_page")||!last.evidence.effectNotApplied||
            last.evidence.summary!=BrowserAgentTools.PHONE_AUTH_REQUIRED)return null
        val recorded=(Json.parseToJsonElement(contextJson).jsonObject[PC_LOGIN_ORIGIN_KEY] as? JsonPrimitive)?.content
        if(recorded!=null)return recorded.takeIf {com.user.localagent.compute.PcLoginSite.origin(it)==it}
        return com.user.localagent.compute.PcLoginSite.KAIRB.takeIf {canReopenPcLogin(steps)}
    }

    fun pcTaskPage(contextJson:String):String? {
        val origin=pcLoginOrigin(contextJson)?:return null
        val recorded=(Json.parseToJsonElement(contextJson).jsonObject[PC_LOGIN_TASK_PAGE_KEY] as? JsonPrimitive)?.content
        return BrowserLoginRoutes.pairedTaskPage(recorded,origin)
            ?:pcTaskFromSteps(QueuedChatRequest.fromJson(contextJson).previousSteps,origin)
    }

    fun pcTaskFromSteps(steps:List<HarnessStep>,origin:String):String? {
        if(steps.lastOrNull()?.tool !in setOf("open_pc_browser_page","read_pc_browser_page"))return null
        val open=steps.lastOrNull {it.tool in com.user.localagent.core.PcBrowserToolCatalog.names &&
            it.tool!="read_pc_browser_page"}?.takeIf {it.tool=="open_pc_browser_page"}?:return null
        val raw=(open.arguments["url"] as? JsonPrimitive)?.content
        return BrowserLoginRoutes.pairedTaskPage(raw,origin)
    }

    fun canReopenPcLogin(steps:List<HarnessStep>):Boolean {
        if(!safeSteps(steps)||steps.last().tool !in setOf("open_pc_browser_page","read_pc_browser_page"))return false
        val open=steps.lastOrNull {it.tool=="open_pc_browser_page"}?:return false
        val raw=(open.arguments["url"] as? JsonPrimitive)?.content?:return false
        if(!com.user.localagent.core.WebAgentPolicy.urlAllowed(raw))return false
        val uri=runCatching {URI(raw)}.getOrNull()?:return false
        return uri.scheme=="https"&&uri.port in setOf(-1,443)&&uri.host in setOf("kairb.org","www.kairb.org")
    }

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
        if(auth.tool !in setOf("open_app_browser_page","read_app_browser_page","open_pc_browser_page","read_pc_browser_page") ||
            auth.evidence.state!=VerificationState.FAILED || !auth.evidence.effectNotApplied ||
            auth.evidence.summary!=BrowserAgentTools.PHONE_AUTH_REQUIRED)return false
        return safeBeforeLogin(steps.dropLast(1))
    }

    fun safeBeforeLogin(steps:List<HarnessStep>)=steps.all { step ->
            (ToolReadPolicy.isReadOnly(step.tool)||step.tool in setOf("open_app_browser_page","open_pc_browser_page")) &&
                (step.evidence.state==VerificationState.VERIFIED || step.evidence.effectNotApplied)
    }
}
