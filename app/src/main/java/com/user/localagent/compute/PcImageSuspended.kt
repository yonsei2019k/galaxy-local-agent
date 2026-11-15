package com.user.localagent.compute

/** Explicit maintenance/user pause retains recovery and must not trigger automatic retry. */
class PcImageSuspended(val savedStep:Int):IllegalStateException(
    if(savedStep==0)"PC 작업을 일시정지했습니다 · 입력과 설정을 보관했습니다"
    else "PC 작업을 일시정지했습니다 · ${savedStep}단계 계산을 저장했습니다"
) {
    init{require(savedStep in 0..50)}
    companion object {
        fun fromReport(phase:String,paused:Boolean?,checkpointStep:Int,totalSteps:Int):PcImageSuspended? {
            if(paused!=true||phase !in setOf("resumable","suspended"))return null
            require(totalSteps in 1..50&&checkpointStep in 0..totalSteps)
            return PcImageSuspended(checkpointStep)
        }
    }
}
