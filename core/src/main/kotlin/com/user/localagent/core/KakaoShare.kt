package com.user.localagent.core

object KakaoShare {
    const val TOOL = "prepare_kakao_share"
    const val HANDLER = "kakao_share"
    const val PACKAGE = "com.kakao.talk"
    const val HANDOFF = "카카오톡에 공유할 글을 전달했어요. 휴대폰에서 받을 대화방을 고르고 보내기를 눌러 주세요."
    val definition = ToolDefinition(TOOL, Risk.MEDIUM, mapOf("message" to Argument("string", true, 4000)),
        "Prepare plain text for the phone owner's KakaoTalk sharing screen. The owner personally chooses recipients and presses send. No automatic send, delivery receipt or retries.")
}
