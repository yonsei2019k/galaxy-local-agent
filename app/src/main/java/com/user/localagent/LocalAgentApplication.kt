package com.user.localagent

import android.app.Application
import kotlinx.coroutines.*

class LocalAgentApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    private val maintenance=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        com.user.localagent.updates.UpdateManager(this).apply {setBusy(false);schedule()}
        container.callFollowUp.restore()
        maintenance.launch {runCatching {com.user.localagent.audio.AudioChunkDecoder.cleanupOrphanedSessions(this@LocalAgentApplication)}}
    }
}
