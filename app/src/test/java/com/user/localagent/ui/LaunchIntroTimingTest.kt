package com.user.localagent.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntroTimingTest {
    @Test
    fun waitsForChatEvenAfterMinimum() {
        assertTrue(launchIntroVisible(0, chatReady = false, animationsOn = true, dismissed = false))
        assertTrue(launchIntroVisible(5000, chatReady = false, animationsOn = true, dismissed = false))
    }

    @Test
    fun keepsMinimumAfterChatIsReady() {
        assertTrue(launchIntroVisible(200, chatReady = true, animationsOn = true, dismissed = false))
        assertTrue(launchIntroVisible(LAUNCH_INTRO_MIN_MS - 1, chatReady = true, animationsOn = true, dismissed = false))
        assertFalse(launchIntroVisible(LAUNCH_INTRO_MIN_MS, chatReady = true, animationsOn = true, dismissed = false))
    }

    @Test
    fun videoCompletionEndsMinimumEarly() {
        assertFalse(launchIntroVisible(500, chatReady = true, animationsOn = true, dismissed = false, videoDone = true))
        assertTrue(launchIntroVisible(500, chatReady = false, animationsOn = true, dismissed = false, videoDone = true))
    }

    @Test
    fun animationsOffDoNotWait() {
        assertFalse(launchIntroVisible(0, chatReady = true, animationsOn = false, dismissed = false))
        assertTrue(launchIntroVisible(0, chatReady = false, animationsOn = false, dismissed = false))
    }

    @Test
    fun tapDismissesImmediately() {
        assertFalse(launchIntroVisible(0, chatReady = false, animationsOn = true, dismissed = true))
        assertFalse(launchIntroVisible(100, chatReady = true, animationsOn = true, dismissed = true))
    }

    @Test
    fun minimumStaysUnderHardCapAfterReady() {
        assertTrue(LAUNCH_INTRO_MIN_MS < LAUNCH_INTRO_MAX_AFTER_READY_MS)
    }
}
