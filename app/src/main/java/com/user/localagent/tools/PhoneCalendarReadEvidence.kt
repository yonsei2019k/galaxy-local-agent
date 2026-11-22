package com.user.localagent.tools

import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState

internal object PhoneCalendarReadEvidence {
    const val TOOL = "list_calendar_events"
    const val PERMISSION_REQUIRED = "PHONE_CALENDAR_READ_PERMISSION_REQUIRED"
    const val PAUSE_CODE = "permission_calendar"
    const val NOTICE = "휴대폰 일정을 읽으려면 일정 읽기 권한이 필요해요. 요청을 대화에 남겼어요. 기능 목록의 연결에서 휴대폰 캘린더에 읽기를 허용할 수 있어요."

    fun permissionDenied(call: ToolCall): ToolResult = failed(call, NOTICE, PERMISSION_REQUIRED)

    fun providerUnavailable(call: ToolCall): ToolResult =
        failed(call, "휴대폰 캘린더에서 조회 결과를 받지 못했습니다.", "PHONE_CALENDAR_PROVIDER_UNAVAILABLE")

    fun requiresPermission(tool: String, result: ToolResult): Boolean =
        tool == TOOL && !result.success && result.verification == VerificationState.FAILED &&
            result.effectNotApplied && result.verificationSummary == PERMISSION_REQUIRED

    private fun failed(call: ToolCall, message: String, code: String): ToolResult =
        ToolResult(call.id, false, message, VerificationState.FAILED, code, effectNotApplied = true)
}
