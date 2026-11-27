package com.user.localagent

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.*
import com.user.localagent.data.LocalDatabase
import com.user.localagent.data.LocalRepository
import com.user.localagent.scheduling.GoalWaitStore
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opens only a new UUID-named database and preferences, never the application repository. */
@RunWith(AndroidJUnit4::class)
class HeadlessAuthRetryReportCanary {
    @Test fun boundAuthReadAndRetrySurviveReopenWithoutDuplicateChat() = runBlocking(Dispatchers.IO) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture="auth-retry-"+UUID.randomUUID()
        val databaseName="$fixture.db"
        check(!context.getDatabasePath(databaseName).exists())
        val key=java.security.SecureRandom().generateSeed(32)
        System.loadLibrary("sqlcipher")
        fun open()=Room.databaseBuilder(context,LocalDatabase::class.java,databaseName)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf())).build()
        fun waits():GoalWaitStore {
            val preferences=context.getSharedPreferences(fixture,0)
            return GoalWaitStore({preferences.getString("wait",null)},{check(preferences.edit().putString("wait",it).commit())})
        }
        fun envelope(request:QueuedChatRequest)=JsonObject(Json.parseToJsonElement(request.toJson()).jsonObject+("goalActiveMs" to JsonPrimitive(100)))
        fun step(call:ToolCall,result:ToolResult)=HarnessStep(call,StepEvidence(call.name,result.verification,
            result.verificationSummary,effectNotApplied=result.effectNotApplied))
        val now=System.currentTimeMillis()
        val empty=JsonObject(emptyMap())
        var database=open()
        try {
            var repo=LocalRepository(database)
            val room=repo.chat.createThread(title="Synthetic retry fixture")
            val branch=room.activeBranchId
            val user=repo.chat.addMessage(room.id,branch,"user","합성 사이트를 확인해 주세요.")
            val access=repo.chat.accessScope(room.id)
            suspend fun newRun()=repo.chat.createRun(room.id,branch,user.id,"synthetic-local")
            val auth=newRun()
            val goal=GoalDefinition(threadId=room.id,outcome=user.text,criteria=listOf(GoalCriterion(text="합성 화면 확인")),
                status=GoalStatus.ACTIVE,linkedRunIds=listOf(auth.id))
            val first=GoalSegmentBinding(goal.id,0,goalWorkDigest(goal),access.scopeKey,access.threadRevision,
                access.useOwnerMemory,access.memoryEnabled,auth.id,null,null,1,now,0,0)
            val openPage=ToolCall("initial",PcBrowserToolCatalog.OPEN,buildJsonObject {put("url","https://example.org/lesson")})
            val challenge=BrowserAgentTools(PcBrowserToolCatalog,"fixture",{true}){op,_->
                assertEquals("open",op)
                buildJsonObject {put("loginRequired",true);put("nativeOwnerCard",true)}
            }.execute(openPage)
            assertEquals(BrowserAgentTools.PHONE_AUTH_REQUIRED,challenge.verificationSummary)
            val original=QueuedChatRequest(auth.id,room.id,branch,null,user.text,auth.modelId,false,emptyList(),user.id,
                goal=goal,goalSegment=first,previousSteps=listOf(step(openPage,challenge)))
            val history=ChatExecutionReport.observe(ChatExecutionReport.History(),ObservedToolOutcome(openPage,challenge))
            val released=repo.chat.updateRun(auth.id,"paused","waiting_goal_condition",JsonObject(envelope(original)+mapOf(
                ChatExecutionReport.KEY to history.json(),"hostGoalOwnerLogin" to JsonPrimitive(true),
                "hostGoalWake" to buildJsonObject {put("event",WakeEvent.OWNER_AUTH_DONE.name);put("parkedAt",now);put("wokeAt",now)}
            )).toString())

            val retry=newRun()
            val read=ToolCall("timeout",PcBrowserToolCatalog.READ,empty)
            val timeout=ToolResult(read.id,false,"{}",VerificationState.FAILED,"read_timeout_no_mutation",effectNotApplied=true)
            assertTrue(goalRetryableBrowserRead(read,timeout))
            val second=goalSegmentDecision(released,goal,access.scopeKey,now,false,useNativeBudget=true) as GoalSegmentDecision.Continue
            assertEquals(auth.id,second.binding.parentRunId)
            val retryRequest=original.copy(runId=retry.id,goal=goal.copy(linkedRunIds=listOf(auth.id,retry.id)),
                goalSegment=second.binding,goalLedger=second.ledger,previousSteps=listOf(step(read,timeout)))
            val source=checkNotNull(BrowserAuthContinuation.source(retryRequest){repo.chat.run(it)})
            assertEquals(auth.id,source.id)
            val timedHistory=ChatExecutionReport.checkpoint(retryRequest,envelope(retryRequest),ObservedToolOutcome(read,timeout),1){repo.chat.run(it)}
            val parent=repo.chat.updateRun(retry.id,"paused",GOAL_BROWSER_READ_RETRY,JsonObject(envelope(retryRequest)+mapOf(
                BrowserAuthContinuation.PENDING_SOURCE to JsonPrimitive(source.id),ChatExecutionReport.KEY to timedHistory.json()
            )).toString())
            val wait=GoalWaitRecord("wait-$fixture",goal.id,room.id,branch,retry.id,null,WaitCondition.AtTime(now+30_000,false),
                now,now,now+600_000,dayKey="synthetic",transientParentDigest=goalRetryParentDigest(parent))
            assertTrue(waits().replace(wait,null))
            val notice="합성 읽기 연결을 다시 확인할 예정이에요."
            repo.chat.noteGoalRetry(parent,"scheduled",notice)
            database.close()
            database=open()
            repo=LocalRepository(database)
            val restored=checkNotNull(repo.chat.run(retry.id))
            val reservation=checkNotNull(waits().get(goal.id))
            assertTrue(reservation.matchesTransientParent(restored))
            assertFalse(reservation.matchesTransientParent(restored.copy(branchId="other")))
            assertEquals(now+30_000,GoalWaitPolicy.dueAt(reservation))
            repo.chat.noteGoalRetry(restored,"scheduled",notice)
            assertEquals(1,repo.chat.messages(room.id,branch).first().count {it.text==notice})
            assertEquals(parent.contextJson,checkNotNull(repo.chat.run(parent.id)).contextJson)

            val child=newRun()
            val next=goalSegmentDecision(restored,checkNotNull(retryRequest.goal),access.scopeKey,now+30_000,false,useNativeBudget=true) as GoalSegmentDecision.Continue
            val resumed=retryRequest.copy(runId=child.id,goal=goal.copy(linkedRunIds=listOf(auth.id,retry.id,child.id)),
                previousSteps=emptyList(),goalSegment=next.binding,goalLedger=next.ledger)
            assertEquals(restored.id,next.binding.parentRunId)
            val inherited=checkNotNull(BrowserAuthContinuation.source(resumed){repo.chat.run(it)})
            assertEquals(auth.id,inherited.id)
            assertEquals(pcBrowserWorkKey(original),pcBrowserWorkKey(resumed))
            assertTrue(runCatching {BrowserAuthContinuation.source(resumed.copy(capturedPcRevision=7)){repo.chat.run(it)}}.isFailure)
            val required=checkNotNull(BrowserAuthContinuation.readAfterAuth(QueuedChatRequest.fromJson(inherited.contextJson).previousSteps.last()))
            assertEquals(PcBrowserToolCatalog.READ,required)
            val browser=BrowserAgentTools(PcBrowserToolCatalog,"fixture",{true}){op,_->
                assertEquals("read",op)
                buildJsonObject {
                    put("url","https://example.org/lesson?state=fixture-secret")
                    put("snapshotId","reopened");put("loginRequired",false)
                    put("playbackEvidence",buildJsonObject {put("playbackStatus","playing");put("positionMs",4000);put("durationMs",12000)})
                }
            }
            val checkPage=ToolCall("reopen-page",required,empty)
            val observed=ObservedToolOutcome(checkPage,browser.execute(checkPage))
            assertTrue(BrowserAuthContinuation.verifiedPage(observed.result))
            assertFalse(BrowserAuthContinuation.verifiedPage(observed.result.copy(content="""{"loginRequired":false}""")))
            val finalHistory=ChatExecutionReport.checkpoint(resumed,envelope(resumed),observed,1){repo.chat.run(it)}
            val finalContext=JsonObject(envelope(resumed)+(ChatExecutionReport.KEY to finalHistory.json()))
            assertEquals(finalHistory,ChatExecutionReport.checkpoint(resumed,finalContext,observed,1){repo.chat.run(it)})
            val finalRow=repo.chat.updateRun(child.id,"completed",contextJson=finalContext.toString())
            val reply=checkNotNull(repo.chat.assistantForRun(child.id))
            val visible=mapWorkActivities(room.id,listOf(WorkActivityTarget(reply.id,child.id)),mapOf(child.id to reply.id),
                mapOf(child.id to finalRow),emptyMap(),emptyMap()).getValue(reply.id).executionReport
            assertTrue(visible.first().contains("인증"))
            assertTrue(visible.any {it.contains("영상 재생 중")})
            assertTrue(visible.any {it.contains("진도율이나 이수 처리")})
            assertFalse(visible.toString().contains("fixture-secret"))
            verifyChatBundle(visible)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            context.deleteSharedPreferences(fixture)
            key.fill(0)
        }
    }

    /** Only the target APK's bundled React assets are visible to this private fixture origin. */
    private fun verifyChatBundle(lines:List<String>) {
        val test=InstrumentationRegistry.getInstrumentation()
        val origin="https://auth-retry-fixture.invalid"
        val ready=CountDownLatch(1)
        lateinit var web:WebView
        fun js(script:String):String {
            val captured=CountDownLatch(1)
            var result=""
            test.runOnMainSync {web.evaluateJavascript(script){result=it.orEmpty();captured.countDown()}}
            check(captured.await(5,TimeUnit.SECONDS))
            return result
        }
        test.runOnMainSync {
            web=WebView(test.targetContext)
            web.settings.javaScriptEnabled=true
            web.settings.allowFileAccess=false
            web.settings.allowContentAccess=false
            web.settings.blockNetworkLoads=true
            val assets=WebViewAssetLoader.Builder().setDomain("auth-retry-fixture.invalid")
                .addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(test.targetContext)).build()
            web.webViewClient=object:WebViewClient(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse =
                    assets.shouldInterceptRequest(request.url)
                        ?:WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(byteArrayOf()))
            }
            WebViewCompat.addWebMessageListener(web,"ziphapNative",setOf(origin)){_,message,_,main,_->
                if(main && org.json.JSONObject(message.data.orEmpty()).optString("action")=="ready")ready.countDown()
            }
            web.loadUrl("$origin/assets/react/index.html")
        }
        try {
            assertTrue("Installed chat assets did not initialize",ready.await(20,TimeUnit.SECONDS))
            val report=JsonArray(lines.map(::JsonPrimitive))
            val snapshot="""{threadId:'room',branchId:'branch',ready:true,draft:'',busy:false,status:'',modelId:'auto',
                modelLabel:'점검',composerEpoch:0,activeRunId:null,actionEpoch:1,singleConversation:true,
                messages:[{id:'reply',role:'assistant',text:'',executionReport:$report}],tasks:[],
                threads:[{id:'room',title:'합성 시험',selected:true}],models:[{id:'auto',label:'점검'}]}"""
            repeat(2){js("window.dispatchEvent(new CustomEvent('ziphap:snapshot',{detail:$snapshot}))")}
            val expected=report.toString()
            val predicate="""document.querySelectorAll('.execution-report').length===1 &&
                JSON.stringify([...document.querySelectorAll('.execution-report li')].map(li=>li.textContent))===JSON.stringify($expected)"""
            val limit=android.os.SystemClock.elapsedRealtime()+5_000
            while(js(predicate)!="true" && android.os.SystemClock.elapsedRealtime()<limit)Thread.sleep(50)
            assertEquals("Installed conversation did not render the report exactly once","true",js(predicate))
        } finally {
            test.runOnMainSync {web.stopLoading();web.destroy()}
        }
    }
}
