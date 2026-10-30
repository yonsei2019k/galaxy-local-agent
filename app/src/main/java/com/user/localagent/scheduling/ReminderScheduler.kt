package com.user.localagent.scheduling

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.user.localagent.MainActivity
import com.user.localagent.R
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.ReminderRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

private val deliveryMutex = Mutex()
/** Synchronous gate prevents a cancellation/edit from racing the actual notification post. */
private object NotificationGate {
    val lock = Any()
    private val generations = mutableMapOf<String, Long>()
    fun generation(id: String): Long = synchronized(lock) { generations[id] ?: 0 }
    fun advance(id: String) = synchronized(lock) { generations[id] = (generations[id] ?: 0) + 1 }
}

data class ScheduleResult(val exact: Boolean, val notificationsAllowed: Boolean, val message: String)
class ReminderScheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    fun schedule(record: ReminderRecord): ScheduleResult {
        require(record.atEpochMillis > System.currentTimeMillis()) { "이미 지난 시각에는 새 알림을 예약하지 않습니다." }
        synchronized(NotificationGate.lock) {
            NotificationGate.advance(record.id)
            val intent = reminderIntent(record.id).putExtra("id", record.id)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // The grant may be revoked between canScheduleExactAlarms() and setExact*().
            var exact = manager.canScheduleExactAlarms()
            if (exact) {
                try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.atEpochMillis, pending) }
                catch (_: SecurityException) { exact = false }
            }
            if (!exact) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.atEpochMillis, pending)
            // Persistent backup survives exact-alarm revocation; DB claim deduplicates both delivery paths.
            WorkManager.getInstance(context).enqueueUniqueWork(workName(record.id), ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderDeliveryWorker>()
                    .setInputData(workDataOf("id" to record.id, "at" to record.atEpochMillis, "revision" to record.updatedAt))
                    .setInitialDelay((record.atEpochMillis - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build())
            val allowed = ReminderNotifier.notificationsAllowed(context)
            return ScheduleResult(exact, allowed, if (!allowed) "예약했지만 알림 표시 권한이 필요합니다." else if (exact) "정확한 시각에 알림을 예약했습니다." else "정확한 알람 권한이 없어 지연될 수 있는 알림으로 예약했습니다.")
        }
    }
    fun cancel(id: String) = synchronized(NotificationGate.lock) {
        NotificationGate.advance(id)
        val pending = PendingIntent.getBroadcast(context, 0, reminderIntent(id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) { manager.cancel(pending); pending.cancel() }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        context.getSystemService(NotificationManager::class.java).apply {
            cancel(ReminderNotifier.tag(id), 0)
            cancel(id.hashCode()) // Clear notifications posted by the previous app version.
        }
    }
    /** At-least-once restoration: a crash after posting may repost the same stable notification tag/id. */
    suspend fun restoreAll(): Int = deliveryMutex.withLock {
        val repository = LocalRepository(context)
        repository.recoverInterruptedReminders()
        var count = 0
        repository.pendingReminders().forEach { record ->
            currentCoroutineContext().ensureActive()
            if (record.atEpochMillis > System.currentTimeMillis()) {
                try { schedule(record); count++ }
                catch (error: IllegalArgumentException) {
                    // A due time can pass between the comparison and schedule().
                    if (record.atEpochMillis <= System.currentTimeMillis()) ReminderNotifier.deliverUnlocked(context, record.id)
                    else throw error
                }
            } else ReminderNotifier.deliverUnlocked(context, record.id)
        }
        count
    }
    fun scheduleBrief() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-local-brief", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyBriefWorker>(24, TimeUnit.HOURS).build())
    }
    private fun reminderIntent(id: String) = Intent(context, ReminderReceiver::class.java)
        .setAction("com.user.localagent.REMINDER").setData(Uri.parse("localagent://reminder/$id"))
    private fun workName(id: String) = "reminder-delivery-$id"
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { ReminderNotifier.deliver(context, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Pending row remains available for persistent backup/restoration. */ }
            finally { pending.finish() }
        }
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED))
            WorkManager.getInstance(context).enqueueUniqueWork("restore-local-reminders", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RestoreWorker>().build())
    }
}
class RestoreWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        ReminderScheduler(applicationContext).restoreAll(); Result.success()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
}
class ReminderDeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        return try {
            val record = LocalRepository(applicationContext).getReminder(id) ?: return Result.success()
            if (record.atEpochMillis != inputData.getLong("at", -1) || record.updatedAt != inputData.getLong("revision", -1)) return Result.success()
            ReminderNotifier.deliver(applicationContext, id); Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }
}
class DailyBriefWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val now = System.currentTimeMillis()
        val upcoming = LocalRepository(applicationContext).pendingReminders().filter { it.atEpochMillis in now..now + TimeUnit.DAYS.toMillis(1) }
        if (upcoming.isNotEmpty()) ReminderNotifier.notify(applicationContext, "daily-brief", "오늘의 할 일", "앞으로 24시간 동안 예정된 알림 ${upcoming.size}개")
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
private object ReminderNotifier {
    fun tag(id: String) = "localagent-reminder:$id"
    fun notificationsAllowed(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java).getNotificationChannel("reminders")?.importance != NotificationManager.IMPORTANCE_NONE
    }
    suspend fun deliver(context: Context, id: String) = deliveryMutex.withLock { deliverUnlocked(context, id) }
    // Caller must hold deliveryMutex, including restoration's interrupted-state recovery.
    suspend fun deliverUnlocked(context: Context, id: String) {
        val generation = NotificationGate.generation(id)
        val repository = LocalRepository(context)
        val record = repository.getReminder(id) ?: return
        if (record.atEpochMillis > System.currentTimeMillis()) return // Ignore stale alarms following an edit.
        if (!notificationsAllowed(context)) {
            repository.recordToolRun("reminder_delivery", false, "알림 표시 권한 또는 채널 비활성화")
            return
        }
        var claimed = false
        try {
            withContext(NonCancellable) { claimed = repository.claimReminder(record) }
            if (!claimed) return
            currentCoroutineContext().ensureActive()
            val posted = synchronized(NotificationGate.lock) {
                if (NotificationGate.generation(id) != generation) false
                else notify(context, id, "리마인더", record.title)
            }
            repository.finishReminder(record, if (posted) "delivered" else "pending")
        } catch (cancelled: CancellationException) {
            if (claimed) withContext(NonCancellable) { repository.finishReminder(record, "pending") }
            throw cancelled
        } catch (error: Exception) {
            if (claimed) withContext(NonCancellable) { repository.finishReminder(record, "pending") }
            throw error
        }
    }
    fun notify(context: Context, id: String, title: String, text: String): Boolean {
        if (!notificationsAllowed(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("reminders", "개인 리마인더", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Tag+id has no String.hashCode collision and replaces an earlier post after crash recovery.
        try {
            manager.notify(tag(id), 0, NotificationCompat.Builder(context, "reminders").setSmallIcon(R.drawable.ic_agent)
                .setContentTitle(title).setContentText(text).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: SecurityException) { return false }
        return notificationsAllowed(context)
    }
}

