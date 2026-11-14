package com.user.localagent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure host bookkeeping, not an ART/Android failure reproduction. */
class QaBatchIntegrityRegressionTest {
    private fun ledger()=QaBatchIntegrity(listOf("calculate_note","storage_report","file_typo","exact_quote"),2,1067,"synthetic-batch")
    private fun row(a:QaBatchIntegrity.Attempt,passed:Boolean=true,abort:Boolean=false)=buildJsonObject {
        put("kind",a.kind);put("round",a.round);put("passed",passed);put("abortBatch",abort);put("cleanupVerified",true)
    }
    private fun stage(a:QaBatchIntegrity.Attempt,phase:String,cleanup:Boolean?=null)=buildJsonObject {
        put("kind",a.kind);put("round",a.round);put("qaStage",phase);cleanup?.let{put("cleanupVerified",it)}
    }
    private fun count(b:QaBatchIntegrity,key:String)=b.summary().getValue(key).jsonPrimitive.int

    @Test fun eightNormalAttemptsHaveOneRowEachAndNoUnstartedWork()=runBlocking {
        val b=ledger()
        b.plan.forEach {a->b.begin(a);b.capture{row(a)}};b.finish("all_attempts_recorded")
        assertEquals(8,b.rows.size);assertEquals(8,count(b,"startedAttempts"));assertEquals(0,count(b,"unstartedAttempts"))
        assertNull(b.current);assertNull(b.terminalFailure)
    }
    @Test fun escapedAssertionIsFailedAndCleanupIsExplicitlyUnknown()=runBlocking {
        val b=ledger();b.begin(b.plan[0]);val failure=AssertionError("strict stage missing")
        val r=b.capture{throw failure}
        assertSame(failure,b.terminalFailure);assertFalse(r.getValue("passed").jsonPrimitive.boolean)
        assertTrue(r.getValue("abortBatch").jsonPrimitive.boolean);assertEquals("unknown",r.getValue("cleanupState").jsonPrimitive.content)
        assertFalse(r.getValue("cleanupVerified").jsonPrimitive.boolean);assertEquals(1,b.rows.size)
    }
    @Test fun sixthEscapeLeavesSixRowsAndTwoUnstartedDescriptors()=runBlocking {
        val b=ledger()
        b.plan.take(5).forEach{a->b.begin(a);b.capture{row(a,passed=a.index !in setOf(1,2))}}
        b.begin(b.plan[5]);b.observe(stage(b.plan[5],"POST_SEQUENCE_INSPECT_PROJECT_STORAGE"))
        b.capture{throw AssertionError("Missing required verified stage: inspect_project_storage")}
        b.finish("attempt_boundary_failure")
        assertEquals(6,b.rows.size);assertEquals(6,count(b,"startedAttempts"));assertEquals(0,count(b,"startedWithoutRow"));assertEquals(2,count(b,"unstartedAttempts"))
        assertEquals(3,b.rows.take(5).count{it.getValue("passed").jsonPrimitive.boolean})
        val unstarted=b.summary().getValue("unstartedPlan").jsonArray
        assertEquals(listOf("file_typo","exact_quote"),unstarted.map{it.jsonObject.getValue("kind").jsonPrimitive.content})
        assertTrue(unstarted.all{it.jsonObject.getValue("round").jsonPrimitive.int==1})
    }
    @Test fun anActiveDurableStartIsDistinguishedFromNotStartedAttempts() {
        val b=ledger();b.begin(b.plan[0])
        assertEquals(1,count(b,"startedAttempts"));assertEquals(1,count(b,"startedWithoutRow"));assertEquals(7,count(b,"unstartedAttempts"))
        assertEquals("started_without_row",b.summary().getValue("attemptLedger").jsonArray[0].jsonObject.getValue("recordState").jsonPrimitive.content)
        assertTrue(b.rows.isEmpty())
    }
    @Test fun cleanupFailureDoesNotReplaceOriginalCauseOrAddDuplicateSuppressed()=runBlocking {
        val b=ledger();val a=b.plan[0];b.begin(a)
        val cause=IllegalArgumentException("synthetic cause");val original=IllegalStateException("strict failure",cause);val cleanup=IOException("cleanup failed")
        val r=b.capture {
            b.rememberCaseFailure(original);b.rememberCaseFailure(cleanup)
            b.observe(stage(a,"case_cleanup_finished",false));throw cleanup
        }
        assertSame(original,b.terminalFailure);assertSame(cause,original.cause);assertArrayEquals(arrayOf(cleanup),original.suppressed)
        assertEquals("failed",r.getValue("cleanupState").jsonPrimitive.content)
    }
    @Test fun successfulCleanupBeforeRowAssemblyFailureRemainsKnownButCannotPass()=runBlocking {
        val b=ledger();val a=b.plan[0];b.begin(a)
        val r=b.capture{b.observe(stage(a,"case_cleanup_finished",true));throw IllegalStateException("row assembly")}
        assertEquals("verified",r.getValue("cleanupState").jsonPrimitive.content)
        assertTrue(r.getValue("cleanupVerified").jsonPrimitive.boolean);assertFalse(r.getValue("passed").jsonPrimitive.boolean)
    }
    @Test fun reportIoAfterRecordedSuccessDoesNotDuplicateOrRegradeTheRow()=runBlocking {
        val b=ledger();val a=b.plan[0];b.begin(a);b.capture{row(a)}
        val io=IOException("publisher failed");b.abort(io);b.finish("batch_boundary_failure")
        assertSame(io,b.terminalFailure);assertEquals(1,b.rows.size);assertTrue(b.rows.single().getValue("passed").jsonPrimitive.boolean)
        assertEquals(7,count(b,"unstartedAttempts"))
    }
    @Test fun reportIoAfterRecordedFailurePreservesTheOriginalThrowable()=runBlocking {
        val b=ledger();val a=b.plan[0];b.begin(a);val original=AssertionError("strict failure")
        b.capture{b.rememberCaseFailure(original);row(a,passed=false)}
        val io=IOException("final publisher failed");b.abort(io)
        assertSame(original,b.terminalFailure);assertArrayEquals(arrayOf(io),original.suppressed);assertEquals(1,b.rows.size)
    }
    @Test fun invalidReturnedIdentityIsRecordedOnlyAsTheActiveAttemptFailure()=runBlocking {
        for(mode in 0..2){
            val b=ledger();val a=b.plan[0];b.begin(a)
            val malformed=when(mode){
                0->row(b.plan[1])
                1->JsonObject(row(a)-"cleanupVerified")
                else->JsonObject(row(a)+("cleanupVerified" to JsonPrimitive(false)))
            }
            try{b.capture{malformed};fail()}catch(error:IllegalStateException){b.abort(error)}
            assertEquals(1,b.rows.size);assertEquals(a.kind,b.rows.single().getValue("kind").jsonPrimitive.content)
            assertFalse(b.rows.single().getValue("passed").jsonPrimitive.boolean)
        }
    }
    @Test fun cancellationOrCleanupUnknownCannotStartAnotherAttempt()=runBlocking {
        val b=ledger();b.begin(b.plan[0]);val stop=CancellationException("synthetic cancellation")
        b.capture{throw stop};assertSame(stop,b.terminalFailure)
        try{b.begin(b.plan[1]);fail()}catch(_:IllegalStateException){}
        assertEquals(1,count(b,"startedAttempts"));assertEquals(7,count(b,"unstartedAttempts"))
    }
    @Test fun ordinaryCaseRequestedAbortAlsoPreventsStartingAnotherAttempt()=runBlocking {
        val b=ledger();val a=b.plan[0];b.begin(a);b.capture{row(a,passed=false,abort=true)}
        try{b.begin(b.plan[1]);fail()}catch(_:IllegalStateException){}
        assertNull(b.terminalFailure);assertEquals(1,b.rows.size)
    }
    @Test fun initialReportIoCreatesNoFictitiousAttemptAndFinalIoKeepsPrimary()=runBlocking {
        val b=ledger();val initial=IOException("initial publisher");val final=IOException("final publisher")
        b.abort(initial);b.finish("batch_boundary_failure");b.abort(final);b.finish("final_report_write_failed")
        assertSame(initial,b.terminalFailure);assertArrayEquals(arrayOf(final),initial.suppressed)
        assertEquals(0,count(b,"startedAttempts"));assertEquals(8,count(b,"unstartedAttempts"));assertTrue(b.rows.isEmpty())
        assertEquals("final_report_write_failed",b.summary().getValue("terminationReason").jsonPrimitive.content)
    }
    @Test fun storageObservationRemainsSeparateFromTheStrictSequenceFailure()=runBlocking {
        val b=QaBatchIntegrity(listOf("storage_report"),1,1067,"synthetic-batch");val a=b.plan[0];b.begin(a)
        val observation=buildJsonObject{put("observationOnly",true);put("bodyMatches",true);put("goalVerdict","not_assigned")}
        b.observe(buildJsonObject {put("kind",a.kind);put("round",a.round);put("qaStage","storage_state_observed");put("storageStateObservation",observation)})
        val r=b.capture{throw AssertionError("strict inspect missing")}
        assertEquals(observation,r["storageStateObservation"]);assertFalse(r.getValue("passed").jsonPrimitive.boolean)
    }
}
