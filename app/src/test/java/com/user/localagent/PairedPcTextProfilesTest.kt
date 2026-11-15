package com.user.localagent

import com.user.localagent.models.PairedPcTextProfileDescriptor
import org.junit.Assert.*
import org.junit.Test

class PairedPcTextProfilesTest {
    private val legacyHash = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8"
    private val fastHash = "217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14"
    private fun selected(id: String) = checkNotNull(PairedPcTextProfileDescriptor.forSelection(id))

    @Test fun autoAndStoredLegacySelectionRetainExactOriginalModelIdentity() {
        val auto = selected("auto")
        assertSame(auto, selected("paired-pc-text-v1"))
        assertEquals("paired-pc-text-v1", auto.selectionId)
        assertEquals("pc-text", auto.modelId)
        assertEquals(legacyHash, auto.modelSha256)
    }

    @Test fun onlyNewExplicitSelectionResolvesToTheSeparate4BIdentity() {
        val fast = selected("paired-pc-text-fast4b-v1")
        assertNotSame(selected("auto"), fast)
        assertEquals("paired-pc-text-fast4b-v1", fast.selectionId)
        assertEquals("pc-text-4b", fast.modelId)
        assertEquals(fastHash, fast.modelSha256)
    }

    @Test fun explicitSelectorDoesNotTurnAutoIntoANewExplicitChoice() {
        assertNull(PairedPcTextProfileDescriptor.forExplicitSelection("auto"))
        assertSame(selected("auto"), selected("paired-pc-text-v1"))
    }

    @Test fun modelAliasesPathsAndUnrecognizedSelectionIdsAreNotProfiles() {
        for (id in listOf(null, "", "pc-text", "pc-text-4b", "fast4B-v1",
            "Qwen3.5-4B-Q4_K_M.gguf", "paired-pc-text-v2", " paired-pc-text-v1",
            "paired-pc-text-fast4b-v1 ", "PAIRED-PC-TEXT-FAST4B-V1")) {
            assertNull(id, PairedPcTextProfileDescriptor.forSelection(id))
        }
    }

    @Test fun identitiesCannotBeMixedAcrossModelsInEitherDirection() {
        val legacy = selected("paired-pc-text-v1")
        val fast = selected("paired-pc-text-fast4b-v1")
        assertTrue(legacy.matchesModelIdentity("pc-text", legacyHash))
        assertTrue(fast.matchesModelIdentity("pc-text-4b", fastHash))
        assertFalse(legacy.matchesModelIdentity("pc-text-4b", fastHash))
        assertFalse(fast.matchesModelIdentity("pc-text", legacyHash))
        assertFalse(legacy.matchesModelIdentity("pc-text", fastHash))
        assertFalse(fast.matchesModelIdentity("pc-text-4b", legacyHash))
        assertFalse(legacy.matchesModelIdentity("pc-text-4b", legacyHash))
        assertFalse(fast.matchesModelIdentity("pc-text", fastHash))
    }

    @Test fun missingPrefixOnlyOrAlteredHashesDoNotMatchEitherProfile() {
        for (profile in listOf(selected("auto"), selected("paired-pc-text-fast4b-v1"))) {
            for (hash in listOf(null, "", profile.modelSha256.take(16),
                profile.modelSha256.uppercase(), profile.modelSha256 + "0")) {
                assertFalse(profile.matchesModelIdentity(profile.modelId, hash))
            }
            assertFalse(profile.matchesModelIdentity(null, profile.modelSha256))
        }
    }
}
