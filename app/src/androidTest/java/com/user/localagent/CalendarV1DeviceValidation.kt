package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.ComputeResource
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.connections.ConnectionHub
import com.user.localagent.connections.LocalConnection
import com.user.localagent.core.*
import com.user.localagent.core.connections.*
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.models.ChosenInference
import com.user.localagent.models.InstalledModel
import java.io.IOException
import java.io.Closeable
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/**
 * Actual Android Room/Keystore/Compose/coordinator/tool/Hub/Runtime;
 * scripted inference and synthetic Calendar HTTP only. No real OAuth, token, account or network.
 * The UI harness displays actual coordinator-created PendingActions, not MainActivity's sidebar.
 */
class CalendarV1DeviceValidation {
    val compose=createAndroidComposeRule<MainActivity>()
    private var activityLease:Closeable?=null
    private val preActivity=object:ExternalResource() {
        override fun before() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("calendarV1ValidationApproved")=="true")
            val app=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LocalAgentApplication).container
            check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&runBlocking{app.repository.chat.queuedRuns().isEmpty()})
            // The existing owned gate makes MainActivity/UpdateManager defer HTTPS update checks before launch.
            // No benchmark flag, persistent update setting, other owner's lease, or network setting is changed.
            activityLease=checkNotNull(RuntimeGate.compute.tryAcquire("qa-calendar-v1-ui-"+UUID.randomUUID(),ComputeResource.PHONE))
        }
        override fun after(){activityLease?.close();activityLease=null}
    }
    @get:Rule val rules:TestRule=RuleChain.outerRule(preActivity).around(compose)

    private enum class Reply { GOOD, POST_ID, POST_MISSING, GET_ID, GET_MISSING, CANCELLED, MISSING_STATUS, UNKNOWN_STATUS, TITLE, TIME, UNKNOWN_EFFECT }
    private companion object {
        const val CREATE="create_google_calendar_event"
        const val START="2030-01-02T09:00:00+09:00"
        const val END="2030-01-02T10:00:00+09:00"
        fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
        fun LocalConnection.access()=ConnectionAccess(principal,generation,true,writeAllowed)
        val model=InstalledModel("synthetic-calendar-v1","Scripted Calendar V1","/no-model-is-loaded","0".repeat(64),true,"gguf")
        val choice=ChosenInference(model,InferenceOptions(contextSize=8192,maxOutputTokens=512,thinking=false),"Synthetic engine only; no inference provider")
    }

    private class Scripted(private val title:String,private val selector:String,private val repeat:Boolean):ComputeExecutionEngine {
        override val state=MutableStateFlow("synthetic Calendar V1")
        override val residentModelPath:String?=null
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val lastGeneration=MutableStateFlow<GenerationSummary?>(null)
        override val supportsStructuredOutput=true
        // Emulated route metadata only, as in the existing scripted coordinator fixtures; never actual PC evidence.
        override val computeLocation=MutableStateFlow<ComputeResource?>(ComputeResource.PC)
        override val waitingForResource=MutableStateFlow<ComputeResource?>(null)
        override val executionLocation="scripted-no-compute"
        val inputs=CopyOnWriteArrayList<String>();val actions=AtomicInteger()
        override suspend fun load(path:String,options:InferenceOptions){}
        override suspend fun unload(){}
        override fun cancel(){}
        override fun generate(prompt:String):Flow<String> = error("Typed events required")
        override suspend fun countChatTokens(userText:String,systemInstruction:String,history:List<InferenceTurn>)=1
        override suspend fun countStructuredChatTokens(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>)=1
        private fun capture(user:String,system:String,history:List<InferenceTurn>){inputs+=user;inputs+=system;inputs+=history.toString()}
        override fun generateChatEvents(userText:String,systemInstruction:String,history:List<InferenceTurn>):Flow<GenerationEvent> {
            capture(userText,systemInstruction,history);return events(next())
        }
        override fun generateStructuredEvents(userText:String,systemInstruction:String,schema:String,history:List<InferenceTurn>):Flow<GenerationEvent> {
            capture(userText,systemInstruction,history)
            return events(when {
                schema.contains("targetType")&&schema.contains("requiredTools")->buildJsonObject {
                    put("action","TOOL_TASK");put("goal","합성 일정 한 개 생성 후 실제 도구 결과 확인")
                    put("target","");put("publicQuery","");put("sourceKind","none");put("targetType","PERSONAL_RECORD")
                    put("requiredTools",buildJsonArray{add(CREATE)});put("constraints",buildJsonArray{})
                    put("usesPrivateData",true);put("question","")
                }.toString()
                schema.contains("review_complete")->"""{"type":"review_complete","summary":"도구의 실제 저장 근거만 보고합니다."}"""
                else->next()
            })
        }
        private fun next():String {
            val n=actions.incrementAndGet()
            if(n==1||(repeat&&n<=3))return buildJsonObject {
                put("type","tool_call");put("tool",CREATE);put("arguments",buildJsonObject {
                    put("title",title);put("start",START);put("end",END);put("calendarId",selector)
                })
            }.toString()
            return "일정을 생성했습니다. QA_SYNTHETIC_UNTRUSTED_COMPLETION"
        }
        private fun events(text:String):Flow<GenerationEvent> = flow {
            emit(GenerationEvent.Delta(text))
            val summary=GenerationSummary(UUID.randomUUID().toString(),GenerationStopReason.EOG,GenerationEvidence.HOST,false,512,visibleChars=text.length)
            lastGeneration.value=summary;emit(GenerationEvent.Finished(summary))
        }
    }

    private inner class Harness {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(base.applicationContext as LocalAgentApplication).container
        val store=app.repository.chat
        val token=UUID.randomUUID().toString();val title="qa-calendar-v1-"+token
        private val readyText="격리 Calendar 승인 대기 "+token
        var project:ProjectRecord?=null
        var runner:ChatRunCoordinator?=null
        val slots=linkedMapOf<String,Slot>();val extraThreads=mutableSetOf<String>()
        val ownedThreads=mutableSetOf<String>();val ownedRuns=linkedMapOf<String,String>();val ownedPreferences=mutableSetOf<String>()
        var uiInstalled=false
        val shown=MutableStateFlow<String?>(null)
        suspend fun ownerQuiet(){
            check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value){"Owner work active; cancel only fixture"}
            check(store.queuedRuns().none{it.id !in slots}){"Owner request queued; cancel only fixture"}
        }
        suspend fun initialize() {
            val args=InstrumentationRegistry.getArguments()
            assumeTrue("calendarV1ValidationApproved=true required",args.getString("calendarV1ValidationApproved")=="true")
            args.getString("calendarV1ExpectedVersion")?.toLongOrNull()?.let {
                check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode==it)
            }
            ownerQuiet();check(store.queuedRuns().isEmpty())
            project=store.createProject(title,ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-setOf(CREATE)))
            val coordinator=withContext(Dispatchers.Main){ChatRunCoordinator(base,app,restorePersistedQueue=false,
                inferenceChoiceOverride={choice},workerEngineFactory={request->
                    val slot=checkNotNull(slots[request.runId]);slot.checkRequest(request);slot.engine
                },workerConnectionHubFactory={request->
                    val slot=checkNotNull(slots[request.runId]);slot.checkRequest(request)
                    check(slot.hubFactories.incrementAndGet()==1){"Hub must be created once by parent for this request"};slot.hub
                },contactLookup={_,_->error("Contacts forbidden")},externalIntentLauncher={error("External Intent forbidden")})}
            runner=coordinator
            uiInstalled=true
            compose.runOnUiThread {compose.activity.setContent {MaterialTheme {
                Text(readyText)
                val actions by coordinator.pendingActions.collectAsState()
                val selected by shown.collectAsState()
                actions.firstOrNull{it.runId==selected}?.let {action->ActionApprovalDialog(action,checkNotNull(action.reviewText))}
            }}}
            // A queued setContent or an idle test clock is not proof a Compose owner exists yet.
            compose.waitUntil(timeoutMillis=5_000) {
                compose.onAllNodesWithText(readyText,useUnmergedTree=true)
                    .fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
            }
            compose.waitForIdle()
        }

        inner class Slot(val room:ChatThreadRecord,val runId:String,val messageId:String,val owner:String,
            val selector:String,val reply:Reply,val allowTools:Boolean,val ordinal:Int,val preferenceName:String) {
            val eventTitle=title+"-event-"+ordinal
            val label="synthetic-v1-"+ordinal+"@example.invalid"
            val principal="synthetic-v1-"+UUID.randomUUID().toString()
            val hubFactories=AtomicInteger();val posts=AtomicInteger();val gets=AtomicInteger()
            val requests=CopyOnWriteArrayList<ApiRequest>();val approvals=linkedSetOf<Int>()
            var actualCall:ToolCall?=null;var expectedId:String?=null;var approvedAccess:ConnectionAccess?=null
            var contextReady=false
            val engine=Scripted(eventTitle,selector,reply==Reply.UNKNOWN_EFFECT)
            private val isolated=object:ContextWrapper(base) {
                override fun getApplicationContext():Context=this
                override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                    check(name=="ziphap-connections-v1"){"Unexpected fixture preference"}
                    return base.getSharedPreferences(preferenceName,mode)
                }
            }
            val hub=ConnectionHub.isolatedCalendarForValidation(isolated){service,request,access->send(service,request,access)}
            fun initializeConnection(){hub.store.connect(ConnectedService.CALENDAR,LocalConnection(principal,label,true,0))}
            fun checkRequest(request:QueuedChatRequest) {
                check(request.runId==runId&&request.threadId==room.id&&request.branchId==room.activeBranchId&&request.projectId==project?.id)
                check(request.text==owner&&request.allowTools==allowTools&&!request.allowCloudText)
            }
            fun current()=checkNotNull(hub.store.connection(ConnectedService.CALENDAR))
            fun operation():String {
                val call=checkNotNull(actualCall)
                return sha("calendar:"+principal+"\n"+ConnectionExecutionScope.bind(call,runId).id)
            }
            fun journal()=hub.store.mutation(ConnectedService.CALENDAR,operation())
            private suspend fun send(service:ConnectedService,request:ApiRequest,access:ConnectionAccess):ApiResponse {
                ownerQuiet();currentCoroutineContext().ensureActive()
                assertEquals(ConnectedService.CALENDAR,service);assertEquals(current().access(),access)
                assertEquals(checkNotNull(approvedAccess),access)
                assertEquals(runId,checkNotNull(store.run(runId)).id)
                val id=checkNotNull(expectedId)
                val endpoint="https://www.googleapis.com/calendar/v3/calendars/"+ConnectionRequests.key(selector)+"/events"
                requests+=request
                if(request.method=="POST") {
                    assertEquals(endpoint,request.url);assertEquals(1,posts.incrementAndGet())
                    val body=Json.parseToJsonElement(checkNotNull(request.body)).jsonObject
                    assertEquals(id,body.getValue("id").jsonPrimitive.content)
                    assertEquals(eventTitle,body.getValue("summary").jsonPrimitive.content)
                    assertEquals(OffsetDateTime.parse(START).toInstant(),OffsetDateTime.parse(body.getValue("start").jsonObject.getValue("dateTime").jsonPrimitive.content).toInstant())
                    assertEquals(OffsetDateTime.parse(END).toInstant(),OffsetDateTime.parse(body.getValue("end").jsonObject.getValue("dateTime").jsonPrimitive.content).toInstant())
                    if(reply==Reply.UNKNOWN_EFFECT)throw IOException("Synthetic response lost after possible write")
                    return ApiResponse(200,buildJsonObject {if(reply!=Reply.POST_MISSING)put("id",if(reply==Reply.POST_ID)"other-event"else id)}.toString())
                }
                assertEquals("GET",request.method);assertEquals(endpoint+"/"+id,request.url);gets.incrementAndGet()
                return ApiResponse(200,buildJsonObject {
                    if(reply!=Reply.GET_MISSING)put("id",if(reply==Reply.GET_ID)"other-event"else id)
                    if(reply!=Reply.MISSING_STATUS)put("status",when(reply){Reply.CANCELLED->"cancelled";Reply.UNKNOWN_STATUS->"unknown";else->"confirmed"})
                    put("summary",if(reply==Reply.TITLE)eventTitle+" changed"else eventTitle)
                    put("start",buildJsonObject{put("dateTime",if(reply==Reply.TIME)"2030-01-02T02:00:00Z"else"2030-01-02T00:00:00Z")})
                    put("end",buildJsonObject{put("dateTime","2030-01-02T01:00:00Z")})
                }.toString())
            }
        }

        suspend fun create(reply:Reply=Reply.GOOD,selector:String="primary",allowTools:Boolean=true):Slot {
            ownerQuiet();val p=checkNotNull(project);val n=slots.size
            val room=store.createThread(p.id,title+"-room-"+n)
            ownedThreads+=room.id
            val owner="현재 요청에서 허용되면 캘린더 '"+selector+"'에 '"+title+"-event-"+n+"' 일정을 "+START+"부터 "+END+"까지 한 개 생성하고 도구로 재확인해줘."
            val message=store.addMessage(room.id,room.activeBranchId,"user",owner)
            val run=store.createRun(room.id,room.activeBranchId,message.id,model.id,8192,false)
            ownedRuns[run.id]=room.id
            val preferenceName="qa-calendar-v1-"+UUID.randomUUID().toString();ownedPreferences+=preferenceName
            val slot=Slot(room,run.id,message.id,owner,selector,reply,allowTools,n,preferenceName)
            slots[run.id]=slot
            slot.initializeConnection()
            val request=QueuedChatRequest(run.id,room.id,room.activeBranchId,p.id,owner,model.id,false,emptyList(),message.id,
                allowTools=allowTools,mode="act",workspaceBinding=app.workspaceFor(p.id).bindingFingerprint(),allowCloudText=false)
            store.updateRunContext(run.id,request.toJson())
            slot.contextReady=true
            return slot
        }
        suspend fun start(slot:Slot) {
            val coordinator=checkNotNull(runner);val request=QueuedChatRequest.fromJson(checkNotNull(store.run(slot.runId)).contextJson)
            withContext(Dispatchers.Main){coordinator.queueMutation.withLock{coordinator.queued.value=coordinator.queued.value+request};coordinator.drain()}
        }
        suspend fun pending(slot:Slot):PendingAction {
            val coordinator=checkNotNull(runner)
            val action=withTimeout(45_000){while(true){
                ownerQuiet()
                coordinator.pendingActions.value.firstOrNull{it.runId==slot.runId&&!it.decision.isCompleted}?.let{return@withTimeout it}
                check(store.run(slot.runId)?.status in setOf("queued","running","waiting_approval")){"No actual owner approval reached"}
                delay(25)
            };error("unreachable")}
            assertEquals(slot.room.id,action.threadId);assertEquals(project?.id,action.projectId);assertEquals(CREATE,action.call.name)
            assertEquals(slot.eventTitle,action.call.arguments.getValue("title").jsonPrimitive.content)
            assertTrue(checkNotNull(action.reviewText).contains("계정: "+slot.label))
            assertTrue(checkNotNull(action.reviewText).contains(slot.selector))
            slot.actualCall=action.call;slot.expectedId=checkNotNull(ConnectionRequests.build(ConnectionExecutionScope.bind(action.call,slot.runId)).knownId)
            slot.approvedAccess=slot.current().access();slot.approvals+=System.identityHashCode(action.decision)
            assertEquals(1,slot.hubFactories.get());assertEquals(0,slot.posts.get())
            compose.runOnUiThread {shown.value=slot.runId}
            // Wait for this actual bound preview; absence/duplicate/mismatched labels still fail below.
            compose.waitUntil(timeoutMillis=5_000) {
                compose.onAllNodesWithText("계정: "+slot.label,substring=true)
                    .fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithText("계정: "+slot.label,substring=true).assertExists()
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Checkbox)).assertCountEquals(0)
            return action
        }
        fun clickExecute(){compose.onNodeWithText("실행",useUnmergedTree=true).assertIsEnabled().performClick()}
        fun clickDecline(){compose.onNodeWithText("취소",useUnmergedTree=true).performClick()}
        suspend fun finish(slot:Slot):ChatRunRecord=withTimeout(45_000){
            while(true){
                ownerQuiet();val saved=checkNotNull(store.run(slot.runId))
                if(saved.status !in setOf("queued","running","waiting_approval")&&checkNotNull(runner).executionForRun(slot.runId)==null)return@withTimeout saved
                delay(25)
            };error("unreachable")
        }
        suspend fun steps(slot:Slot)=QueuedChatRequest.fromJson(checkNotNull(store.run(slot.runId)).contextJson).previousSteps
        suspend fun body(slot:Slot):String {
            val text=StringBuilder()
            for(message in store.messages(slot.room.id).first().filter{it.role=="assistant"})text.append(store.fullMessageText(message.id)).append('\n')
            return text.toString()
        }
        suspend fun assertGood(slot:Slot) {
            val saved=finish(slot);assertEquals("completed",saved.status)
            val verified=steps(slot).filter{it.evidence.state==VerificationState.VERIFIED}
            assertEquals(1,verified.size);assertEquals(CREATE,verified.single().tool)
            assertEquals(1,slot.posts.get());assertEquals(1,slot.gets.get());assertEquals(1,slot.approvals.size)
            val journal=checkNotNull(slot.journal());assertEquals("verified",journal.phase);assertEquals(slot.expectedId,journal.remoteId)
            val text=body(slot);assertTrue(text.contains("일정 생성 후 실제 ID·활성 상태"));assertTrue(text.contains("참석자 수신·수락은 확인하지 않았습니다."))
            assertFalse(text.contains("QA_SYNTHETIC_UNTRUSTED_COMPLETION"))
        }
        suspend fun assertNotCreated(slot:Slot) {
            val saved=finish(slot);assertNotEquals("completed",saved.status)
            assertFalse(steps(slot).any{it.tool==CREATE&&it.evidence.state==VerificationState.VERIFIED})
            assertFalse(body(slot).contains("일정 생성 후 실제 ID·활성 상태"))
            assertFalse(body(slot).contains("QA_SYNTHETIC_UNTRUSTED_COMPLETION"))
        }
        suspend fun assertPrivate(slot:Slot) {
            val actualSteps=steps(slot).joinToString("\n"){it.tool+it.arguments.toString()+it.evidence.summary}
            val snapshots=slot.engine.inputs+listOf(body(slot),actualSteps,app.repository.workTrace.events(slot.room.id,slot.runId).toString())
            val labels=slots.values.flatMap{listOfNotNull(it.label,it.hub.store.connection(ConnectedService.CALENDAR)?.accountName)}.toSet()
            for(label in labels)assertTrue("Owner-only synthetic account label escaped preview",snapshots.none{it.contains(label)})
            assertNull(slot.hub.store.read("token.calendar"))
        }
        suspend fun close()=withContext(NonCancellable) {
            try {
            runner?.let{coordinator->
                val jobs=withContext(Dispatchers.Main){slots.values.mapNotNull {slot->
                    coordinator.runningJobForThread(slot.room.id).also{coordinator.cancelThread(slot.room.id)}
                }}
                withTimeout(30_000){jobs.forEach{it.join()};while(coordinator.hasActiveRuns.value)delay(25)}
                coordinator.scope.cancel()
            }
            project?.let {p->
                check(p.title==title&&store.project(p.id)?.title==title&&p.workspaceKey==p.id&&p.workspaceKey !in setOf("default","personal"))
                val expected=ownedThreads+extraThreads
                check(store.threads.first().filter{it.projectId==p.id}.map{it.id}.toSet()==expected)
                expected.forEach {threadId->check(store.runsForThread(threadId).map{it.id}.toSet()==ownedRuns.filterValues{it==threadId}.keys)}
                slots.values.forEach {slot->
                    check(store.runsForThread(slot.room.id).map{it.id}==listOf(slot.runId))
                    val run=checkNotNull(store.run(slot.runId));check(run.threadId==slot.room.id&&run.projectId==p.id)
                    if(slot.contextReady){val context=QueuedChatRequest.fromJson(run.contextJson);slot.checkRequest(context)}
                    check(store.messages(slot.room.id).first().single{it.id==slot.messageId}.role=="user")
                    check(store.fullMessageText(slot.messageId)==slot.owner)
                }
                check(app.repository.reminderIdsForProjectRemoval(p.id).isEmpty())
                store.deleteProject(p.id,deleteDerivedMemory=true);app.workspaceFor(p.id).discard()
                assertNull(store.project(p.id));expected.forEach{assertNull(store.thread(it))}
            }
            } finally {
            ownedPreferences.forEach {name->check(name.startsWith("qa-calendar-v1-"));check(base.deleteSharedPreferences(name))}
            // The shared Android Keystore alias and real owner preferences are never removed.
            if(uiInstalled){
                compose.runOnUiThread{compose.activity.setContent{MaterialTheme{Text("격리 Calendar 검증 종료")}}}
                compose.waitForIdle()
            }
            }
        }
    }

    private suspend fun withHarness(block:suspend Harness.()->Unit) {
        val harness=Harness()
        var original:Throwable?=null
        try{harness.initialize();harness.block()}
        catch(error:Throwable){original=error;throw error}
        finally{try{harness.close()}catch(cleanup:Throwable){val failure=original;if(failure==null)throw cleanup else if(failure!==cleanup)failure.addSuppressed(cleanup)}}
    }

    @Test fun sameTargetUsesOneActualUiApprovalThroughTheRealWiring()=runBlocking(Dispatchers.Default){withHarness {
        val s=create();start(s);pending(s);clickExecute();assertGood(s);assertPrivate(s)
    }}
    @Test fun uiDeclineAndOriginalRunCancellationStartNoHttp()=runBlocking(Dispatchers.Default){
        for(cancelRun in listOf(false,true))withHarness {
            val s=create();start(s);pending(s)
            if(cancelRun)withContext(Dispatchers.Main){checkNotNull(runner).stopRun(s.runId,s.room.id)}else clickDecline()
            assertNotCreated(s);assertEquals(0,s.posts.get());assertEquals(0,s.gets.get());assertPrivate(s)
            assertEquals("cancelled",checkNotNull(store.run(s.runId)).status)
        }
    }
    @Test fun accountGenerationAndWriteChangesCannotInheritThePreview()=runBlocking(Dispatchers.Default){
        for(change in 0..2)withHarness {
            val s=create();start(s);pending(s);val old=s.current()
            s.hub.store.connect(ConnectedService.CALENDAR,when(change){
                0->old.copy(principal="synthetic-other-"+token,accountName="other@example.invalid")
                1->old.copy(generation=old.generation+1)
                else->old.copy(writeAllowed=false)
            })
            clickExecute();assertNotCreated(s);assertEquals(0,s.posts.get());assertEquals(0,s.gets.get());assertPrivate(s)
        }
    }
    @Test fun changedProjectScopeAndOldReadOnlyRequestCannotAcquireWrites()=runBlocking(Dispatchers.Default){
        withHarness {
            val s=create();start(s);pending(s);val p=checkNotNull(project)
            store.updateProject(p.id,settings=p.settings.copy(disabledTools=ToolRegistry.definitions.keys),expectedRevision=p.revision)
            clickExecute();assertNotCreated(s);assertEquals(0,s.posts.get());assertEquals(0,s.gets.get())
        }
        withHarness {
            val s=create(allowTools=false);start(s);finish(s)
            assertTrue(checkNotNull(runner).pendingActions.value.none{it.runId==s.runId});assertTrue(steps(s).isEmpty())
            assertEquals("paused",checkNotNull(store.run(s.runId)).status)
            assertFalse(body(s).contains("QA_SYNTHETIC_UNTRUSTED_COMPLETION"))
            assertEquals(0,s.posts.get());assertEquals(0,s.gets.get());assertFalse(body(s).contains("일정 생성 후 실제 ID·활성 상태"))
        }
    }
    @Test fun changingSelectedChatDoesNotRetargetNamedCalendarOrOriginalRun()=runBlocking(Dispatchers.Default){withHarness {
        val s=create(selector="synthetic-v1-calendar@example.invalid");start(s);pending(s)
        val p=checkNotNull(project);val decoy=store.createThread(p.id,title+"-decoy");extraThreads+=decoy.id
        withContext(Dispatchers.Main){checkNotNull(runner).selectedThreadId.value=decoy.id;checkNotNull(runner).selectedProjectId.value=p.id}
        clickExecute();assertGood(s);assertPrivate(s)
        assertTrue(store.runsForThread(decoy.id).isEmpty());assertTrue(store.messages(decoy.id).first().isEmpty())
    }}
    @Test fun mismatchedCreatedOrObservedIdentityStateAndTimesNeverVerify()=runBlocking(Dispatchers.Default){
        for(reply in listOf(Reply.POST_ID,Reply.POST_MISSING,Reply.GET_ID,Reply.GET_MISSING,Reply.CANCELLED,Reply.MISSING_STATUS,Reply.UNKNOWN_STATUS,Reply.TITLE,Reply.TIME))withHarness {
            val s=create(reply);start(s);pending(s);clickExecute();assertNotCreated(s)
            assertEquals(1,s.posts.get());assertEquals(if(reply in setOf(Reply.POST_ID,Reply.POST_MISSING))0 else 1,s.gets.get())
            assertNotEquals("verified",checkNotNull(s.journal()).phase);assertPrivate(s)
        }
    }
    @Test fun uncertainPostRetainsEncryptedJournalAndCannotAutomaticallyRepost()=runBlocking(Dispatchers.Default){withHarness {
        val s=create(Reply.UNKNOWN_EFFECT);start(s);pending(s);clickExecute();assertNotCreated(s)
        assertEquals(1,s.posts.get());assertEquals(0,s.gets.get())
        val journal=checkNotNull(s.journal());assertEquals("request_started",journal.phase);assertEquals(s.expectedId,journal.remoteId)
        assertTrue(steps(s).any{it.tool==CREATE&&it.evidence.state==VerificationState.UNVERIFIED})
        assertTrue(s.engine.actions.get()>=2);assertEquals(1,s.approvals.size);assertPrivate(s)
    }}
    @Test fun concurrentRunsKeepTheirOwnHubsMarkersAndOwnerOnlyLabels()=runBlocking(Dispatchers.Default){withHarness {
        val a=create();val b=create(selector="synthetic-v1-calendar@example.invalid")
        start(a);start(b);pending(a);clickExecute();assertGood(a);pending(b);clickExecute();assertGood(b)
        assertNotSame(a.hub,b.hub);assertNotEquals(a.expectedId,b.expectedId)
        assertEquals(1,a.hubFactories.get());assertEquals(1,b.hubFactories.get());assertPrivate(a);assertPrivate(b)
    }}
}
