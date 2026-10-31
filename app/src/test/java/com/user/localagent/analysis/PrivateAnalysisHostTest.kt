package com.user.localagent.analysis

import com.user.localagent.core.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateAnalysisHostTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    private val call=ToolCall("synthetic","analyze_sms",buildJsonObject {put("question","일정 변경과 후속 업무는?")})
    private fun result(analysisId:String=id,version:Int=1)=ToolResult(call.id,true,buildJsonObject {
        put("status","partial");put("analysisId",analysisId);put("analysisCompleteWithinSelectedText",false)
        put("canResumeStoredText",true);put("resumeWithAnalysisId",analysisId);put("resumeExpectedProcessedChunks",version)
        put("resumeArguments",buildJsonObject {put("analysisId",analysisId);put("expectedProcessedChunks",version)})
    }.toString(),VerificationState.UNVERIFIED,"선택 본문 일부 처리")

    @Test fun storedEvidenceReaderAndSourceDiscoveryKeepOriginalJobCancellation() {
        assertTrue("read_analysis_evidence" in PrivateAnalysisHost.cancellableNames)
        assertTrue("list_analysis_sources" in PrivateAnalysisHost.cancellableNames)
    }

    @Test fun auditFailurePreservesStructuredSourceResumeAndArtifactData() {
        val original=result(version=17)
        val warned=PrivateAnalysisHost.afterAudit(original,false)
        assertEquals(original.content,warned.content)
        assertEquals(17,Json.parseToJsonElement(PrivateAnalysisModelContext.build(warned)).jsonObject.getValue("resumeExpectedProcessedChunks").jsonPrimitive.int)
        assertTrue(warned.verificationSummary.contains("감사 기록 저장 미확인"))
        assertSame(original,PrivateAnalysisHost.afterAudit(original,true))
    }

    @Test fun cancelledOriginalJobStopsWorkAndCommitsOnlyUnverifiedReceipt()=runBlocking {
        val entered=CompletableDeferred<Unit>();val cleanup=CompletableDeferred<Unit>();val receipts=mutableListOf<ToolResult>()
        var reachedAfter=false
        val job=launch {
            PrivateAnalysisHost.executeCancellable(call,{
                try {entered.complete(Unit);awaitCancellation()}finally{cleanup.complete(Unit)}
            },{receipt->delay(5);receipts+=receipt},30_000)
            reachedAfter=true
        }
        entered.await();job.cancelAndJoin()
        assertTrue(cleanup.isCompleted);assertFalse(reachedAfter);assertTrue(job.isCancelled)
        assertEquals(1,receipts.size);assertFalse(receipts.single().success)
        assertEquals(VerificationState.UNVERIFIED,receipts.single().verification)
    }

    @Test fun projectPolicyChangeCancelsLongReadBeforeAnOutcomeCanBePublished()=runBlocking {
        val authorized=java.util.concurrent.atomic.AtomicBoolean(true)
        val entered=CompletableDeferred<Unit>();var cleaned=false;var published=false
        val operation=launch {
            PrivateAnalysisHost.withLiveAccess({check(authorized.get())},{
                try {entered.complete(Unit);awaitCancellation()}finally{cleaned=true}
            },5)
            published=true
        }
        entered.await();authorized.set(false)
        withTimeout(2000){operation.join()}
        assertTrue(operation.isCancelled);assertTrue(cleaned);assertFalse(published)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun ownTimeoutStopsWorkerAndReturnsUnverifiedWithoutCancellingCaller()=runBlocking {
        var cleaned=false;val receipts=mutableListOf<ToolResult>()
        val observed=PrivateAnalysisHost.executeCancellable(call,{
            try {awaitCancellation()}finally{cleaned=true}
        },{receipts+=it},20)
        assertTrue(currentCoroutineContext().isActive);assertTrue(cleaned)
        assertEquals(listOf(observed),receipts);assertEquals(VerificationState.UNVERIFIED,observed.verification)
    }

    @Test fun callerTimeoutIsPropagatedAfterDurableUnverifiedReceipt()=runBlocking {
        val receipts=mutableListOf<ToolResult>()
        val value=withTimeoutOrNull(30){
            PrivateAnalysisHost.executeCancellable(call,{awaitCancellation()},{delay(5);receipts+=it},30_000)
        }
        assertNull(value);assertEquals(1,receipts.size);assertFalse(receipts.single().success)
    }

    @Test fun completedWorkerReceiptIsCommittedBeforeReturn()=runBlocking {
        val expected=result();var committed=false
        val observed=PrivateAnalysisHost.executeCancellable(call,{expected},{assertSame(expected,it);committed=true},1000)
        assertSame(expected,observed);assertTrue(committed)
    }

    @Test fun sameAnalysisReplacesOnlyItsOlderPreviewWithLatestVersion() {
        val other="12345678-1234-1234-1234-123456789abd"
        val evidence=mutableListOf<JsonObject>()
        PrivateAnalysisHost.addEvidence(evidence,call,result())
        PrivateAnalysisHost.addEvidence(evidence,call,result(other))
        PrivateAnalysisHost.addEvidence(evidence,call,result(version=32))
        assertEquals(2,evidence.size)
        val latest=evidence.single {it.getValue("result").jsonObject["analysisId"]?.jsonPrimitive?.content==id}.getValue("result").jsonObject
        assertEquals(32,latest.getValue("resumeExpectedProcessedChunks").jsonPrimitive.int)
        assertEquals(other,evidence.first().getValue("result").jsonObject.getValue("analysisId").jsonPrimitive.content)
    }

    @Test fun actualReportMustExistAndMatchDeclaredByteCountAndSha() {
        val directory=Files.createTempDirectory("private-analysis-host-test").toFile()
        try {
            val file=java.io.File(directory,"artifact-$id.txt");val body="공개 합성 자료의 근거 보고서🙂".toByteArray(Charsets.UTF_8)
            val sha=MessageDigest.getInstance("SHA-256").digest(body).joinToString(""){"%02x".format(it.toInt()and 255)}
            val payload=Json.parseToJsonElement(result().content).jsonObject
            fun artifact(size:Long=body.size.toLong())=buildJsonObject {
                put("fileName",file.name);put("format","txt");put("mimeType","text/plain");put("bytes",size);put("sha256",sha)
                put("privateArtifact",true);put("exportOrShareRequiresUserAction",true)
            }
            fun receipt(size:Long=body.size.toLong())=result().copy(content=JsonObject(payload+mapOf("artifact" to artifact(size))).toString())
            assertNull(PrivateAnalysisHost.verifiedArtifactToken(receipt(),directory))
            file.writeBytes(body)
            assertEquals("[[file:${file.name}]]",PrivateAnalysisHost.verifiedArtifactToken(receipt(),directory))
            assertNull(PrivateAnalysisHost.verifiedArtifactToken(receipt(body.size+1L),directory))
            file.appendText("변경")
            assertNull(PrivateAnalysisHost.verifiedArtifactToken(receipt(),directory))
        } finally {directory.listFiles().orEmpty().forEach {it.delete()};directory.delete()}
    }

    @Test fun transcriptionPreviewKeepsRealNextSourceIdWithoutClaimingRecognitionAccuracy() {
        val actual=ToolResult("synthetic",true,buildJsonObject {
            put("status","ok");put("transcriptId",id);put("nextTool","analyze_audio_transcript")
            put("chunkCount",30);put("durationMillis",450_000);put("fullTextPersisted",true)
            put("recognitionAccuracyVerified",false);put("originalSpeechCompletenessVerified",false)
        }.toString(),VerificationState.VERIFIED)
        val preview=Json.parseToJsonElement(PrivateAnalysisModelContext.build(actual)).jsonObject
        assertEquals(id,preview.getValue("transcriptId").jsonPrimitive.content)
        assertFalse(preview.getValue("recognitionAccuracyVerified").jsonPrimitive.boolean)
        assertFalse(preview.getValue("originalSpeechCompletenessVerified").jsonPrimitive.boolean)
    }

    @Test fun connectionFailureRetainsOnlyRealPermissionAndTypedFailureStatus() {
        val actual=ToolResult("synthetic",false,"""{"status":"connection_required","connectionRequired":true,"requiredPermission":"android.permission.READ_MEDIA_AUDIO"}""",VerificationState.FAILED)
        val preview=Json.parseToJsonElement(PrivateAnalysisModelContext.build(actual)).jsonObject
        assertEquals("connection_required",preview.getValue("status").jsonPrimitive.content)
        assertEquals("android.permission.READ_MEDIA_AUDIO",preview.getValue("requiredPermission").jsonPrimitive.content)
        assertFalse(preview.getValue("toolSuccess").jsonPrimitive.boolean)
    }
}
