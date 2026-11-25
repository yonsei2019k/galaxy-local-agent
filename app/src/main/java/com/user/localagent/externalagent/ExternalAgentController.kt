package com.user.localagent.externalagent

import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.ziphap_android.NativePhoneVerification
import uniffi.ziphap_android.nativeCanWithholdPublicRead

internal class PrivateResultWithheld(val kind: com.user.localagent.core.ConversationPrivacy.MatchKind) :
    IllegalStateException("PRIVATE_RESULT_WITHHELD")

enum class AgentSessionState { ACTIVE,CANCELLING,REVOKED,CLOSED,EXPIRED }
data class AgentSessionSnapshot(
    val sessionId:String,val revision:Long,val state:AgentSessionState,val expiresAtEpochMs:Long,
    val lastSequence:Int,val pending:ExternalAgentProposal?=null,
    val requiresPlan:Boolean=false,val plan:ExternalAgentPlan?=null,
)
/** Metadata only. Agent capability is obtained by Root's PC-local bootstrap, never by Android HTTP. */
class ExternalAgentBootstrap(val parentRunId:String,val sessionId:String) {
    override fun toString()="ExternalAgentBootstrap(private session locator)"
}
data class AgentSessionRegistration(val snapshot:AgentSessionSnapshot,val bootstrap:ExternalAgentBootstrap)
data class AgentClaimAck(val snapshot:AgentSessionSnapshot,val callId:String,val sequence:Int)
data class AgentResultAck(val snapshot:AgentSessionSnapshot,val callId:String,val sequence:Int)

/** Implementations never deserialize a server-supplied ToolResult as phone execution evidence. */
interface ExternalAgentClient {
    suspend fun register(capture:ExternalAgentCapture):AgentSessionRegistration
    suspend fun poll():AgentSessionSnapshot
    suspend fun claim(proposal:ExternalAgentProposal,revision:Long):AgentClaimAck
    suspend fun report(proposal:ExternalAgentProposal,result:ToolResult,revision:Long):AgentResultAck
    suspend fun cancel(revision:Long):AgentSessionSnapshot
    suspend fun close(revision:Long):AgentSessionSnapshot
    suspend fun acknowledgePlan(planDigest:String,accepted:Boolean):AgentSessionSnapshot=error("BRIDGE_PLAN_ACK_UNSUPPORTED")
    fun cancelNetwork()
}

/** This type is only created from the actual phone executor callback, never model/server JSON. */
class PhoneAgentExecution internal constructor(
    val parentRunId:String,val projectBinding:String,val authorityRevision:Long,
    val proposal:ExternalAgentProposal,val result:ToolResult,
    internal val withheldFromCloud:Boolean=false,
) {
    override fun toString()="PhoneAgentExecution(actual phone executor observation)"
}

/**
 * A foreground child of one captured phone request. No background authority, automatic approval,
 * tool/intent classification, unknown-effect replay, raw-input upload or persistent session resume.
 * [execute] MUST use the existing scoped permission/approval executor and recheck live authority
 * immediately before every effect. [recordActual] joins its actual outcome to the parent host ledger.
 */
