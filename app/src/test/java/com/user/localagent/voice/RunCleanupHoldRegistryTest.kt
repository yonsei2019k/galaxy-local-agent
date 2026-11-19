package com.user.localagent.voice

import org.junit.Assert.*
import org.junit.Test

class RunCleanupHoldRegistryTest {
    @Test fun terminalParentStaysHeldUntilChildHandoffClosesLease() {
        var now=100L
        val holds=RunCleanupHoldRegistry {now}
        val lease=checkNotNull(holds.acquire("parent",1_000L))
        now=500L
        assertTrue(holds.isHeld("parent"))
        assertFalse(holds.isHeld("child"))
        lease.close()
        assertFalse(holds.isHeld("parent"))
        lease.close()
        assertFalse(holds.isHeld("parent"))
    }

    @Test fun fixedDeadlineExpiresAndCannotBeRenewedWhileHeld() {
        var now=100L
        val holds=RunCleanupHoldRegistry {now}
        val old=checkNotNull(holds.acquire("parent",200L))
        assertNull(holds.acquire("parent",300L))
        now=200L
        assertFalse(holds.isHeld("parent"))
        val next=checkNotNull(holds.acquire("parent",300L))
        old.close()
        assertTrue(holds.isHeld("parent"))
        next.close()
        assertFalse(holds.isHeld("parent"))
    }

    @Test fun rejectsExpiredUnboundedAndInvalidLeases() {
        val now=100L
        val holds=RunCleanupHoldRegistry {now}
        assertNull(holds.acquire("parent",now))
        assertNull(holds.acquire("parent",now-1))
        assertNull(holds.acquire("parent",now+RunCleanupHoldRegistry.MAX_HOLD_MILLIS+1))
        assertNull(holds.acquire("",now+1))
        assertNull(holds.acquire("x".repeat(129),now+1))
        assertNotNull(holds.acquire("parent",now+RunCleanupHoldRegistry.MAX_HOLD_MILLIS))
    }

    @Test fun capacityNeverEvictsLiveHoldsAndExpiredSlotsAreReusable() {
        var now=0L
        val holds=RunCleanupHoldRegistry {now}
        repeat(64){assertNotNull(holds.acquire("run-$it",100L))}
        assertNull(holds.acquire("extra",100L))
        assertTrue(holds.isHeld("run-0"))
        now=100L
        assertNotNull(holds.acquire("extra",200L))
        assertFalse(holds.isHeld("run-0"))
    }

    @Test fun serviceDestructionAndRunRemovalInvalidateHolds() {
        val holds=RunCleanupHoldRegistry {0L}
        val old=checkNotNull(holds.acquire("parent",100L))
        holds.clear("parent")
        assertFalse(holds.isHeld("parent"))
        val next=checkNotNull(holds.acquire("parent",100L))
        old.close()
        assertTrue(holds.isHeld("parent"))
        holds.clear()
        assertFalse(holds.isHeld("parent"))
        next.close()
    }
}
