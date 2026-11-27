package com.user.localagent.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntroTimingTest {
    @Test
    fun ordinaryLauncherIntentOpensChatWithGreeting() {
        assertTrue(shouldShowLaunchIntro(false, true, null))
        assertTrue(shouldShowLaunchIntro(false, true, 0))
        assertFalse(shouldShowLaunchIntro(false, true, 4))
        assertFalse(shouldShowLaunchIntro(true, true, null))
        assertFalse(shouldShowLaunchIntro(false, false, null))
    }

    private fun visible(
        elapsedMs: Long,
        chatReady: Boolean = true,
        animationsOn: Boolean = true,
        dismissed: Boolean = false,
        videoStarted: Boolean = false,
        videoDone: Boolean = false,
    ) = launchIntroVisible(elapsedMs, chatReady, animationsOn, dismissed, videoStarted, videoDone)

    @Test
    fun slowChatCannotExtendMaximum() {
        assertTrue(visible(0, chatReady = false))
        assertFalse(visible(5000, chatReady = false, videoStarted = true))
    }

    @Test
    fun neverStartedVideoKeepsMinimumOnly() {
        assertTrue(visible(200))
        assertTrue(visible(LAUNCH_INTRO_MIN_MS - 1))
        assertFalse(visible(LAUNCH_INTRO_MIN_MS))
        assertFalse(visible(1500))
    }

    @Test
    fun startedVideoPlaysToTheEndUpToMaximum() {
        assertTrue(visible(2000, videoStarted = true))
        assertTrue(visible(LAUNCH_INTRO_MAX_MS - 1, videoStarted = true))
        assertFalse(visible(LAUNCH_INTRO_MAX_MS, videoStarted = true))
        assertFalse(visible(3000, videoStarted = true))
    }

    @Test
    fun completedVideoEndsEarly() {
        assertFalse(visible(2300, videoStarted = true, videoDone = true))
        assertFalse(visible(500, videoStarted = true, videoDone = true))
    }

    @Test
    fun animationsOffDoNotWait() {
        assertFalse(visible(0, animationsOn = false))
        assertFalse(visible(0, animationsOn = false, videoStarted = true))
        assertFalse(visible(0, chatReady = false, animationsOn = false))
    }

    @Test
    fun tapDismissesImmediately() {
        assertFalse(visible(0, chatReady = false, dismissed = true))
        assertFalse(visible(100, videoStarted = true, dismissed = true))
    }

    @Test
    fun minimumIsBelowMaximum() {
        assertTrue(LAUNCH_INTRO_MIN_MS < LAUNCH_INTRO_MAX_MS)
    }
}
