package com.user.localagent.scheduling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.*
import com.user.localagent.LocalAgentApplication
import com.user.localagent.FailureLog
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

internal class GoalWaitWork(private val context: Context) {
    private val encrypted = ConnectionStore(context)
    val store = GoalWaitStore({ encrypted.read("goal.wait.v1") }, { encrypted.write("goal.wait.v1", it) })

    suspend fun arm(record: GoalWaitRecord) {
        val time = GoalWaitPolicy.dueAt(record) ?: return
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

    suspend fun onPcAvailable() {
        for (previous in store.all()) {
            if (previous.pcRetries == 0 || previous.retryAt == null || previous.resumedOnceFor == previous.id) continue
            val immediate = previous.copy(id = java.util.UUID.randomUUID().toString(), retryAt = null)
            if (store.replace(immediate, previous)) arm(immediate)
        }
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
        val dueAt=GoalWaitPolicy.dueAt(record) ?: return Result.success()
        if(System.currentTimeMillis()<dueAt){
            val delayed=record.copy(id=java.util.UUID.randomUUID().toString())
            if(app.goalWaits.store.replace(delayed,record))app.goalWaits.arm(delayed)
            return Result.success()
        }
        if(record.ordinaryBrowserAuth)return if(app.runs.wakeBrowserAuth(record))Result.success()
            else if(runAttemptCount<5)Result.retry() else Result.failure()
        val ready=if(app.runs.isGoalWaitReady(record))true
            else if(record.condition is WaitCondition.MediaEnd)withTimeoutOrNull(12_000) {
                while(app.runs.isRunLive(record.parentRunId)){
                    delay(250)
                    if(app.runs.isGoalWaitReady(record))return@withTimeoutOrNull true
                }
                app.runs.isGoalWaitReady(record)
            }==true else false
        if(!ready)
            return if(app.runs.isRunLive(record.parentRunId))Result.retry() else Result.success()
        if (System.currentTimeMillis() >= record.deadlineAt) {
            return if (app.runs.wakeGoal(record, WakeEvent.RESTORED_PAST_DUE)) Result.success() else Result.retry()
        }
        val existing=record.observedEvent
        val media=record.condition as? WaitCondition.MediaEnd
        if(existing==null && record.pcMedia && media!=null) {
            return pollPcMedia(app,record,media)
        }
        val event = existing ?: when (val condition=record.condition) {
            is WaitCondition.AtTime -> WakeEvent.TIME_REACHED
            is WaitCondition.OwnerAuth -> WakeEvent.OWNER_AUTH_TIMEOUT
            is WaitCondition.MediaEnd -> watchMedia(record,condition)
        }
        if(existing==null && record.condition is WaitCondition.MediaEnd) {
            FailureLog.record(applicationContext,"goal_media_wait",null,event.name)
            val observed=record.copy(observedEvent=event)
            if(!app.goalWaits.store.replace(observed,record))return Result.success()
            record=observed
        }
        return if (app.runs.wakeGoal(record,event)) Result.success()
            else if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    private suspend fun pollPcMedia(app:com.user.localagent.AppContainer,record:GoalWaitRecord,
        condition:WaitCondition.MediaEnd):Result {
        val now=System.currentTimeMillis()
        val parent=app.repository.chat.run(record.parentRunId)?:return Result.success()
        val request=com.user.localagent.QueuedChatRequest.fromJson(parent.contextJson)
        var transportFailed=false
        val observation=try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.user.localagent.compute.BrowserAgentTools.samplePairedMedia(applicationContext,
                    com.user.localagent.pcBrowserWorkKey(request))
            }
        } catch(cancel:CancellationException){throw cancel}
          catch(_:Exception){transportFailed=true;null}
        if(!app.runs.isGoalWaitReady(record)||app.goalWaits.store.get(record.goalId)!=record)return Result.success()
        val player=observation?.player
        val moved=player!=null&&record.playingPositionMs?.let { player.positionMs>=it+100 }==true
        val lastProgress=record.lastMediaProgressAt?:record.createdAt
        val event=when {
            now-record.createdAt !in 0 until GoalWaitPolicy.MEDIA_MAX_MS -> WakeEvent.MEDIA_LIMIT
            transportFailed -> if(now-lastProgress>=GoalWaitPolicy.MEDIA_STALL_MS)WakeEvent.MEDIA_STALLED else null
            observation?.loginRequired==true -> WakeEvent.LOGIN_EXPIRED
            observation==null||player==null||observation.pageUrl!=condition.pageUrl||
                player.playerId+":"+player.epoch!=condition.playerSignature -> WakeEvent.PLAYER_GONE
            MediaEndWatcher.endedDuringHandoff(player,record.playingPositionMs,record.playingDurationMs,
                now-(record.lastMediaSampleAt?:record.createdAt)) -> WakeEvent.MEDIA_ENDED
            player.ended -> WakeEvent.PLAYER_GONE
            !moved&&now-lastProgress>=GoalWaitPolicy.MEDIA_STALL_MS -> WakeEvent.MEDIA_STALLED
            else -> null
        }
        if(event!=null) {
            val observed=record.copy(observedEvent=event)
            if(!app.goalWaits.store.replace(observed,record))return Result.success()
            return if(app.runs.wakeGoal(observed,event))Result.success() else Result.retry()
        }
        val next=record.copy(id=java.util.UUID.randomUUID().toString(),
            mediaPollAt=now+GoalWaitPolicy.mediaPollDelay(player?.positionMs,player?.durationMs,player?.rate,moved),lastMediaProgressAt=if(moved)now else lastProgress,
            playingPositionMs=player?.positionMs?:record.playingPositionMs,
            lastMediaSampleAt=if(player==null)record.lastMediaSampleAt else now)
        if(app.goalWaits.store.replace(next,record))app.goalWaits.arm(next)
        return Result.success()
    }

