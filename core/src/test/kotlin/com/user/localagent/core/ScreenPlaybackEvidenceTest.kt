package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ScreenPlaybackEvidenceTest {
    @Test fun advancementRequiresAnIncreasingClockAtTheSameDuration() {
        val before = checkNotNull(ScreenPlaybackEvidence.from(listOf("0:02 / 10:05")))
        assertTrue(checkNotNull(ScreenPlaybackEvidence.from(listOf("0:04 / 10:05"))).advancedFrom(before))
        assertFalse(before.advancedFrom(before))
        assertFalse(ScreenPlaybackEvidence(4, 606).advancedFrom(before))
        assertFalse(ScreenPlaybackEvidence(600, 605).advancedFrom(before))
    }

    @Test fun unboundMalformedOrConflictingClocksDoNotCount() {
        assertNull(ScreenPlaybackEvidence.from(listOf("12:34", "2:99 / 10:05", "Play 0:06 / 10:05")))
        assertNull(ScreenPlaybackEvidence.from(listOf("0:05 / 10:05", "0:06 / 10:05")))
        assertNull(ScreenPlaybackEvidence.from(listOf("10:06 / 10:05")))
    }
}
