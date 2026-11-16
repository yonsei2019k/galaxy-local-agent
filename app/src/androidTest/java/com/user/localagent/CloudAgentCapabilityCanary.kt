package com.user.localagent

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.externalagent.ExternalAgentSettings
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real cloud phone-agent requests through the ordinary coordinator, each in its own QA project that is
 * deleted afterwards. Uses the owner's existing cloud consent (never enables it). No approval is given:
 * a request that waits for the owner is recorded as such and cancelled.
 */
@RunWith(AndroidJUnit4::class)
class CloudAgentCapabilityCanary {
    private val cases=linkedMapOf(
        "date" to "오늘 날짜 알려줘",
        "contacts" to "내 연락처에 저장된 사람이 몇 명인지 연락처를 조회해서 숫자만 알려줘",
        "calendar" to "오늘 내 일정이 몇 개인지 일정을 조회해서 알려줘",
        "calculator" to "화면 제어로 계산기 앱(패키지 com.sec.android.app.popupcalculator)을 열고, 화면을 읽은 뒤 7, +, 8, = 버튼을 차례로 눌러서 화면에 나온 결과를 알려줘")

    @Test fun realCloudRequests()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cloudCapabilityCanaryApproved")=="true")
        val selected=(args.getString("cases")?:cases.keys.joinToString(",")).split(',').map{it.trim()}.filter{it in cases}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val coordinator=app.runs;val store=app.repository.chat
        check(ExternalAgentSettings.get(context).capture().enabled){"소유자의 클라우드 동의(판2)가 필요합니다."}
        suspend fun waitFor(ms:Long,cond:suspend()->Boolean){withTimeout(ms){while(!cond())delay(100)}}
        waitFor(20_000){coordinator.selectionReady.value&&coordinator.selectedThreadId.value.isNotBlank()}
        check(!coordinator.hasActiveRuns.value&&store.queuedRuns().isEmpty()){"다른 요청이 실행 중입니다."}
        val screen=com.user.localagent.integrations.ScreenControl(context)
        val bridge=com.user.localagent.integrations.ScreenBridgeController(context).state()
        val direct=screen.execute(com.user.localagent.core.ToolCall("diag",com.user.localagent.core.ScreenControlToolCatalog.READ,buildJsonObject{}))
        val screenState=buildJsonObject {
            put("switchOn",screen.enabled());put("systemEnabled",bridge.systemEnabled);put("connected",bridge.connected)
            put("directReadSuccess",direct.success)
            // Only the app identity or the error message; never the screen text.
            put("directReadDetail",if(direct.success)runCatching{Json.parseToJsonElement(direct.content).jsonObject["packageName"]}.getOrNull()?:JsonNull else JsonPrimitive(direct.content.take(200)))
        }
        if(args.getString("screenStateOnly")=="true"){
            File(checkNotNull(context.getExternalFilesDir("diagnostics")),"cloud-capability-canary.json").writeText(screenState.toString());return@runBlocking
        }
        val originalThread=coordinator.selectedThreadId.value
        val originalModel=store.readDraft(originalThread)?.modelId
        val results=buildJsonArray {
            for(key in selected){
                val started=SystemClock.elapsedRealtime()
                val p=store.createProject("qa-cloud-cap-$key-"+System.currentTimeMillis(),com.user.localagent.data.ProjectSettings())
                var row=buildJsonObject{put("case",key)}
                try {
                    val t=store.createThread(p.id,"qa-cloud-cap-$key")
                    withContext(Dispatchers.Main.immediate){coordinator.selectThread(t.id)}
                    waitFor(20_000){coordinator.selectionReady.value&&coordinator.selectedThreadId.value==t.id}
                    withContext(Dispatchers.Main.immediate){coordinator.chooseModel(ExternalAgentSettings.MODEL_ID,t.id)}
                    waitFor(15_000){store.readDraft(t.id)?.modelId==ExternalAgentSettings.MODEL_ID}
                    check(withContext(Dispatchers.Main.immediate){coordinator.send(cases.getValue(key),t.id)})
                    waitFor(20_000){store.runsForThread(t.id).isNotEmpty()}
                    val runId=store.runsForThread(t.id).single().id
                    var waitedForOwner=false
                    withTimeoutOrNull(420_000){
                        while(true){
                            val r=checkNotNull(store.run(runId))
                            if(r.status in setOf("completed","paused","failed","cancelled"))break
                            if(coordinator.approval.value!=null){waitedForOwner=true;break}
                            delay(500)
                        }
                    }
                    if(waitedForOwner||store.run(runId)?.status !in setOf("completed","paused","failed","cancelled"))
                        withContext(Dispatchers.Main.immediate){coordinator.stopRun(runId,t.id)}
                    delay(1500)
                    val r=checkNotNull(store.run(runId))
                    val ctx=runCatching{Json.parseToJsonElement(r.contextJson).jsonObject}.getOrNull()
                    val answer=store.assistantForRun(runId)?.text.orEmpty()
                    row=buildJsonObject {
                        put("case",key);put("status",r.status);put("errorCode",r.errorCode);put("waitedForOwner",waitedForOwner)
                        put("elapsedMs",SystemClock.elapsedRealtime()-started)
                        put("planTools",ctx?.get("externalAgentPlan")?.jsonObject?.get("requiredTools")?:JsonNull)
                        put("steps",buildJsonArray{ctx?.get("previousSteps")?.jsonArray.orEmpty().forEach{s->val o=s.jsonObject
                            add(buildJsonObject{put("tool",o["tool"]?:JsonNull);put("state",o["state"]?:JsonNull);if(key=="calculator")put("summary",o["summary"]?:JsonNull)})}})
                        put("receipt",ctx?.get("externalAgentReceipt")?:JsonNull)
                        put("answer",answer.take(600))
                    }
                } catch(error:Throwable){
                    row=buildJsonObject{put("case",key);put("error",error.javaClass.simpleName+": "+(error.message?:"").take(200))}
                } finally {
                    withContext(Dispatchers.Main.immediate){coordinator.selectThread(originalThread)}
                    runCatching{waitFor(20_000){coordinator.selectionReady.value&&coordinator.selectedThreadId.value==originalThread}}
                    runCatching{withContext(Dispatchers.Main.immediate){coordinator.deleteProject(p.id,false)}}
                    runCatching{waitFor(20_000){store.project(p.id)==null}}
                }
                add(JsonObject(row+mapOf("projectRemoved" to JsonPrimitive(runCatching{store.project(p.id)==null}.getOrDefault(false)))))
            }
        }
        originalModel?.let{m->withContext(Dispatchers.Main.immediate){coordinator.chooseModel(m,originalThread)}}
        File(checkNotNull(context.getExternalFilesDir("diagnostics")),"cloud-capability-canary.json").writeText(results.toString())
    }
}
