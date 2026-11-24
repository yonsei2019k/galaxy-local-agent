package com.user.localagent.externalagent

import com.user.localagent.core.HarnessStep
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.*

/** Owner-facing endings of a cloud phone-agent run that are decided without new authority. */
object CloudAgentFinals {
    const val PREPARED_PAUSE="작성 화면을 열어뒀어요. 내용을 확인한 뒤 그 앱에서 보내거나 저장해 주세요. 발송이나 저장이 끝났는지는 아직 확인하지 못했어요."
    private const val LEGACY_TOOL_FREE_PREFIX="휴대폰 도구로 확인하지 않고 클라우드 모델이 생성한 답변입니다. 기기 자료나 현재 시각을 조회한 근거는 없습니다."

    /**
     * The model reported done without a plan and without any phone call: a plain answer.
     * Any recorded phone step means work happened, so the normal evidence check must decide instead.
     */
    fun isToolFreeAnswer(resultState:String,hasPlan:Boolean,phoneCalls:Int,recordedSteps:Int):Boolean=
        resultState=="reported_done"&&!hasPlan&&phoneCalls==0&&recordedSteps==0

    fun toolFreeAnswer(answer:String):String=visibleAnswer(answer).trim()

    /** Removes the old host prefix from display and sharing without mutating saved replies. */
    fun visibleAnswer(answer: String): String {
        var body = answer
        while (body.trimStart().startsWith(LEGACY_TOOL_FREE_PREFIX)) {
            body = body.trimStart().removePrefix(LEGACY_TOOL_FREE_PREFIX).trimStart()
        }
        return body
    }

    internal val browserReadTools=setOf("read_app_browser_page","read_screen")

    internal fun unfinishedNotice(actual:Collection<PhoneAgentExecution>):String {
        val lastBrowser=actual.lastOrNull { it.proposal.tool in com.user.localagent.core.PcBrowserToolCatalog.names ||
            it.proposal.tool in com.user.localagent.core.AppBrowserToolCatalog.names }
        if(lastBrowser?.result?.verificationSummary==com.user.localagent.compute.BrowserAgentTools.PC_AUTH_REQUIRED)
            return "PC 브라우저에서 사이트가 별도 로그인을 요구했어요. 휴대폰에 로그인한 계정은 PC에 연결되지 않아 이 단계에서 멈췄어요."
        if(lastBrowser?.result?.verificationSummary==com.user.localagent.compute.BrowserAgentTools.PHONE_AUTH_REQUIRED)
            return "사이트가 로그인 화면을 보여줬어요. 로그인이 확인되면 이 단계부터 이어갈 수 있어요."
        if(lastBrowser!=null)return browserStopNotice(lastBrowser)
        val verified=actual.count { it.result.success && it.result.verification==VerificationState.VERIFIED }
        return if(verified>0)"확인한 결과 ${verified}건을 작업 카드에 남겼어요. 요청하신 작업은 아직 끝나지 않았어요."
            else "작업을 시작했지만 확인할 수 있는 결과를 받기 전에 멈췄어요."
    }

