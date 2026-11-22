package com.user.localagent.automation

import android.content.SharedPreferences
import com.user.localagent.data.KnowledgeSourceInvalidated
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.*
import org.junit.Test

class KnowledgeIngestionRecoveryTest {
    @Test fun transientCheckpointFailureReplaysFromLastCommittedCursor() {
        val store = Checkpoints()
        val processed = linkedSetOf<String>()
        val attempts = mutableListOf<String>()
        fun scan(failAt: String?) {
            for ((index, id) in listOf("one", "two", "three").withIndex()) {
                val at = index + 1L
                if (at <= (store.visible["at"] as? Long ?: 0L)) continue
                attempts += id
                processed += id // Stable claim IDs allow the replayed source to be upserted.
                store.failNext = id == failAt
                store.prefs.saveKnowledgeCheckpoint("at" to at, "id" to id)
            }
        }

        val error = assertThrows(IOException::class.java) { scan("two") }
        assertEquals(setOf("one", "two"), processed)
        assertEquals(mapOf("at" to 1L, "id" to "one"), store.durable)
        assertEquals(store.durable, store.visible)
        val status = ingestionFailureStatus(error, sameGeneration = true)
        assertEquals(IngestionWork.RETRY, ingestionWork(status, true, 0, true))

        scan(null)
        assertEquals(listOf("one", "two", "two", "three"), attempts)
        assertEquals(setOf("one", "two", "three"), processed)
        assertEquals(mapOf("at" to 3L, "id" to "three"), store.durable)
    }

    @Test fun failedFirstCheckpointRestoresMissingKeys() {
        val store = Checkpoints()
        store.failNext = true
        assertThrows(IOException::class.java) { store.prefs.saveKnowledgeCheckpoint("at" to 1L) }
        assertTrue(store.visible.isEmpty())
        assertTrue(store.durable.isEmpty())
    }

    @Test fun permissionOrGenerationChangeStopsEvenWhenThereWasAlsoAnIoFailure() {
        for (error in listOf(SecurityException(), KnowledgeSourceInvalidated("erased source"))) {
            assertEquals(INGESTION_SOURCE_CHANGED, ingestionFailureStatus(error, sameGeneration = true))
            assertEquals(IngestionWork.STOP, ingestionWork(INGESTION_SOURCE_CHANGED, false, 0, true))
        }
        assertEquals(INGESTION_SOURCE_CHANGED, ingestionFailureStatus(IOException(), sameGeneration = false))
        assertEquals(IngestionWork.STOP, ingestionWork(INGESTION_RETRY, true, 0, false))
        assertEquals(IngestionWork.STOP, ingestionWork("incremental_checkpoint_saved", true, 0, false))
    }

    @Test fun transientErrorsBackOffAndFailAfterThreeRetries() {
        val status = ingestionFailureStatus(IOException(), sameGeneration = true)
        for (attempt in 0..2) assertEquals(IngestionWork.RETRY, ingestionWork(status, true, attempt, true))
        assertEquals(IngestionWork.FAIL, ingestionWork(status, true, 3, true))
        assertEquals(IngestionWork.CONTINUE, ingestionWork("incremental_checkpoint_saved", true, 3, true))
        assertEquals(IngestionWork.RETRY, ingestionWork("learning_device_busy", true, 3, true))
        assertEquals(IngestionWork.STOP, ingestionWork("learning_pending_evidence", true, 0, true))
    }

    @Test fun cancellationPropagatesWithoutRetry() {
        val cancellation = CancellationException()
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            ingestionFailureStatus(cancellation, sameGeneration = false)
        })
    }

    /** Models the Android distinction between immediate visible values and a failed disk commit. */
    private class Checkpoints {
        val visible = mutableMapOf<String, Any>()
        var durable = emptyMap<String, Any>()
        var failNext = false
        val prefs = proxy(SharedPreferences::class.java) { name, _ ->
            when (name) {
                "getAll" -> visible.toMap()
                "edit" -> editor()
                else -> error(name)
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy(SharedPreferences.Editor::class.java) { name, args ->
                when (name) {
                    "putLong", "putString", "putBoolean" -> editor.also { pending[args[0] as String] = args[1] }
                    "remove" -> editor.also { pending[args[0] as String] = null }
                    "commit", "apply" -> {
                        pending.forEach { (key, value) -> if (value == null) visible.remove(key) else visible[key] = value }
                        val saved = !failNext
                        failNext = false
                        if (saved) durable = visible.toMap()
                        if (name == "commit") saved else null
                    }
                    else -> error(name)
                }
            }
            return editor
        }

        private fun <T : Any> proxy(type: Class<T>, body: (String, Array<out Any?>) -> Any?): T =
            requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                body(method.name, args ?: emptyArray())
            }))
    }
}
