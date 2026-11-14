package com.user.localagent.inference

/** Host-generated request ID and numerical runtime metadata only; no tokens, source, prompt or reasoning text. */
enum class NativeGenerationPhase(val code:Int) { PREPARE(1), SAMPLER_INIT(2), PREFILL(3), DECODE(4), FINISHED(5) }

data class NativeGenerationProgress(
    val requestId:String,val phase:NativeGenerationPhase,val processedPromptTokens:Int,val totalPromptTokens:Int,
    val sampledTokens:Int,val rawUtf8Bytes:Long,val visibleUtf8Bytes:Long,val elapsedMillis:Long,
)

/** Labels measured counters only; sampled tokens need not be visible answer text. */
fun NativeGenerationProgress.publicDescription():String=when(phase){
    NativeGenerationPhase.PREPARE->"모델 입력 준비"
    NativeGenerationPhase.SAMPLER_INIT->"출력 형식 준비"
    NativeGenerationPhase.PREFILL->"입력 계산 $processedPromptTokens/$totalPromptTokens 토큰"
    NativeGenerationPhase.DECODE->"출력 계산 $sampledTokens 토큰 · 화면 전달 $visibleUtf8Bytes 바이트"
    NativeGenerationPhase.FINISHED->"모델 계산 종료"
}

/** Rejects stale/invalid/decreasing diagnostic snapshots without changing inference control or options. */
internal class NativeProgressTracker(private val requestId:String) {
    init {require(runCatching {java.util.UUID.fromString(requestId)}.isSuccess)}
    var latest:NativeGenerationProgress?=null
        private set
    @Synchronized fun accept(stage:Int,processed:Int,total:Int,sampled:Int,rawBytes:Long,visibleBytes:Long,elapsed:Long):NativeGenerationProgress? {
        val phase=NativeGenerationPhase.entries.firstOrNull {it.code==stage}?:return null
        if(total !in 0..4096||processed !in 0..total||sampled !in 0..2056||rawBytes !in 0..1_048_576L||
            visibleBytes !in 0..1_048_576L||elapsed<0)return null
        val old=latest
        if(old==null&&(phase!=NativeGenerationPhase.PREPARE||processed!=0||sampled!=0||rawBytes!=0L||visibleBytes!=0L))return null
        if(old!=null&&(old.phase==NativeGenerationPhase.FINISHED||phase.code<old.phase.code||
            processed<old.processedPromptTokens||sampled<old.sampledTokens||rawBytes<old.rawUtf8Bytes||
            visibleBytes<old.visibleUtf8Bytes||elapsed<old.elapsedMillis||
            old.totalPromptTokens!=0&&total!=old.totalPromptTokens))return null
        if(phase.code<NativeGenerationPhase.DECODE.code&&(sampled!=0||rawBytes!=0L||visibleBytes!=0L))return null
        if(phase.code>=NativeGenerationPhase.DECODE.code&&(total==0||processed!=total))return null
        return NativeGenerationProgress(requestId,phase,processed,total,sampled,rawBytes,visibleBytes,elapsed).also {latest=it}
    }
}
