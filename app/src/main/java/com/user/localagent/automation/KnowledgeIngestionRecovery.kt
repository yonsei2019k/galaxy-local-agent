package com.user.localagent.automation

import android.content.SharedPreferences
import com.user.localagent.data.KnowledgeSourceInvalidated
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal const val INGESTION_SOURCE_CHANGED = "source_or_generation_changed"
internal const val INGESTION_RETRY = "ingestion_retry_pending"
internal const val INGESTION_MAX_RETRIES = 3
internal const val INGESTION_BACKOFF_SECONDS = 30L

internal fun ingestionFailureStatus(error: Exception, sameGeneration: Boolean): String = when {
    error is CancellationException -> throw error
    !sameGeneration || error is KnowledgeSourceInvalidated || error is SecurityException -> INGESTION_SOURCE_CHANGED
    else -> INGESTION_RETRY
}

internal enum class IngestionWork { STOP, CONTINUE, RETRY, FAIL }

internal fun ingestionWork(status: String, hasMore: Boolean, attempt: Int, sameGeneration: Boolean): IngestionWork = when {
    !sameGeneration -> IngestionWork.STOP
    status == INGESTION_RETRY -> if (attempt < INGESTION_MAX_RETRIES) IngestionWork.RETRY else IngestionWork.FAIL
    status in setOf("busy", "learning_device_busy", "deferred_device_state") -> IngestionWork.RETRY
    status == "incremental_checkpoint_saved" && hasMore -> IngestionWork.CONTINUE
    else -> IngestionWork.STOP
}

/** SharedPreferences updates its in-process values even when the disk commit fails. */
internal fun SharedPreferences.saveKnowledgeCheckpoint(vararg values: Pair<String, Any>) {
    val previous = all
    fun SharedPreferences.Editor.put(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is Long -> putLong(key, value)
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            else -> error("Unsupported ingestion checkpoint value")
        }
    }
    val updated = edit()
    values.forEach { (key, value) -> updated.put(key, value) }
    if (updated.commit()) return
    val restore = edit()
    values.forEach { (key, _) -> restore.put(key, previous[key]) }
    restore.apply()
    throw IOException("Knowledge checkpoint was not committed")
}
