package com.user.localagent.callfollowup

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes only one job's publish/cleanup. The durable source fence is set before cleanup waits. */
internal class CallPublicationGuard {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> commit(jobId: String, verifyBefore: suspend () -> Unit, alreadyExists: suspend () -> Boolean,
        write: suspend () -> T, verifyAfter: suspend () -> Unit, eraseInserted: suspend () -> Unit): T =
        locks.getOrPut(jobId) { Mutex() }.withLock {
            verifyBefore()
            val existed = alreadyExists()
            var attempted = false
            try {
                attempted = true // Room may commit just before cancellation resumes its caller.
                val value = write()
                verifyAfter()
                value
            } catch (error: Throwable) {
                if (attempted && !existed) withContext(NonCancellable) { eraseInserted() }
                throw error
            }
        }

    suspend fun cleanup(jobId: String, erase: suspend () -> Unit) = locks.getOrPut(jobId) { Mutex() }.withLock { erase() }
}
