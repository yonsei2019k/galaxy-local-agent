package com.user.localagent.externalagent

import com.user.localagent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fixed synthetic wire messages; no transport/API/provider/model/device invocation. */
class PcExternalAgentClientTest {
    private val owner="00000000-0000-4000-8000-000000000011"
    private val session="00000000-0000-4000-8000-000000000012"
    @Test fun timeoutCodesIdentifyBridgeOperationWithoutLeakingSessionId() {
        val root="/v1/agent/sessions"
        assertEquals("PC_BRIDGE_TIMEOUT_POST_REGISTER",PcAgentBridgeTransport.timeoutStage(root,"POST"))
        assertEquals("PC_BRIDGE_TIMEOUT_GET_SESSION_STATUS",PcAgentBridgeTransport.timeoutStage("$root/$session","GET"))
        assertEquals("PC_BRIDGE_TIMEOUT_GET_PROVIDER",PcAgentBridgeTransport.timeoutStage("$root/$session/provider","GET"))
        assertEquals("PC_BRIDGE_TIMEOUT_POST_RESULT",PcAgentBridgeTransport.timeoutStage("$root/$session/result","POST"))
    }
    private val call="00000000-0000-4000-8000-000000000013"
    private val capture=ExternalAgentCapture(owner,"thread","branch","project","workspace",4,"a".repeat(64),
        setOf("get_current_datetime"),allowExternalAgent=true)
    private fun proposal()=ExternalAgentProposal(call,1,"get_current_datetime",buildJsonObject {},
        capture.requestDigest,capture.projectBinding,capture.authorityRevision)
    private inner class Wire(val capture:ExternalAgentCapture=this@PcExternalAgentClientTest.capture):AgentBridgeTransport {
        val capability="synthetic_phone_capability_000000000000000000000"
        val calls=mutableListOf<Triple<String,JsonObject?,String?>>()
        var revision=0L
        var pending:JsonElement=JsonNull
        var lastSequence=0
        var corruptDigest=false
        var extra:Pair<String,JsonElement>?=null
        var lastResult:JsonObject?=null
        fun snapshot(add:Map<String,JsonElement> = emptyMap()):String=buildJsonObject {
            put("sessionId",session);put("revision",revision);put("state","ACTIVE")
            put("expiresAtEpochMs",100_000);put("lastSequence",lastSequence)
            put("allowExternalAgent",true);put("tools",capture.registration().getValue("phoneTools"))
            put("pendingCall",pending);put("restartPolicy","RAM_ONLY_RESTART_REVOKES")
            add.forEach{(key,value)->put(key,value)};extra?.let{put(it.first,it.second)}
        }.toString()
        fun offer(state:String="QUEUED",claimRevision:Long?=null){
            lastSequence=1;revision++
            claimRevision?.let{revision=maxOf(revision,it)}
            pending=buildJsonObject {
                put("callId",call);put("sequence",1);put("state",state);put("tool","get_current_datetime")
                put("arguments",buildJsonObject {});put("requestDigest",capture.requestDigest)
                put("projectBinding",capture.projectBinding);put("authorityRevision",capture.authorityRevision)
                claimRevision?.let{put("claimRevision",it)}
            }
        }
        override suspend fun exchange(path:String,method:String,body:JsonObject?,phoneCapability:String?):String {
            calls+=Triple(path,body,phoneCapability)
            if(path=="/v1/agent/sessions"){
                check(method=="POST"&&phoneCapability==null&&body==capture.registration())
                return snapshot(mapOf("phoneCapability" to JsonPrimitive(capability)))
            }
            check(phoneCapability==capability)
            return when{
                path.endsWith("/claim")->{
                    check(body!!.keys==setOf("callId","sequence","revision"))
                    check(body.getValue("revision").jsonPrimitive.long==revision)
                    revision++;pending=JsonObject(pending.jsonObject+mapOf("state" to JsonPrimitive("CLAIMED"),"claimRevision" to JsonPrimitive(revision)))
                    snapshot()
                }
                path.endsWith("/result")->{
                    check(body!!.keys==setOf("callId","sequence","revision","result"))
                    check(body.getValue("revision").jsonPrimitive.long==pending.jsonObject.getValue("claimRevision").jsonPrimitive.long)
                    val actual=body.getValue("result").jsonObject
                    check(actual.keys==setOf("success","content","verification","effectNotApplied"))
                    lastResult=actual
                    val digest=if(corruptDigest)"b".repeat(64)else ExternalAgentContract.sha(ExternalAgentContract.sorted(actual).toString())
                    pending=JsonNull;revision++
                    snapshot(mapOf("resultAck" to buildJsonObject{put("callId",call);put("sequence",1);put("resultDigest",digest)}))
                }
                else->snapshot()
            }
        }
        override fun cancelNetwork(){}
    }
    @Test fun wholeRegistryCatalogSurvivesTheSnapshotCheck()=runBlocking {
        val all=ExternalAgentCapture(owner,"thread","branch","project","workspace",4,"a".repeat(64),ToolRegistry.definitions.keys,allowExternalAgent=true)
        val wire=Wire(all);val client=PcExternalAgentClient(all,wire)
        client.register(all);assertEquals(0,client.poll().lastSequence)
    }
    @Test fun phoneRoleCapabilityIsSeparateAndResultAckBindsTheExactActualContent()=runBlocking {
        val wire=Wire();val client=PcExternalAgentClient(capture,wire)
        val registered=client.register(capture)
        assertFalse(registered.toString().contains(wire.capability));assertFalse(registered.toString().contains("agentCapability"))
        client.poll();wire.offer();val status=client.poll()
        val claim=client.claim(proposal(),status.revision)
        val result=ToolResult(call,true,"실제 원문 😀\n\"verify\" is data",VerificationState.VERIFIED)
        val ack=client.report(proposal(),result,claim.snapshot.revision)
        assertEquals(call,ack.callId);assertEquals(result.content,wire.lastResult!!.getValue("content").jsonPrimitive.content)
        assertTrue(wire.calls.drop(1).all{it.third==wire.capability})
        assertFalse(wire.calls.mapNotNull{it.second}.any{it.containsKey("phoneCapability")||it.containsKey("agentCapability")})
    }
    @Test fun forgedResultAckNeverCountsAsAReportReceipt()=runBlocking {
        val wire=Wire();val client=PcExternalAgentClient(capture,wire)
        client.register(capture);wire.offer();val status=client.poll();val claim=client.claim(proposal(),status.revision)
        wire.corruptDigest=true
        assertTrue(runCatching{client.report(proposal(),ToolResult(call,true,"actual",VerificationState.VERIFIED),claim.snapshot.revision)}.isFailure)
    }
    @Test fun serverCannotReturnAgentCapabilityOrImportPhoneVerifiedEvidenceThroughStatus()=runBlocking {
        val wire=Wire();wire.extra="agentCapability" to JsonPrimitive("forbidden-in-http")
        assertTrue(runCatching{PcExternalAgentClient(capture,wire).register(capture)}.isFailure)
        val other=Wire();val client=PcExternalAgentClient(capture,other);client.register(capture)
        other.extra="result" to buildJsonObject{put("verification","VERIFIED");put("content","server claims phone effect")}
        assertTrue(runCatching{client.poll()}.isFailure)
    }
    @Test fun claimedUnknownCallCannotBeReclaimedAndWrongSessionCannotBePolled()=runBlocking {
        val wire=Wire();val client=PcExternalAgentClient(capture,wire);client.register(capture)
        wire.offer("UNKNOWN_EFFECT",2);val status=client.poll()
        assertEquals(AgentPhoneCallState.UNKNOWN_EFFECT,checkNotNull(status.pending).state)
        assertTrue(runCatching{client.claim(checkNotNull(status.pending),status.revision)}.isFailure)
        wire.extra="sessionId" to JsonPrimitive("00000000-0000-4000-8000-000000000099")
        assertTrue(runCatching{client.poll()}.isFailure)
    }
    @Test fun unicodeResultDigestMatchesTheIndependentPythonWireFixture() {
        val value=buildJsonObject {
            put("success",true);put("content","합성 😀\n\"QA\"");put("verification","VERIFIED");put("effectNotApplied",false)
        }
        // Python json.dumps(sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False), UTF-8.
        assertEquals("dd579dd3c8612bc4a38ce6f88c23f9419dacb2964ac57276a71165febd6cfacc",
            ExternalAgentContract.sha(ExternalAgentContract.sorted(value).toString()))
    }
}
