package com.user.localagent.compute

/** A native failure is not a transport disconnect and must not silently change the selected model. */
class PcImageComputationFailure private constructor(message:String):IllegalStateException(message) {
    companion object {
        fun fromReport(phase:String,code:String?):PcImageComputationFailure {
            val message=when {
                phase!="failed"->"PC 이미지 작업이 서버에서 중단되었거나 이미 종료됐습니다. 작업 상태를 확인한 뒤 다시 시도해주세요."
                code=="REFERENCE_MODEL_NOT_SUPPORTED"->"현재 PC 연결의 SDXL은 참고 사진 편집을 지원하지 않습니다. PC의 Qwen 또는 휴대폰 SDXL을 선택해주세요."
                code=="NATIVE_MEMORY_SERVER_LOAD_FAILED"||code=="NATIVE_MEMORY_SERVER_LOAD_TIMEOUT"->"선택한 PC 이미지 모델을 준비하지 못했습니다. PC 자원과 모델 상태를 확인해주세요."
                code=="NATIVE_MEMORY_GENERATION_TIMEOUT"->"선택한 PC 모델의 이미지 계산이 제한 시간을 넘었습니다. 완료 이미지로 저장하지 않았습니다."
                else->"선택한 PC 모델의 이미지 계산에 실패했습니다. 모델을 유지한 채 PC 상태를 확인한 뒤 다시 시도해주세요."
            }
            return PcImageComputationFailure(message)
        }
    }
}
