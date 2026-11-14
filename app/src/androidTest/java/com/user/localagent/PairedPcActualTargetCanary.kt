package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.core.ToolRegistry
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.*
import com.user.localagent.workspace.ProjectWorkspace
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ROOT-ONLY opt-in actual PC canary. Not a clean installation or an empty-owner-model test.
 * Normal send/capture/selector/planner/writer are used; the observer delegates to the real PC engine.
 * No fake choice/verified file, phone model mutation, imported credential or automatic approval exists. */
@RunWith(AndroidJUnit4::class)
class PairedPcActualTargetCanary {
    private class ObservedEngine(private val real:AutomaticComputeEngine,private val expected:QueuedChatRequest,
        private val receipts:MutableList<PairedPcLoadReceipt>):ComputeExecutionEngine by real,PairedPcTextLoader {
        override suspend fun load(path:String,options:InferenceOptions){error("Logical PC canary reached a file loader")}
        override suspend fun loadPairedPcText(target:PairedPcText,options:InferenceOptions):PairedPcLoadReceipt {
            check(expected.modelId==PairedPcTextProfile.SELECTION_ID&&!expected.allowCloudText)
            check(expected.capturedPcRevision!=null&&expected.capturedPcRevision==target.authorizationRevision)
            val receipt=real.loadPairedPcText(target,options)
            check(receipt.matches(target,options.contextSize));receipts+=receipt
            return receipt
        }
    }

