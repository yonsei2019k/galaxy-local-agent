package com.user.localagent.scheduling

import com.user.localagent.core.ScheduledReadRequest
import com.user.localagent.core.ScheduledReadScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ScheduledReadRestoreTest {
    private val scope = ScheduledReadScope("qa-room", "qa-branch", null, 0, 0)

    private fun plan(due: Long) =
        ScheduledReadRequest("시각 조회", "현재 시각을 확인해 줘", due, "get_current_datetime", scope)

    @Test fun enqueueFailurePreservesTheOtherReservationAndItsDueTime() = runBlocking {
        val failure = IOException("synthetic enqueue failure")
        val attempts = mutableListOf<Pair<String, Long>>()
        val failures = mutableListOf<Exception>()
        restorePendingScheduledReads(listOf("first" to plan(1000), "second" to plan(5000)), { id, due ->
            attempts += id to due
            if (id == "first") throw failure
        }, { failures += it })
        assertEquals(listOf("first" to 1000L, "second" to 5000L), attempts)
        assertEquals(listOf(failure), failures)
    }

    @Test fun cancellationStopsWithoutStartingTheNextReservation() = runBlocking {
        val cancel = CancellationException("stop restore")
        val attempts = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        try {
            restorePendingScheduledReads(listOf("first" to plan(1000), "second" to plan(5000)), { id, _ ->
                attempts += id
                throw cancel
            }, { failures += it })
            fail("Cancellation should propagate")
        } catch (observed: CancellationException) {
            assertSame(cancel, observed)
        }
        assertEquals(listOf("first"), attempts)
        assertEquals(emptyList<Exception>(), failures)
    }
}
