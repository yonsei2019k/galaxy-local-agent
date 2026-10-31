package com.user.localagent.core

/** A full SDXL checkpoint; not a low-step LCM checkpoint or an external service. */
object PhoneImageQuality {
    const val MODEL_ID="SDXL-base-1.0-Q4_K"
    const val REPOSITORY="kostakoff/stable-diffusion-xl-base-1.0-GGUF"
    const val REVISION="ca58fb3aced770f3dffddabf01725b29207305ef"
    const val SHA256="51ab2be4ec58905b4cbfe5c0d2f3d0cdc5b0192e8e85dbe9a00481858c8389b0"
    const val BYTES=2_797_752_960L
    const val FILENAME="sdxl-base-q4k-51ab2be4ec58.gguf"
    const val SIZE=1024
    const val STEPS=40
    const val THREADS=2
    const val TIMEOUT_MILLIS=43_200_000L
    // CPU characterization: ~1.8GB private / 3.17GB peak working set, with mapped weights.
    // Preserve a reserve and disable model prefetch; do not require all mapped weights resident.
    const val START_AVAILABLE_BYTES=4L*1024*1024*1024
    const val RESERVE_BYTES=1536L*1024*1024
    fun throttleMillis(callActive:Boolean,severeThermal:Boolean,lowMemory:Boolean,availableBytes:Long,otherAppActive:Boolean=false):Int=when{
        severeThermal->2000
        callActive||lowMemory||availableBytes<RESERVE_BYTES->1000
        otherAppActive->250
        else->0
    }
    fun throttleReason(callActive:Boolean,severeThermal:Boolean,lowMemory:Boolean,availableBytes:Long,otherAppActive:Boolean=false):String?=when{
        callActive->"전화·음성 통신을 위해 속도를 낮춰 계산을 계속합니다."
        severeThermal->"발열을 줄이도록 속도를 낮춰 계산을 계속합니다."
        lowMemory||availableBytes<RESERVE_BYTES->"다른 앱의 메모리 여유를 위해 속도를 낮춰 계산을 계속합니다."
        otherAppActive->"다른 앱을 위해 속도를 낮춰 계산을 계속합니다."
        else->null
    }
}
