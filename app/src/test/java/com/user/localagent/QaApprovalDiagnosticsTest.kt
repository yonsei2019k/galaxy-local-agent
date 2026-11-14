package com.user.localagent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QaApprovalDiagnosticsTest {
    @Test fun proposalIsPersistedBeforeAnyGuardOrApproval(){
        val writes=mutableListOf<JsonObject>();val log=QaApprovalDiagnostics{writes+=it}
        log.capture("save_note",buildJsonObject{put("content","fixture");put("title","demo")})
        assertEquals(1,writes.size)
        val attempt=writes.single().getValue("approvalAttempts").jsonArray.single().jsonObject
        assertEquals("CAPTURED",attempt.getValue("phase").jsonPrimitive.content)
        assertEquals("fixture",attempt.getValue("resolvedArguments").jsonObject.getValue("content").jsonPrimitive.content)
    }
    @Test fun mismatchIsPersistedWithExactDiagnosticBeforeException(){
        val writes=mutableListOf<JsonObject>();val log=QaApprovalDiagnostics{writes+=it}
        val index=log.capture("save_note",buildJsonObject{put("content","a\r\nb")})
        try{log.assertExactContent(index,"QA_SAVE_CONTENT_EXACT","a\nb",JsonPrimitive("a\r\nb"));fail("Strict mismatch must fail")}
        catch(error:IllegalStateException){assertEquals("QA_SAVE_CONTENT_EXACT",error.message)}
        val final=writes.last();assertEquals("QA_SAVE_CONTENT_EXACT",final.getValue("qaFailureCode").jsonPrimitive.content)
        val compare=final.getValue("approvalAttempts").jsonArray.single().jsonObject.getValue("checks").jsonArray.single().jsonObject.getValue("comparison").jsonObject
        assertFalse(compare.getValue("equal").jsonPrimitive.boolean)
        assertEquals(1,compare.getValue("firstDifferenceUtf16").jsonPrimitive.int)
        assertEquals(1,compare.getValue("actualCrCount").jsonPrimitive.int)
    }
    @Test fun noWhitespaceOrUnitNormalizationIsIntroduced(){
        for(text in listOf("a b","ab ","a\nb")){
            val log=QaApprovalDiagnostics();val i=log.capture("save_note",buildJsonObject{put("content",text)})
            try{log.assertExactContent(i,"QA_SAVE_CONTENT_EXACT","ab",JsonPrimitive(text));fail("Exact only")}
            catch(_:IllegalStateException){}
        }
    }
    @Test fun missingAndWrongTypeAreDistinctFromActualEmptyString(){
        for(actual in listOf<JsonElement?>(null,JsonPrimitive(4),JsonPrimitive(""))){
            val log=QaApprovalDiagnostics();val i=log.capture("save_note",buildJsonObject{})
            try{log.assertExactContent(i,"QA_SAVE_CONTENT_EXACT","fixture",actual);fail("Must reject")}
            catch(_:IllegalStateException){}
            assertEquals("QA_SAVE_CONTENT_EXACT",log.failureCode)
        }
    }
    @Test fun acceptedApprovalAndPostconditionCheckHaveSeparateStages(){
        val log=QaApprovalDiagnostics();val i=log.capture("save_note",buildJsonObject{put("content","same")})
        log.assertExactContent(i,"QA_SAVE_CONTENT_EXACT","same",JsonPrimitive("same"))
        log.approvalSubmitted(i,true);log.markCheck("POST_STORAGE_SEQUENCE")
        assertEquals("POST_STORAGE_SEQUENCE",log.lastCheckCode);assertNull(log.failureCode)
        assertEquals("APPROVAL_SUBMITTED",log.snapshot().getValue("approvalAttempts").jsonArray.single().jsonObject.getValue("phase").jsonPrimitive.content)
    }
    @Test fun attemptAndArgumentLimitsAreHonestAndDoNotAutoApprove(){
        val log=QaApprovalDiagnostics();repeat(34){log.capture("save_note",buildJsonObject{put("content","x")})}
        assertEquals(32,log.snapshot().getValue("approvalAttempts").jsonArray.size)
        assertEquals(2,log.snapshot().getValue("omittedAttempts").jsonPrimitive.int)
        val large=QaApprovalDiagnostics();large.capture("save_note",buildJsonObject{put("content","x".repeat(33_000))})
        assertTrue(large.snapshot().getValue("approvalAttempts").jsonArray.single().jsonObject.getValue("argumentsOmitted").jsonPrimitive.boolean)
    }
}
