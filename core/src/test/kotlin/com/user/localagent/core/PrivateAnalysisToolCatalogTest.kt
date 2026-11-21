package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateAnalysisToolCatalogTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    private val hash="a".repeat(64)
    private fun call(name:String,vararg args:Pair<String,JsonElement>)=ToolCall("synthetic",name,JsonObject(mapOf(*args)))
    private fun rejected(call:ToolCall)=assertThrows(IllegalArgumentException::class.java){PrivateAnalysisToolCatalog.validate(call)}

    @Test fun resumeDoesNotAcceptANewQuestionRangeOrRetargetedSource() {
        for(name in PrivateAnalysisToolCatalog.analysisNames) {
            PrivateAnalysisToolCatalog.validate(call(name,"analysisId" to JsonPrimitive(id),"expectedProcessedChunks" to JsonPrimitive(16),"maxChunks" to JsonPrimitive(32)))
            rejected(call(name,"analysisId" to JsonPrimitive(id)))
            for(extra in listOf("question" to "replace scope","start" to "2026-10-01T00:00:00+09:00","transcriptId" to id,"contentSha256" to hash)) {
                rejected(call(name,"analysisId" to JsonPrimitive(id),"expectedProcessedChunks" to JsonPrimitive(16),extra.first to JsonPrimitive(extra.second)))
            }
        }
        rejected(call("analyze_sms","analysisId" to JsonPrimitive("invented")))
    }

    @Test fun newAnalysisNeedsTheQuestionAndTypedCatalogIdentityRatherThanAPathOrUri() {
        rejected(call("analyze_sms"))
        PrivateAnalysisToolCatalog.validate(call("analyze_sms","question" to JsonPrimitive("원래 질문")))
        rejected(call("analyze_audio_transcript","question" to JsonPrimitive("확인"),"transcriptId" to JsonPrimitive("content://media/external/audio/1")))
        PrivateAnalysisToolCatalog.validate(call("analyze_audio_transcript","question" to JsonPrimitive("확인"),"transcriptId" to JsonPrimitive(id)))
        PrivateAnalysisToolCatalog.validate(call("analyze_audio_transcript","question" to JsonPrimitive("확인"),"transcriptId" to JsonPrimitive("legacy:$id")))
        rejected(call("analyze_conversation_import","question" to JsonPrimitive("확인"),"contentSha256" to JsonPrimitive("C:/private/chat.txt")))
        PrivateAnalysisToolCatalog.validate(call("analyze_conversation_import","question" to JsonPrimitive("확인"),"contentSha256" to JsonPrimitive(hash)))
    }

    @Test fun unknownTransportOrAuthorityArgumentsCannotBroadenPrivateAnalysis() {
        for(extra in listOf("url","path","cookie","accountId","packageName","allowUpload","sourceScope")) {
            rejected(call("analyze_sms","question" to JsonPrimitive("확인"),extra to JsonPrimitive("untrusted")))
        }
        rejected(call("analyze_sms","question" to JsonPrimitive("x".repeat(2001))))
        rejected(call("analyze_sms","question" to JsonPrimitive("확인"),"maxChunks" to JsonPrimitive(33)))
        rejected(call("analyze_sms","question" to JsonPrimitive("확인"),"maxChunks" to JsonPrimitive(0)))
    }

    @Test fun evidenceLookupAndPagingCannotBeMixedOrExceedTheirBounds() {
        PrivateAnalysisToolCatalog.validate(call("read_analysis_evidence","analysisId" to JsonPrimitive(id),"evidenceId" to JsonPrimitive(hash)))
        PrivateAnalysisToolCatalog.validate(call("read_analysis_evidence","analysisId" to JsonPrimitive(id),"offset" to JsonPrimitive(20),"limit" to JsonPrimitive(10)))
        rejected(call("read_analysis_evidence","analysisId" to JsonPrimitive(id),"evidenceId" to JsonPrimitive(hash),"offset" to JsonPrimitive(0)))
        rejected(call("read_analysis_evidence","analysisId" to JsonPrimitive(id),"evidenceId" to JsonPrimitive(hash),"limit" to JsonPrimitive(1)))
        rejected(call("read_analysis_evidence","analysisId" to JsonPrimitive(id),"offset" to JsonPrimitive(-1)))
        rejected(call("read_analysis_evidence","analysisId" to JsonPrimitive(id),"limit" to JsonPrimitive(11)))
    }

    @Test fun metadataListingOnlyAcceptsItsDeclaredKindsAndLimits() {
        for(kind in listOf("sms","audio_transcript","conversation_import"))PrivateAnalysisToolCatalog.validate(call("list_analysis_sources","kind" to JsonPrimitive(kind),"limit" to JsonPrimitive(50)))
        rejected(call("list_analysis_sources","kind" to JsonPrimitive("all_phone_private_databases")))
        rejected(call("list_analysis_sources","limit" to JsonPrimitive(51)))
        PrivateAnalysisToolCatalog.validate(call("list_analysis_sources","includeSavedReports" to JsonPrimitive(true)))
        rejected(call("list_analysis_sources","includeSavedReports" to JsonPrimitive("true")))
    }
}
