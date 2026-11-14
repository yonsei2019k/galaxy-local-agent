package com.user.localagent.core.connections
import com.user.localagent.core.ToolCall
// Synthetic explicit owner-consent seam, not evidence of the actual PendingAction UI.
internal suspend fun calendarTestExecute(runtime:ConnectionRuntime,call:ToolCall,approvedPreview:CalendarTargetReview?)=
    if(approvedPreview==null)runtime.execute(call)else runtime.executeApprovedCalendar(call,CalendarTargetIntegrity.ownerConfirmed(approvedPreview,approvedPreview.approvalText()))
