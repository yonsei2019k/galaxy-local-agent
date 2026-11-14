package com.user.localagent.compute

import com.user.localagent.inference.InferenceOptions
import org.junit.Assert.*
import org.junit.Test

class PcTextAuthorityTest {
    @Test fun absentLegacyCloudPermissionStaysLocalAcrossLaterOwnerChoices(){
        val captured=PcTextAuthority(7)
        assertFalse(captured.usesCloudText(InferenceOptions()))
        assertTrue(captured.matches(7))
        assertFalse(captured.matches(8))
        assertFalse(captured.matches(9)) // Re-enabling an identical setting cannot revive old authority.
    }
    @Test fun explicitNewCloudSelectionCannotMoveGemmaImageOrAudioWorkToCloud(){
        val captured=PcTextAuthority(8,allowCloudText=true)
        assertTrue(captured.usesCloudText(InferenceOptions()))
        assertFalse(captured.usesCloudText(InferenceOptions(imagePreparation=true)))
        assertFalse(captured.usesCloudText(InferenceOptions(visionEnabled=true)))
        assertFalse(captured.usesCloudText(InferenceOptions(audioEnabled=true)))
        assertFalse(captured.usesCloudText(InferenceOptions(imagePreparation=true,visionEnabled=true,audioEnabled=true)))
    }
    @Test fun invalidCapturedRevisionIsRejected(){
        assertTrue(runCatching{PcTextAuthority(-1,true)}.isFailure)
    }
}
