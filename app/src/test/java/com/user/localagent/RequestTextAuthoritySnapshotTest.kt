package com.user.localagent

import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.InferenceTurn
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RequestTextAuthoritySnapshotTest {
    private fun request(revision:Long?=null,cloud:Boolean=false)=QueuedChatRequest(
        "qa-run","qa-thread","qa-branch","qa-project","합성 요청 🙂","local-model",false,emptyList(),"qa-user",
        queueOrder=7,capturedPcRevision=revision,allowCloudText=cloud)
    private fun json(request:QueuedChatRequest)=Json.parseToJsonElement(request.toJson()).jsonObject
    private fun replace(original:QueuedChatRequest,key:String,value:JsonElement)=JsonObject(json(original)+mapOf(key to value)).toString()
    private fun reject(encoded:String)=assertTrue("Malformed authority must not be restored",runCatching{QueuedChatRequest.fromJson(encoded)}.isFailure)

    @Test fun explicitLocalAndCloudAuthorityRoundTripWithoutChangingOtherRequestFields(){
        for(cloud in listOf(false,true)){
            val original=request(17,cloud)
            val encoded=json(original)
            assertFalse(encoded.getValue("capturedPcRevision").jsonPrimitive.isString)
            assertFalse(encoded.getValue("allowCloudText").jsonPrimitive.isString)
            assertEquals(original,QueuedChatRequest.fromJson(encoded.toString()))
        }
    }
    @Test fun legacyMissingFieldsRestoreLocalOnlyAndKeepCapturedReadOnlyRestrictions(){
        val original=request(17,true).copy(mode="plan",allowTools=false)
        val legacy=JsonObject(json(original)-setOf("capturedPcRevision","allowCloudText"))
        val restored=QueuedChatRequest.fromJson(legacy.toString())
        assertNull(restored.capturedPcRevision);assertFalse(restored.allowCloudText)
        assertEquals("plan",restored.mode);assertFalse(restored.allowTools)
        assertEquals(original.text,restored.text);assertEquals(original.projectId,restored.projectId)
    }
    @Test fun stringsAndOtherNonBooleanValuesCannotBecomeCloudConsent(){
        for(value in listOf(JsonPrimitive("true"),JsonPrimitive("false"),JsonPrimitive(1),JsonNull,buildJsonArray{})){
            reject(replace(request(17),"allowCloudText",value))
        }
    }
    @Test fun stringsFractionsAndBooleansCannotBecomeCapturedRevision(){
        for(value in listOf(JsonPrimitive("17"),JsonPrimitive(1.5),JsonPrimitive(true),buildJsonObject{})){
            reject(replace(request(),"capturedPcRevision",value))
        }
    }
    @Test fun negativeRevisionFailsForDirectConstructionAndStoredRequests(){
        assertTrue(runCatching{request(-1)}.isFailure)
        reject(replace(request(),"capturedPcRevision",JsonPrimitive(-1)))
        assertEquals(0L,QueuedChatRequest.fromJson(request(0).toJson()).capturedPcRevision)
    }
    @Test fun cloudTrueNeedsAnExplicitCapturedRevisionInBothConstructionAndJson(){
        assertTrue(runCatching{request(cloud=true)}.isFailure)
        reject(replace(request(),"allowCloudText",JsonPrimitive(true)))
        val missing=JsonObject(json(request(17,true))-"capturedPcRevision")
        reject(missing.toString())
    }
    @Test fun copyingForSteeringKeepsOriginalAuthorityAndReadOnlyScope(){
        val original=request(17,true).copy(mode="plan",allowTools=false)
        val steered=original.copy(text=original.text+"\n추가 지시: 제목만 확인",userMessageId="qa-steer",queueOrder=9)
        val restored=QueuedChatRequest.fromJson(steered.toJson())
        assertEquals(original.capturedPcRevision,restored.capturedPcRevision)
        assertEquals(original.allowCloudText,restored.allowCloudText)
        assertFalse(restored.allowTools);assertEquals("plan",restored.mode)
        assertEquals(steered.text,restored.text)
    }
    @Test fun continuationCopyAndRestartKeepTheOriginalTopLevelTextAuthority(){
        val original=request(17,true)
        val snapshot=ContinuationSnapshot("qa-answer","local-model","이어서","합성 지침",
            listOf(InferenceTurn("user",original.text)),InferenceOptions(contextSize=8192,maxOutputTokens=768),2)
        val continued=original.copy(runId="qa-continuation",continuation=snapshot,allowTools=false,queueOrder=11)
        val restored=QueuedChatRequest.fromJson(continued.toJson())
        assertEquals(original.capturedPcRevision,restored.capturedPcRevision)
        assertEquals(original.allowCloudText,restored.allowCloudText)
        assertFalse(restored.allowTools);assertEquals(snapshot,restored.continuation)
        val legacyLocal=request().copy(continuation=snapshot)
        val legacyRestored=QueuedChatRequest.fromJson(legacyLocal.toJson())
        assertNull(legacyRestored.capturedPcRevision);assertFalse(legacyRestored.allowCloudText)
    }
}
