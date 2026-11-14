package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class BriefingLedgerTest {
    private val hash="a".repeat(64)
    @Test fun completedContentIsSkippedButEditedContentIsNew() {
        val old=BriefingLedger.version("sms","42",hash)
        assertFalse(BriefingLedger.shouldRead(old,setOf(old)))
        assertTrue(BriefingLedger.shouldRead(BriefingLedger.version("sms","42","b".repeat(64)),setOf(old)))
        assertTrue(BriefingLedger.shouldRead(BriefingLedger.version("sms","41",hash),setOf(old)))
    }
    @Test fun acknowledgementRequiresReadBackAndSameContent() {
        val key=BriefingLedger.version("sms","42",hash)
        assertEquals(key,BriefingLedger.acknowledge(key,key,true))
        assertThrows(IllegalStateException::class.java){BriefingLedger.acknowledge(key,key,false)}
        assertThrows(IllegalArgumentException::class.java){BriefingLedger.acknowledge(key,"changed",true)}
    }
}
