package com.user.localagent.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentNotificationKeysTest {
    @Test
    fun reachingCapacityKeepsRecentEntriesAndDoesNotCountUpdatesTwice() {
        val keys=RecentNotificationKeys(capacity=2)
        assertTrue(keys.record("older"))
        assertTrue(keys.record("recent"))
        assertTrue(keys.record("latest"))

        assertFalse(keys.record("recent"))
        assertFalse(keys.record("latest"))
        assertTrue(keys.record("older"))
    }

    @Test
    fun removingANotificationAllowsANewPostingWithTheSameKey() {
        val keys=RecentNotificationKeys(capacity=2)
        assertTrue(keys.record("same-key"))
        assertFalse(keys.record("same-key"))
        keys.remove("same-key")
        assertTrue(keys.record("same-key"))
    }
}
