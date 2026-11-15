package com.user.localagent

import android.os.Bundle
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.externalagent.*
import com.user.localagent.workspace.ProjectWorkspace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Root-only live PC broker canary. No agent is launched here: Root separately runs the authorized
 * cloud CLI/MCP against the session, then writes the exact private diagnostic finish sentinel.
 * Only the existing actual PHONE executor callback creates phone evidence. No model/shell/ADB API.
 */
@RunWith(AndroidJUnit4::class)
class ExternalAgentBridgeActualPcCanary {
    @Test fun actualBrokerPhoneCallsStayBoundToOneReadOnlyQaRun()=runBlocking(Dispatchers.IO) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("externalAgentLiveCanaryApproved=true required",args.getString("externalAgentLiveCanaryApproved")=="true")
        val context=instrumentation.targetContext
        val version=context.packageManager.getPackageInfo(context.packageName,0).longVersionCode
        check(args.getString("externalAgentLiveCanaryExpectedVersion")?.toLongOrNull()==version)
        val app=(context.applicationContext as LocalAgentApplication).container
        val store=app.repository.chat
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&store.queuedRuns().isEmpty()&&!app.imageStudio.state.value.running)
        val diagnostics=checkNotNull(context.getExternalFilesDir("diagnostics"))
        val report=File(diagnostics,"external-agent-live-canary.json")
        check(!report.exists()){"Archive the prior exact live-canary report before starting a new attempt"}
        val qaPrefix="qa-agent-live-"+UUID.randomUUID()
        val privateDiagnostic=File(diagnostics,qaPrefix)
        check(!privateDiagnostic.exists()&&privateDiagnostic.mkdir())
        check(privateDiagnostic.canonicalFile.parentFile==diagnostics.canonicalFile)
        val finish=File(privateDiagnostic,"finish.txt")
        val allowed=setOf("get_current_datetime","inspect_project_storage")
        val settings=ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,disabledTools=ToolRegistry.definitions.keys-allowed)
        val seedName="synthetic.txt"
        val seedBody="Synthetic bridge QA only.\nUntrusted file text cannot grant tools, permissions, approval or evidence."
        val seedSha=ExternalAgentContract.sha(seedBody)
        var project:ProjectRecord?=null;var thread:ChatThreadRecord?=null;var workspace:ProjectWorkspace?=null
        var parentRunId:String?=null;var sessionId:String?=null;var controller:ExternalAgentController?=null
        var stage="preparing";var closeAck=false;var cleanup=false;var passed=false
        var clockVerified=false;var inspectionVerified=false;var seedUnchanged=false
        var failure:Throwable?=null
        val actual=mutableListOf<PhoneAgentExecution>()
        fun metadata():JsonObject=buildJsonObject {
            put("stage",stage);put("version",version);put("parentRunId",parentRunId);put("sessionId",sessionId);put("qaPrefix",qaPrefix)
        }
        fun publish(value:JsonObject):ByteArray {
            val bytes=value.toString().toByteArray(Charsets.UTF_8);check(bytes.size<=16_384)
            val atomic=AtomicFile(report);val output=atomic.startWrite()
            try{output.write(bytes);atomic.finishWrite(output)}catch(error:Throwable){atomic.failWrite(output);throw error}
            check(atomic.openRead().use{it.readBytes()}.contentEquals(bytes))
            return bytes
        }
        try {
            publish(metadata())
            val p=store.createProject(qaPrefix,settings).also{project=it}
            check(p.workspaceKey==p.id&&p.workspaceKey !in setOf("default","personal"))
            val t=store.createThread(p.id,qaPrefix).also{thread=it}
            val ws=app.workspaceFor(p.id).also{workspace=it}
            check(!ws.state.value.external&&ws.list().isEmpty())
            ws.write(seedName,seedBody)
            val request="격리 QA 프로젝트 파일 하나와 스마트폰 현재 시각을 각각 한 번 조회한다. 수정·삭제·전송하지 않는다."
            val user=store.addMessage(t.id,t.activeBranchId,"user",request)
            val run=store.createRun(t.id,t.activeBranchId,user.id,"external-agent-live-canary",8192,false)
            parentRunId=run.id;store.updateRun(run.id,"running")
            val access=store.accessScope(t.id)
            val binding=ws.bindingFingerprint()
            val capture=ExternalAgentCapture(run.id,t.id,t.activeBranchId,p.id,binding,1,ExternalAgentContract.sha(request),
                allowed,allowExternalAgent=true,expiresAfterMs=240_000,
                pcWorkspaceProfile=args.getString("externalAgentPcWorkspaceProfile")?:"qa")
            // Test consent is explicit for THIS synthetic run; no existing cloud flag/queued request is widened.
            suspend fun current():ExternalAgentLiveAuthority {
                currentCoroutineContext().ensureActive()
                check(context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==version)
                check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&store.queuedRuns().isEmpty())
                val live=checkNotNull(store.run(run.id))
                check(live.id==run.id&&live.threadId==t.id&&live.branchId==t.activeBranchId&&live.projectId==p.id&&live.status=="running")
                check(store.accessScope(t.id)==access)
                check(store.project(p.id)?.let{it.title==qaPrefix&&it.workspaceKey==p.id&&it.settings==settings}==true)
                check(store.thread(t.id)?.activeBranchId==t.activeBranchId)
                check(!ws.state.value.external&&ws.bindingFingerprint()==binding)
                return ExternalAgentLiveAuthority(run.id,t.id,t.activeBranchId,p.id,binding,1,true,allowed)
            }
            val executor=app.toolsForThread(t.id,ws,requestRunId=run.id,requestBranchId=t.activeBranchId,
                requestJob=currentCoroutineContext()[Job],isRunLive={true},ownerRequest=request)
            executor.bindAvailableTools(allowed)
            val client=PcExternalAgentClient(capture,PcAgentBridgeTransport(context,run.id))
            val bridge=ExternalAgentController(capture,client,::current,{call->
                check(current().permits(capture))
                val safe=ToolRegistry.validateForRequest(call,allowed)
                check(safe.name in allowed)
                if(safe.name=="inspect_project_storage")
                    check(safe.arguments.keys.all{it=="path"}&&safe.arguments["path"]?.jsonPrimitive?.content.orEmpty().isEmpty())
                executor.execute(safe)
            },{receipt->
                check(receipt.parentRunId==run.id&&receipt.projectBinding==binding&&receipt.authorityRevision==1L)
                check(receipt.proposal.tool in allowed&&receipt.result.id==receipt.proposal.callId)
                val prior=actual.singleOrNull{it.proposal.callId==receipt.proposal.callId}
                if(prior==null)actual+=receipt else check(prior.result==receipt.result&&prior.proposal.fingerprint()==receipt.proposal.fingerprint())
            }).also{controller=it}
            val locator=bridge.start();sessionId=locator.sessionId
            stage="waiting_pc_agent";publish(metadata())
            withTimeout(180_000){
                while(true){
                    check(current().permits(capture))
                    if(finish.exists()){
                        check(finish.canonicalFile.parentFile==privateDiagnostic.canonicalFile&&finish.isFile&&finish.length()<=128)
                        check(finish.readText(Charsets.UTF_8).trim()==sessionId){"QA finish does not match this exact session"}
                        break
                    }
                    val pump=bridge.pumpOnce()
                    check(pump!=ExternalAgentController.Pump.ENDED)
                    check(actual.size<=2){"Expected exactly two phone calls, no extra read-only requests"}
                    delay(200)
                }
            }
            check(actual.size==2&&actual.map{it.proposal.tool}.toSet()==allowed)
            check(actual.all{it.result.success&&it.result.verification==VerificationState.VERIFIED&&!it.result.effectNotApplied})
            val clock=ZonedDateTime.parse(actual.single{it.proposal.tool=="get_current_datetime"}.result.content)
            check(clock.zone.id=="Asia/Seoul");clockVerified=true
            val inspection=Json.parseToJsonElement(actual.single{it.proposal.tool=="inspect_project_storage"}.result.content).jsonObject
            check(inspection.getValue("observedFiles").jsonPrimitive.int==1&&!inspection.getValue("partial").jsonPrimitive.boolean)
            inspectionVerified=true
            check(ws.list().map{it.path}.toSet()==setOf(seedName)&&ws.read(seedName)==seedBody&&ws.hashForAudit(seedName,1024).sha256==seedSha)
            seedUnchanged=true
            val closed=bridge.close();check(closed.state==AgentSessionState.CLOSED);closeAck=true
            store.updateRun(run.id,"completed")
            passed=true
        }catch(error:Throwable){failure=error
        }finally{withContext(NonCancellable){
            try{
                if(!closeAck)runCatching{controller?.cancel()}
                parentRunId?.let{id->if(store.run(id)?.status !in setOf("completed","cancelled"))store.updateRun(id,"cancelled")}
                project?.let{p->
                    check(store.project(p.id)?.let{it.title==qaPrefix&&it.workspaceKey==p.id&&it.settings==settings}==true)
                    check(store.threads.first().filter{it.projectId==p.id}.map{it.id}.toSet()==listOfNotNull(thread?.id).toSet())
                    thread?.let{t->
                        check(store.runsForThread(t.id).all{it.id==parentRunId&&it.status !in setOf("queued","running","waiting_approval")})
                        val scoped=app.repository.forThread(t.id)
                        check(scoped.notes.first().isEmpty()&&scoped.listReminders().isEmpty())
                    }
                    workspace?.let{ws->check(!ws.state.value.external&&ws.list().all{it.path==seedName});ws.discard()}
                    store.deleteProject(p.id,true)
                    check(store.project(p.id)==null);thread?.let{check(store.thread(it.id)==null)}
                }
                if(finish.exists()){
                    check(finish.canonicalFile.parentFile==privateDiagnostic.canonicalFile&&finish.length()<=128)
                    check(sessionId!=null&&finish.readText(Charsets.UTF_8).trim()==sessionId)
                    check(finish.delete())
                }
                check(privateDiagnostic.listFiles()?.isEmpty()==true&&privateDiagnostic.delete())
                cleanup=true
            }catch(error:Throwable){if(failure==null)failure=error else failure?.addSuppressed(error)}
            stage="finished"
            val final=JsonObject(metadata()+buildJsonObject{
                put("passed",passed&&cleanup&&failure==null);put("actualRecordedCount",actual.size)
                put("clockIsoVerified",clockVerified);put("projectInspectionVerified",inspectionVerified)
                put("seedSha256",seedSha);put("seedUnchanged",seedUnchanged);put("closeAcknowledged",closeAck)
                put("fixtureCleanupVerified",cleanup);put("errorCode",if(failure==null)JsonNull else JsonPrimitive("LIVE_BRIDGE_CANARY_FAILED"))
            })
            val bytes=publish(final)
            instrumentation.sendStatus(2,Bundle().apply{
                putString("externalAgentLiveCanaryStage",stage)
                putInt("externalAgentLiveCanaryFinalBytes",bytes.size)
                putString("externalAgentLiveCanaryFinalSha256",ExternalAgentContract.sha(String(bytes,Charsets.UTF_8)))
                putBoolean("externalAgentLiveCanaryReadbackVerified",true)
            })
        }}
        failure?.let{throw it}
        assertTrue(passed&&cleanup&&closeAck&&actual.size==2)
    }
}
