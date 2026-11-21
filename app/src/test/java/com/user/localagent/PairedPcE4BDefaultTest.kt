package com.user.localagent

import com.user.localagent.core.ConversationPrivacy
import com.user.localagent.inference.pcTextProfileWireSupported
import com.user.localagent.models.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PairedPcE4BDefaultTest {
    private val gemma=PairedPcTextProfileDescriptor.GEMMA_E4B_V1
    private val request=QueuedChatRequest("r","t","b",null,"개인 일정 확인",
        ConversationPrivacy.LOCAL_SELECTION,false,emptyList(),"u",capturedPcRevision=7)

    private fun target(value:QueuedChatRequest, revision:Long=7, paired:Boolean=true):PairedPcText? {
        val selection=PairedPcTextProfileDescriptor.resolveRequestSelection(value.modelId,value.capturedPcTextDefault)
        return RequestInferenceSelection.pairedText(selection,value.capturedPcRevision,value.allowCloudText,
            revision,paired,value.thinking)?.model as? PairedPcText
    }

    @Test fun savedGemmaTargetRemainsBoundButCannotRunText() {
        val fresh=request.copy(capturedPcTextDefault=gemma.selectionId)
        val restored=QueuedChatRequest.fromJson(fresh.toJson())
        assertEquals(gemma.selectionId,restored.capturedPcTextDefault)
        assertEquals(InferencePrerequisiteReason.IMAGE_ONLY_MODEL,
            assertThrows(InferencePrerequisiteMissing::class.java) {target(restored)}.reason)
        for(retired in listOf(QueuedChatRequest.fromJson(request.toJson()),fresh.copy(modelId=PairedPcTextProfile.SELECTION_ID),
            fresh.copy(modelId=PairedPcFast4BProfile.SELECTION_ID))) {
            assertEquals(InferencePrerequisiteReason.PC_MODEL_RETIRED,
                assertThrows(InferencePrerequisiteMissing::class.java) {target(retired)}.reason)
        }
        assertNull(target(fresh.copy(allowCloudText=true)))
        requireSameGoalAuthority(fresh,restored)
        assertThrows(IllegalStateException::class.java) {requireSameGoalAuthority(request,restored)}
        assertThrows(IllegalArgumentException::class.java) {fresh.copy(capturedPcRevision=null)}
    }

    @Test fun directTextLoadNeverSelectsTheImageHelper() {
        val profiles=PairedPcTextProfileDescriptor
        assertEquals(InferencePrerequisiteReason.PC_MODEL_RETIRED,assertThrows(InferencePrerequisiteMissing::class.java) {
            profiles.directTextProfile(null,false)
        }.reason)
        assertEquals(InferencePrerequisiteReason.IMAGE_ONLY_MODEL,assertThrows(InferencePrerequisiteMissing::class.java) {
            profiles.directTextProfile(gemma.selectionId,true)
        }.reason)
        for(retired in listOf(null,PairedPcTextProfile.SELECTION_ID,PairedPcFast4BProfile.SELECTION_ID)) {
            assertEquals(InferencePrerequisiteReason.PC_MODEL_RETIRED,assertThrows(InferencePrerequisiteMissing::class.java) {
                profiles.directTextProfile(retired,true)
            }.reason)
        }
    }

    @Test fun unavailablePairAndRevokedRevisionPreserveThePrerequisiteFailure() {
        val fresh=request.copy(capturedPcTextDefault=gemma.selectionId)
        assertEquals(InferencePrerequisiteReason.PC_PAIRING_REQUIRED,
            assertThrows(InferencePrerequisiteMissing::class.java) {target(fresh,paired=false)}.reason)
        assertEquals(InferencePrerequisiteReason.PC_AUTHORITY_CHANGED,
            assertThrows(InferencePrerequisiteMissing::class.java) {target(fresh,revision=8)}.reason)
    }

    @Test fun imageHelperRemainsAvailableByIdentityButImportedAliasCannotRunText() {
        assertEquals("gemma-e4b",com.user.localagent.core.GemmaImagePreparation.modelId("gemma-e4b-" + gemma.modelSha256.take(16) + ".gguf"))
        assertFalse(GemmaMediaCatalog.supportsText("imported-model", gemma.modelSha256))
        assertTrue(GemmaMediaCatalog.supportsText("qwen-4b"))
    }

    @Test fun continuationAndLoadCannotSubstituteTheOldProfile() {
        val target=checkNotNull(PairedPcText.forRequest(gemma.selectionId,7,false,7,true))
        assertFalse(pcTextProfileWireSupported(target,null))
        val health=buildJsonObject {put("textGenerationContract",buildJsonObject {
            put("wireVersion",1);put("strictThinkingBool",true)
        })}
        assertTrue(pcTextProfileWireSupported(target,health))
        val receipt=PairedPcLoadReceipt.fromAuthenticatedLoad(target,gemma.modelId,gemma.modelSha256,8192,true,8192)
        assertTrue(receipt.matches(target,8192))
        assertThrows(IllegalArgumentException::class.java) {
            PairedPcLoadReceipt.fromAuthenticatedLoad(target,"pc-text",PairedPcTextProfile.MODEL_SHA256,8192,true,8192)
        }
        assertTrue(PairedPcContinuationProfileGuard.compatible(gemma.selectionId,gemma.selectionId))
        assertFalse(PairedPcContinuationProfileGuard.compatible("auto",gemma.selectionId))
        assertFalse(PairedPcContinuationProfileGuard.compatible(gemma.selectionId,PairedPcTextProfile.SELECTION_ID))
    }
}
