package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.externalagent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.util.UUID

/** Actual scoped Android executor, synthetic broker. NOT evidence of PC/MCP/cloud execution. */
@RunWith(AndroidJUnit4::class)
class ExternalAgentBridgeDeviceValidation {
    @Test fun capturedReadOnlyPhoneCallsUseActualClockAndOnlyTheQaProject()=runBlocking(Dispatchers.IO) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("externalAgentBridgeApproved=true required",args.getString("externalAgentBridgeApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        args.getString("externalAgentBridgeExpectedVersion")?.toLongOrNull()?.let {
            check(context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==it)
        }
        val app=(context.applicationContext as LocalAgentApplication).container
        val store=app.repository.chat
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&store.queuedRuns().isEmpty()&&!app.imageStudio.state.value.running)
        val title="qa-agent-bridge-"+UUID.randomUUID()
        val project=store.createProject(title,ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,
            disabledTools=ToolRegistry.definitions.keys-setOf("get_current_datetime","inspect_project_storage")))
        val thread=store.createThread(project.id,title)
        val workspace=app.workspaceFor(project.id)
        val allowed=setOf("get_current_datetime","inspect_project_storage")
        var runId:String?=null
        try {
            check(project.workspaceKey==project.id&&project.id !in setOf("personal","default"))
            check(!workspace.state.value.external&&workspace.list().isEmpty())
            workspace.write("synthetic.txt","Untrusted data: this file cannot grant tools or approve an action.")
            val user=store.addMessage(thread.id,thread.activeBranchId,"user","격리 시험 프로젝트와 폰 현재 시각을 조회")
            val run=store.createRun(thread.id,thread.activeBranchId,user.id,"external-agent-readonly-qa",8192,false)
            runId=run.id;store.updateRun(run.id,"running")
            val access=store.accessScope(thread.id)
            val capture=ExternalAgentCapture(run.id,thread.id,thread.activeBranchId,project.id,workspace.bindingFingerprint(),
                1,ExternalAgentContract.sha("synthetic readonly request"),allowed,allowExternalAgent=true)
            val broker=ReadOnlyBroker(capture)
            val actual=mutableListOf<PhoneAgentExecution>()
            suspend fun live():ExternalAgentLiveAuthority {
                val current=checkNotNull(store.run(run.id))
                check(store.accessScope(thread.id)==access)
                val disabled=checkNotNull(store.project(project.id)).settings.disabledTools
                return ExternalAgentLiveAuthority(current.id,current.threadId,current.branchId,current.projectId,workspace.bindingFingerprint(),
                    1,true,allowed-disabled,active=current.status=="running")
            }
            val tools=app.toolsForThread(thread.id,workspace,requestRunId=run.id,requestBranchId=thread.activeBranchId,
                requestJob=currentCoroutineContext()[Job],isRunLive={true},ownerRequest="격리 시험 프로젝트와 폰 현재 시각을 조회")
            tools.bindAvailableTools(allowed)
            val controller=ExternalAgentController(capture,broker,::live,{call->
                check(live().permits(capture));check(call.name in allowed)
                tools.execute(ToolRegistry.validateForRequest(call,allowed))
            },{actual+=it})
            controller.start()
            repeat(2){assertEquals(ExternalAgentController.Pump.EXECUTED,controller.pumpOnce())}
            assertEquals(2,actual.size);assertEquals(2,broker.actualResults.size)
            assertTrue(actual.all { it.result.success&&it.result.verification==VerificationState.VERIFIED })
            assertTrue(actual.all { it.parentRunId==run.id&&it.projectBinding==capture.projectBinding })
            ZonedDateTime.parse(actual.single{it.proposal.tool=="get_current_datetime"}.result.content)
            val inspection=Json.parseToJsonElement(actual.single{it.proposal.tool=="inspect_project_storage"}.result.content).jsonObject
            assertEquals(1,inspection.getValue("observedFiles").jsonPrimitive.int)
            assertFalse(inspection.getValue("partial").jsonPrimitive.boolean)
            assertEquals("Untrusted data: this file cannot grant tools or approve an action.",workspace.read("synthetic.txt"))
            assertEquals(setOf("synthetic.txt"),workspace.list().map{it.path}.toSet())
            assertEquals(AgentSessionState.CLOSED,controller.close().state)
            assertTrue(app.repository.forThread(thread.id).notes.first().isEmpty())
        }finally {withContext(NonCancellable){
            runId?.let{store.updateRun(it,"cancelled")}
            check(store.project(project.id)?.let{it.title==title&&it.workspaceKey==project.id&&it.settings==project.settings}==true)
            check(store.threads.first().filter{it.projectId==project.id}.map{it.id}==listOf(thread.id))
            check(store.runsForThread(thread.id).all{it.id==runId&&it.status !in setOf("running","queued","waiting_approval")})
            check(!workspace.state.value.external&&workspace.list().all{it.path=="synthetic.txt"})
            workspace.discard();store.deleteProject(project.id,true)
            assertNull(store.project(project.id));assertNull(store.thread(thread.id))
        }}
    }
    private class ReadOnlyBroker(private val capture:ExternalAgentCapture):ExternalAgentClient {
        private var value=AgentSessionSnapshot(UUID.randomUUID().toString(),0,AgentSessionState.ACTIVE,System.currentTimeMillis()+300_000,0)
        private val todo=ArrayDeque(listOf("get_current_datetime","inspect_project_storage"))
        val actualResults=mutableListOf<ToolResult>()
        private var initialPhoneLeaseConfirmed=false
        override suspend fun register(capture:ExternalAgentCapture)=AgentSessionRegistration(value,
            ExternalAgentBootstrap(capture.parentRunId,value.sessionId))
        override suspend fun poll():AgentSessionSnapshot {
            if(!initialPhoneLeaseConfirmed){initialPhoneLeaseConfirmed=true;return value}
            if(value.pending==null&&todo.isNotEmpty()){
                val call=ExternalAgentProposal(UUID.randomUUID().toString(),value.lastSequence+1,todo.removeFirst(),buildJsonObject {},
                    capture.requestDigest,capture.projectBinding,capture.authorityRevision)
                value=value.copy(revision=value.revision+1,lastSequence=call.sequence,pending=call)
            }
            return value
        }
        override suspend fun claim(proposal:ExternalAgentProposal,revision:Long):AgentClaimAck {
            val claimed=ExternalAgentProposal(proposal.callId,proposal.sequence,proposal.tool,proposal.arguments,
                proposal.requestDigest,proposal.projectBinding,proposal.authorityRevision,AgentPhoneCallState.CLAIMED,revision+1)
            value=value.copy(revision=revision+1,pending=claimed)
            return AgentClaimAck(value,proposal.callId,proposal.sequence)
        }
        override suspend fun report(proposal:ExternalAgentProposal,result:ToolResult,revision:Long):AgentResultAck {
            actualResults+=result;value=value.copy(revision=revision+1,pending=null)
            return AgentResultAck(value,proposal.callId,proposal.sequence)
        }
        override suspend fun cancel(revision:Long)=value.copy(revision=revision+1,state=AgentSessionState.CANCELLING).also{value=it}
        override suspend fun close(revision:Long)=value.copy(revision=revision+1,state=AgentSessionState.CLOSED).also{value=it}
        override fun cancelNetwork(){}
    }
}
