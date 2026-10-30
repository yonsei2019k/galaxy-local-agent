package com.user.localagent.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/** Counts opted-in notification events and forwards only per-app-consented messages to the session inbox. */
class AssistantNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(notification: StatusBarNotification) {
        runCatching {com.user.localagent.integrations.MessengerInbox.receive(this,notification)}
        if (notification.packageName == packageName || notification.isOngoing) return
        val prefs = getSharedPreferences("notification-events", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        // Updated notifications must not inflate counts. In-memory keys aren't a private archive.
        if (!seen.add(notification.key)) return
        if (seen.size > 256) seen.clear()
        val now = System.currentTimeMillis()
        val day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()
        val oldDay = prefs.getString("day", null)
        prefs.edit().putString("day", day)
            .putInt("count", if (oldDay == day) prefs.getInt("count", 0) + 1 else 1)
            .putLong("last_event", now).apply()
    }
    override fun onNotificationRemoved(notification: StatusBarNotification) = onMessengerNotificationRemoved(notification)
    override fun onNotificationRemoved(notification: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap, reason: Int) = onMessengerNotificationRemoved(notification)
    private fun onMessengerNotificationRemoved(notification: StatusBarNotification) {
        seen.remove(notification.key)
        com.user.localagent.integrations.MessengerInbox.removeNotification(notification.key)
    }
    override fun onListenerDisconnected() {
        com.user.localagent.integrations.MessengerInbox.clearSession()
        super.onListenerDisconnected()
    }
    private val seen = mutableSetOf<String>()
    companion object {
        fun permissionIntent(context: Context) = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, AssistantNotificationListener::class.java).flattenToString())
        fun granted(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        fun setEnabled(context: Context, enabled: Boolean) {
            val prefs=context.getSharedPreferences("notification-events",Context.MODE_PRIVATE)
            if (enabled) prefs.edit().putBoolean("enabled",true).apply() else prefs.edit().clear().apply()
        }
        fun enabled(context: Context) = context.getSharedPreferences("notification-events",Context.MODE_PRIVATE).getBoolean("enabled",false)
        fun count(context: Context) = context.getSharedPreferences("notification-events",Context.MODE_PRIVATE).let {
            if(it.getString("day",null)==java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString())it.getInt("count",0)else 0
        }
    }
}
