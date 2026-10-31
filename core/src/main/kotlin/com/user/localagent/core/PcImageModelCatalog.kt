package com.user.localagent.core

/** Public, immutable model integrity pins. Server labels alone cannot select executable weights. */
object PcImageModelCatalog {
    const val QUALITY_ID="Qwen-Image-2512-20B-Q4_K_M"
    const val QUALITY_SHA="134aa66a4cb27b46e7528486a37d87736a03e48cea6ba88e5de2068543ec6bcc"
    const val SDXL_ID="SDXL-1.0-Q4_K"
    const val SDXL_SHA="51ab2be4ec58905b4cbfe5c0d2f3d0cdc5b0192e8e85dbe9a00481858c8389b0"
    private val pins=mapOf(QUALITY_ID to QUALITY_SHA,
        SDXL_ID to SDXL_SHA)
    data class Pin internal constructor(val id:String,val sha256:String)
    fun selection(id:String):Pin=verified(id,pins[id])
    fun verified(id:String?,sha256:String?):Pin {
        require(id!=null&&sha256!=null&&pins[id]==sha256){"지원하지 않거나 해시가 다른 PC 이미지 모델입니다."}
        return Pin(id,sha256)
    }
    fun verifyResult(expected:Pin,id:String?,sha256:String?):Pin = verified(id,sha256).also {
        require(it==expected){"PC 작업 준비 후 결과의 모델이 변경되었습니다."}
    }
}
