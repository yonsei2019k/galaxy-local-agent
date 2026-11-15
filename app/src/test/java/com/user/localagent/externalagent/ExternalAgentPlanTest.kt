package com.user.localagent.externalagent

import com.user.localagent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Plan intent/host persistence ordering only. No GoalStore, API/provider, Android or tool effects. */
class ExternalAgentPlanTest {
    private val owner="00000000-0000-4000-8000-000000000031"
    private val session="00000000-0000-4000-8000-000000000032"
    private val callId="00000000-0000-4000-8000-000000000033"
    private fun capture(required:Boolean=true)=ExternalAgentCapture(owner,"thread","branch","project","workspace",3,"a".repeat(64),
        setOf("get_current_datetime","save_note","search_notes","read_note"),allowExternalAgent=true,requirePlan=required)
    private fun live(c:ExternalAgentCapture)=ExternalAgentLiveAuthority(c.parentRunId,c.threadId,c.branchId,c.projectId,
        c.projectBinding,c.authorityRevision,true,c.phoneTools)
    private fun plan(c:ExternalAgentCapture,state:AgentPlanState=AgentPlanState.AWAITING_HOST,
        tools:Set<String> = setOf("get_current_datetime")):ExternalAgentPlan {
        val goal="현재 시각을 조회";val constraints=listOf("다른 자료를 변경하지 않는다")
        return ExternalAgentPlan(state,ExternalAgentContract.planDigest(c,goal,tools,constraints),c.requestDigest,goal,tools,constraints)
    }
    private inner class Broker(private val capture:ExternalAgentCapture):ExternalAgentClient {
        var value=AgentSessionSnapshot(session,1,AgentSessionState.ACTIVE,100_000,0,requiresPlan=capture.requirePlan)
        var registrations=0;var acknowledgements=0;var claims=0;var persisted=false;var failAckBeforeCommit=false;var failAckAfterCommit=false
        override suspend fun register(capture:ExternalAgentCapture):AgentSessionRegistration {
            registrations++;return AgentSessionRegistration(value,ExternalAgentBootstrap(owner,session))
        }
        override suspend fun poll()=value
        fun offer(p:ExternalAgentPlan){value=value.copy(revision=value.revision+1,plan=p)}
        fun offerCall(tool:String){value=value.copy(revision=value.revision+1,lastSequence=1,pending=ExternalAgentProposal(
            callId,1,tool,if(tool=="save_note")buildJsonObject{put("content","synthetic")}else buildJsonObject {},
            capture.requestDigest,capture.projectBinding,capture.authorityRevision))}
        override suspend fun acknowledgePlan(planDigest:String,accepted:Boolean):AgentSessionSnapshot {
            check(persisted||!accepted);acknowledgements++
            if(failAckBeforeCommit){failAckBeforeCommit=false;throw java.io.IOException("synthetic ACK loss")}
            val p=checkNotNull(value.plan);check(p.planDigest==planDigest)
            value=value.copy(revision=value.revision+1,plan=ExternalAgentPlan(
                if(accepted)AgentPlanState.ACCEPTED else AgentPlanState.REJECTED,p.planDigest,p.requestDigest,p.goal,p.requiredTools,p.constraints))
            if(failAckAfterCommit){failAckAfterCommit=false;throw java.io.IOException("synthetic ACK response loss")}
            return value
        }
        override suspend fun claim(proposal:ExternalAgentProposal,revision:Long):AgentClaimAck {
            claims++;value=value.copy(revision=revision+1)
            return AgentClaimAck(value,proposal.callId,proposal.sequence)
        }
        override suspend fun report(proposal:ExternalAgentProposal,result:ToolResult,revision:Long):AgentResultAck {
            value=value.copy(revision=revision+1,pending=null)
            return AgentResultAck(value,proposal.callId,proposal.sequence)
        }
        override suspend fun cancel(revision:Long)=value.copy(state=AgentSessionState.REVOKED)
        override suspend fun close(revision:Long)=value.copy(state=AgentSessionState.CLOSED)
        override fun cancelNetwork(){}
    }
    @Test fun requirePlanFalseOmitsBothNewWireFieldsAndPlanCallbackIsRequiredBeforeRegistration() {
        val old=capture(false)
        assertFalse(old.registration().containsKey("requirePlan"))
        val c=capture();val broker=Broker(c)
        assertTrue(runCatching{ExternalAgentController(c,broker,{live(c)},{error("no effect")},{})}.isFailure)
        assertEquals(0,broker.registrations)
        assertTrue(runCatching{ExternalAgentCapture(owner,"thread","branch","project","workspace",3,"a".repeat(64),
            setOf("patch_note"),allowExternalAgent=true,requirePlan=true)}.isFailure)
    }
    @Test fun hostContextMustBePersistedBeforePlanAckAndNoActionRunsJustBecauseAPlanWasAccepted()=runBlocking {
        val c=capture();val b=Broker(c);var effects=0;var plans=0
        val controller=ExternalAgentController(c,b,{live(c)},{effects++;ToolResult(it.id,true,"actual",VerificationState.VERIFIED)},{},
            onPlan={plans++;b.persisted=true;true})
        controller.start();b.offer(plan(c))
        assertEquals(ExternalAgentController.Pump.PLAN_ACCEPTED,controller.pumpOnce())
        assertEquals(1,plans);assertEquals(1,b.acknowledgements);assertEquals(0,effects)
        b.offerCall("get_current_datetime");controller.pumpOnce();assertEquals(1,effects)
    }
    @Test fun planAckLossDoesNotRepeatTheHostSelectionAndCannotWidenTheCapturedOrSelectedTools()=runBlocking {
        for(committed in listOf(false,true)){
            val c=capture();val b=Broker(c);var plans=0;var effects=0
            b.failAckBeforeCommit=!committed;b.failAckAfterCommit=committed
            val controller=ExternalAgentController(c,b,{live(c)},{effects++;error("forbidden")},{},
                onPlan={plans++;b.persisted=true;true})
            controller.start();b.offer(plan(c))
            assertTrue(runCatching{controller.pumpOnce()}.isFailure)
            controller.pumpOnce();assertEquals(1,plans)
            b.offerCall("save_note")
            assertTrue(runCatching{controller.pumpOnce()}.isFailure);assertEquals(0,effects);assertEquals(0,b.claims)
        }
    }
    @Test fun hostRejectionAndUnsolicitedAcceptedPlanNeverGrantEffectAuthority()=runBlocking {
        for(spoofed in listOf(false,true)){
            val c=capture();val b=Broker(c);var effects=0;var callback=0
            val controller=ExternalAgentController(c,b,{live(c)},{effects++;error("forbidden")},{},
                onPlan={callback++;false})
            controller.start();b.offer(plan(c,if(spoofed)AgentPlanState.ACCEPTED else AgentPlanState.AWAITING_HOST))
            if(spoofed)assertTrue(runCatching{controller.pumpOnce()}.isFailure)
            else assertEquals(ExternalAgentController.Pump.PLAN_REJECTED,controller.pumpOnce())
            assertEquals(0,effects);assertEquals(if(spoofed)0 else 1,callback);assertEquals(0,b.claims)
        }
    }
    @Test fun planRequestBindingAndRequiredToolSubsetAreValidatedBeforeTheHostCallback()=runBlocking {
        for(wrong in listOf("request","tools")){
            val c=capture();val b=Broker(c);var callbacks=0
            val controller=ExternalAgentController(c,b,{live(c)},{error("forbidden")},{},onPlan={callbacks++;true})
            controller.start()
            b.offer(ExternalAgentPlan(AgentPlanState.AWAITING_HOST,"b".repeat(64),if(wrong=="request")"c".repeat(64)else c.requestDigest,
                "untrusted intent",if(wrong=="tools")setOf("delete_note")else setOf("get_current_datetime"),emptyList()))
            assertTrue(runCatching{controller.pumpOnce()}.isFailure);assertEquals(0,callbacks);assertEquals(0,b.claims)
        }
    }
    @Test fun emptyRequiredToolsCanRepresentAnAnswerOnlyPlanAndHasNoPersistentGoalSideEffect()=runBlocking {
        val c=capture();val b=Broker(c);var callbacks=0
        val controller=ExternalAgentController(c,b,{live(c)},{error("no tool")},{},onPlan={p->
            assertTrue(p.requiredTools.isEmpty());callbacks++;b.persisted=true;true
        })
        controller.start();b.offer(plan(c,tools=emptySet()))
        assertEquals(ExternalAgentController.Pump.PLAN_ACCEPTED,controller.pumpOnce())
        assertEquals(ExternalAgentController.Pump.IDLE,controller.pumpOnce());assertEquals(1,callbacks);assertEquals(0,b.claims)
    }
    @Test fun planBindingDigestMatchesAnIndependentPythonFixtureWithoutSortingItsLists() {
        val c=capture()
        assertEquals("6df546da979bfa3527c70dbdfc9e4b73744a1943d5d6b18a54d657cee3c030ca",
            ExternalAgentContract.planDigest(c,"현재 시각을 조회",listOf("get_current_datetime"),listOf("다른 자료를 변경하지 않는다")))
        val first=ExternalAgentContract.planDigest(c,"goal",listOf("save_note","read_note"),listOf("A","B"))
        assertNotEquals(first,ExternalAgentContract.planDigest(c,"goal",listOf("read_note","save_note"),listOf("A","B")))
        assertNotEquals(first,ExternalAgentContract.planDigest(c,"goal",listOf("save_note","read_note"),listOf("B","A")))
    }
}