    private suspend fun watchMedia(record: GoalWaitRecord,condition: WaitCondition.MediaEnd):WakeEvent {
        val app=(applicationContext as LocalAgentApplication).container
        val web=AppWebAgent.get(applicationContext)
        val work=AppWebAgent.WorkBinding(record.parentRunId,record.threadId,record.branchId)
        fun active()=System.currentTimeMillis()<record.deadlineAt && app.goalWaits.store.get(record.goalId)==record
        val observation=web.sampleGoalMedia(work) ?: return WakeEvent.PLAYER_GONE
        if(observation.loginRequired)return WakeEvent.LOGIN_EXPIRED
        val player=observation.player ?: return WakeEvent.PLAYER_GONE
        if(observation.pageUrl!=condition.pageUrl || player.playerId+":"+player.epoch!=condition.playerSignature)
            return WakeEvent.PLAYER_GONE
        if(MediaEndWatcher.endedDuringHandoff(player,record.playingPositionMs,record.playingDurationMs,
                System.currentTimeMillis()-record.createdAt))return WakeEvent.MEDIA_ENDED
        if(player.paused)return WakeEvent.MEDIA_STALLED
        try { setForeground(mediaNotification()) }
        catch(cancel:CancellationException){ throw cancel }
        catch(_:Exception){
            withTimeoutOrNull(3_000){web.stopGoalMedia(condition,work)}
            return WakeEvent.CONDITIONS_UNMET
        }
        val wakeLock=applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ZipHap:GoalMedia")
        wakeLock.acquire(GoalWaitPolicy.MEDIA_MAX_MS)
        var ended=false
        return try {
            val watcher=MediaEndWatcher({ if(app.runs.isGoalWaitReady(record)) web.sampleGoalMedia(work) else null },
                ::active,SystemClock::elapsedRealtime,::delay)
            watcher.watch(condition.pageUrl,player).also { ended=it==WakeEvent.MEDIA_ENDED }
        } finally {
            if(wakeLock.isHeld)wakeLock.release()
            if(!ended)withContext(NonCancellable) {
                withTimeoutOrNull(3_000){web.stopGoalMedia(condition,work)}
            }
        }
    }

    private fun mediaNotification():ForegroundInfo {
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        val channel="goal-media-progress"
        manager.createNotificationChannel(NotificationChannel(channel,"교육 재생 확인",NotificationManager.IMPORTANCE_LOW))
        val notice=Notification.Builder(applicationContext,channel)
            // The launcher icon is a full-color picture; the status bar needs the monochrome mark the other notices use.
            .setSmallIcon(com.user.localagent.runtime.R.drawable.ic_agent)
            .setContentTitle("교육 영상이 끝나는지 확인 중")
            .setContentText("집합 앱 브라우저에서 실제 재생 상태를 확인하고 있어요.")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo(2701,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
}
