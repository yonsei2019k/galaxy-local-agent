package com.user.localagent.core.connections

import com.user.localagent.core.ToolCall
import java.security.MessageDigest

/** The host supplies a persisted run ID. A model's reusable call ID cannot identify a goal. */
object ConnectionExecutionScope {
    fun bind(call:ToolCall,hostRunId:String?):ToolCall {
        val spec=ConnectionCatalog.tools[call.name]?:error("연결 도구가 아닙니다.")
        if(hostRunId==null){require(!spec.writes){"변경에는 호스트의 실행 범위가 필요합니다."};return call}
        require(hostRunId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val digest=MessageDigest.getInstance("SHA-256").digest((hostRunId+"\n"+call.id).toByteArray())
        return call.copy(id=digest.joinToString(""){"%02x".format(it)})
    }
}
