package com.user.localagent.analysis

import com.user.localagent.core.PrivateCoverageReason
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateAnalysisModelContextTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    private val file="artifact-12345678-1234-1234-1234-123456789abc.txt"
    private val sha="a".repeat(64)
    private fun artifact(name:String=file)=buildJsonObject {
        put("fileName",name);put("format","txt");put("mimeType","text/plain");put("bytes",1200);put("sha256",sha)
        put("privateArtifact",true);put("exportOrShareRequiresUserAction",true)
    }
    private fun finding(index:Int,text:String="근거 있는 설명 ".repeat(30),quote:String="실제 원문🙂 ".repeat(30))=buildJsonObject {
        put("evidenceId",index.toString().padStart(64,'a'));put("finding",text);put("quote",quote)
        put("startByte",index*100);put("endByte",index*100+50);put("excerptTruncated",false)
        put("timeReferences",buildJsonArray {add(buildJsonObject {put("atEpochMillis",1700000000000L+index);put("meaning","source_record_time")})})
    }
    private fun payload(reasons:List<String> = listOf("CHUNK_LIMIT","UNPROCESSED_TEXT"))=buildJsonObject {
        put("status","partial");put("analysisId",id);put("analysisCompleteWithinSelectedText",false)
        put("coverage",buildJsonObject {put("knownTextBytes",90000);put("processedBytes",4096);put("retrievedBytes",4096);put("completeSelectedText",false);put("reasons",JsonArray(reasons.map(::JsonPrimitive)))})
        put("resumeWithAnalysisId",id);put("resumeExpectedProcessedChunks",16)
        put("canResumeStoredText",true)
        put("resumeArguments",buildJsonObject {put("analysisId",id);put("expectedProcessedChunks",16)})
        put("analysisScope","sms");put("findings",buildJsonArray {add(finding(1));add(finding(2))})
        put("totalSavedFindings",60);put("detailedResultsAvailable",true)
        put("sourceMetadata",buildJsonObject {repeat(40){put("irrelevant-$it","UNRELATED-LARGE-METADATA".repeat(50))};put("providerRecordCount",6000);put("includedRecordCount",6000);put("bodyFormat","exact_provider_body_in_jsonl_v1")})
        put("artifact",artifact())
    }
    private fun result(value:JsonObject=payload(),success:Boolean=true,state:VerificationState=VerificationState.UNVERIFIED)=ToolResult("synthetic",success,value.toString(),state)
    private fun parsed(result:ToolResult,max:Int=2200):JsonObject {
        val text=PrivateAnalysisModelContext.build(result,max)
        assertTrue(text.length<=max);assertEquals(text,text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test fun historicalFileExcerptIsPreservedWithoutClaimingSourceVerification() {
        val source=buildJsonObject {
            put("savedReports",buildJsonArray {
                repeat(3){add(buildJsonObject {
                    put("coverage","처리: 4/40 UTF-8바이트 · 부분 분석")
                    put("findingsExcerpt","합성 근거만 포함")
                    put("originalSourceReverified",true)
                })}
            })
        }
        val preview=parsed(result(source,state=VerificationState.VERIFIED),1600)
        val report=preview.getValue("savedReports").jsonArray.single().jsonObject
        assertEquals("합성 근거만 포함",report.getValue("findingsExcerpt").jsonPrimitive.content)
        assertFalse(report.getValue("originalSourceReverified").jsonPrimitive.boolean)
        assertTrue(preview.getValue("contextTruncated").jsonPrimitive.boolean)
    }

    @Test fun boundedReportOmissionsNeverBecomeAnAllFindingsExportClaim() {
        val source=JsonObject(payload()+mapOf("reportFindingCount" to JsonPrimitive(100),"reportOmittedFindingCount" to JsonPrimitive(800),
            "reportContainsAllFindings" to JsonPrimitive(false),"reportLimitationsTruncated" to JsonPrimitive(true)))
        val value=parsed(result(source),1600)
        assertEquals(800,value.getValue("reportOmittedFindingCount").jsonPrimitive.int)
        assertFalse(value.getValue("reportContainsAllFindings").jsonPrimitive.boolean)
        assertTrue(value.getValue("reportLimitationsTruncated").jsonPrimitive.boolean)
        assertTrue(value.containsKey("resumeArguments"))
    }

    @Test fun hugeMetadataCannotEvictAnalysisResumeVersionCoverageGroundedPreviewsOrArtifact() {
        val value=parsed(result())
        assertEquals(id,value.getValue("analysisId").jsonPrimitive.content)
        assertEquals(id,value.getValue("resumeWithAnalysisId").jsonPrimitive.content)
        assertEquals(16,value.getValue("resumeExpectedProcessedChunks").jsonPrimitive.int)
        assertEquals(16,value.getValue("resumeArguments").jsonObject.getValue("expectedProcessedChunks").jsonPrimitive.int)
        assertEquals(4096,value.getValue("coverage").jsonObject.getValue("processedBytes").jsonPrimitive.int)
        assertEquals(2,value.getValue("findings").jsonArray.size)
        assertTrue(value.getValue("findings").jsonArray.all {it.jsonObject.containsKey("quote")&&it.jsonObject.containsKey("evidenceId")&&it.jsonObject.containsKey("timeReferences")})
        assertEquals(file,value.getValue("artifact").jsonObject.getValue("fileName").jsonPrimitive.content)
        assertFalse(value.toString().contains("UNRELATED-LARGE-METADATA"))
        assertTrue(value.getValue("sourceScopePartial").jsonPrimitive.boolean)
    }

    @Test fun smallestBudgetStillRetainsContinuationAndTwoCitationIdsWithValidUnicode() {
        val value=parsed(result(),1600)
        assertEquals(id,value.getValue("analysisId").jsonPrimitive.content)
        assertTrue(value.containsKey("resumeArguments"));assertTrue(value.containsKey("coverage"));assertTrue(value.containsKey("artifact"))
        assertEquals(2,value.getValue("findings").jsonArray.size)
        assertTrue(value.getValue("contextTruncated").jsonPrimitive.boolean)
        value.getValue("findings").jsonArray.forEach {row->
            val quote=row.jsonObject.getValue("quote").jsonPrimitive.content
            assertFalse(quote.last().isHighSurrogate())
        }
    }

    @Test fun allCoverageReasonsRemainPartialWithoutOverflowingTheMinimumBudget() {
        val value=parsed(result(payload(PrivateCoverageReason.entries.map {it.name})),1600)
        assertEquals(90000,value.getValue("coverage").jsonObject.getValue("knownTextBytes").jsonPrimitive.int)
        assertFalse(value.getValue("coverage").jsonObject.getValue("completeSelectedText").jsonPrimitive.boolean)
        assertTrue(value.getValue("sourceScopePartial").jsonPrimitive.boolean)
        assertTrue(value.containsKey("resumeArguments"));assertEquals(2,value.getValue("findings").jsonArray.size)
    }

    @Test fun sampledOverviewLimitSurvivesMinimumBudgetAlongWithAllCoverageReasons() {
        val source=JsonObject(payload(PrivateCoverageReason.entries.map {it.name})+mapOf(
            "overviewIndexedFindings" to JsonPrimitive(48),
            "overviewSelectionVerified" to JsonPrimitive(true),
            "overviewIndexSampled" to JsonPrimitive(true)))
        val value=parsed(result(source),1600)
        assertEquals(48,value.getValue("overviewIndexedFindings").jsonPrimitive.int)
        assertTrue(value.getValue("overviewSelectionVerified").jsonPrimitive.boolean)
        assertTrue(value.getValue("overviewIndexSampled").jsonPrimitive.boolean)
        assertEquals(2,value.getValue("findings").jsonArray.size)
        assertTrue(value.containsKey("resumeArguments"))
    }

    @Test fun partialVerifiedArtifactCandidateDoesNotRequireClaimingAnalysisCompletion() {
        assertEquals("[[file:$file]]",PrivateAnalysisModelContext.trustedArtifactToken(result()))
        val value=parsed(result())
        assertEquals("UNVERIFIED",value.getValue("verification").jsonPrimitive.content)
        assertFalse(value.getValue("analysisCompleteWithinSelectedText").jsonPrimitive.boolean)
        assertFalse(value.getValue("interpretationAccuracyVerified").jsonPrimitive.boolean)
    }

    @Test fun artifactCandidateRejectsPathsRemoteNamesWrongTypesAndFailedResults() {
        for(name in listOf("../$file","C:/$file","https://example.com/$file",file.replace(".txt",".pdf"))) {
            assertNull(PrivateAnalysisModelContext.trustedArtifactToken(result(JsonObject(payload()+mapOf("artifact" to artifact(name))))))
        }
        val original=artifact()
        for(change in listOf("privateArtifact" to JsonPrimitive(false),"privateArtifact" to JsonPrimitive("true"),"privateArtifact" to buildJsonObject {},
            "exportOrShareRequiresUserAction" to JsonPrimitive(false),"sha256" to JsonPrimitive("invented"),"bytes" to JsonPrimitive(-1),"mimeType" to JsonPrimitive("application/pdf"))) {
            val bad=JsonObject(original+change)
            assertNull(PrivateAnalysisModelContext.trustedArtifactToken(result(JsonObject(payload()+mapOf("artifact" to bad)))))
        }
        assertNull(PrivateAnalysisModelContext.trustedArtifactToken(result(success=false)))
        assertNull(PrivateAnalysisModelContext.trustedArtifactToken(result(state=VerificationState.FAILED)))
    }

    @Test fun detailedEvidencePreviewPreservesPagedAvailabilityAndDoesNotClaimExhaustedSource() {
        val content=buildJsonObject {
            put("analysisId",id);put("findings",JsonArray((1..10).map {finding(it)}));put("totalFindings",60);put("offset",10);put("hasMore",true)
            put("sourceExhausted",false);put("semanticAccuracyVerified",false)
        }
        val value=parsed(result(content))
        assertEquals(2,value.getValue("findings").jsonArray.size)
        assertEquals(60,value.getValue("totalFindings").jsonPrimitive.int)
        assertTrue(value.getValue("hasMore").jsonPrimitive.boolean);assertFalse(value.getValue("sourceExhausted").jsonPrimitive.boolean)
        assertTrue(value.getValue("sourceScopePartial").jsonPrimitive.boolean)
    }

    @Test fun listingKeepsRealTypedSourceIdsAndSignalsItsOmittedRows() {
        val content=buildJsonObject {
            put("sources",buildJsonArray {repeat(20){add(buildJsonObject {
                put("sourceType","conversation_import");put("contentSha256",it.toString().padStart(64,'a'));put("label","자료 설명 ".repeat(50));put("bodyFetched",false)
            })}});put("catalogEntries",20);put("hasMore",false);put("bodyFetched",false)
        }
        val value=parsed(result(content),1600)
        assertTrue(value.getValue("sources").jsonArray.isNotEmpty())
        assertTrue(value.getValue("sources").jsonArray.all {it.jsonObject.getValue("contentSha256").jsonPrimitive.content.length==64})
        assertTrue(value.getValue("catalogPreviewPartial").jsonPrimitive.boolean)
        assertFalse(value.getValue("bodyFetched").jsonPrimitive.boolean)
        assertTrue(value.getValue("sourceScopePartial").jsonPrimitive.boolean)
    }

    @Test fun transcriptionIdAndRecognitionLimitsSurviveWithoutInventingWordOrSpeakerVerification() {
        val value=parsed(result(buildJsonObject {
            put("status","ok");put("transcriptId",id);put("durationMillis",30000);put("chunkCount",2)
            put("fullTextPersisted",true);put("nextTool","analyze_audio_transcript")
            put("timestampPrecision","decoded_15_second_window");put("wordTimestamps",false);put("speakerDiarization",false);put("speakerIdentity",false)
            put("recognitionAccuracyVerified",false);put("originalSpeechCompletenessVerified",false);put("audioSentToServer",false)
        },state=VerificationState.VERIFIED))
        assertEquals(id,value.getValue("transcriptId").jsonPrimitive.content)
        assertEquals("analyze_audio_transcript",value.getValue("nextTool").jsonPrimitive.content)
        assertFalse(value.getValue("originalSpeechCompletenessVerified").jsonPrimitive.boolean)
        assertFalse(value.getValue("speakerIdentity").jsonPrimitive.boolean)
        assertEquals("decoded_15_second_window",value.getValue("timestampPrecision").jsonPrimitive.content)
    }

    @Test fun malformedResultAndInventedAnalysisIdentifiersDoNotBecomeResumableOrTrustedArtifacts() {
        val failed=ToolResult("synthetic",false,"not json",VerificationState.FAILED)
        assertTrue(parsed(failed).getValue("sourceScopePartial").jsonPrimitive.boolean)
        assertNull(PrivateAnalysisModelContext.analysisId(failed));assertNull(PrivateAnalysisModelContext.trustedArtifactToken(failed))
        val bad=result(JsonObject(payload()+mapOf("analysisId" to JsonPrimitive("invented"))))
        assertNull(PrivateAnalysisModelContext.analysisId(bad));assertNull(PrivateAnalysisModelContext.trustedArtifactToken(bad))
        assertFalse(parsed(bad).containsKey("resumeArguments"))
    }

    @Test fun exhaustedPartialCaptureCannotKeepStaleResumeArguments() {
        val value=parsed(result(JsonObject(payload()+mapOf("canResumeStoredText" to JsonPrimitive(false),"sourceExhausted" to JsonPrimitive(true)))))
        assertFalse(value.getValue("canResumeStoredText").jsonPrimitive.boolean)
        assertFalse(value.containsKey("resumeArguments"));assertFalse(value.containsKey("resumeWithAnalysisId"));assertFalse(value.containsKey("resumeExpectedProcessedChunks"))
        assertTrue(value.getValue("sourceScopePartial").jsonPrimitive.boolean)
    }

    @Test fun twoSmallPreviewsDoNotBecomeTheWholeDetailedReportWhenCoverageIsComplete() {
        val base=payload().toMutableMap()
        base["status"]=JsonPrimitive("complete_selected_text");base["analysisCompleteWithinSelectedText"]=JsonPrimitive(true)
        base["canResumeStoredText"]=JsonPrimitive(false);base["sourceExhausted"]=JsonPrimitive(true)
        base["coverage"]=buildJsonObject {put("knownTextBytes",100);put("processedBytes",100);put("retrievedBytes",100);put("completeSelectedText",true);put("reasons",buildJsonArray {})}
        base["findings"]=JsonArray(listOf(finding(1,"small","one"),finding(2,"small","two")))
        val value=parsed(result(JsonObject(base),state=VerificationState.VERIFIED))
        assertTrue(value.getValue("analysisCompleteWithinSelectedText").jsonPrimitive.boolean)
        assertTrue(value.getValue("sourceScopePartial").jsonPrimitive.boolean)
        assertTrue(value.getValue("contextTruncated").jsonPrimitive.boolean)
        assertEquals(60,value.getValue("totalSavedFindings").jsonPrimitive.int)
    }
}
