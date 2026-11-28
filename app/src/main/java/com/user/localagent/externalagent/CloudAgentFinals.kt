package com.user.localagent.externalagent

import com.user.localagent.core.HarnessStep
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.*

/** Owner-facing endings of a cloud phone-agent run that are decided without new authority. */
object CloudAgentFinals {
    const val PREPARED_PAUSE="작성 화면 열어 둠 · 보냈는지 저장했는지는 아직 모름"
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

    internal val browserPageReads=setOf("read_app_browser_page","read_pc_browser_page")
    internal val browserReadTools=browserPageReads+"read_screen"

    internal fun unfinishedNotice(actual:Collection<PhoneAgentExecution>):String {
        val lastBrowser=actual.lastOrNull { it.proposal.tool in com.user.localagent.core.PcBrowserToolCatalog.names ||
            it.proposal.tool in com.user.localagent.core.AppBrowserToolCatalog.names }
        if(lastBrowser?.result?.verificationSummary==com.user.localagent.compute.BrowserAgentTools.PC_AUTH_REQUIRED)
            return "PC 브라우저에서 따로 로그인 필요 · 휴대폰 로그인은 PC에 안 이어짐"
        if(lastBrowser?.result?.verificationSummary==com.user.localagent.compute.BrowserAgentTools.PHONE_AUTH_REQUIRED)
            return "사이트 로그인 필요 · 로그인하면 여기부터 이어감"
        if(lastBrowser!=null)return browserStopNotice(lastBrowser)
        val verified=actual.count { it.result.success && it.result.verification==VerificationState.VERIFIED }
        return if(verified>0)"확인한 것 ${verified}개는 카드에 남겨 둠 · 아직 안 끝남"
            else "시작했지만 결과를 받기 전에 멈춤"
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
                "PAGE_TIMEOUT"->"다음 화면이 안 옴"
                "PAGE_UNREACHABLE","HTTP_ERROR"->"다음 화면이 안 열림"
                "TLS_ERROR","HTTPS_REQUIRED"->"다음 화면에 안전하게 못 이어짐"
                "NAVIGATION_BLOCKED"->"집합에서 못 여는 화면으로 가려고 함"
                else->null
            }
            if(isClick&&destinationFailure!=null)return "$target 누름 · $destinationFailure"
            return when((page?.get("error") as? JsonPrimitive)?.contentOrNull){
                "ELEMENT_OBSCURED"->"다른 화면이 $target 가림 · 못 누름"
                "ELEMENT_UNAVAILABLE"->"$target 못 찾음 · 여기서 멈춤"
                "SNAPSHOT_STALE"->"누르기 전에 화면이 바뀜 · 다음 버튼 못 봄"
                "TRANSACTION_READBACK_REQUIRED"->"앞 거래 결과를 못 봄 · 주문이나 예약 내역 확인 필요"
                "ACTION_OUTCOME_UNKNOWN"->"$target 누르는 중 페이지를 못 봄 · 눌렸는지 아직 모름"
                "PAGE_TIMEOUT"->"사이트가 답하지 않아 페이지가 안 열림"
                "TLS_ERROR","HTTPS_REQUIRED"->"사이트에 안전하게 못 이어져 페이지가 안 열림"
                "APPROVAL_MISMATCH"->"허락받은 화면이 바뀌어 $target 안 누름"
                else->if(isClick)"$target 누르다가 멈춤"
                    else "사이트 화면을 못 열거나 못 읽어 멈춤"
            }
        }
        val performed=page?.get("performed") as? JsonObject
        val clicked=label(performed?.get("control"))
        if(isClick&&page?.get("pageChanged")==JsonPrimitive(false)){
            return when(clicked?.filterNot(Char::isWhitespace)){
                "강의실입장"->"‘강의실 입장’ 누름 · 영상 플레이어나 재생 버튼 못 봄 · 영상 재생은 아직 모름"
                "학습시작하기","학습진행하기","이어듣기"->"‘$clicked’ 누름 · 다음 화면이나 영상 재생 버튼 못 찾음"
                else->if(clicked!=null)"‘$clicked’ 누름 · 화면 그대로"
                    else "버튼 누름 · 화면 그대로"
            }
        }
        val title=label(page?.get("title"))
        return when {
            clicked!=null->"‘$clicked’ 누른 뒤 화면 확인함 · 다음 동작은 안 함"
            title!=null->"‘$title’ 화면까지 열어 봄 · 다음 동작이나 끝 표시 못 찾음"
            else->"사이트 화면까지 열어 봄 · 다음 동작이나 끝 표시 못 찾음"
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
        val last=actual.lastOrNull { it.proposal.tool in browserPageReads } ?: return null
        val count=runCatching {
            val field=Json.parseToJsonElement(last.result.content).jsonObject["sameSessionPdfCount"] as? JsonPrimitive
            field?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..120 }
        }.getOrNull() ?: return null
        return buildList {
            add(if(last.proposal.tool=="read_pc_browser_page") "PC 브라우저 페이지 읽음" else "앱 브라우저 페이지 읽음")
            if(actual.any { it.proposal.tool=="read_screen" })add("휴대폰 화면도 봄")
            if("save_browser_pdf" in required){
                add(if(count==0)"마지막 화면에 바로 저장할 PDF 링크 없음" else "마지막 화면에 바로 저장할 PDF 링크 ${count}개")
                add("파일 저장은 안 함")
            }else add("최종 결과는 아직 모름")
        }.joinToString(" · ")
    }

    /** True when an executed tool changed or handed off something; then the host report is authoritative. */
    fun needsHostReport(executedTools:Collection<String>):Boolean=executedTools.any{name->
        name in com.user.localagent.core.ActionCompletionReport.managedTools&&!com.user.localagent.core.ToolReadPolicy.isReadOnly(name)
    }
}
