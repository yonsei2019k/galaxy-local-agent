package com.user.localagent.automation

import com.user.localagent.core.ToolRegistry
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlanningFeedbackStoreTest {
    private class MemoryPersistence:PlanningFeedbackPersistence {
        val values=linkedMapOf<String,String>()
        var failDeleteOnce=false
        override suspend fun read(id:String)=values[id]
        override suspend fun write(id:String,body:String){values[id]=body}
        override suspend fun delete(id:String){if(failDeleteOnce){failDeleteOnce=false;error("synthetic cleanup interruption")};values.remove(id)}
    }
    private fun fails(block:()->Unit){try{block();fail("Unbounded or non-taxonomy data was accepted")}catch(expected:IllegalArgumentException){/* fixed-key rejection */}}

    @Test fun preparedUnverifiedAndFailedNeverCountAsVerifiedCompletion()=runBlocking {
        val backend=MemoryPersistence();val store=PlanningFeedbackStore(backend)
        store.record("calendar","prepare_calendar_event",VerificationState.PREPARED,true)
        store.record("calendar","prepare_calendar_event",VerificationState.UNVERIFIED,true)
        store.record("calendar","prepare_calendar_event",VerificationState.VERIFIED,false,"verification_failed")
        var row=store.snapshot().stats.single()
        assertEquals(0L,row.verifiedSuccess);assertEquals(1L,row.prepared);assertEquals(1L,row.unverified);assertEquals(1L,row.failed)
        assertEquals(3L,row.attempts)
        val context=store.contextFor("calendar")
        assertTrue(context.contains("prepared_is_not_external_completion"));assertTrue(context.contains("verify_postcondition_before_completion"))
        store.record("calendar","prepare_calendar_event",VerificationState.VERIFIED,true)
        row=PlanningFeedbackStore(backend).snapshot().stats.single()
        assertEquals(1L,row.verifiedSuccess);assertEquals(4L,row.attempts)
    }

    @Test fun rejectsCredentialsUnboundedKeysAndRawCorrectionsWithoutPersisting() {
        val backend=MemoryPersistence();val store=PlanningFeedbackStore(backend)
        listOf("sk-synthetic-secret","owner@example.invalid","messages\nrun_command","x".repeat(5000)).forEach {value->
            fails {runBlocking {store.record(value,"planner",VerificationState.FAILED,false)}}
            fails {runBlocking {store.record("general",value,VerificationState.FAILED,false)}}
            fails {runBlocking {store.record("general","planner",VerificationState.FAILED,false,value)}}
            fails {runBlocking {store.proposeLesson("general","planner",value)}}
        }
        assertTrue(backend.values.isEmpty())
    }

    @Test fun sharedMutexAcrossInstancesPreventsLostConcurrentUpdates()=runBlocking {
        val backend=MemoryPersistence();val a=PlanningFeedbackStore(backend);val b=PlanningFeedbackStore(backend)
        val generation=a.generation()
        coroutineScope {repeat(200){index->launch(Dispatchers.Default){assertTrue((if(index%2==0)a else b).record("notes","save_note",VerificationState.VERIFIED,true,expectedGeneration=generation))}}}
        assertEquals(200L,PlanningFeedbackStore(backend).snapshot().stats.single().verifiedSuccess)
        assertEquals(2,backend.values.size)
    }

    @Test fun resetRejectsOldExecutionsAndKeepsNoForgottenStatsAfterRecreation()=runBlocking {
        val backend=MemoryPersistence();val store=PlanningFeedbackStore(backend)
        val before=store.generation()
        store.record("notes","save_note",VerificationState.VERIFIED,true,expectedGeneration=before)
        val after=store.reset();assertTrue(after>before)
        assertFalse(store.record("notes","save_note",VerificationState.VERIFIED,true,expectedGeneration=before))
        val recreated=PlanningFeedbackStore(backend)
        assertEquals(after,recreated.generation());assertTrue(recreated.snapshot().stats.isEmpty())
        assertEquals("",recreated.contextFor("notes"))
        assertTrue(recreated.record("notes","save_note",VerificationState.VERIFIED,true,expectedGeneration=after))
        assertEquals(1L,recreated.snapshot().stats.single().verifiedSuccess)
    }

    @Test fun deletingOneToolPreservesOthersButInvalidatesAllOldWriters()=runBlocking {
        val store=PlanningFeedbackStore(MemoryPersistence());val generation=store.generation()
        store.record("notes","save_note",VerificationState.VERIFIED,true,expectedGeneration=generation)
        store.record("calendar","prepare_calendar_event",VerificationState.PREPARED,true,expectedGeneration=generation)
        assertTrue(store.delete("notes","save_note"))
        val result=store.snapshot()
        assertTrue(result.generation>generation);assertEquals("prepare_calendar_event",result.stats.single().tool);assertEquals(1L,result.stats.single().prepared)
        assertFalse(store.record("notes","save_note",VerificationState.VERIFIED,true,expectedGeneration=generation))
        assertEquals(1,store.snapshot().stats.size)
    }

    @Test fun interruptedDeletionRecoversWithoutRevealingOldCounters()=runBlocking {
        val backend=MemoryPersistence();val store=PlanningFeedbackStore(backend);val generation=store.generation()
        store.record("web","search_web",VerificationState.FAILED,false,"timeout",generation)
        backend.failDeleteOnce=true
        try{store.reset();fail("Synthetic cleanup interruption must be reported")}catch(expected:IllegalStateException){/* generation was committed first */}
        val recreated=PlanningFeedbackStore(backend)
        assertTrue(recreated.generation()>generation);assertTrue(recreated.snapshot().stats.isEmpty())
        assertFalse(recreated.record("web","search_web",VerificationState.VERIFIED,true,expectedGeneration=generation))
        assertEquals(1,backend.values.size)
    }

    @Test fun aggregatesAreBoundedTo128AndEvictedNotesAreActuallyRemoved()=runBlocking {
        val backend=MemoryPersistence();val store=PlanningFeedbackStore(backend)
        val combinations=PlanningFeedbackStore.knownDomains.flatMap {domain->ToolRegistry.definitions.keys.map {domain to it}}.take(140)
        assertEquals(140,combinations.size)
        for((domain,tool)in combinations)store.record(domain,tool,VerificationState.VERIFIED,true)
        assertEquals(128,store.snapshot().stats.size);assertEquals(129,backend.values.size)
        assertTrue(backend.values.values.all {it.length<=12000})
        val evicted=combinations.first()
        assertFalse(store.snapshot().stats.any {it.domain==evicted.first&&it.tool==evicted.second})
    }

    @Test fun proposedLessonsStayProposedAndDoNotCreateExecutionSuccess()=runBlocking {
        val store=PlanningFeedbackStore(MemoryPersistence())
        store.proposeLesson("projects","write_project_file","read_before_write")
        val row=store.snapshot().stats.single()
        assertEquals(0L,row.attempts);assertEquals(1L,row.proposedLessons.getValue("read_before_write"))
        val context=Json.parseToJsonElement(store.contextFor("projects")).jsonObject
        assertEquals("proposed_only",context.getValue("lessonsStatus").jsonPrimitive.content)
        assertFalse(context.getValue("modelWeightsChanged").jsonPrimitive.boolean)
        assertFalse(context.getValue("grantsPermissions").jsonPrimitive.boolean)
        assertTrue(context.getValue("untrustedData").jsonPrimitive.boolean)
    }

    @Test fun repeatedSchemaFailuresYieldFixedHintsAndCancelledWorkDoesNotBiasAlternatives()=runBlocking {
        val store=PlanningFeedbackStore(MemoryPersistence())
        repeat(2){store.record("general","planner",VerificationState.FAILED,false,"invalid_json")}
        val context=store.contextFor("calendar")
        assertTrue(context.contains("validate_schema_before_execution"));assertTrue(context.contains("try_verified_alternative_or_read_first"));assertTrue(context.length<=1800)
        store.reset()
        repeat(3){store.record("web","search_web",VerificationState.FAILED,false,"cancelled")}
        assertFalse(store.contextFor("web").contains("try_verified_alternative_or_read_first"))
        assertTrue(store.contextFor("images").isEmpty())
    }

    @Test fun toolDomainHelperUsesOnlyRegisteredMetadata(){
        assertEquals("projects",PlanningFeedbackStore.domainForTool("write_project_file"))
        assertEquals("messages",PlanningFeedbackStore.domainForTool("get_sms_summary"))
        assertEquals("general",PlanningFeedbackStore.domainForTool("planner"))
        fails {PlanningFeedbackStore.domainForTool("unknown_private_payload")}
    }
}
