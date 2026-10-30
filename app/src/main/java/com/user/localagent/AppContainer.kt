package com.user.localagent

import android.content.Context
import com.user.localagent.data.LocalRepository
import com.user.localagent.inference.LocalInferenceEngine
import com.user.localagent.imports.ConversationImport
import com.user.localagent.imports.PhoneRecords
import com.user.localagent.models.ModelManager
import com.user.localagent.scheduling.ReminderScheduler
import com.user.localagent.tools.ToolExecutor
import com.user.localagent.updates.UpdateManager
import com.user.localagent.automation.AutomationController
import com.user.localagent.skills.BuiltinSkills
import com.user.localagent.models.LocalOrchestration
import kotlinx.coroutines.*
import com.user.localagent.benchmark.RuntimeGate

class AppContainer(context: Context) {
    private val appContext=context.applicationContext
    val hostCodeHash:String by lazy {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        java.io.File(appContext.applicationInfo.sourceDir).inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        digest.digest().joinToString(""){"%02x".format(it)}
    }
    val repository = LocalRepository(context.applicationContext)
    val models = ModelManager(context.applicationContext)
    val engine = LocalInferenceEngine(context.applicationContext)
    val orchestration = LocalOrchestration(context.applicationContext,models,engine)
    val scheduler = ReminderScheduler(context.applicationContext)
    val conversationImports = ConversationImport(context.applicationContext,repository)
    val phoneRecords = PhoneRecords(context.applicationContext)
    val tools = ToolExecutor(context.applicationContext, repository, scheduler,conversationImports,phoneRecords)
    val updates = UpdateManager(context.applicationContext)
    val automation = AutomationController(context.applicationContext)
    val skills = BuiltinSkills(context.applicationContext)
    private val maintenanceScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var idleUnload:Job?=null
    @Synchronized fun cancelIdleUnload(){idleUnload?.cancel();idleUnload=null}
    @Synchronized fun scheduleIdleUnload(){
        cancelIdleUnload()
        idleUnload=maintenanceScope.launch {
            delay(120_000)
            if(RuntimeGate.inference.compareAndSet(false,true))try{engine.unload()}finally{RuntimeGate.inference.set(false)}
        }
    }
}
