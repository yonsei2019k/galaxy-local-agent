package com.user.localagent.core

/** Elapsed time and duration on a single independently identified media element. */
data class ScreenPlaybackEvidence(val elapsed: Int, val duration: Int) {
    fun advancedFrom(before: ScreenPlaybackEvidence): Boolean =
        duration == before.duration && elapsed - before.elapsed in 1..8

    companion object {
        private val clock = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
        private val pair = Regex("""(\d{1,2}:\d{2}(?::\d{2})?)\s*/\s*(\d{1,2}:\d{2}(?::\d{2})?)""")

        fun from(labels: List<String>): ScreenPlaybackEvidence? {
            fun seconds(label: String): Int? {
                if (!clock.matches(label)) return null
                val parts = label.split(':').map(String::toInt)
                if (parts.drop(1).any { it >= 60 }) return null
                return parts.fold(0) { result, value -> result * 60 + value }
            }
            return labels.mapNotNull { label ->
                val match = pair.matchEntire(label.trim()) ?: return@mapNotNull null
                val elapsed = seconds(match.groupValues[1]) ?: return@mapNotNull null
                val duration = seconds(match.groupValues[2]) ?: return@mapNotNull null
                ScreenPlaybackEvidence(elapsed, duration).takeIf { duration > 0 && elapsed <= duration }
            }.distinct().singleOrNull()
        }
    }
}
