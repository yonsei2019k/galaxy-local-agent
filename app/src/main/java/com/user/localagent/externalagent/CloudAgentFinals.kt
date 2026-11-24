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
        val last=actual.lastOrNull { it.proposal.tool in setOf("open_app_browser_page","read_app_browser_page","app_browser_action") }
        if(last!=null){
            return when {
                last.result.success && last.result.verification==VerificationState.VERIFIED ->
                    "휴대폰 브라우저에서 마지막 페이지를 확인했어요. 사이트에 요청하신 작업의 완료 표시가 있는지는 확인하지 못했어요."
                last.result.effectNotApplied ->
                    "휴대폰 브라우저에서 마지막 페이지를 가져오지 못했어요. 이전에 확인한 단계는 작업 카드에서 볼 수 있어요."
                else -> "브라우저의 마지막 동작이 사이트에 반영됐는지 확인하지 못했어요."
            }
        }
        val verified=actual.count { it.result.success && it.result.verification==VerificationState.VERIFIED }
        return if(verified>0)"확인한 결과 ${verified}건을 실행 기록에 남겼어요. 요청 전체가 완료됐다는 확인은 받지 못했어요."
            else "이번 실행에서 실제 결과를 받지 못했어요."
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
