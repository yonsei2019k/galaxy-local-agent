package com.user.localagent.connections

import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.connections.ConnectionExecutionScope
import com.user.localagent.core.connections.GmailSendPreview

/** Captured request authority. Selecting another chat cannot retarget connection execution or approval. */
internal class RequestConnectionExecutor(
    private val runId:String?,private val isRunLive:()->Boolean,
    private val approve:suspend(String,ToolCall,GmailSendPreview)->Boolean,
    private val execute:suspend(ToolCall,String,suspend(GmailSendPreview)->Boolean)->ToolResult,
    private val verifyAccess:suspend()->Unit={},
    private val captureCalendar:(suspend(ToolCall,String)->com.user.localagent.core.connections.CalendarTargetReview)?=null,
    private val executeCalendar:(suspend(ToolCall,String,com.user.localagent.core.connections.ApprovedCalendarTarget)->ToolResult)?=null,
) {
    private val calendarApprovals=CalendarApprovalSession()
    suspend fun reviewForAction(call:ToolCall):String? {
        if(call.name!=com.user.localagent.core.connections.CalendarTargetIntegrity.CREATE)return null
        val owner=checkNotNull(runId);verifyAccess();check(isRunLive())
        return calendarApprovals.review(call,checkNotNull(captureCalendar).invoke(call,owner))
    }
    fun confirmCalendarOwner(call:ToolCall,actuallyDisplayedPreview:String?){calendarApprovals.ownerConfirmed(call,actuallyDisplayedPreview)}
    suspend fun execute(call:ToolCall):ToolResult {
        val owner=checkNotNull(runId){"연결 도구를 실행할 원래 요청이 없습니다."}
        verifyAccess()
        check(isRunLive()){"연결 도구를 실행할 원래 요청이 끝났습니다."}
        if(call.name==com.user.localagent.core.connections.CalendarTargetIntegrity.CREATE)
            return checkNotNull(executeCalendar).invoke(call,owner,calendarApprovals.consume(call))
        return execute(call,owner){preview->
            verifyAccess()
            isRunLive()&&preview.toolName==call.name&&preview.callId==ConnectionExecutionScope.bind(call,owner).id&&approve(owner,call,preview)
        }
    }
}
