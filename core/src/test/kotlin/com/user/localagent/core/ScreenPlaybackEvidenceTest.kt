package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ScreenPlaybackEvidenceTest {
    @Test fun adaptiveObservationEstimatesAnEndAndWaitsForTerminalEvidence() {
        val proof=ScreenPlaybackProgress()
        fun observe(elapsed:Int,now:Long,enabled:Boolean=false)=
            proof.observe(ScreenPlaybackEvidence(elapsed,600),now,enabled,!enabled,adaptive=true)
        assertFalse(observe(570,0))
        assertNull(proof.remainingSeconds())
        assertFalse(observe(572,2_000))
        assertEquals(28,proof.remainingSeconds())
        assertFalse(observe(592,22_000,enabled=true))
        assertEquals(8,proof.remainingSeconds())
        assertFalse(observe(592,24_000,enabled=true))
        assertNull(proof.remainingSeconds())
        assertFalse(observe(598,30_000,enabled=true))
        assertFalse(observe(600,32_000))
        assertTrue(proof.observe(null,50_000,nextEnabled=true,nextDisabled=false,adaptive=true))
    }

    @Test fun findsOneRelevantControlWithoutGuessingOrLeavingThePlayer() {
        val offscreen=ScreenLessonControl("다음", "e1")
        val arrow=ScreenLessonControl("→","e2",true)
        val play=ScreenLessonControl("▶","e3",true)
        val next=ScreenLessonControl("다음 차시",null)
        assertEquals(next,ScreenLessonControl.choose(listOf(offscreen,arrow,play,next)))
        assertEquals(arrow,ScreenLessonControl.choose(listOf(offscreen,arrow,play)))
        assertNull(ScreenLessonControl.choose(listOf(arrow,arrow.copy(ref="e4"))))
        assertNull(ScreenLessonControl.choose(listOf(offscreen,play)))
        assertEquals(offscreen,ScreenLessonControl.choose(listOf(offscreen,play),"다음"))
    }
    @Test fun advancementRequiresAnIncreasingClockAtTheSameDuration() {
        val before = checkNotNull(ScreenPlaybackEvidence.from(listOf("0:02 / 10:05")))
        assertTrue(checkNotNull(ScreenPlaybackEvidence.from(listOf("0:04 / 10:05"))).advancedFrom(before, 2000))
        assertFalse(before.advancedFrom(before, 2000))
        assertFalse(ScreenPlaybackEvidence(4, 606).advancedFrom(before, 2000))
        assertFalse(ScreenPlaybackEvidence(600, 605).advancedFrom(before, 2000))
    }

    @Test fun unboundMalformedOrConflictingClocksDoNotCount() {
        assertNull(ScreenPlaybackEvidence.from(listOf("12:34", "2:99 / 10:05", "Play 0:06 / 10:05")))
        assertNull(ScreenPlaybackEvidence.from(listOf("0:05 / 10:05", "0:06 / 10:05")))
        assertNull(ScreenPlaybackEvidence.from(listOf("10:06 / 10:05")))
    }

    @Test fun supportsBrowserTimeLabelsWithoutCombiningConflictingClocks() {
        val split = listOf("경과 시간: 0:06", "남은 시간: −9:54")
        assertEquals(ScreenPlaybackEvidence(6, 600), ScreenPlaybackEvidence.from(split))
        assertEquals(ScreenPlaybackEvidence(6, 600), ScreenPlaybackEvidence.from(listOf("elapsed time: 0:06", "remaining time: 9:54")))
        assertNull(ScreenPlaybackEvidence.from(split + "0:07 / 10:00"))
        assertNull(ScreenPlaybackEvidence.from(split + "경과 시간: 0:07"))
    }

    @Test fun nextAppearingMidPlaybackDoesNotMatchAnExplicitlyDisabledCompletionGate() {
        val proof = ScreenPlaybackProgress()
        assertFalse(proof.observe(ScreenPlaybackEvidence(1,100), 1_000, nextEnabled=false, nextDisabled=false))
        assertFalse(proof.observe(ScreenPlaybackEvidence(3,100), 3_000, nextEnabled=true, nextDisabled=false))
        assertFalse(proof.observe(ScreenPlaybackEvidence(99,100), 99_000, nextEnabled=true, nextDisabled=false))
        assertTrue(proof.observe(ScreenPlaybackEvidence(100,100), 100_000, nextEnabled=true, nextDisabled=false))
    }

    @Test fun actualDisabledGateRequiresRecentProgressNearTheEnd() {
        val proof = ScreenPlaybackProgress()
        assertFalse(proof.observe(ScreenPlaybackEvidence(89,100), 89_000, nextEnabled=false, nextDisabled=true))
        assertFalse(proof.observe(ScreenPlaybackEvidence(91,100), 91_000, nextEnabled=true, nextDisabled=false))
        assertFalse(proof.observe(ScreenPlaybackEvidence(93,100), 93_000, nextEnabled=false, nextDisabled=true))
        assertFalse(proof.observe(ScreenPlaybackEvidence(95,100), 95_000, nextEnabled=false, nextDisabled=true))
        assertTrue(proof.observe(null, 98_000, nextEnabled=true, nextDisabled=false))
        assertFalse(proof.observe(null, 104_000, nextEnabled=true, nextDisabled=false))
    }

    @Test fun terminalClockJumpAndUnrelatedReplacementDoNotProveCompletion() {
        val proof = ScreenPlaybackProgress()
        assertFalse(proof.observe(ScreenPlaybackEvidence(1,100), 1_000, nextEnabled=false, nextDisabled=true))
        assertFalse(proof.observe(ScreenPlaybackEvidence(100,100), 2_000, nextEnabled=true, nextDisabled=false))
        assertFalse(proof.observe(ScreenPlaybackEvidence(100,100), 20_000, nextEnabled=true, nextDisabled=false))
        assertFalse(proof.observe(ScreenPlaybackEvidence(0,101), 21_000, nextEnabled=true, nextDisabled=false))
    }

    @Test fun jumpToEndDiscardsEvenRecentNearEndProgressAndDisabledGate() {
        val proof = ScreenPlaybackProgress()
        assertFalse(proof.observe(ScreenPlaybackEvidence(93,100), 93_000, nextEnabled=false, nextDisabled=true))
        assertFalse(proof.observe(ScreenPlaybackEvidence(95,100), 95_000, nextEnabled=false, nextDisabled=true))
        assertFalse(proof.observe(ScreenPlaybackEvidence(100,100), 95_250, nextEnabled=true, nextDisabled=false))
    }

    @Test fun chromeControlsWithoutAResourceIdRemainOutsideWebContent() {
        assertTrue(ScreenControlToolCatalog.isBrowserUi("com.android.chrome", false))
        assertFalse(ScreenControlToolCatalog.matchesTapArea(ScreenControlToolCatalog.isBrowserUi("com.android.chrome", false), null))
        assertFalse(ScreenControlToolCatalog.isBrowserUi("com.android.chrome", true))
        assertFalse(ScreenControlToolCatalog.isBrowserUi("com.example.app", false))
    }
}