class ExternalAgentController(
    private val capture:ExternalAgentCapture,private val client:ExternalAgentClient,
    private val current:suspend()->ExternalAgentLiveAuthority,
    private val execute:suspend(ToolCall)->ToolResult,
    private val recordActual:suspend(PhoneAgentExecution)->Unit,
    private val monotonicMs:()->Long={System.nanoTime()/1_000_000},
    private val heartbeatMs:Long=5000,
    private val onPlan:(suspend(ExternalAgentPlan)->Boolean)?=null,
) {
    enum class Phase { NEW,ACTIVE,EXECUTING,RESULT_PENDING,CANCELLED,UNKNOWN,CLOSED }
    enum class Pump { IDLE,PLAN_ACCEPTED,PLAN_REJECTED,EXECUTED,REPORTED_CACHED,DUPLICATE_IGNORED,ENDED }
    @Volatile var phase=Phase.NEW
        private set
    @Volatile private var locallyCancelled=false
    @Volatile private var executionJob:Deferred<ToolResult>?=null
    private val gate=Mutex()
    private var session:AgentSessionSnapshot?=null
    private var startedAt=0L
    private var highestPhoneSequence=0
    internal data class StopMetadata(val phase:Phase,val state:AgentSessionState?,val pending:AgentPhoneCallState?,val sequence:Int,
        val pendingTool:String?,val verification:String?,val resultSuccess:Boolean?,val effectNotApplied:Boolean?,
        val pendingCallId:String?=null)
    internal suspend fun stopMetadata():StopMetadata=gate.withLock {
        val call=session?.pending
        val result=call?.let{entries[it.callId]?.result}
        StopMetadata(phase,session?.state,call?.state,session?.lastSequence?:0,call?.tool,result?.verification?.name,
            result?.success,result?.effectNotApplied,call?.callId)
    }
    private class Entry(val proposal:ExternalAgentProposal,val fingerprint:String) {
        var result:ToolResult?=null
        var recorded=false
        var reported=false
    }
    private val entries=linkedMapOf<String,Entry>()
    private var pending:Entry?=null
    private var decidedPlan:ExternalAgentPlan?=null
    private var planAccepted:Boolean?=null
    init { require(heartbeatMs in 1..10_000);require(!capture.requirePlan||onPlan!=null){"BRIDGE_PLAN_CALLBACK_REQUIRED"} }

    private suspend fun fence() {
        currentCoroutineContext().ensureActive()
        check(!locallyCancelled&&capture.allowExternalAgent&&current().permits(capture)){"BRIDGE_AUTHORITY_CHANGED"}
        if(phase!=Phase.NEW){
            val elapsed=monotonicMs()-startedAt
            check(elapsed>=0&&elapsed<capture.expiresAfterMs){"BRIDGE_TTL"}
        }
    }
    private fun accept(value:AgentSessionSnapshot):AgentSessionSnapshot {
        ExternalAgentContract.uuid(value.sessionId)
        val old=session
        require(value.revision>=0&&value.lastSequence in 0..18&&value.expiresAtEpochMs>0)
        require(value.requiresPlan==capture.requirePlan)
        if(!value.requiresPlan)require(value.plan==null)
        value.plan?.checked(capture)
        old?.plan?.let{prior->
            val next=checkNotNull(value.plan)
            require(next.planDigest==prior.planDigest&&
                (prior.state==AgentPlanState.AWAITING_HOST||next.state==prior.state)){"BRIDGE_PLAN_CHANGED"}
        }
        if(old!=null)require(value.sessionId==old.sessionId&&value.revision>=old.revision&&
            value.lastSequence>=old.lastSequence&&value.expiresAtEpochMs==old.expiresAtEpochMs){"BRIDGE_STALE_SESSION"}
        session=value
        return value
    }
    private suspend fun handlePlan(snapshot:AgentSessionSnapshot):Pump? {
        if(!capture.requirePlan)return null
        val plan=snapshot.plan?:return Pump.IDLE
        val prior=decidedPlan
        if(prior!=null)require(prior.planDigest==plan.planDigest){"BRIDGE_PLAN_CHANGED"}
        if(plan.state==AgentPlanState.ACCEPTED){
            check(planAccepted==true&&prior!=null){"BRIDGE_PLAN_NOT_HOST_ACCEPTED"}
            return null
        }
        if(plan.state==AgentPlanState.REJECTED){phase=Phase.CANCELLED;return Pump.PLAN_REJECTED}
        require(snapshot.lastSequence==0&&snapshot.pending==null&&entries.isEmpty()){"BRIDGE_EFFECT_BEFORE_PLAN"}
        if(planAccepted==null){
            fence()
            // The callback must validate/select TaskGoal/readback and persist the encrypted context BEFORE true.
            val accepted=withTimeout(10_000){checkNotNull(onPlan).invoke(plan)}
            decidedPlan=plan;planAccepted=accepted
        }
        fence()
        val accepted=checkNotNull(planAccepted)
        val acknowledged=accept(client.acknowledgePlan(plan.planDigest,accepted))
        val ackPlan=checkNotNull(acknowledged.plan)
        val expected=if(accepted)AgentPlanState.ACCEPTED else AgentPlanState.REJECTED
        require(ackPlan.planDigest==plan.planDigest&&ackPlan.state==expected){"BRIDGE_PLAN_ACK"}
        if(!accepted){phase=Phase.CANCELLED;return Pump.PLAN_REJECTED}
        return Pump.PLAN_ACCEPTED
    }
    suspend fun start():ExternalAgentBootstrap=gate.withLock {
        check(phase==Phase.NEW)
        fence();startedAt=monotonicMs()
        // Registration is never blindly retried after transport failure.
        phase=Phase.UNKNOWN
        val registered=client.register(capture)
        val snapshot=accept(registered.snapshot)
        require(snapshot.state==AgentSessionState.ACTIVE&&snapshot.lastSequence==0&&snapshot.pending==null)
        require(registered.bootstrap.parentRunId==capture.parentRunId&&registered.bootstrap.sessionId==snapshot.sessionId)
        // Confirm phone capability receipt and begin the broker's phone-presence lease before gateway bootstrap.
        accept(client.poll()).also { require(it.state==AgentSessionState.ACTIVE&&it.pending==null&&it.lastSequence==0) }
        phase=Phase.ACTIVE;fence()
        registered.bootstrap
    }
    private suspend fun send(entry:Entry):Pump {
        val result=checkNotNull(entry.result)
        val kind=if(capture.allowCloudPersonalData)null else com.user.localagent.core.ConversationPrivacy.matchKind(result.content)
        val outgoing=if(kind==null)result else publicResultForCloud(entry.proposal.tool,result)
        if(!entry.recorded){
            // Preserve an actual effect even if cancellation arrives after execute returned.
            withContext(NonCancellable){withTimeout(10_000){
                recordActual(PhoneAgentExecution(capture.parentRunId,capture.projectBinding,capture.authorityRevision,
                    entry.proposal,result,withheldFromCloud=kind!=null&&outgoing?.success!=true))
            }}
            entry.recorded=true
        }
        fence()
        require(result.id==entry.proposal.callId&&result.content.toByteArray(Charsets.UTF_8).size<=ExternalAgentContract.MAX_RESULT_BYTES){"BRIDGE_RESULT_BOUND"}
        val report=outgoing?:throw PrivateResultWithheld(checkNotNull(kind))
        val ack=client.report(entry.proposal,report,checkNotNull(session).revision)
        require(ack.callId==entry.proposal.callId&&ack.sequence==entry.proposal.sequence){"BRIDGE_RESULT_ACK"}
        accept(ack.snapshot)
        entry.reported=true;pending=null
        phase=when {
            ack.snapshot.pending?.state==AgentPhoneCallState.UNKNOWN_EFFECT||ack.snapshot.state==AgentSessionState.CANCELLING->Phase.UNKNOWN
            ack.snapshot.state==AgentSessionState.ACTIVE->Phase.ACTIVE
            else->Phase.CANCELLED
        }
        return Pump.REPORTED_CACHED
    }

    private fun publicResultForCloud(tool:String,result:ToolResult):ToolResult? {
        if(tool !in ExternalAgentContract.PUBLIC_WEB_READS || !com.user.localagent.core.ToolReadPolicy.isReadOnly(tool))return null
        val verification=NativePhoneVerification.valueOf(result.verification.name)
        if(!nativeCanWithholdPublicRead(tool,result.success,verification,result.effectNotApplied))return null
        return CloudPublicSearchProjection.from(tool,result)?:ToolResult(
            result.id,false,
            "이 공개 자료는 개인정보 보호 확인을 통과하지 못해 사용할 수 없습니다. 기존 허용 도구로 다른 공개 출처를 확인하거나 이미 확인한 자료만 답변에 사용하세요.",
            VerificationState.FAILED,"PUBLIC_READ_WITHHELD",effectNotApplied=true)
    }
    suspend fun pumpOnce():Pump=gate.withLock {
        check(phase in setOf(Phase.ACTIVE,Phase.RESULT_PENDING)){"BRIDGE_NOT_ACTIVE"}
        fence()
        pending?.let { return@withLock send(it) }
        val snapshot=accept(client.poll())
        if(snapshot.state!=AgentSessionState.ACTIVE){
            phase=when {
                snapshot.state==AgentSessionState.CLOSED->Phase.CLOSED
                snapshot.state==AgentSessionState.CANCELLING||snapshot.pending?.state in setOf(AgentPhoneCallState.CLAIMED,AgentPhoneCallState.UNKNOWN_EFFECT)->Phase.UNKNOWN
                else->Phase.CANCELLED
            }
            return@withLock Pump.ENDED
        }
        handlePlan(snapshot)?.let{return@withLock it}
        val proposal=snapshot.pending?:return@withLock Pump.IDLE
        if(proposal.state!=AgentPhoneCallState.QUEUED){phase=Phase.UNKNOWN;error("BRIDGE_CLAIMED_OR_UNKNOWN_CALL")}
        val call=proposal.checkedCall(capture)
        if(capture.requirePlan)require(planAccepted==true&&proposal.tool in checkNotNull(decidedPlan).requiredTools){"BRIDGE_TOOL_NOT_IN_PLAN"}
        entries[proposal.callId]?.let { old ->
            require(old.fingerprint==proposal.fingerprint()){"BRIDGE_DUPLICATE_CONFLICT"}
            // An acknowledged duplicate is not a new execution, even if the broker repeats a poll.
            check(old.reported){"BRIDGE_UNRESOLVED_CALL"}
            return@withLock Pump.DUPLICATE_IGNORED
        }
        require(proposal.sequence>highestPhoneSequence&&proposal.sequence<=snapshot.lastSequence&&entries.size<18){"BRIDGE_SEQUENCE"}
        fence()
        val entry=Entry(proposal,proposal.fingerprint())
        entries[proposal.callId]=entry;highestPhoneSequence=proposal.sequence
        // Claim transport failure is uncertain; no execution/reclaim retry is permitted here.
        phase=Phase.UNKNOWN
        val claim=client.claim(proposal,snapshot.revision)
        require(claim.callId==proposal.callId&&claim.sequence==proposal.sequence){"BRIDGE_CLAIM_ACK"}
        val claimed=accept(claim.snapshot)
        require(claimed.state==AgentSessionState.ACTIVE)
        fence()
        phase=Phase.EXECUTING
        try {
            val actual=coroutineScope {
                val task=async { fence();execute(call) }.also { executionJob=it }
                var result:ToolResult?=null
                while(result==null){
                    result=withTimeoutOrNull(heartbeatMs){task.await()}
                    if(result==null){
                        // Read-only lease renewal while THIS claimed call awaits its own approval/effect.
                        // It does not grant approval; execute's original permission/consent fence still owns that.
                        val elapsed=monotonicMs()-startedAt
                        check(!locallyCancelled&&elapsed>=0&&elapsed<capture.expiresAfterMs&&current().sameCapturedAuthority(capture)){"BRIDGE_AUTHORITY_CHANGED"}
                        val pulse=accept(client.poll())
                        val pulseCall=checkNotNull(pulse.pending)
                        require(pulse.state==AgentSessionState.ACTIVE&&pulseCall.state==AgentPhoneCallState.CLAIMED&&
                            pulseCall.fingerprint()==proposal.fingerprint()){"BRIDGE_CLAIM_LOST"}
                    }
                }
                checkNotNull(result)
            }
            require(actual.id==proposal.callId){"BRIDGE_EXECUTOR_ID"}
            entry.result=actual;pending=entry;phase=Phase.RESULT_PENDING
            send(entry)
            Pump.EXECUTED
        }catch(error:Throwable){
            phase=if(entry.result!=null)Phase.RESULT_PENDING else Phase.UNKNOWN
            throw error
        }finally{ executionJob=null }
    }
    /** Idempotent revocation; an interrupted claimed effect remains unknown and cannot be replayed. */
    suspend fun cancel():AgentSessionSnapshot? {
        locallyCancelled=true;executionJob?.cancel(CancellationException("외부 에이전트의 폰 작업을 중지했습니다."));client.cancelNetwork()
        return gate.withLock {
            val before=session
            if(before==null){phase=Phase.CANCELLED;return@withLock null}
            val uncertain=phase in setOf(Phase.EXECUTING,Phase.UNKNOWN,Phase.RESULT_PENDING)
            phase=if(uncertain)Phase.UNKNOWN else Phase.CANCELLED
            withContext(NonCancellable){withTimeout(5000){
                accept(client.cancel(before.revision)).also { if(it.state==AgentSessionState.CANCELLING)phase=Phase.UNKNOWN }
            }}
        }
    }
    suspend fun close():AgentSessionSnapshot=gate.withLock {
        check(phase==Phase.ACTIVE&&pending==null&&entries.values.all { it.reported }){"BRIDGE_CLOSE_UNRESOLVED"}
        fence()
        val closed=accept(client.close(checkNotNull(session).revision))
        require(closed.state==AgentSessionState.CLOSED)
        phase=Phase.CLOSED
        closed
    }
}
