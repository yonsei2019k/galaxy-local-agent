package com.user.localagent.inference

import com.user.localagent.models.PairedPcText
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Host policy bindings only; no native renderer or thinking qualification is inferred. */
class PcFast4BWireContractTest {
    private val id = "synthetic-fast4b-request"
    private val fastHash = "217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14"
    private val legacyHash = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8"
    private fun target(selection: String) = checkNotNull(PairedPcText.forRequest(selection, 17, false, 17, true))
    private fun health(version: JsonPrimitive = JsonPrimitive(1), strict: JsonPrimitive = JsonPrimitive(true)) = buildJsonObject {
        put("textGenerationContract", buildJsonObject { put("wireVersion", version); put("strictThinkingBool", strict) })
    }
    private fun receipt(thinking: Boolean = false, schema: Boolean = false, cap: Int = 768) = buildJsonObject {
        val effective = thinking && !schema && cap > 512
        put("wireVersion", 1); put("requestId", id); put("modelId", "pc-text-4b"); put("modelSha256", fastHash)
        put("contextSize", 8192); put("maxOutputTokensTotal", cap + if (effective) 900 else 0)
        put("requestedThinking", thinking); put("appliedThinking", effective)
        put("extraReasoningTokens", if (effective) 900 else 0); put("legacyMayReopen", effective)
        put("preparedInputSha256", "a".repeat(64))
        put("thinkingHonored", JsonNull); put("exactRendererVerified", false); put("qualification", "unknown")
    }
    private fun matches(value: JsonObject, thinking: Boolean = false, schema: Boolean = false, cap: Int = 768) =
        pcThinkingReceiptMatches(value, id, "pc-text-4b", cap, 8192, thinking, schema)

    @Test fun fastProfileRequiresTheRawV1ContractWithoutChangingLegacyWorkerCompatibility() {
        val fast = target("paired-pc-text-fast4b-v1")
        val legacy = target("paired-pc-text-v1")
        assertTrue(pcTextProfileWireSupported(legacy, null))
        assertFalse(pcTextProfileWireSupported(fast, null))
        assertFalse(pcTextProfileWireSupported(fast, health(JsonPrimitive("1"))))
        assertFalse(pcTextProfileWireSupported(fast, health(strict = JsonPrimitive("true"))))
        assertTrue(pcTextProfileWireSupported(fast, health()))
    }

    @Test fun falseKeepsZeroExtraBudgetAcrossStructuredAndPlainBoundaryCaps() {
        for (cap in listOf(1, 128, 512, 513, 768, 2048, 4096)) for (schema in listOf(false, true)) {
            assertTrue(matches(receipt(false, schema, cap), schema = schema, cap = cap))
        }
    }

    @Test fun trueWithASchemaDoesNotPretendThatNativeThinkingWasEnabled() {
        val value = receipt(true, true, 2048)
        assertTrue(matches(value, true, true, 2048))
        assertEquals(0, value.getValue("extraReasoningTokens").jsonPrimitive.int)
        assertFalse(value.getValue("appliedThinking").jsonPrimitive.boolean)
        assertFalse(matches(JsonObject(value + ("appliedThinking" to JsonPrimitive(true))), true, true, 2048))
    }

    @Test fun plainTrueRequiresTheSameBoundedWorkerPolicyAndCannotAcceptAnUnsupportedReply() {
        assertTrue(matches(receipt(true, false, 2048), true, false, 2048))
        assertTrue(matches(receipt(true, false, 512), true, false, 512))
        val unsupported = JsonObject(receipt(true, false, 2048) + mapOf(
            "appliedThinking" to JsonPrimitive(false), "extraReasoningTokens" to JsonPrimitive(0),
            "maxOutputTokensTotal" to JsonPrimitive(2048), "legacyMayReopen" to JsonPrimitive(false),
        ))
        assertFalse(matches(unsupported, true, false, 2048))
    }

    @Test fun knownLegacyHashCannotBeUsedAsAFastReceiptOrViceVersa() {
        assertFalse(matches(JsonObject(receipt() + ("modelSha256" to JsonPrimitive(legacyHash)))))
        assertFalse(matches(JsonObject(receipt() + ("modelId" to JsonPrimitive("pc-text")))))
        assertFalse(pcThinkingReceiptMatches(receipt(), id, "pc-text", 768, 8192, false, false))
    }

    @Test fun qualificationClaimsDoNotRepairWrongSettingsRequestOrContext() {
        val claimed = JsonObject(receipt() + mapOf(
            "thinkingHonored" to JsonPrimitive(true), "exactRendererVerified" to JsonPrimitive(true),
            "qualification" to JsonPrimitive("claimed"), "appliedThinking" to JsonPrimitive(true),
        ))
        assertFalse(matches(claimed))
        assertFalse(matches(JsonObject(receipt() + ("requestId" to JsonPrimitive("another-request")))))
        assertFalse(matches(JsonObject(receipt() + ("contextSize" to JsonPrimitive(4096)))))
        assertFalse(matches(JsonObject(receipt() + ("requestedThinking" to JsonPrimitive("false")))))
    }
}
