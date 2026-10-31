package com.user.localagent.connections

import org.junit.Assert.*
import org.junit.Test

class PcDataSelectionTest {
    private fun selection(thread:String="public-thread-a",version:String="public-version-a")=
        PcDataSelection(thread,"content://public.fixture/document/data","public.csv","a".repeat(64),version)
    @Test fun identicalFilesInDifferentChatsCannotShareASelectionBinding() {
        val first=selection();val second=selection("public-thread-b")
        assertNotEquals(first.binding,second.binding)
        assertNotEquals(PcDataSelection.storageKey(first.threadId),PcDataSelection.storageKey(second.threadId))
    }
    @Test fun selectingTheSameFileAgainCannotReuseThePreviousRequestsVersion() {
        val original=selection();val replacement=selection(version="public-version-b")
        assertEquals(original.sha256,replacement.sha256);assertNotEquals(original.binding,replacement.binding)
        assertEquals("public-version-a",PcDataSelection.decode(original.encode()).version)
    }
    @Test fun codecPreservesExactSourceButItsDiagnosticStringOmitsPrivateMetadata() {
        val original=selection();val restored=PcDataSelection.decode(original.encode())
        assertEquals(original,restored);assertEquals(original.binding,restored.binding)
        assertFalse(restored.toString().contains(original.uri));assertFalse(restored.toString().contains(original.name))
    }
    @Test fun unknownMetadataAndNonContentSourcesAreRejected() {
        assertThrows(IllegalArgumentException::class.java){PcDataSelection.decode(selection().encode().dropLast(1)+",\"extra\":true}")}
        assertThrows(IllegalArgumentException::class.java){selection().copy(uri="https://public.invalid/data.csv")}
    }
}
