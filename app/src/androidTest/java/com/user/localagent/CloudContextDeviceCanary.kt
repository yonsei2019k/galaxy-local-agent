package com.user.localagent

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.ToolRegistry
import com.user.localagent.data.ProjectSettings
import com.user.localagent.externalagent.ExternalAgentContract
import com.user.localagent.externalagent.ExternalAgentSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual singleton send -> HTTPS -> subscription provider -> persisted reply. Synthetic text only. */
@RunWith(AndroidJUnit4::class)
class CloudContextDeviceCanary {
    @Test fun aSecondRequestRecallsOnlyItsOwnPriorCloudConversation()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cloudContextCanaryApproved")=="true")
        val context=instrumentation.targetContext
        val version=context.packageManager.getPackageInfo(context.packageName,0).longVersionCode
        check(version==2018L&&args.getString("expectedVersion")?.toLongOrNull()==version)
        val app=(context.applicationContext as LocalAgentApplication).container
        val coordinator=app.runs;val store=app.repository.chat
        suspend fun waitFor(block:suspend ()->Boolean)=withTimeout(360_000){while(!block())delay(100)}
        waitFor{coordinator.selectionReady.value&&coordinator.selectedThreadId.value.isNotBlank()}
        check(!coordinator.hasActiveRuns.value&&!coordinator.busy.value&&store.queuedRuns().isEmpty())
        check(!app.imageStudio.state.value.running&&app.chatAttachments.state.value.isEmpty())
        val originalThread=coordinator.selectedThreadId.value
        val originalProject=coordinator.selectedProjectId.value
        val originalDraft=store.readDraft(originalThread)
        val originalDraftText=coordinator.draft.value
        val originalModel=coordinator.preferredModelId.value
        val preferences=context.getSharedPreferences("chat-preferences",Context.MODE_PRIVATE)
        val originalPreferences=preferences.all.toMap()
        val external=ExternalAgentSettings.get(context)
        val originalAuthority=external.capture()
        val name="qa-context-"+UUID.randomUUID()
        val settings=ProjectSettings(modelId=ExternalAgentSettings.MODEL_ID,contextSize=8192,
            useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-setOf("get_current_datetime"))
        val project=store.createProject(name,settings)
        val thread=store.createThread(project.id,name)
        val alias="햇살회랑-"+UUID.randomUUID().toString().take(8)
        val first="이번 대화에서 프로젝트 별칭은 '$alias'야. 기억만 하고 도구는 실행하지 마."
        val second="내가 방금 정한 프로젝트 별칭은 무엇이지? 그 별칭만 답하고 도구는 실행하지 마."
        var qaAuthority:ExternalAgentSettings.Authority?=null
        var passed=false;var cleanup=false;var ownRuns=emptyList<String>()
        try {
            withContext(Dispatchers.Main.immediate){coordinator.selectThread(thread.id)}
            waitFor{coordinator.selectionReady.value&&coordinator.selectedThreadId.value==thread.id}
            withContext(Dispatchers.Main.immediate){coordinator.chooseModel(ExternalAgentSettings.MODEL_ID,thread.id)}
            waitFor{store.readDraft(thread.id)?.modelId==ExternalAgentSettings.MODEL_ID}
            qaAuthority=external.setEnabled(true)
            suspend fun send(text:String,number:Int):com.user.localagent.data.ChatRunRecord {
                check(external.capture()==qaAuthority)
                check(coordinator.selectedThreadId.value==thread.id&&coordinator.selectedProjectId.value==project.id)
                check(withContext(Dispatchers.Main.immediate){coordinator.send(text,thread.id)})
                waitFor{store.runsForThread(thread.id).size==number}
                val run=store.runsForThread(thread.id).last()
                ownRuns=ownRuns+run.id
                waitFor{store.run(run.id)?.status in setOf("completed","paused","failed","cancelled")}
                val terminal=checkNotNull(store.run(run.id))
                check(terminal.status=="completed")
                val captured=QueuedChatRequest.fromJson(terminal.contextJson)
                check(captured.text==text&&captured.threadId==thread.id&&captured.projectId==project.id)
                check(captured.previousSteps.isEmpty())
                val receipt=Json.parseToJsonElement(terminal.contextJson).jsonObject.getValue("externalAgentReceipt").jsonObject
                check(receipt.getValue("actualPhoneCalls").jsonPrimitive.int==0)
                check(receipt.getValue("sessionClosed").jsonPrimitive.boolean)
                return terminal
            }
            val firstRun=send(first,1)
            waitFor{!coordinator.busy.value&&!coordinator.hasActiveRuns.value}
            val secondRun=send(second,2)
            val payload=Json.parseToJsonElement(secondRun.contextJson).jsonObject.getValue("cloudConversationContext").jsonObject
            val turns=payload.getValue("recentTurns").jsonArray.map{it.jsonObject}
            check(turns.any{it.getValue("sourceRunId").jsonPrimitive.content==firstRun.id&&
                it.getValue("role").jsonPrimitive.content=="user"&&it.getValue("text").jsonPrimitive.content==first})
            check(turns.all{it.getValue("sourceRunId").jsonPrimitive.content==firstRun.id})
            val answer=checkNotNull(store.assistantForRun(secondRun.id))
            check(store.fullMessageText(answer.id).contains(alias))
            passed=true
        } finally {
            withContext(NonCancellable){
                val job=withContext(Dispatchers.Main.immediate){coordinator.runningJobForThread(thread.id).also{coordinator.cancelThread(thread.id)}}
                withTimeout(60_000){job?.join()}
                check(store.runsForThread(thread.id).all{it.id in ownRuns})
                check(store.readDraft(originalThread)==originalDraft)
                withContext(Dispatchers.Main.immediate){coordinator.selectThread(originalThread)}
                waitFor{coordinator.selectionReady.value&&coordinator.selectedThreadId.value==originalThread}
                check(coordinator.selectedProjectId.value==originalProject&&coordinator.draft.value==originalDraftText&&
                    coordinator.preferredModelId.value==originalModel)
                val workspace=app.workspaceFor(project.id)
                check(!workspace.state.value.external&&workspace.list().isEmpty())
                store.deleteProject(project.id,true);workspace.discard()
                val edit=preferences.edit()
                for(key in listOf("model","thread")){
                    if(originalPreferences.containsKey(key))edit.putString(key,originalPreferences[key] as? String)else edit.remove(key)
                }
                check(edit.commit()&&preferences.all==originalPreferences)
                if(external.capture()==qaAuthority)external.setEnabled(originalAuthority.enabled)
                check(external.capture().enabled==originalAuthority.enabled&&external.capture().revision>=originalAuthority.revision)
                cleanup=store.project(project.id)==null&&store.thread(thread.id)==null&&store.readDraft(originalThread)==originalDraft
            }
            instrumentation.sendStatus(2,Bundle().apply{
                putBoolean("cloudContextActualWorkflowPassed",passed&&cleanup)
                putBoolean("fixtureCleanupVerified",cleanup)
                putBoolean("ownerContentCaptured",false)
                putInt("actualPhoneCalls",0)
                putLong("versionCode",version)
            })
        }
        check(passed&&cleanup)
    }
}
