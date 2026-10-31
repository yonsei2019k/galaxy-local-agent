package com.user.localagent.skills

import org.junit.Assert.*
import org.junit.Test

/** No model/device/assets: verifies bounded discovery without loading or filtering the actual catalog. */
class BundledSkillDiscoveryTest {
    @Test fun discoveryDoesNotReadAssetsOrInjectTheCatalogBeforeItIsRequested(){
        var reads=0
        val store=BundledSkillStore{reads++;error("Discovery must not load asset bodies or catalog metadata")}
        val hint=store.discoveryForPrompt(setOf("list_skills","read_skill","get_current_datetime"))
        assertTrue(hint.length in 1..220);assertTrue(hint.contains("list_skills")&&hint.contains("read_skill"))
        assertEquals(0,reads)
    }
    @Test fun onlyOfferedDiscoveryCapabilitiesAreMentioned(){
        val store=BundledSkillStore{error("No asset reads")}
        val listOnly=store.discoveryForPrompt(setOf("list_skills"))
        assertTrue(listOnly.contains("list_skills"));assertFalse(listOnly.contains("read_skill"))
        val readOnly=store.discoveryForPrompt(setOf("read_skill"))
        assertTrue(readOnly.contains("read_skill"));assertFalse(readOnly.contains("list_skills"))
    }
    @Test fun RestrictedProjectReceivesNoUnavailableSkillCallsOrExtraPermissions(){
        val store=BundledSkillStore{error("No asset reads")}
        assertEquals("",store.discoveryForPrompt(setOf("get_current_datetime","save_note","search_notes")))
        assertEquals("",store.discoveryForPrompt(emptySet()))
        assertTrue(store.discoveryForPrompt(setOf("list_skills","read_skill")).contains("no permissions"))
    }
}
