package com.user.localagent.tools

import com.user.localagent.core.*

/** Actual injected hosts own scope/cancellation/evidence; forwarding never upgrades partial results. */
internal object PrivateToolDispatch {
    suspend fun execute(call:ToolCall,privateAnalysis:(suspend(ToolCall)->ToolResult)?,
        transcribeRecording:(suspend(ToolCall)->ToolResult)?):ToolResult? {
        val handler=when(call.name){
            in PrivateAnalysisToolCatalog.names->privateAnalysis?:error("현재 요청의 개인 자료 분석 연결이 필요합니다.")
            in RecordingToolCatalog.names->transcribeRecording?:error("현재 요청의 녹음 선택·전사 연결이 필요합니다.")
            else->return null
        }
        ToolRegistry.validate(call)
        return handler(call).also {check(it.id==call.id){"요청한 작업과 실행 결과의 식별자가 다릅니다."}}
    }
}
