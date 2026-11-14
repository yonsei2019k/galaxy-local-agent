package com.user.localagent

import com.user.localagent.models.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Tests the raw authenticated-load boundary, not a real backend or model. */
class PairedPcLoadWireTest {
    private fun target()=checkNotNull(PairedPcText.forRequest(PairedPcTextProfile.SELECTION_ID,7,false,7,true))
    private fun reply()=buildJsonObject {
        put("modelId",PairedPcTextProfile.MODEL_ID)
        put("modelSha256",PairedPcTextProfile.MODEL_SHA256)
        put("contextSize",8192)
        put("structuredOutput",true)
    }
    private fun rejected(value:JsonObject){
        assertTrue(runCatching{PairedPcLoadReceipt.fromAuthenticatedLoadReply(target(),value,8192)}.isFailure)
    }
    @Test fun literalWireTypesProduceTheBoundReceipt(){
        assertTrue(PairedPcLoadReceipt.fromAuthenticatedLoadReply(target(),reply(),8192).matches(target(),8192))
    }
    @Test fun stringNumbersAndBooleansAreRejectedBeforeTypeInformationIsLost(){
        rejected(JsonObject(reply()+mapOf("contextSize" to JsonPrimitive("8192"))))
        rejected(JsonObject(reply()+mapOf("structuredOutput" to JsonPrimitive("true"))))
    }
    @Test fun missingNullAndContainerFieldsCannotBecomeAReceipt(){
        for(key in reply().keys){
            rejected(JsonObject(reply()-key))
            for(value in listOf(JsonNull,JsonArray(emptyList()),JsonObject(emptyMap())))
                rejected(JsonObject(reply()+mapOf(key to value)))
        }
    }
    @Test fun incorrectProfileHashContextAndStructuredFlagAreRejected(){
        for((key,value) in mapOf("modelId" to JsonPrimitive("other"),"modelSha256" to JsonPrimitive("a".repeat(64)),
            "contextSize" to JsonPrimitive(4096),"structuredOutput" to JsonPrimitive(false)))
            rejected(JsonObject(reply()+mapOf(key to value)))
    }
}
