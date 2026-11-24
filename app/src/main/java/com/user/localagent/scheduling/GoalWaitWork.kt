package com.user.localagent.scheduling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.*
import com.user.localagent.LocalAgentApplication
import com.user.localagent.connections.ConnectionStore
import com.user.localagent.core.GoalWaitPolicy
import com.user.localagent.core.GoalWaitRecord
import com.user.localagent.core.WaitCondition
import com.user.localagent.core.WakeEvent
import com.user.localagent.integrations.AppWebAgent
import com.user.localagent.integrations.MediaEndWatcher
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

internal fun playbackConditions(context: Context): Boolean {
    val network=context.getSystemService(ConnectivityManager::class.java)
    val capabilities=network.getNetworkCapabilities(network.activeNetwork)
    return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true &&
        context.getSystemService(BatteryManager::class.java).isCharging
}

internal class GoalWaitWork(private val context: Context) {
    private val encrypted = ConnectionStore(context)
    val store = GoalWaitStore({ encrypted.read("goal.wait.v1") }, { encrypted.write("goal.wait.v1", it) })

    suspend fun arm(record: GoalWaitRecord) {
        val time = when(val condition=record.condition) {
            is WaitCondition.AtTime -> condition.atEpochMillis
            is WaitCondition.OwnerAuth -> if(record.observedEvent!=null) 0L else record.deadlineAt
            is WaitCondition.MediaEnd -> 0L
        }
        val request = OneTimeWorkRequestBuilder<GoalWaitWorker>()
            .setInputData(workDataOf("goalId" to record.goalId, "waitId" to record.id))
            .setInitialDelay((time - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        WorkManager.getInstance(context).enqueueUniqueWork("goal-wait-"+record.id, ExistingWorkPolicy.KEEP, request.build()).await()
    }

    suspend fun confirmAuth(binding:String):Boolean {
        val previous=store.all().singleOrNull { it.condition==WaitCondition.OwnerAuth(binding) && it.observedEvent==null &&
            it.resumedOnceFor!=it.id && it.deadlineAt>System.currentTimeMillis() } ?: return false
        val confirmed=previous.copy(id=java.util.UUID.randomUUID().toString(),observedEvent=WakeEvent.OWNER_AUTH_DONE)
        if(!store.replace(confirmed,previous))return false
        val app=(context.applicationContext as? LocalAgentApplication)?.container
        app?.runs?.scope?.launch {
            repeat(40) {
                val handled=if(confirmed.ordinaryBrowserAuth)app.runs.wakeBrowserAuth(confirmed)
                    else app.runs.isGoalWaitReady(confirmed)&&app.runs.wakeGoal(confirmed,WakeEvent.OWNER_AUTH_DONE)
                if(handled)return@launch
                delay(250)
            }
        }
        arm(confirmed)
        return true
    }

    suspend fun cancel(record: GoalWaitRecord) {
        store.replace(record.copy(resumedOnceFor=record.id),record)
        WorkManager.getInstance(context).cancelUniqueWork("goal-wait-"+record.id).await()
    }

    suspend fun restore() {
        for (record in store.all()) arm(record)
    }
}

class GoalWaitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = (applicationContext as LocalAgentApplication).container
        val goalId = inputData.getString("goalId") ?: return Result.failure()
        var record = app.goalWaits.store.get(goalId) ?: return Result.success()
        if (record.id != inputData.getString("waitId")) return Result.success()
        if(record.ordinaryBrowserAuth)return if(app.runs.wakeBrowserAuth(record))Result.success()
            else if(runAttemptCount<5)Result.retry() else Result.failure()
        if(!app.runs.isGoalWaitReady(record))
            return if(runAttemptCount<5 && app.runs.isRunLive(record.parentRunId))Result.retry() else Result.success()
        val existing=record.observedEvent
        val event = existing ?: when (val condition=record.condition) {
            is WaitCondition.AtTime -> WakeEvent.TIME_REACHED
            is WaitCondition.OwnerAuth -> WakeEvent.OWNER_AUTH_TIMEOUT
            is WaitCondition.MediaEnd -> watchMedia(record,condition)
        }
        if(existing==null && record.condition is WaitCondition.MediaEnd) {
            val observed=record.copy(observedEvent=event)
            if(!app.goalWaits.store.replace(observed,record))return Result.success()
            record=observed
        }
        return if (app.runs.wakeGoal(record,event)) Result.success()
            else if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    private suspend fun watchMedia(record: GoalWaitRecord,condition: WaitCondition.MediaEnd):WakeEvent {
        if(!playbackConditions(applicationContext))return WakeEvent.CONDITIONS_UNMET
        val app=(applicationContext as LocalAgentApplication).container
        val web=AppWebAgent.get(applicationContext)
        fun active()=app.goalWaits.store.get(record.goalId)==record && playbackConditions(applicationContext)
        val observation=web.sampleGoalMedia() ?: return WakeEvent.PLAYER_GONE
        if(observation.loginRequired)return WakeEvent.LOGIN_EXPIRED
        val player=observation.player ?: return WakeEvent.PLAYER_GONE
        if(observation.pageUrl!=condition.pageUrl || player.playerId+":"+player.epoch!=condition.playerSignature || player.paused)
            return WakeEvent.PLAYER_GONE
        try { setForeground(mediaNotification()) }
        catch(cancel:CancellationException){ throw cancel }
        catch(_:Exception){
            withTimeoutOrNull(3_000){web.stopGoalMedia(condition)}
            return WakeEvent.CONDITIONS_UNMET
        }
        val wakeLock=applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ZipHap:GoalMedia")
        wakeLock.acquire(GoalWaitPolicy.MEDIA_MAX_MS)
        var ended=false
        return try {
            val watcher=MediaEndWatcher({ if(app.runs.isGoalWaitReady(record)) web.sampleGoalMedia() else null },
                ::active,SystemClock::elapsedRealtime,::delay)
            watcher.watch(condition.pageUrl,player).also { ended=it==WakeEvent.MEDIA_ENDED }
        } finally {
            if(wakeLock.isHeld)wakeLock.release()
            if(!ended)withContext(NonCancellable) {
                withTimeoutOrNull(3_000){web.stopGoalMedia(condition)}
            }
        }
    }

    private fun mediaNotification():ForegroundInfo {
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        val channel="goal-media-progress"
        manager.createNotificationChannel(NotificationChannel(channel,"교육 재생 확인",NotificationManager.IMPORTANCE_LOW))
        val notice=Notification.Builder(applicationContext,channel)
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentTitle("교육 영상이 끝나는지 확인 중")
            .setContentText("집합 앱 브라우저에서 실제 재생 상태를 확인하고 있어요.")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo(2701,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
}
