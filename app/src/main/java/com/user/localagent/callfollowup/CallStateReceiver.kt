package com.user.localagent.callfollowup

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.user.localagent.LocalAgentApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Protected Android phone-state broadcast only; no incoming-number extra is read. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state !in setOf(TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE)) return
        val pending = goAsync()
        SCOPE.launch {
            try { withTimeout(7000) { (context.applicationContext as LocalAgentApplication).container.callFollowUp.onPhoneState(state) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* No grant or process-unlock assumption; periodic reconciliation remains durable. */ }
            finally { pending.finish() }
        }
    }
    companion object { private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
