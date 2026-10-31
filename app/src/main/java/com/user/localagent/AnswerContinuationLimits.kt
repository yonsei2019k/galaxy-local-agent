package com.user.localagent

/** Decoder windows are finite; prose continues in the same durable answer.
 * Whole-request safeguards bound runaway output. Small limits are used only by
 * explicit pause/resume regression fixtures.
 */
internal data class AnswerContinuationLimits(
    val maxParts:Int=32,
    val maxElapsedMillis:Long=2*60*60*1000L,
    val maxSavedChars:Int=1024*1024,
) {
    init {
        require(maxParts in 1..32)
        require(maxElapsedMillis in 1..2*60*60*1000L)
        require(maxSavedChars in 1..1024*1024)
    }
}
