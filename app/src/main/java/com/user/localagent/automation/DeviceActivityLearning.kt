package com.user.localagent.automation

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.user.localagent.data.KnowledgeClaim
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Android-owned timeline observations. They do not infer what a user did inside an app or call. */
internal class DeviceActivityLearning(
    private val context: Context,
    private val repository: LocalRepository,
) {
    private val prefs = context.getSharedPreferences("knowledge-device-activity-v1", Context.MODE_PRIVATE)
    private val automation = AutomationController(context)

    fun canReadCalls(): Boolean = ContextCompat.checkSelfPermission(context,
        Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    suspend fun capture(floor: Long, epoch: Long, eligible: suspend () -> Unit): Int {
        val usage = captureUsage(floor, epoch, eligible)
        return usage + captureCalls(floor, epoch, eligible)
    }

    private suspend fun captureUsage(floor: Long, epoch: Long, eligible: suspend () -> Unit): Int {
        fun allowed() = automation.settings().usageStatsEnabled && automation.hasUsageAccess()
        if (!allowed()) return 0
        val now = System.currentTimeMillis() - 5_000
        val start = maxOf(prefs.getLong("usage_cursor", 0), now - TimeUnit.DAYS.toMillis(7), floor + 1)
        if (start >= now) return 0
        val stream = context.getSystemService(UsageStatsManager::class.java).queryEvents(start, now) ?: return 0
        val event = UsageEvents.Event()
        var count = 0
        var lastTime = start
        while (stream.getNextEvent(event)) {
            currentCoroutineContext().ensureActive()
            if (count >= 150 && event.timeStamp > lastTime) break
            val pkg = event.packageName ?: continue
            val action = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> "foreground"
                UsageEvents.Event.ACTIVITY_PAUSED -> "background"
                else -> continue
            }
            eligible()
            if (!allowed()) return count
            val label = runCatching {
                context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString()
            }.getOrDefault(pkg).ifBlank { pkg }.take(100)
            val title = if (action == "foreground") "화면에 열림" else "화면에서 벗어남"
            save("device_usage", "${event.timeStamp}|${event.eventType}|$pkg", event.timeStamp,
                label, "앱 사용 · $label · $title", listOf("앱", "사용 기록", action, pkg.take(80)), epoch)
            lastTime = event.timeStamp
            count++
        }
        eligible()
        if (allowed()) prefs.saveKnowledgeCheckpoint("usage_cursor" to (if (count >= 150) lastTime + 1 else now))
        return count
    }

    private suspend fun captureCalls(floor: Long, epoch: Long, eligible: suspend () -> Unit): Int {
        if (!canReadCalls()) return 0
        val after = prefs.getLong("call_cursor", -1)
        val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf("_id", "date", "type", "duration"), "_id > ? AND date > ?",
            arrayOf(after.toString(), floor.toString()), "_id ASC") ?: return 0
        var count = 0
        cursor.use {
            while (count < 80 && it.moveToNext()) {
                eligible()
                if (!canReadCalls()) return count
                val id = it.getLong(0)
                val at = it.getLong(1)
                val kind = when (it.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "수신"
                    CallLog.Calls.OUTGOING_TYPE -> "발신"
                    CallLog.Calls.MISSED_TYPE -> "부재중"
                    else -> "기타"
                }
                save("call_log", "$id|$at", at, null,
                    "통화 기록 · $kind · ${it.getLong(3).coerceAtLeast(0)}초",
                    listOf("통화", kind, "통화 내역"), epoch)
                prefs.saveKnowledgeCheckpoint("call_cursor" to id)
                count++
            }
        }
        return count
    }

    private suspend fun save(kind: String, unique: String, at: Long, label: String?,
        value: String, tags: List<String>, epoch: Long) {
        val id = MessageDigest.getInstance("SHA-256").digest("$kind|$unique".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (!repository.knowledgeSourceAllowed(kind, id, at)) return
        val saved = repository.saveKnowledgeClaim(KnowledgeClaim(subject = "record:${id.take(24)}",
            predicate = "record.device_event", value = value, sourceKind = kind, sourceId = id,
            status = "observed", confidence = 0.9, tags = listOf("device_activity") + tags,
            objectSubject = label, updatedAt = at), epoch)
        check(repository.getKnowledge(saved.id)?.sourceId == id)
    }
}
