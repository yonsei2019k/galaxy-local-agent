package com.user.localagent.core

/** Legacy queued is ambiguous: only explicit worker evidence establishes another operation. */
object PcImageProgressPolicy {
    /**
     * Progress poll interval: 1 s right after a change, growing to 5 s while nothing changes.
     * A fixed 1 s poll was ~95% of all worker traffic; over Tailscale it also costs battery/data.
     */
    fun pollDelayMillis(unchangedPolls:Int):Long = (1000L+unchangedPolls.coerceAtLeast(0)*500L).coerceAtMost(5000L)
    fun label(phase:String,stage:String?):String {
        if(phase=="queued")return when(stage){
            "admitting"->"PC 이미지 요청 접수 · 실행 준비 중"
            "waiting_compute"->"다른 PC 연산이 끝나기를 기다리는 중"
            "releasing_model"->"이전 PC 모델 메모리 정리 중"
            "verifying_models"->"PC 이미지 모델 파일 검사 중"
            "preparing_runtime"->"PC 이미지 실행 환경 준비 중"
            else->"PC 실행 상태 확인 중 · 준비 또는 대기"
        }
        return when(phase){
            "waiting_resources"->when(stage){
                "waiting_memory"->"PC 메모리 여유 대기"
                "waiting_gpu"->"PC GPU 여유 대기"
                else->"PC 메모리 또는 GPU 여유 대기"
            }
            "loading"->if(stage=="preparing_inputs")"PC 입력 처리 · 첫 생성 단계 준비 중"else"PC 이미지 모델 로딩 중"
            "sampling"->"PC에서 장면을 정교하게 만드는 중"
            "decoding"->"PC에서 이미지 디코딩 중"
            "completed"->"완성 이미지를 스마트폰으로 받는 중"
            else->error("알 수 없는 PC 작업 상태")
        }
    }
}
