package com.user.localagent.connections

import com.user.localagent.core.ToolCall
import com.user.localagent.core.connections.*
import kotlinx.serialization.json.JsonObject

/** Request-local binding, not a permission grant. The coordinator retains its existing single approval gate. */
internal class CalendarApprovalSession {
    private val reviewed=linkedMapOf<String,CalendarTargetReview>()
    private val approved=linkedMapOf<String,ApprovedCalendarTarget>()
    private fun key(call:ToolCall)=call.id+":"+call.name+":"+JsonObject(call.arguments.toSortedMap())
    fun review(call:ToolCall,snapshot:CalendarTargetReview):String {
        require(call.name==CalendarTargetIntegrity.CREATE&&reviewed.size<18)
        val key=key(call);reviewed[key]=snapshot;approved.remove(key);return snapshot.approvalText()
    }
    fun ownerConfirmed(call:ToolCall,actuallyDisplayedPreview:String?) {
        if(call.name!=CalendarTargetIntegrity.CREATE)return
        val snapshot=checkNotNull(reviewed.remove(key(call))){"calendar_target_review_missing"}
        approved[key(call)]=CalendarTargetIntegrity.ownerConfirmed(snapshot,checkNotNull(actuallyDisplayedPreview))
    }
    fun consume(call:ToolCall):ApprovedCalendarTarget=checkNotNull(approved.remove(key(call))){"calendar_target_approval_required"}
}
