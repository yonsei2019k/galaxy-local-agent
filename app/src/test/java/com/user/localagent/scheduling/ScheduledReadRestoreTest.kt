package com.user.localagent.scheduling

import com.user.localagent.core.ScheduledReadRequest
import com.user.localagent.core.ScheduledReadScope
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ScheduledReadRestoreTest {
    private val scope = ScheduledReadScope("qa-room", "qa-branch", null, 0, 0)

    private fun plan(due: Long) =
        ScheduledReadRequest("시각 조회", "현재 시각을 확인해 줘", due, "get_current_datetime", scope)

    @Test fun completedCancellationPreventsRearmingFromAnOlderSnapshot() = runBlocking {
        val target = plan(5000)
        val sibling = plan(1000)
        val targetId = target.idFor("target-request")
        val siblingId = sibling.idFor("sibling-request")
        val reservations = mapOf(targetId to target, siblingId to sibling)
        val snapshot = reservations.toList()
        val pending = reservations.keys.toMutableSet()
        val jobs = mutableMapOf(targetId to target.atEpochMillis)
        val attempts = mutableListOf<String>()
        val recovery = ScheduledReadRecovery(pending::contains) { id, due ->
            attempts += id
            jobs.putIfAbsent(id, due)
        }

        recovery.cancel {
            pending.remove(targetId)
            jobs.remove(targetId)
        }
        restorePendingScheduledReads(snapshot, recovery::rearm) { throw AssertionError(it) }

        assertEquals(listOf(siblingId), attempts)
        assertEquals(mapOf(siblingId to sibling.atEpochMillis), jobs)
    }

    @Test fun cancellationWaitsForInFlightRearmAndRemovesTheSameUniqueJob() = runBlocking {
        val target = plan(5000)
        val id = target.idFor("target-request")
        val jobs = mutableMapOf<String, Long>()
        val enqueueStarted = CompletableDeferred<Unit>()
        val finishEnqueue = CompletableDeferred<Unit>()
        val recovery = ScheduledReadRecovery({ it == id }) { workId, due ->
            enqueueStarted.complete(Unit)
            finishEnqueue.await()
            jobs[workId] = due
        }

        val restore = launch { recovery.rearm(id, target.atEpochMillis) }
        enqueueStarted.await()
        val cancel = launch(start = CoroutineStart.UNDISPATCHED) {
            recovery.cancel { assertEquals(target.atEpochMillis, jobs.remove(id)) }
        }
        assertFalse(cancel.isCompleted)
        finishEnqueue.complete(Unit)
        restore.join()
        cancel.join()
        assertEquals(emptyMap<String, Long>(), jobs)
    }

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
