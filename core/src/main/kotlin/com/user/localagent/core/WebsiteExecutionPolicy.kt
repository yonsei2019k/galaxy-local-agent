package com.user.localagent.core

import kotlinx.serialization.json.JsonPrimitive

/** Applies to agent tools. Protected sign-in cards are presented directly by the host. */
object WebsiteExecutionPolicy {
    const val HEADLESS_NOTICE="사이트 작업은 연결된 PC의 백그라운드 브라우저에서 진행해요. 휴대폰에서는 인증 카드에서 본인 확인을 마쳐 주세요."
    const val RECONNECT_CODE="pc_browser_waiting_connection"
    const val RECONNECT_NOTICE="PC에 연결하지 못해 사이트 작업을 시작하지 않았어요. 원래 요청과 확인한 결과를 보존했어요. PC 작업기를 켜고 연결한 뒤 같은 대화에서 이어가 주세요."
    val phoneWebTools=AppBrowserToolCatalog.names+setOf("open_url",com.user.localagent.core.connections.GovernmentServiceCatalog.OPEN)
    val pcWebTools=PcBrowserToolCatalog.names+"save_browser_pdf"

    fun offered(tools:Set<String>,hasCapturedPcAuthority:Boolean):Set<String> =
        tools-phoneWebTools-(if(hasCapturedPcAuthority)emptySet() else pcWebTools)

    fun allows(call:ToolCall):Boolean = call.name !in phoneWebTools &&
        (call.name!="save_browser_pdf" || call.arguments["browser"]==JsonPrimitive("pc")) &&
        !ScreenControlToolCatalog.isPlaybackRead(call.name,call.arguments) &&
        (call.name!=ScreenControlToolCatalog.ACTION || call.arguments["targetArea"]!=JsonPrimitive("browser_ui"))

    fun permitsNativeScreen(isBrowser:Boolean,visiblePrimaryWebView:Boolean):Boolean = !isBrowser&&!visiblePrimaryWebView

    fun waiting(tool:String,result:ToolResult)=tool in pcWebTools && !result.success && result.effectNotApplied &&
        result.verification==VerificationState.FAILED && result.verificationSummary==RECONNECT_CODE
}
