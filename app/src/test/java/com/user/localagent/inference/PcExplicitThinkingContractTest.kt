package com.user.localagent.inference

import com.user.localagent.models.PairedPcTextProfile
import com.user.localagent.models.PairedPcTextProfileDescriptor
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure host wire bindings, not installed-template or native thinking-OFF qualification. */
class PcExplicitThinkingContractTest {
    private val id="10000000-0000-4000-8000-000000000001" // Public synthetic fixture only.
    private fun receipt(thinking:Boolean=false,schema:Boolean=false,cap:Int=1024)=buildJsonObject {
        val automatic=thinking&&!schema&&cap>512
        put("wireVersion",1);put("requestId",id);put("modelId","pc-text")
        put("modelSha256",PairedPcTextProfile.MODEL_SHA256);put("contextSize",8192)
        put("maxOutputTokensTotal",cap+if(automatic)900 else 0)
        put("requestedThinking",thinking);put("appliedThinking",automatic)
        put("extraReasoningTokens",if(automatic)900 else 0);put("legacyMayReopen",automatic)
        put("preparedInputSha256","a".repeat(64));put("thinkingHonored",JsonNull)
        put("exactRendererVerified",false);put("qualification","unknown")
    }
    private fun matches(value:JsonObject,thinking:Boolean=false,schema:Boolean=false,cap:Int=1024)=
        pcThinkingReceiptMatches(value,id,"pc-text",cap,8192,thinking,schema)
    private fun changed(value:JsonObject,key:String,replacement:JsonElement)=JsonObject(value+mapOf(key to replacement))

    @Test fun wireAdvertisementRequiresRawIntegerAndBoolean() {
        fun health(version:JsonPrimitive,strict:JsonPrimitive)=buildJsonObject {
            put("textGenerationContract",buildJsonObject {put("wireVersion",version);put("strictThinkingBool",strict)})
        }
        assertTrue(pcThinkingWireSupported(health(JsonPrimitive(1),JsonPrimitive(true))))
        assertFalse(pcThinkingWireSupported(health(JsonPrimitive("1"),JsonPrimitive(true))))
        assertFalse(pcThinkingWireSupported(health(JsonPrimitive(1),JsonPrimitive("true"))))
        assertFalse(pcThinkingWireSupported(null))
    }

    @Test fun falseHasNoAllowanceAtPlainAndStructuredBoundaryCaps() {
        for(cap in listOf(1,128,512,513,1024,4096))for(schema in listOf(false,true))
            assertTrue(matches(receipt(false,schema,cap),schema=schema,cap=cap))
    }

    @Test fun deepKeepsLegacyCapAndSchemaPolicyWithoutForgingAnUnsupportedError() {
        assertTrue(matches(receipt(true,false,2048),true,false,2048))
        assertTrue(matches(receipt(true,true,2048),true,true,2048))
        assertTrue(matches(receipt(true,false,512),true,false,512))
        assertFalse(matches(changed(receipt(true),"extraReasoningTokens",JsonPrimitive(0)),true))
    }

    @Test fun anotherRequestContextModelOrCapDoesNotMatch() {
        val good=receipt()
        for((key,value)in listOf("requestId" to JsonPrimitive("another-synthetic-request"),
            "contextSize" to JsonPrimitive(4096),"modelId" to JsonPrimitive("another-model"),
            "modelSha256" to JsonPrimitive("b".repeat(64)),"maxOutputTokensTotal" to JsonPrimitive(1924)))
            assertFalse(matches(changed(good,key,value)))
    }

    @Test fun stringFlagsMissingFieldsAndMalformedHashesAreNotAccepted() {
        val good=receipt()
        assertFalse(matches(changed(good,"requestedThinking",JsonPrimitive("false"))))
        assertFalse(matches(changed(good,"extraReasoningTokens",JsonPrimitive("0"))))
        assertFalse(matches(changed(good,"preparedInputSha256",JsonPrimitive("unknown"))))
        assertFalse(matches(JsonObject(good.filterKeys { it!="requestedThinking" })))
    }

    @Test fun falseCannotBeReinterpretedAsLegacyAutomaticThinking() {
        assertFalse(matches(changed(receipt(),"appliedThinking",JsonPrimitive(true))))
        assertFalse(matches(changed(receipt(),"legacyMayReopen",JsonPrimitive(true))))
    }

    @Test fun gemmaReceiptKeepsItsHashAndNeverReportsQwenReasoningAllowance() {
        val gemma=PairedPcTextProfileDescriptor.GEMMA_E4B_V1
        val actual=JsonObject(receipt(cap=2048)+mapOf(
            "modelId" to JsonPrimitive(gemma.modelId),"modelSha256" to JsonPrimitive(gemma.modelSha256),
            "requestedThinking" to JsonPrimitive(true)))
        assertTrue(pcThinkingReceiptMatches(actual,id,gemma.modelId,2048,8192,true,false))
        assertFalse(pcThinkingReceiptMatches(changed(actual,"modelSha256",JsonPrimitive(PairedPcTextProfile.MODEL_SHA256)),
            id,gemma.modelId,2048,8192,true,false))
        assertFalse(pcThinkingReceiptMatches(changed(actual,"appliedThinking",JsonPrimitive(true)),
            id,gemma.modelId,2048,8192,true,false))
    }
}
