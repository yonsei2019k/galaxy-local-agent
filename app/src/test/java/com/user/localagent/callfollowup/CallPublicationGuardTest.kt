package com.user.localagent.callfollowup

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CallPublicationGuardTest {
    @Test fun forgetFenceDuringCommitRollsBackLateMessageBeforeCleanupReturns() = runBlocking {
        supervisorScope {
            val guard = CallPublicationGuard(); var live = true; var body: String? = null
            val inserted = CompletableDeferred<Unit>(); val releaseCommit = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val writer = async {
                guard.commit("job", { check(live) }, { body != null }, {
                    body = "synthetic report"; inserted.complete(Unit); releaseCommit.await()
                }, { check(live) }, { if (body == "synthetic report") body = null })
            }
            inserted.await()
            val eraser = async {
                live = false // Durable source fence comes before waiting for the publication lock.
                cleanupStarted.complete(Unit)
                guard.cleanup("job") { if (body == "synthetic report") body = null }
            }
            cleanupStarted.await(); yield()
            assertFalse(eraser.isCompleted)
            releaseCommit.complete(Unit)
            expectRejected { writer.await() }
            eraser.await()
            assertNull(body)
        }
    }

    @Test fun rollbackNeverErasesAnOwnerEditedBody() = runBlocking {
        val guard = CallPublicationGuard(); var body: String? = null
        expectRejected {
            guard.commit("job", {}, { false }, { body = "synthetic original" }, {
                body = "synthetic owner edit"; error("scope changed")
            }, { if (body == "synthetic original") body = null })
        }
        assertEquals("synthetic owner edit", body)
    }

    @Test fun cancellationAfterDatabaseCommitStillCleansNewPublicationAndUnlocks() = runBlocking {
        val guard = CallPublicationGuard(); var body: String? = null
        val inserted = CompletableDeferred<Unit>()
        val writer = launch {
            guard.commit("job", {}, { false }, {
                body = "synthetic report"; inserted.complete(Unit); awaitCancellation()
            }, {}, { body = null })
        }
        inserted.await(); writer.cancelAndJoin()
        assertNull(body)
        guard.commit("job", {}, { false }, { body = "second synthetic report" }, {}, { body = null })
        assertEquals("second synthetic report", body)
    }

    @Test fun rejectedIdempotentRetryPreservesPriorPublishedHistory() = runBlocking {
        val guard = CallPublicationGuard(); var body: String? = "prior synthetic report"; var erased = false
        expectRejected {
            guard.commit("job", {}, { true }, { assertEquals("prior synthetic report", body) },
                { error("authorization changed") }, { erased = true; body = null })
        }
        assertFalse(erased)
        assertEquals("prior synthetic report", body)
    }

    @Test fun differentJobsDoNotHoldAProcessWideSuspendingLock() = runBlocking {
        val guard = CallPublicationGuard(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async { guard.commit("first", {}, { false }, { started.complete(Unit); release.await() }, {}, {}) }
        started.await()
        val second = withTimeout(1000) { guard.commit("second", {}, { false }, { "independent" }, {}, {}) }
        assertEquals("independent", second)
        assertFalse(first.isCompleted)
        release.complete(Unit); first.await()
    }

    private suspend fun expectRejected(action: suspend () -> Unit) {
        try { action(); fail("A cancelled scope publication must not succeed") }
        catch (_: IllegalStateException) { }
    }
}
