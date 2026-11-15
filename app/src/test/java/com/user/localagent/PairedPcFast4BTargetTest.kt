package com.user.localagent

import com.user.localagent.inference.InferenceOptions
import com.user.localagent.models.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PairedPcFast4BTargetTest {
    private val fastId = "paired-pc-text-fast4b-v1"
    private val fastHash = "217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14"
    private val legacyHash = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8"
    private fun target(id: String = fastId, revision: Long = 17) =
        checkNotNull(PairedPcText.forRequest(id, revision, false, revision, true))
    private fun reply(alias: String = "pc-text-4b", hash: String = fastHash) = buildJsonObject {
        put("modelId", alias); put("modelSha256", hash); put("contextSize", 8192); put("structuredOutput", true)
    }
    private fun rejected(value: JsonObject) = assertTrue(runCatching {
        PairedPcLoadReceipt.fromAuthenticatedLoadReply(target(), value, 8192)
    }.isFailure)
    private fun missing(reason: InferencePrerequisiteReason, block: () -> Unit) {
        try { block(); fail("Expected missing original authority") }
        catch (error: InferencePrerequisiteMissing) { assertEquals(reason, error.reason) }
    }

    @Test fun explicitFastSelectionUsesTypedPcTargetWithTheExistingOutputOptions() {
        val chosen = checkNotNull(RequestInferenceSelection.pairedText(fastId, 17, false, 17, true, false))
        assertTrue(chosen.model is PairedPcText)
        assertFalse(chosen.model is LocalWeight)
        assertEquals(fastId, chosen.model.id)
        assertEquals(InferenceOptions(contextSize = 8192, backend = "CPU", thinking = false, maxOutputTokens = 768), chosen.options)
        assertEquals(2048, checkNotNull(RequestInferenceSelection.pairedText(fastId, 17, false, 17, true, true)).options.maxOutputTokens)
        assertEquals("paired-pc-text-v1", checkNotNull(RequestInferenceSelection.pairedText("auto", 17, false, 17, true, false)).model.id)
    }

    @Test fun newProfileRequiresOriginalPairingAndCannotAcquireCloudAuthority() {
        missing(InferencePrerequisiteReason.ORIGINAL_PC_AUTHORITY_MISSING) {
            PairedPcText.forRequest(fastId, null, false, 17, true)
        }
        missing(InferencePrerequisiteReason.PC_AUTHORITY_CHANGED) {
            PairedPcText.forRequest(fastId, 16, false, 17, true)
        }
        missing(InferencePrerequisiteReason.PC_PAIRING_REQUIRED) {
            PairedPcText.forRequest(fastId, 17, false, 17, false)
        }
        assertNull(PairedPcText.forRequest(fastId, 17, true, 17, true))
        assertNull(PairedPcText.forRequest("pc-text-4b", 17, false, 17, true))
    }

    @Test fun authenticFastReceiptIsBoundToProfileFullHashContextAndOriginalRevision() {
        val receipt = PairedPcLoadReceipt.fromAuthenticatedLoadReply(target(), reply(), 8192)
        assertEquals(fastId, receipt.profileId)
        assertEquals(fastHash, receipt.modelSha256)
        assertTrue(receipt.matches(target(), 8192))
        assertFalse(receipt.matches(target(revision = 18), 8192))
        assertFalse(receipt.matches(target(), 4096))
        assertFalse(receipt.matches(target("paired-pc-text-v1"), 8192))
    }

    @Test fun twoKnownHashesAreNeverInterchangeableAcrossAliasesOrTargets() {
        rejected(reply("pc-text", legacyHash))
        rejected(reply("pc-text-4b", legacyHash))
        rejected(reply("pc-text", fastHash))
        rejected(reply("unknown", fastHash))
        rejected(reply(hash = fastHash.take(16)))
        val legacy = target("paired-pc-text-v1")
        val receipt = PairedPcLoadReceipt.fromAuthenticatedLoadReply(legacy, reply("pc-text", legacyHash), 8192)
        assertTrue(receipt.matches(legacy, 8192))
        assertFalse(receipt.matches(target(), 8192))
        assertTrue(runCatching { PairedPcLoadReceipt.fromAuthenticatedLoadReply(legacy, reply(), 8192) }.isFailure)
    }

    @Test fun malformedFastWireFieldsCannotBecomePreparationEvidence() {
        for ((key, value) in listOf(
            "modelId" to JsonPrimitive(4), "modelSha256" to JsonNull,
            "contextSize" to JsonPrimitive("8192"), "structuredOutput" to JsonPrimitive("true"),
            "contextSize" to JsonPrimitive(4096), "structuredOutput" to JsonPrimitive(false),
        )) rejected(JsonObject(reply() + (key to value)))
    }
}
