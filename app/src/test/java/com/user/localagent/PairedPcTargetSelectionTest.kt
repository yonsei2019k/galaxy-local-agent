package com.user.localagent

import com.user.localagent.inference.*
import com.user.localagent.models.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.*
import org.junit.Test

class PairedPcTargetSelectionTest {
    private fun target(revision:Long=7)=checkNotNull(PairedPcText.forRequest("auto",revision,false,revision,true))
    private fun missing(reason:InferencePrerequisiteReason,block:()->Unit) {
        try { block();fail("Expected missing prerequisite") }
        catch(e:InferencePrerequisiteMissing){assertEquals(reason,e.reason)}
    }
    private fun invalid(block:()->Unit) {
        try { block();fail("Expected invalid receipt") }catch(_:IllegalArgumentException){}
    }
    @Test fun e4bCannotBecomeAnExecutableTextTarget() {
        val profile=PairedPcTextProfileDescriptor.GEMMA_E4B_V1
        missing(InferencePrerequisiteReason.IMAGE_ONLY_MODEL) {
            RequestInferenceSelection.pairedText(profile.selectionId,7,false,7,true,false)
        }
    }
    @Test fun retiredAutoAndExplicitNineBFailBeforeReturningAnExecutionChoice() {
        for(id in listOf("auto",PairedPcTextProfile.SELECTION_ID))
            missing(InferencePrerequisiteReason.PC_MODEL_RETIRED) {
                RequestInferenceSelection.pairedText(id,7,false,7,true,true)
            }
    }
    @Test fun legacyAutoDoesNotAcquireCurrentPairing() {
        assertNull(RequestInferenceSelection.pairedText("auto",null,false,7,true,false))
    }
    @Test fun legacyExplicitPairedTargetNeedsOriginalAuthority() {
        missing(InferencePrerequisiteReason.ORIGINAL_PC_AUTHORITY_MISSING){PairedPcText.forRequest(PairedPcTextProfile.SELECTION_ID,null,false,7,true)}
    }
    @Test fun changedRevisionCannotSelectAReplacementPair() {
        missing(InferencePrerequisiteReason.PC_AUTHORITY_CHANGED){PairedPcText.forRequest("auto",6,false,7,true)}
    }
    @Test fun noPairIsAnExplicitPrerequisiteFailure() {
        missing(InferencePrerequisiteReason.PC_PAIRING_REQUIRED){PairedPcText.forRequest("auto",7,false,7,false)}
    }
    @Test fun explicitCloudAuthorityIsNotReinterpretedAsLocalPc() {
        assertNull(RequestInferenceSelection.pairedText("auto",7,true,7,true,false))
    }
    @Test fun localHelperSelectionIsNotRewrittenToPcText() {
        assertNull(RequestInferenceSelection.pairedText("gemma-e2b",7,false,7,true,false))
    }
    @Test fun unknownSelectionCannotBecomeAHostTarget() {
        assertNull(RequestInferenceSelection.pairedText("unknown-selection",7,false,7,true,false))
    }
    @Test fun expectedProfileAloneIsNotAReceipt() {
        invalid {PairedPcLoadReceipt.fromAuthenticatedLoad(target(),null,null,null,null,8192)}
    }
    @Test fun prefixOnlyHashCannotPassTheActualLoadCheck() {
        invalid {PairedPcLoadReceipt.fromAuthenticatedLoad(target(),PairedPcTextProfile.MODEL_ID,PairedPcTextProfile.MODEL_SHA256.take(16),8192,true,8192)}
    }
    @Test fun wrongModelOrContextOrStructuredSupportCannotPass() {
        invalid {PairedPcLoadReceipt.fromAuthenticatedLoad(target(),"unknown-model",PairedPcTextProfile.MODEL_SHA256,8192,true,8192)}
        invalid {PairedPcLoadReceipt.fromAuthenticatedLoad(target(),PairedPcTextProfile.MODEL_ID,PairedPcTextProfile.MODEL_SHA256,4096,true,8192)}
        invalid {PairedPcLoadReceipt.fromAuthenticatedLoad(target(),PairedPcTextProfile.MODEL_ID,PairedPcTextProfile.MODEL_SHA256,8192,false,8192)}
    }
    @Test fun actualReceiptIsBoundToOriginalRevisionAndContext() {
        val receipt=PairedPcLoadReceipt.fromAuthenticatedLoad(target(),PairedPcTextProfile.MODEL_ID,PairedPcTextProfile.MODEL_SHA256,8192,true,8192)
        assertTrue(receipt.matches(target(),8192))
        assertFalse(receipt.matches(target(8),8192))
        assertFalse(receipt.matches(target(),4096))
    }
    @Test fun typedLoaderNeverUsesAPlaceholderLocalFile()=runBlocking {
        val engine=PairedPcTargetRecordingEngine()
        val receipt=engine.loadTarget(target(),InferenceOptions(contextSize=8192))
        assertTrue(checkNotNull(receipt).matches(target(),8192))
        assertEquals(1,engine.typedLoads);assertEquals(0,engine.pathLoads)
    }
    @Test fun mismatchedTypedReceiptIsRejectedBeforeGeneration()=runBlocking {
        val engine=PairedPcTargetRecordingEngine().apply{receiptForDifferentRevision=true}
        try {engine.loadTarget(target(),InferenceOptions(contextSize=8192));fail("Expected receipt mismatch")}
        catch(_:IllegalStateException){}
        assertEquals(1,engine.typedLoads);assertEquals(0,engine.pathLoads);assertEquals(0,engine.generations)
    }
    @Test fun aPathOnlyEngineCannotInterpretTheLogicalTarget()=runBlocking {
        var pathLoads=0
        val engine=object:InferenceEngine {
            override val state=MutableStateFlow("")
            override val residentModelPath:String?=null
            override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
            override suspend fun load(path:String,options:InferenceOptions){pathLoads++}
            override fun generate(prompt:String):Flow<String> = error("Unexpected generation")
            override suspend fun unload(){}
            override fun cancel(){}
        }
        try {engine.loadTarget(target(),InferenceOptions(contextSize=8192));fail("Expected unsupported target")}
        catch(e:InferencePrerequisiteMissing){assertEquals(InferencePrerequisiteReason.PC_LOADER_UNAVAILABLE,e.reason)}
        assertEquals(0,pathLoads)
    }
    @Test fun prerequisitePauseOnlyAppliesToTheOriginalRunningState() {
        val reason=InferencePrerequisiteReason.MODEL_SELECTION_MISSING
        assertEquals(reason,InferencePrerequisiteFinalization.pauseReason("running",null,reason))
        for(status in listOf("completed","failed","cancelled","paused","waiting_approval"))
            assertNull(InferencePrerequisiteFinalization.pauseReason(status,null,reason))
    }
    @Test fun failureAndCancellationTakePriorityOverTheNotice() {
        val reason=InferencePrerequisiteReason.MODEL_SELECTION_MISSING
        assertNull(InferencePrerequisiteFinalization.pauseReason("running","failed",reason))
        assertNull(InferencePrerequisiteFinalization.pauseReason("running","cancelled",reason))
        assertNull(InferencePrerequisiteFinalization.pauseReason("running",null,null))
    }
}
