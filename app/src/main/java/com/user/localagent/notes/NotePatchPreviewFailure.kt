package com.user.localagent.notes

import com.user.localagent.core.*

/** Host-only proof that the pure preview rejected before approval or mutation. */
class NotePatchPreviewRejected internal constructor(cause:NotePatchRejected):IllegalStateException(cause.code,cause)

/** Shared by the coordinator and isolated native controls; never infers no-effect from error text. */
object NotePatchFailureEvidence {
    fun result(call:ToolCall,error:Exception)=ToolResult(call.id,false,error.message?:"실행 전 확인 실패",
        VerificationState.FAILED,"실행 또는 대상 확인 실패",
        effectNotApplied=call.name=="patch_note"&&error is NotePatchPreviewRejected)
}
