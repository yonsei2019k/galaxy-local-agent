package com.user.localagent.core

/** Elapsed time and duration on a single independently identified media element. */
data class ScreenPlaybackEvidence(val elapsed: Int, val duration: Int) {
    fun advancedFrom(before: ScreenPlaybackEvidence, intervalMillis: Long): Boolean =
        duration == before.duration && intervalMillis in 1..12_000 &&
            (elapsed - before.elapsed).toLong() in 1..(intervalMillis / 1_000 + 2)

    companion object {
        private const val CLOCK = """[0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?"""
        private val clock = Regex(CLOCK)
        private val pair = Regex("""($CLOCK)\s*[/／]\s*($CLOCK)""")
        private val elapsedLabel = Regex("""(?:elapsed time|경과 시간)\s*:\s*($CLOCK)""", RegexOption.IGNORE_CASE)
        private val remainingLabel = Regex("""(?:remaining time|남은 시간)\s*:\s*[-−]?($CLOCK)""", RegexOption.IGNORE_CASE)

        fun from(labels: List<String>): ScreenPlaybackEvidence? {
            fun seconds(label: String): Int? {
                if (!clock.matches(label)) return null
                val parts = label.split(':').map(String::toInt)
                if (parts.drop(1).any { it >= 60 }) return null
                return parts.fold(0) { result, value -> result * 60 + value }
            }
            val parsed = labels.mapNotNull { label ->
                val match = pair.matchEntire(label.trim()) ?: return@mapNotNull null
                val elapsed = seconds(match.groupValues[1]) ?: return@mapNotNull null
                val duration = seconds(match.groupValues[2]) ?: return@mapNotNull null
                ScreenPlaybackEvidence(elapsed, duration).takeIf { duration > 0 && elapsed <= duration }
            }.distinct()
            val elapsed = labels.mapNotNull { elapsedLabel.matchEntire(it.trim())?.groupValues?.get(1)?.let(::seconds) }.distinct()
            val remaining = labels.mapNotNull { remainingLabel.matchEntire(it.trim())?.groupValues?.get(1)?.let(::seconds) }.distinct()
            if (elapsed.size > 1 || remaining.size > 1) return null
            val split = if (elapsed.size == 1 && remaining.size == 1) {
                ScreenPlaybackEvidence(elapsed.single(), elapsed.single() + remaining.single()).takeIf { it.duration > 0 }
            } else null
            return (parsed + listOfNotNull(split)).distinct().singleOrNull()
        }
    }
}

/** Evidence for one bound player and lesson. A missing/ambiguous button is not a disabled button. */
class ScreenPlaybackProgress {
    private data class Sample(val clock: ScreenPlaybackEvidence, val atMillis: Long)

    private var last: Sample? = null
    private var lastAdvance: Sample? = null
    private var sawDisabled = false

    fun observe(clock: ScreenPlaybackEvidence?, nowMillis: Long, nextEnabled: Boolean, nextDisabled: Boolean): Boolean {
        if (clock != null) {
            val previous = last
            val advanced = previous != null && clock.advancedFrom(previous.clock, nowMillis - previous.atMillis)
            if (previous != null && (clock.duration != previous.clock.duration ||
                    clock.elapsed < previous.clock.elapsed || (clock.elapsed > previous.clock.elapsed && !advanced))) {
                lastAdvance = null
                sawDisabled = false
            }
            if (advanced) lastAdvance = Sample(clock, nowMillis)
            last = Sample(clock, nowMillis)
        }
        if (nextDisabled && !nextEnabled) sawDisabled = true
        val advanced = lastAdvance ?: return false
        if (!nextEnabled || nowMillis - advanced.atMillis !in 0..8_000) return false
        if (clock != null && clock.duration == advanced.clock.duration &&
            clock.elapsed == clock.duration && clock.elapsed == advanced.clock.elapsed) return true
        return sawDisabled && advanced.clock.duration - advanced.clock.elapsed in 0..5
    }
}