    @Test fun explicitPairedTargetPassesNormalCaptureSelectionAndActualLoad()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("pairedPcActualCanaryApproved")=="true")
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val expectedVersion=checkNotNull(args.getString("pairedPcCanaryExpectedVersion")?.toLongOrNull())
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode==expectedVersion)
        val app=(base.applicationContext as LocalAgentApplication).container
        check(!app.runs.hasActiveRuns.value&&!app.imageStudio.state.value.running&&!RuntimeGate.inference.get()&&!RuntimeGate.benchmark.get())
        val settings=PcComputeSettings.get(base)
        check(settings.snapshot()!=null&&!settings.state.value.claudeText)
        val originalRevision=settings.authorizationRevision
        val originalProject=app.runs.selectedProjectId.value
        val prefix="paired-pc-canary-"+UUID.randomUUID()
        val prefName="$prefix-chat-preferences"
        val context=object:ContextWrapper(base) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences =
                if(name=="chat-preferences")base.getSharedPreferences(prefName,mode)else super.getSharedPreferences(name,mode)
        }
        val receipts=CopyOnWriteArrayList<PairedPcLoadReceipt>()
        val captured=CopyOnWriteArrayList<QueuedChatRequest>()
        var project:ProjectRecord?=null
        var room:ChatThreadRecord?=null
        var workspace:ProjectWorkspace?=null
        var privateRoot:File?=null
        var binding:String?=null
        var coordinator:ChatRunCoordinator?=null
        var primary:Throwable?=null
        try {
            project=app.repository.chat.createProject(prefix,ProjectSettings(modelId=PairedPcTextProfile.SELECTION_ID,
                useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys))
            val ownedProject=checkNotNull(project)
            room=app.repository.chat.createThread(ownedProject.id,prefix)
            val ownedRoom=checkNotNull(room)
            val privateParent=File(base.filesDir,"projects").canonicalFile
            privateRoot=File(privateParent,ownedProject.workspaceKey).canonicalFile
            check(privateRoot?.parentFile==privateParent&&privateRoot?.name==ownedProject.workspaceKey)
            val ownedSpace=app.workspaceFor(ownedProject.id)
            workspace=ownedSpace;binding=ownedSpace.bindingFingerprint()
            check(!ownedSpace.state.value.external)
            coordinator=withContext(Dispatchers.Main.immediate){ChatRunCoordinator(context,app,restorePersistedQueue=false,
                workerEngineFactory={request->
                    check(request.projectId==ownedProject.id&&request.threadId==ownedRoom.id&&request.modelId==PairedPcTextProfile.SELECTION_ID)
                    check(request.capturedPcRevision==originalRevision&&!request.allowCloudText&&request.allowTools)
                    captured+=request
                    ObservedEngine(AutomaticComputeEngine(context,ownerRunId=request.runId,
                        beforePhoneLoad={app.engine.unloadPhoneResidency()},capturedPcRevision=request.capturedPcRevision,
                        allowCloudText=request.allowCloudText),request,receipts)
                })}
            val own=checkNotNull(coordinator)
            withContext(Dispatchers.Main.immediate){own.selectThread(ownedRoom.id)}
            withTimeout(15_000){while(!own.selectionReady.value)delay(50)}
            assertEquals(PairedPcTextProfile.SELECTION_ID,own.preferredModelId.value)
            check(withContext(Dispatchers.Main.immediate){own.send("짧게 연결 확인이라고만 답해주세요.",ownedRoom.id)})
            val run=withTimeout(600_000){
                var result:ChatRunRecord?=null
                while(result==null){
                    val rows=app.repository.chat.runsForThread(ownedRoom.id)
                    check(rows.size<=1)
                    val row=rows.singleOrNull()
                    if(row!=null&&row.status !in setOf("queued","running","waiting_approval")&&!own.hasActiveRuns.value)result=row
                    else delay(100)
                }
                checkNotNull(result)
            }
            assertEquals("completed",run.status)
            assertEquals(1,captured.size)
            val request=captured.single()
            val saved=Json.parseToJsonElement(run.contextJson).jsonObject
            assertEquals(PairedPcTextProfile.SELECTION_ID,saved["modelId"]?.jsonPrimitive?.content)
            assertEquals(originalRevision,saved.getValue("capturedPcRevision").jsonPrimitive.long)
            assertFalse(saved.getValue("allowCloudText").jsonPrimitive.boolean)
            assertTrue(receipts.isNotEmpty())
            assertTrue(receipts.all{it.profileId==PairedPcTextProfile.SELECTION_ID&&it.authorizationRevision==request.capturedPcRevision&&it.modelSha256==PairedPcTextProfile.MODEL_SHA256})
            val answer=checkNotNull(app.repository.chat.assistantForRun(run.id))
            assertTrue(app.repository.chat.fullMessageText(answer.id).isNotBlank())
            assertEquals(originalRevision,settings.authorizationRevision)
        }catch(error:Throwable){primary=error;throw error}
        finally{withContext(NonCancellable){
            try {
                coordinator?.let{own->withContext(Dispatchers.Main.immediate){own.stop()}
                    withTimeout(30_000){while(own.hasActiveRuns.value)delay(50)}
                    withTimeout(30_000){own.scope.coroutineContext[Job]?.cancelAndJoin()}
                    check(!own.hasActiveRuns.value)}
                project?.let{expected->
                    val current=checkNotNull(app.repository.chat.project(expected.id))
                    check(current.title==prefix&&current.workspaceKey==expected.workspaceKey&&current.settings==expected.settings&&!current.archived)
                    val rooms=app.repository.chat.threads.first().filter{it.projectId==expected.id}
                    check(rooms.map{it.id}==listOfNotNull(room?.id))
                    room?.let{ownedRoom->
                        val scoped=app.repository.forThread(ownedRoom.id)
                        check(scoped.notes.first().isEmpty()&&scoped.listReminders().isEmpty())
                    }
                    workspace?.let{space->check(!space.state.value.external&&space.bindingFingerprint()==binding&&space.list().isEmpty())}
                    app.repository.chat.deleteProject(expected.id,true)
                    workspace?.discard()
                    assertNull(app.repository.chat.project(expected.id))
                    privateRoot?.let{assertFalse(it.exists())}
                }
                if(app.runs.selectedProjectId.value==originalProject)app.selectProjectWorkspace(originalProject.takeIf{it.isNotBlank()})
                check(base.deleteSharedPreferences(prefName))
                assertEquals(originalRevision,settings.authorizationRevision)
            }catch(cleanup:Throwable){if(primary!=null&&primary!==cleanup)primary.addSuppressed(cleanup)else throw cleanup}
        }}
    }
}
