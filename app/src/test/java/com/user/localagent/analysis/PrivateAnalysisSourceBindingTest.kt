package com.user.localagent.analysis

import com.user.localagent.core.*
import com.user.localagent.data.NoteRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateAnalysisSourceBindingTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    private val sha="a".repeat(64)
    private fun call(args:JsonObject)=ToolCall("synthetic","analyze_conversation_import",args)
    private fun note(scope:String="scope-a",kind:String="IMPORTED_CONVERSATION",locator:String="conversation_import")=NoteRecord(id,"合成",buildJsonObject {
        put("kind",RepositoryAnalysisJournal.ROOT_KIND);put("id",id);put("scopeId",scope)
        put("source",buildJsonObject {put("scopeId",scope);put("kind",kind)})
        put("sourceLocator",buildJsonObject {put("kind",locator);put("contentSha256",sha)})
    }.toString(),RepositoryAnalysisJournal.COLLECTION)

    @Test fun explicitActualHexHashIsNormalizedWithoutRequiringDiscovery()=runBlocking {
        val value=PrivateAnalysisSourceBinding.importedHash(call(buildJsonObject {put("contentSha256",sha.uppercase())}),"scope-a") {error("No checkpoint read needed")}
        assertEquals(sha,value)
    }

    @Test fun resumeResolvesTheAuthorizedImmutableLocatorInsteadOfRequiringNewSourceArgs()=runBlocking {
        val value=PrivateAnalysisSourceBinding.importedHash(call(buildJsonObject {put("analysisId",id);put("expectedProcessedChunks",1)}),"scope-a") {requested->assertEquals(id,requested);note()}
        assertEquals(sha,value)
    }

    @Test fun foreignWrongKindAndUnpublishedSourcesNeverBecomeMessengerAuthority()=runBlocking {
        for(invalid in listOf(note(scope="scope-b"),note(kind="SMS"),note(locator="sms"),note().copy(collection="default"))) {
            assertTrue(runCatching {PrivateAnalysisSourceBinding.importedHash(call(buildJsonObject {put("analysisId",id)}),"scope-a"){invalid}}.isFailure)
        }
        assertTrue(runCatching {PrivateAnalysisSourceBinding.importedHash(call(buildJsonObject {put("analysisId",id)}),"scope-a"){null}}.isFailure)
    }
}
