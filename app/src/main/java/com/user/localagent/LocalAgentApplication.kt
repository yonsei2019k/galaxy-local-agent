package com.user.localagent

import android.app.Application

class LocalAgentApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        com.user.localagent.updates.UpdateManager(this).apply {setBusy(false);schedule()}
    }
}
