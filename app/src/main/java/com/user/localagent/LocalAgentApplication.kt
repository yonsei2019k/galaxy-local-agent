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
        if (getProcessName() == "$packageName:coupang_review") {
            android.webkit.WebView.setDataDirectorySuffix("coupang_review")
            return
        }
        if (!startAssistantBackgroundWork) return
        com.user.localagent.updates.UpdateManager(this).apply {setBusy(false);schedule()}
        container.callFollowUp.restore()
        container.remoteTests.start(maintenance)
        maintenance.launch {runCatching {com.user.localagent.audio.AudioChunkDecoder.cleanupOrphanedSessions(this@LocalAgentApplication)}}
    }
}
