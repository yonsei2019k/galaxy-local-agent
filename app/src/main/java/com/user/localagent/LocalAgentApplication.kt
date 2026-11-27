package com.user.localagent

import android.app.Application
import kotlinx.coroutines.*

open class LocalAgentApplication : Application() {
    /** Image-only consumers reuse the runtime without starting assistant background work. */
    protected open val startAssistantBackgroundWork: Boolean = true
    val container: AppContainer by lazy { AppContainer(this,assistantWorkEnabled=startAssistantBackgroundWork) }
    private val maintenance=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() == "$packageName:coupang_review") {
            android.webkit.WebView.setDataDirectorySuffix("coupang_review")
            return
        }
        if (!startAssistantBackgroundWork) return
        com.user.localagent.updates.UpdateManager(this).apply {setBusy(false);schedule()}
        container.callFollowUp.restore()
        maintenance.launch {container.knowledgeIngestion.schedule()}
        maintenance.launch {
            try {container.scheduledReads.restore()} catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){ /* Dormant rows remain available to retry on next process start. */ }
        }
        com.user.localagent.analysis.AnalysisMemoryWorker.enqueue(this)
        maintenance.launch {
            try { container.goalWaits.restore() } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { FailureLog.record(this@LocalAgentApplication,"goal_wait_restore",error) }
        }
        maintenance.launch {
            com.user.localagent.compute.PcTransport.pairedReachable.collect { reachable ->
                if (reachable) try { container.goalWaits.onPcAvailable() }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { FailureLog.record(this@LocalAgentApplication,"goal_wait_pc_reconnected",error) }
            }
        }
        container.remoteTests.start(maintenance)
        maintenance.launch {runCatching {com.user.localagent.audio.AudioChunkDecoder.cleanupOrphanedSessions(this@LocalAgentApplication)}}
    }
}
