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
import com.user.localagent.automation.ChatGoalStore
import com.user.localagent.connections.ConnectionStore
import com.user.localagent.compute.PairedMediaRead
import com.user.localagent.core.GoalStatus
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class GoalWaitWork(private val context: Context) {
    private val encrypted = ConnectionStore(context)
    val store = GoalWaitStore({ encrypted.read("goal.wait.v1") }, { encrypted.write("goal.wait.v1", it) })
    private val registration = Mutex()

    internal suspend fun register(record: GoalWaitRecord, expected: GoalWaitRecord?): Boolean = registration.withLock {
        if (expected != null || !store.atCapacity()) return@withLock store.replace(record, expected)
        val repository = (context.applicationContext as LocalAgentApplication).container.repository
        val goals = ChatGoalStore(repository)
        val reclaim = mutableSetOf<GoalWaitRecord>()
        for (candidate in store.all()) {
            val obsolete = try {
                if (repository.chat.thread(candidate.threadId) == null || repository.chat.run(candidate.parentRunId) == null) true
                else if (candidate.ordinaryBrowserAuth) false
                else goals.load(candidate.threadId).let { it?.id != candidate.goalId || it.status == GoalStatus.COMPLETE }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                false
            }
            if (obsolete) reclaim += candidate
        }
        if (reclaim.isEmpty() && store.atCapacity()) return@withLock false
        store.replace(record, expected, reclaim)
    }

    suspend fun arm(record: GoalWaitRecord) {
        val time = GoalWaitPolicy.dueAt(record) ?: return
        val request = OneTimeWorkRequestBuilder<GoalWaitWorker>()
            .setInputData(workDataOf("goalId" to record.goalId, "waitId" to record.id))
            .setInitialDelay((time - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        WorkManager.getInstance(context).enqueueUniqueWork("goal-wait-"+record.id, ExistingWorkPolicy.KEEP, request.build()).await()
    }

    suspend fun armAfterWake(record:GoalWaitRecord):Boolean = try {
        arm(record)
        true
    } catch (cancel:CancellationException) {
        throw cancel
    } catch (error:Exception) {
        FailureLog.record(context,"goal_wait_rearm",error)
        false
    }

    suspend fun confirmAuth(binding:String):Boolean {
        val previous=store.all().singleOrNull { it.condition==WaitCondition.OwnerAuth(binding) && it.observedEvent==null && it.stopReason==null &&
            it.resumedOnceFor!=it.id && it.deadlineAt>System.currentTimeMillis() } ?: return false
        val confirmed=previous.copy(id=java.util.UUID.randomUUID().toString(),observedEvent=WakeEvent.OWNER_AUTH_DONE)
        if(!store.replace(confirmed,previous))return false
        val app=(context.applicationContext as? LocalAgentApplication)?.container
        app?.scopedRuns?.scope?.launch {
            repeat(40) {
                if(!confirmed.ordinaryBrowserAuth)app.scopedRuns.prepareScheduledGoalWait(confirmed)
                val handled=if(confirmed.ordinaryBrowserAuth)app.scopedRuns.wakeBrowserAuth(confirmed)
                    else app.scopedRuns.isGoalWaitReady(confirmed)&&app.scopedRuns.wakeGoal(confirmed,WakeEvent.OWNER_AUTH_DONE)
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
            if (previous.pcRetries == 0 || previous.retryAt == null || previous.resumedOnceFor == previous.id || previous.stopReason!=null) continue
            val immediate = previous.copy(id = java.util.UUID.randomUUID().toString(), retryAt = null)
            if (store.replace(immediate, previous)) arm(immediate)
        }
    }

    suspend fun restore() {
        for (record in store.all()) arm(record)
    }
}

class GoalWaitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        runDueWait()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        FailureLog.record(applicationContext,"goal_wait_dispatch",error)
        Result.retry()
    }

    private suspend fun runDueWait(): Result {
        val app = (applicationContext as LocalAgentApplication).container
        val goalId = inputData.getString("goalId") ?: return Result.failure()
        var record = app.goalWaits.store.get(goalId) ?: return Result.success()
        val waitId = inputData.getString("waitId") ?: return Result.failure()
        if (record.id != waitId) {
            // The previous worker may have saved its successor before enqueue was acknowledged.
            // Uniqueness and the worker's record check keep this dispatch from running obsolete work.
            app.goalWaits.arm(record)
            return Result.success()
        }
        val dueAt=GoalWaitPolicy.dueAt(record) ?: return Result.success()
        if(!record.ordinaryBrowserAuth)app.scopedRuns.prepareScheduledGoalWait(record)
        if(record.stopReason!=null)
            return if(app.scopedRuns.settleUnreadyGoalWait(record))Result.success() else Result.retry()
        if(System.currentTimeMillis()<dueAt){
            val delayed=record.copy(id=java.util.UUID.randomUUID().toString())
            if(app.goalWaits.store.replace(delayed,record))app.goalWaits.arm(delayed)
            return Result.success()
        }
        if(record.ordinaryBrowserAuth)return if(app.scopedRuns.wakeBrowserAuth(record))Result.success()
            else if(runAttemptCount<5)Result.retry() else Result.failure()
        val ready=if(app.scopedRuns.isGoalWaitReady(record))true
            else if(record.condition is WaitCondition.MediaEnd)withTimeoutOrNull(12_000) {
                while(app.scopedRuns.isRunLive(record.parentRunId)){
                    delay(250)
                    if(app.scopedRuns.isGoalWaitReady(record))return@withTimeoutOrNull true
                }
                app.scopedRuns.isGoalWaitReady(record)
            }==true else false
        if (!ready) return if (app.scopedRuns.settleUnreadyGoalWait(record)) Result.success() else Result.retry()
        if (System.currentTimeMillis() >= record.deadlineAt) {
            return if (app.scopedRuns.wakeGoal(record, WakeEvent.RESTORED_PAST_DUE)) Result.success() else Result.retry()
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
        return if (app.scopedRuns.wakeGoal(record,event)) Result.success()
            else if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    private suspend fun pollPcMedia(app:com.user.localagent.AppContainer,record:GoalWaitRecord,
        condition:WaitCondition.MediaEnd):Result {
        val now=System.currentTimeMillis()
        val parent=app.repository.chat.run(record.parentRunId)?:return Result.success()
        val request=com.user.localagent.QueuedChatRequest.fromJson(parent.contextJson)
        val probe=try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.user.localagent.compute.BrowserAgentTools.samplePairedMedia(applicationContext,
                    com.user.localagent.pcBrowserWorkKey(request))
            }
        } catch(cancel:CancellationException){throw cancel}
          catch(_:Exception){PairedMediaRead.Unavailable}
        if (app.goalWaits.store.get(record.goalId) != record) return Result.success()
        if (!app.scopedRuns.isGoalWaitReady(record)) {
            return if (app.scopedRuns.settleUnreadyGoalWait(record)) Result.success() else Result.retry()
        }
        val observation=(probe as? PairedMediaRead.Observed)?.page
        val player=observation?.player
        val moved=player!=null&&record.playingPositionMs?.let { player.positionMs>=it+100 }==true
        val lastProgress=record.lastMediaProgressAt?:record.createdAt
        val event=when {
            now-record.createdAt !in 0 until GoalWaitPolicy.MEDIA_MAX_MS -> WakeEvent.MEDIA_LIMIT
            probe==PairedMediaRead.Unavailable ->
                if(now-lastProgress>=GoalWaitPolicy.MEDIA_STALL_MS)WakeEvent.MEDIA_STALLED else null
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
            return if(app.scopedRuns.wakeGoal(observed,event))Result.success() else Result.retry()
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
            val watcher=MediaEndWatcher({ if(app.scopedRuns.isGoalWaitReady(record)) web.sampleGoalMedia(work) else null },
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
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentTitle("교육 영상이 끝나는지 확인 중")
            .setContentText("집합 앱 브라우저에서 실제 재생 상태를 확인하고 있어요.")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo(2701,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
}
