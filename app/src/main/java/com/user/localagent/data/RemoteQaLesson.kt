package com.user.localagent.data

/** Fixed engineering lessons from host error codes; no model prose or owner facts. */
enum class RemoteQaLesson(val code:String,val guidance:String) {
    STATUS_TIMEOUT("PC_BRIDGE_TIMEOUT_GET_SESSION_STATUS","세션 상태 조회만 원래 연결에서 재시도하고, 실행 요청은 재전송하지 않습니다."),
    PROVIDER_BUSY("PcAgentProviderBusy","PC가 실행 시작을 거부한 경우에만 제한적으로 대기하고 권한을 다시 확인합니다.");

    companion object {
        fun observed(status:String,errorCode:String?):RemoteQaLesson? {
            if(status !in setOf("failed","interrupted"))return null
            return entries.singleOrNull{it.code==errorCode}
        }
    }
}
