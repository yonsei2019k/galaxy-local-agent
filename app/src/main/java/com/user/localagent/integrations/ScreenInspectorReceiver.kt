package com.user.localagent.integrations

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Private, explicit notification PendingIntent entry point. Never launches an Activity. */
class ScreenInspectorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSPECT) return
        runCatching { ScreenBridgeController(context).showInspector() }.onFailure {
            Toast.makeText(context, "화면 연결 서비스를 켜고 허용한 앱을 앞에 두세요.", Toast.LENGTH_SHORT).show()
        }
    }
    companion object { const val ACTION_INSPECT = "com.user.localagent.INSPECT_CURRENT_APP_SCHEMA" }
}
