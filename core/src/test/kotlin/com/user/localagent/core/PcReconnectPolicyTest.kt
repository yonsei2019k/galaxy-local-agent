package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class PcReconnectPolicyTest {
    @Test fun recentlyReachablePcIsRetriedForAboutTwentySeconds(){
        val waits=PcReconnectPolicy.retryDelays(true,30_000L)
        assertEquals(3,waits.size);assertEquals(20_000L,waits.sum())
    }
    @Test fun pcOffForLongOrStatusChecksFailImmediately(){
        assertTrue(PcReconnectPolicy.retryDelays(true,PcReconnectPolicy.RECENT_MILLIS).isEmpty())
        assertTrue(PcReconnectPolicy.retryDelays(true,System.currentTimeMillis()).isEmpty())
        assertTrue(PcReconnectPolicy.retryDelays(false,1_000L).isEmpty())
        assertTrue(PcReconnectPolicy.retryDelays(true,-5L).isEmpty())
    }
}
