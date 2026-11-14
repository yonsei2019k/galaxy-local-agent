package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.core.RequiredReadbackObligations as L
import com.user.localagent.core.RequiredReadbackPolicy as P
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure serialization candidates, unexecuted by the staging author. */
class ChatReadbackObligationCodecTest {
    private fun fixture():Pair<QueuedChatRequest,L.State> {
        val context=L.Context("run","thread",null,"scope","workspace")
        val step=HarnessStep("save_note",buildJsonObject {put("title","synthetic");put("content","synthetic body")},StepEvidence("save_note",VerificationState.VERIFIED,"fixture"))
        val selected=L.select(null,context,setOf("save_note","search_notes"))
        val snapshot=P.Snapshot(P.Resource("note","synthetic-id"),"1","a".repeat(64))
        val state=L.capture(selected,context,listOf(step),listOf(P.Mutation(context.proofBinding(),0,"save_note",snapshot)),emptyList(),true)!!
        return QueuedChatRequest("run","thread","branch",null,"request","model",false,emptyList(),"user",previousSteps=listOf(step),workspaceBinding="workspace",readbackObligations=state) to state
    }
    @Test fun strictTypedMetadataRoundTripsInQueuedRequestWithoutChangingAuthority() {
        val (request,state)=fixture()
        val restored=QueuedChatRequest.fromJson(request.copy(allowTools=false,mode="plan").toJson())
        assertEquals(state,restored.readbackObligations)
        assertFalse(restored.allowTools)
        assertEquals("plan",restored.mode)
        assertFalse(ChatReadbackObligationCodec.encode(state).toString().contains("synthetic body"))
    }
    @Test fun absentLegacyMetadataStaysAbsentAndPresentNullIsRejected() {
        val (request,_)=fixture()
        val absent=request.copy(readbackObligations=null)
        assertNull(QueuedChatRequest.fromJson(absent.toJson()).readbackObligations)
        val withNull=JsonObject(Json.parseToJsonElement(absent.toJson()).jsonObject+("readbackObligations" to JsonNull))
        assertTrue(runCatching {QueuedChatRequest.fromJson(withNull.toString())}.isFailure)
    }
    @Test fun unknownFieldsStringBooleansAndDuplicatePairsAreRejected() {
        val (_,state)=fixture();val encoded=ChatReadbackObligationCodec.encode(state)
        for(bad in listOf(JsonObject(encoded+("authority" to JsonPrimitive("write"))),
            JsonObject(encoded+("invalidHostProof" to JsonPrimitive("false"))),
            JsonObject(encoded+("selectedPairs" to buildJsonArray {add("NOTE_SAVE");add("NOTE_SAVE")}))))
            assertTrue(runCatching {ChatReadbackObligationCodec.decode(bad)}.isFailure)
    }
    @Test fun readSignatureSourceReferenceAndNullTargetProofRoundTripExplicitly() {
        val (request,state)=fixture()
        val steps=request.previousSteps+HarnessStep("search_notes",buildJsonObject {put("query","synthetic")},StepEvidence("search_notes",VerificationState.VERIFIED,"empty"))
        val captured=L.capture(state,state.context,steps,emptyList(),listOf(P.Read(state.context.proofBinding(),1,"search_notes",emptyList())),true,mapOf(1 to "c".repeat(64)))!!
        val restored=ChatReadbackObligationCodec.decode(ChatReadbackObligationCodec.encode(captured))
        assertNull(restored.applied.single().latestRead!!.returned)
        assertEquals("c".repeat(64),restored.applied.single().latestRead!!.querySha256)
        assertEquals(captured,restored)
    }
    @Test fun invalidVersionBadDigestAndExcessivePayloadAreRejected() {
        val (_,state)=fixture();val encoded=ChatReadbackObligationCodec.encode(state)
        assertTrue(runCatching {ChatReadbackObligationCodec.decode(JsonObject(encoded+("version" to JsonPrimitive(2))))}.isFailure)
        val bad=encoded.toString().replace("a".repeat(64),"A".repeat(64))
        assertTrue(runCatching {ChatReadbackObligationCodec.decode(Json.parseToJsonElement(bad))}.isFailure)
        assertTrue(runCatching {ChatReadbackObligationCodec.decode(buildJsonObject {put("oversize","x".repeat(ChatReadbackObligationCodec.MAX_JSON_CHARS+1))})}.isFailure)
    }
}
