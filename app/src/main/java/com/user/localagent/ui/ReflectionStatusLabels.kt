package com.user.localagent.ui

internal fun reflectionReadinessMessage(enabled:Boolean,automationEnabled:Boolean,patternsEnabled:Boolean):String=when{
    !enabled->"개선 제안이 꺼져 있습니다."
    !patternsEnabled->"실행하려면 ‘비서 작업 결과 분석’을 먼저 켜주세요. 다른 앱 분석은 필요하지 않습니다."
    !automationEnabled->"개선 제안이 예약되어 있지만 분석 모드가 중지돼 있습니다. ‘상시 모드 시작’을 눌러주세요."
    else->"필수 설정이 켜져 있습니다. 충전·기기 상태·집계 자료가 준비되면 시스템 일정에 따라 실행합니다."
}
internal fun reflectionStatusMessage(status:String):String=when(status){
    "disabled"->"꺼짐"
    "scheduled"->"예약됨 · 아직 실행 성공을 뜻하지 않습니다."
    "assistant_pattern_opt_in_required"->"비서 작업 결과 분석과 분석 모드를 켜야 합니다."
    "deferred_device_state"->"충전·잠금·배터리·발열 조건을 기다립니다."
    "deferred_runtime_busy","deferred_runtime_or_opt_in"->"다른 추론 작업이나 설정 변경 때문에 연기했습니다."
    "verified_local_model_required"->"검증된 로컬 모델이 필요합니다."
    "not_enough_aggregate_data"->"분석할 작업 집계가 아직 부족합니다."
    "aggregate_unchanged"->"새로운 집계 변화가 없어 제안을 반복하지 않았습니다."
    "proposal_saved_for_approval"->"제안을 저장했습니다. 기억 화면에서 승인할 수 있습니다."
    "inference_time_limit"->"추론 시간 한도에 도달해 제안을 저장하지 않았습니다."
    "invalid_proposal_output"->"제안 형식을 검증하지 못해 저장하지 않았습니다."
    "reflection_failed"->"제안 작업을 완료하지 못했습니다. 다음 예약에서 다시 확인합니다."
    else->"실행 완료를 확인하지 못했습니다. 다음 예약에서 다시 확인합니다."
}