    private fun browserStopNotice(last:PhoneAgentExecution):String {
        val result=last.result
        val page=runCatching {Json.parseToJsonElement(result.content).jsonObject}.getOrNull()
        fun label(field:JsonElement?)=(field as? JsonPrimitive)?.contentOrNull
            ?.filterNot(Char::isISOControl)?.trim()?.take(60)?.takeIf(String::isNotBlank)
        val isClick=last.proposal.arguments["action"]==JsonPrimitive("click")
        if(!result.success||result.verification!=VerificationState.VERIFIED){
            val target=label(page?.get("attemptedControl"))?.let { "‘$it’ 버튼" } ?: "누르려던 버튼"
            val destinationFailure=when(label(page?.get("navigationError"))){
                "PAGE_TIMEOUT"->"사이트가 다음 화면을 보내지 않았어요."
                "PAGE_UNREACHABLE","HTTP_ERROR"->"사이트에서 다음 화면을 열어주지 못했어요."
                "TLS_ERROR","HTTPS_REQUIRED"->"다음 화면에 안전하게 연결할 수 없었어요."
                "NAVIGATION_BLOCKED"->"사이트가 집합에서 열 수 없는 화면으로 이동하려고 했어요."
                else->null
            }
            if(isClick&&destinationFailure!=null)return "${target}을 누른 뒤 $destinationFailure"
            return when((page?.get("error") as? JsonPrimitive)?.contentOrNull){
                "ELEMENT_OBSCURED"->"다른 화면이 ${target}을 가려 누르지 못했어요."
                "ELEMENT_UNAVAILABLE"->"${target}을 현재 화면에서 찾지 못했어요. 이 단계에서 멈췄어요."
                "SNAPSHOT_STALE"->"버튼을 누르기 전에 화면이 바뀌었어요. 바뀐 화면에서 다음 버튼을 확인하지 못했어요."
                "ACTION_OUTCOME_UNKNOWN"->"${target}을 누르는 중에 페이지를 다시 확인하지 못했어요. 버튼이 눌렸는지는 아직 확인하지 못했어요."
                "PAGE_TIMEOUT"->"사이트 응답을 기다렸지만 페이지가 열리지 않아 멈췄어요."
                "TLS_ERROR","HTTPS_REQUIRED"->"사이트에 안전하게 연결하지 못해 페이지를 열 수 없었어요."
                "APPROVAL_MISMATCH"->"확인받은 화면이 바뀌어 ${target}을 누르지 않았어요."
                else->if(isClick)"${target}을 누르는 단계에서 멈췄어요."
                    else "사이트 화면을 열거나 읽지 못해 이 단계에서 멈췄어요."
            }
        }
        val performed=page?.get("performed") as? JsonObject
        val clicked=label(performed?.get("control"))
        if(isClick&&page?.get("pageChanged")==JsonPrimitive(false)){
            return when(clicked?.filterNot(Char::isWhitespace)){
                "강의실입장"->"‘강의실 입장’을 눌렀지만 영상 플레이어나 재생 버튼을 확인하지 못했어요. 영상 재생은 확인되지 않았어요."
                "학습시작하기","학습진행하기","이어듣기"->"‘$clicked’를 눌렀지만 다음 화면이나 영상 재생 버튼을 찾지 못했어요."
                else->if(clicked!=null)"‘$clicked’ 버튼을 눌렀지만 화면이 그대로였어요."
                    else "버튼을 눌렀지만 화면이 그대로였어요."
            }
        }
        val title=label(page?.get("title"))
        return when {
            clicked!=null->"‘$clicked’ 버튼을 누른 뒤 화면을 확인했어요. 다음 동작은 실행되지 않았어요."
            title!=null->"‘$title’ 화면까지 열었어요. 이 화면에서 다음 동작이나 완료 표시를 찾지 못했어요."
            else->"사이트 화면까지 열었어요. 이 화면에서 다음 동작이나 완료 표시를 찾지 못했어요."
        }
    }

    /** Render only counts from the phone's structured observation. Page prose may contain private or hostile text. */
    internal fun browserReadProgress(required:Set<String>, actual:List<PhoneAgentExecution>, steps:List<HarnessStep>):String? {
        if(steps.size!=actual.size || steps.zip(actual).any { (step,local)->
                step.tool !in browserReadTools || step.tool !in required || step.tool!=local.proposal.tool ||
                    step.arguments!=local.proposal.arguments || step.evidence.state!=VerificationState.VERIFIED ||
                    step.evidence.effectNotApplied || local.withheldFromCloud || !local.result.success ||
                    local.result.verification!=VerificationState.VERIFIED || local.result.effectNotApplied
            })return null
        val last=actual.lastOrNull { it.proposal.tool=="read_app_browser_page" } ?: return null
        val count=runCatching {
            val field=Json.parseToJsonElement(last.result.content).jsonObject["sameSessionPdfCount"] as? JsonPrimitive
            field?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..120 }
        }.getOrNull() ?: return null
        return buildString {
            append("앱 브라우저에서 페이지를 읽었어요.")
            if(actual.any { it.proposal.tool=="read_screen" })append(" 휴대폰 화면도 확인했어요.")
            if("save_browser_pdf" in required){
                if(count==0)append(" 마지막으로 읽은 화면에서는 바로 저장할 수 있는 PDF 링크를 찾지 못했어요.")
                else append(" 마지막으로 읽은 화면에 바로 저장할 수 있는 PDF 링크가 ${count}개 있었어요.")
                append(" 파일 저장은 실행하지 않았어요.")
            }else append(" 요청하신 작업의 최종 결과는 아직 확인하지 못했어요.")
        }
    }

    /** True when an executed tool changed or handed off something; then the host report is authoritative. */
    fun needsHostReport(executedTools:Collection<String>):Boolean=executedTools.any{name->
        name in com.user.localagent.core.ActionCompletionReport.managedTools&&!com.user.localagent.core.ToolReadPolicy.isReadOnly(name)
    }
}
