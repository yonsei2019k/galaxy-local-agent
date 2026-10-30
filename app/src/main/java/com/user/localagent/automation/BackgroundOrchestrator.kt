package com.user.localagent.automation

import android.app.AppOpsManager
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.user.localagent.R
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Explicit local observation settings; neither option permits external content access. */
data class AutomationSettings(
    val assistantPatternsEnabled: Boolean = false,
    val usageStatsEnabled: Boolean = false,
    val intervalMinutes: Long = 15,
)
data class AutomationRecommendation(val id: String, val title: String, val reason: String)
data class AutomationState(
    val enabled: Boolean,
    val settings: AutomationSettings,
    val usageAccessGranted: Boolean,
    val pending: Boolean,
    val lastAnalysisAt: Long,
    val lastStatus: String,
    val recordedOutcomes: Long,
    val recommendations: List<AutomationRecommendation>,
    val visibleModeRequested: Boolean = false,
)

/** No prompts, arguments, messages, contact data, or other-app content are recorded. */
class AutomationController(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val work get() = WorkManager.getInstance(context)

    /** Changes on observation settings, erasure, stop, or newly recorded assistant outcomes. */
    internal fun observationRevision(): Long = synchronized(LOCK) { prefs.getLong("generation", 0) }
    fun settings() = AutomationSettings(
        prefs.getBoolean("assistant_opt_in", false), prefs.getBoolean("usage_opt_in", false),
        prefs.getLong("interval_minutes", MIN_INTERVAL).coerceIn(MIN_INTERVAL, 1440),
    )
    fun state(): AutomationState {
        val recommendations = runCatching {
            val items = JSONArray(prefs.getString("recommendations", "[]"))
            (0 until items.length()).map { i -> items.getJSONObject(i).let {
                AutomationRecommendation(it.getString("id"), it.getString("title"), it.getString("reason"))
            } }
        }.getOrDefault(emptyList())
        return AutomationState(prefs.getBoolean("enabled", false), settings(), hasUsageAccess(),
            prefs.getBoolean("pending", false), prefs.getLong("last_analysis_at", 0),
            prefs.getString("last_status", "disabled") ?: "disabled", prefs.getLong("outcome_count", 0), recommendations, prefs.getBoolean("visible_mode", false))
    }
    fun updateSettings(value: AutomationSettings): AutomationState = synchronized(LOCK) {
        val old = settings()
        prefs.edit().putBoolean("assistant_opt_in", value.assistantPatternsEnabled)
            .putBoolean("usage_opt_in", value.usageStatsEnabled)
            .putLong("interval_minutes", value.intervalMinutes.coerceIn(MIN_INTERVAL, 1440))
            .remove("recommendations").putLong("generation", prefs.getLong("generation", 0) + 1).apply {
                if (!value.assistantPatternsEnabled && old.assistantPatternsEnabled) {
                    remove("counters"); remove("outcome_count")
                }
                if (!value.usageStatsEnabled) remove("usage_summary")
            }.apply()
        if (prefs.getBoolean("enabled", false)) {
            if (!value.assistantPatternsEnabled && !value.usageStatsEnabled) stop()
            else { schedulePeriodic(); requestAnalysis() }
        }
        state()
    }
    /** Call only from an explicit user action. Does not open settings or request permission. */
    fun start(): AutomationState = synchronized(LOCK) {
        check(settings().let { it.assistantPatternsEnabled || it.usageStatsEnabled }) { "Enable at least one observation option first" }
        prefs.edit().putBoolean("enabled", true).putBoolean("pending", true).putString("last_status", "scheduled").apply()
        schedulePeriodic(); requestAnalysis(); state()
    }
    /** Keeps existing local aggregates; clearLocalHistory() is the separate erasure action. */
    fun stop(): AutomationState = synchronized(LOCK) {
        prefs.edit().putBoolean("enabled", false).putBoolean("pending", false).putString("last_status", "disabled").putLong("generation", prefs.getLong("generation", 0) + 1).apply()
        work.cancelUniqueWork(PERIODIC); work.cancelUniqueWork(EVENT)
        context.stopService(Intent(context, LocalOrchestratorService::class.java))
        prefs.edit().putBoolean("visible_mode", false).apply()
        state()
    }
    /** Invoke from a visible Activity after an explicit tap; never call from boot/Worker. */
    fun startVisibleMode(): AutomationState {
        start()
        prefs.edit().putBoolean("visible_mode", true).apply()
        try { ContextCompat.startForegroundService(context, Intent(context, LocalOrchestratorService::class.java)) }
        catch (error: RuntimeException) { prefs.edit().putBoolean("visible_mode", false).apply(); throw error }
        return state()
    }
    fun stopVisibleMode(): AutomationState {
        prefs.edit().putBoolean("visible_mode", false).apply()
        context.stopService(Intent(context, LocalOrchestratorService::class.java))
        return state()
    }
    internal fun onVisibleModeStopped() { prefs.edit().putBoolean("visible_mode", false).apply() }
    internal fun onDeviceEvent() = synchronized(LOCK) {
        if (prefs.getBoolean("enabled", false)) {
            prefs.edit().putBoolean("pending", true).apply()
            requestAnalysis()
        }
    }
    fun clearLocalHistory(): AutomationState = synchronized(LOCK) {
        prefs.edit().remove("counters").remove("outcome_count").remove("recommendations")
            .remove("usage_summary").remove("last_analysis_at").putBoolean("pending", false)
            .putLong("generation", prefs.getLong("generation", 0) + 1).apply()
        work.cancelUniqueWork(EVENT); state()
    }
    /** toolName is reduced to a fixed category before persistence. Never pass text/arguments. */
    fun recordOutcome(toolName: String, success: Boolean) = synchronized(LOCK) {
        if (!prefs.getBoolean("enabled", false) || !settings().assistantPatternsEnabled) return@synchronized
        val category = when (toolName) {
            "save_note", "search_notes", "update_note", "delete_note" -> "notes"
            "create_reminder", "update_reminder", "delete_reminder", "create_alarm" -> "planning"
            "list_calendar_events", "prepare_calendar_event" -> "calendar"
            "prepare_sms", "prepare_email", "search_contacts" -> "communication"
            "open_map" -> "navigation"
            "save_memory", "search_memories", "delete_memory" -> "memory"
            "open_url" -> "web"
            else -> "other"
        }
        val counters = readCounters()
        val key = category + if (success) ":success" else ":failure"
        counters.put(key, (counters.optLong(key) + 1).coerceAtMost(1_000_000))
        prefs.edit().putString("counters", counters.toString())
            .putLong("outcome_count", (prefs.getLong("outcome_count", 0) + 1).coerceAtMost(10_000_000))
            .putBoolean("pending", true).putLong("generation", prefs.getLong("generation", 0) + 1).apply()
        requestAnalysis()
    }
    fun hasUsageAccess(): Boolean = runCatching {
        context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    private fun schedulePeriodic() {
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LocalAnalysisWorker>(settings().intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
    }
    private fun requestAnalysis() {
        val remaining = (COOLDOWN - (System.currentTimeMillis() - prefs.getLong("last_analysis_at", 0))).coerceIn(0, COOLDOWN)
        work.enqueueUniqueWork(EVENT, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalAnalysisWorker>().setInitialDelay(remaining, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
    }
    private fun readCounters() = runCatching { JSONObject(prefs.getString("counters", "{}")) }.getOrDefault(JSONObject())

    internal fun analyze(): ListenableWorker.Result {
        val snapshot = synchronized(LOCK) {
            if (!prefs.getBoolean("enabled", false)) return ListenableWorker.Result.success()
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("last_analysis_at", 0) in 0 until COOLDOWN) return ListenableWorker.Result.success()
            Triple(settings(), prefs.getLong("generation", 0), readCounters())
        }
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return ListenableWorker.Result.retry()
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (power.isPowerSaveMode || power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || battery in 0..15) {
            synchronized(LOCK) { prefs.edit().putString("last_status", "deferred_power_or_thermal").apply() }
            return ListenableWorker.Result.success() // Periodic work will try later; no retry loop.
        }
        val recommendations = mutableListOf<AutomationRecommendation>()
        val options = snapshot.first
        val counters = snapshot.third
        if (options.assistantPatternsEnabled) {
            fun recommend(category: String, title: String) {
                val count = counters.optLong("$category:success")
                if (count >= 5) recommendations += AutomationRecommendation(category, title, "This assistant completed $count $category actions since local history was cleared.")
            }
            recommend("notes", "Review a document or note organization skill")
            recommend("planning", "Review a planning or reminder workflow")
            recommend("calendar", "Review a calendar planning workflow")
            recommend("communication", "Review a message drafting workflow")
        }
        var status = "analyzed_local_counters"
        var usageSummary: String? = null
        if (options.usageStatsEnabled) {
            if (!hasUsageAccess()) status = "usage_access_required"
            else {
                // System daily buckets can overlap the requested window; do not label as exact 24h totals.
                val now = System.currentTimeMillis()
                val stats = context.getSystemService(UsageStatsManager::class.java)
                    .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - TimeUnit.DAYS.toMillis(1), now)
                    .orEmpty()
                val byPackage = stats.groupBy { it.packageName }.mapValues { (_, rows) -> rows.sumOf { it.totalTimeInForeground.coerceAtLeast(0) } }
                val docsMillis = byPackage.filterKeys { it in DOCUMENT_APPS }.values.sum()
                usageSummary = JSONObject().put("observedAt", now).put("dailyBucketForegroundMillis", byPackage.values.sum())
                    .put("documentAppForegroundMillis", docsMillis).put("appCount", byPackage.size).toString()
                if (docsMillis >= TimeUnit.MINUTES.toMillis(15)) recommendations += AutomationRecommendation(
                    "document_usage", "Review a document organization skill",
                    "Known document apps total ${TimeUnit.MILLISECONDS.toMinutes(docsMillis)} foreground minutes in system daily buckets; no document content was read.")
                status = if (stats.isEmpty()) "usage_data_unavailable" else "analyzed_local_usage_and_counters"
            }
        }
        synchronized(LOCK) {
            // An opt-out or erase while the system query ran must prevent publication.
            if (!prefs.getBoolean("enabled", false) || prefs.getLong("generation", 0) != snapshot.second || settings() != options) return ListenableWorker.Result.success()
            val items = JSONArray()
            recommendations.forEach { items.put(JSONObject().put("id", it.id).put("title", it.title).put("reason", it.reason)) }
            prefs.edit().putString("recommendations", items.toString()).putString("last_status", status)
                .putLong("last_analysis_at", System.currentTimeMillis()).putBoolean("pending", false)
                .apply { if (usageSummary != null) putString("usage_summary", usageSummary) else remove("usage_summary") }.apply()
        }
        return ListenableWorker.Result.success()
    }
    companion object {
        private val LOCK = Any()
        private const val PREFS = "local_automation_v1"
        private const val PERIODIC = "local-analysis-periodic-v1"
        private const val EVENT = "local-analysis-event-v1"
        private const val MIN_INTERVAL = 15L
        private val COOLDOWN = TimeUnit.MINUTES.toMillis(15)
        private val DOCUMENT_APPS = setOf("com.google.android.apps.docs.editors.docs", "com.microsoft.office.word", "com.google.android.apps.docs")
    }
}

class LocalAnalysisWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        AutomationController(applicationContext).analyze()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (_: SecurityException) { Result.success() // User can revoke usage access at any time.
    } catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
}

/** User-started visible orchestration only. No wake lock, polling timer, or loaded LLM. */
class LocalOrchestratorService : Service() {
    private val controller by lazy { AutomationController(this) }
    private var receiverRegistered = false
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { controller.onDeviceEvent() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !controller.state().enabled) {
            controller.onVisibleModeStopped(); stopSelf(); return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Local assistant background mode", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, LocalOrchestratorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Local assistant background mode")
            .setContentText("Local event analysis enabled; no continuous model inference")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Stop visible mode", stop)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        try {
            ServiceCompat.startForeground(this, 7314, builder.build(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: RuntimeException) {
            controller.onVisibleModeStopped(); stopSelf(); return START_NOT_STICKY
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, events, IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        controller.onDeviceEvent()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(events)
        controller.onVisibleModeStopped()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "local-orchestration"
        private const val ACTION_STOP = "com.user.localagent.STOP_VISIBLE_ORCHESTRATION"
    }
}



