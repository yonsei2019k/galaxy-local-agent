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
) {
    suspend fun execute(call:ToolCall):ToolResult {
        val owner=checkNotNull(runId){"연결 도구를 실행할 원래 요청이 없습니다."}
        verifyAccess()
        check(isRunLive()){"연결 도구를 실행할 원래 요청이 끝났습니다."}
        return execute(call,owner){preview->
            verifyAccess()
            isRunLive()&&preview.toolName==call.name&&preview.callId==ConnectionExecutionScope.bind(call,owner).id&&approve(owner,call,preview)
        }
    }
}
