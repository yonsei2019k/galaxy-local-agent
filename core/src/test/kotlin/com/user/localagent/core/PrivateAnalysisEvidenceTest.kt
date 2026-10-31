package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class PrivateAnalysisEvidenceTest {
    private fun source(text:String,id:String="synthetic-1",scope:String="synthetic-scope",complete:PrivateTextCompleteness=PrivateTextCompleteness.COMPLETE_SELECTED_TEXT)=
        PrivateAnalysisSource.create(scope,id,PrivateEvidenceKind.SMS,text,complete)
    private fun chunks(source:PrivateAnalysisSource,max:Int=128,overlap:Int=16,limit:Int=2):List<PrivateEvidenceChunk> {
        val result=mutableListOf<PrivateEvidenceChunk>()
        var cursor:PrivateChunkCursor?=null
        do {val page=PrivateAnalysisEvidence.page(source,max,overlap,limit,cursor);result+=page.chunks;cursor=page.next}while(cursor!=null)
        return result
    }
    private fun markAll(tracker:PrivateEvidenceCoverage,source:PrivateAnalysisSource):List<PrivateEvidenceChunk> = chunks(source).onEach {chunk->assertTrue(tracker.markRetrieved(source,chunk));tracker.markModelProcessed(chunk.id)}

    @Test fun exactDecodedTextHashesAreStableButDoNotNormalizeDistinctUnicodeForms() {
        val first=source("가😀e\u0301")
        val again=source("가😀e\u0301")
        val normalized=source("가😀é")
        assertEquals(first.ref,again.ref);assertNotEquals(first.ref.contentSha256,normalized.ref.contentSha256)
        assertEquals(10,first.utf8ByteLength)
        assertEquals(chunks(first),chunks(again))
    }
    @Test fun paginationPreservesUtf8BoundariesOverlapAndStableAbsoluteSpans() {
        val text="가😀e\u0301".repeat(50)
        val input=source(text)
        val items=chunks(input)
        assertTrue(items.size>2);assertEquals(0,items.first().startByte);assertEquals(input.utf8ByteLength,items.last().endByte)
        items.forEach {chunk->
            assertTrue(chunk.endByte-chunk.startByte<=128)
            assertEquals(chunk.endByte-chunk.startByte,chunk.text.toByteArray(Charsets.UTF_8).size)
            assertEquals(chunk.text,text.toByteArray(Charsets.UTF_8).copyOfRange(chunk.startByte,chunk.endByte).decodeToString(throwOnInvalidSequence=true))
        }
        items.zipWithNext().forEach {(left,right)->
            assertTrue(right.startByte>left.startByte);assertTrue(right.startByte<=left.endByte)
            assertTrue(left.endByte-right.startByte<=16)
        }
        assertEquals(items.map {it.id}.distinct().size,items.size)
    }
    @Test fun cursorCannotResumeDifferentSourceVersionScopeOrChunkSettings() {
        val input=source("x".repeat(600))
        val page=PrivateAnalysisEvidence.page(input,128,16,1)
        val cursor=page.next!!
        assertTrue(runCatching {PrivateAnalysisEvidence.page(source("y".repeat(600)),128,16,1,cursor)}.isFailure)
        assertTrue(runCatching {PrivateAnalysisEvidence.page(source("x".repeat(600),scope="other"),128,16,1,cursor)}.isFailure)
        assertTrue(runCatching {PrivateAnalysisEvidence.page(input,256,16,1,cursor)}.isFailure)
        assertTrue(runCatching {PrivateAnalysisEvidence.page(input,128,16,1,cursor.copy(nextByte=-1))}.isFailure)
    }
    @Test fun chunkIdsBindSourceIdentityAndScopeEvenWhenTextIsIdentical() {
        val a=chunks(source("identical"))[0]
        val b=chunks(source("identical",id="synthetic-2"))[0]
        val c=chunks(source("identical",scope="other"))[0]
        assertNotEquals(a.id,b.id);assertNotEquals(a.id,c.id)
    }
    @Test fun sourceAndPageBoundsRejectMalformedOrExcessiveInputs() {
        assertTrue(runCatching {source("\uD800")}.isFailure)
        assertTrue(runCatching {source("x".repeat(PrivateAnalysisSource.MAX_SOURCE_BYTES+1))}.isFailure)
        val input=source("abc")
        assertTrue(runCatching {PrivateAnalysisEvidence.page(input,127,0)}.isFailure)
        assertTrue(runCatching {PrivateAnalysisEvidence.page(input,128,65)}.isFailure)
        assertTrue(runCatching {PrivateAnalysisEvidence.page(input,128,0,65)}.isFailure)
    }
    @Test fun exactUniqueQuotesResolveToUtf8ByteOffsetsAndOnlyClaimSpanMatching() {
        val input=source("시작🌤️ 약속은 오후 세 시. 끝.")
        val items=chunks(input)
        val quote="약속은 오후 세 시."
        val result=PrivateAnalysisEvidence.validateCitation(input,items,PrivateCitationProposal(items.single().id,quote))
        assertTrue(result.accepted)
        val citation=result.citation!!
        assertEquals("시작🌤️ ".toByteArray(Charsets.UTF_8).size,citation.startByte)
        assertEquals(quote.toByteArray(Charsets.UTF_8).size,citation.endByte-citation.startByte)
        assertEquals("exact_quote_span_match_only",citation.validationMeaning)
        assertFalse(citation.semanticAccuracyVerified)
    }
    @Test fun repeatedQuoteRequiresExplicitSpanRatherThanChoosingAnOccurrence() {
        val input=source("같은 말, 같은 말")
        val items=chunks(input);val quote="같은 말"
        val ambiguous=PrivateAnalysisEvidence.validateCitation(input,items,PrivateCitationProposal(items[0].id,quote))
        assertEquals(PrivateCitationStatus.AMBIGUOUS_QUOTE,ambiguous.status)
        val exact=PrivateAnalysisEvidence.validateCitation(input,items,PrivateCitationProposal(items[0].id,quote,0,quote.toByteArray(Charsets.UTF_8).size))
        assertTrue(exact.accepted)
    }
    @Test fun citationRejectsInventedChunkStaleSourceForgedTextAndWrongQuotes() {
        val input=source("synthetic exact quote")
        val item=chunks(input).single()
        assertEquals(PrivateCitationStatus.UNKNOWN_CHUNK,PrivateAnalysisEvidence.validateCitation(input,listOf(item),PrivateCitationProposal("made-up","quote")).status)
        assertEquals(PrivateCitationStatus.STALE_OR_WRONG_SOURCE,PrivateAnalysisEvidence.validateCitation(source("changed source"),listOf(item),PrivateCitationProposal(item.id,"quote")).status)
        assertEquals(PrivateCitationStatus.STALE_OR_WRONG_SOURCE,PrivateAnalysisEvidence.validateCitation(source("synthetic exact quote",scope="other"),listOf(item),PrivateCitationProposal(item.id,"quote")).status)
        assertEquals(PrivateCitationStatus.INVALID_CHUNK,PrivateAnalysisEvidence.validateCitation(input,listOf(item.copy(text="forged")),PrivateCitationProposal(item.id,"forged")).status)
        assertEquals(PrivateCitationStatus.QUOTE_MISMATCH,PrivateAnalysisEvidence.validateCitation(input,listOf(item),PrivateCitationProposal(item.id,"EXACT")).status)
    }
    @Test fun explicitCitationSpansCannotSplitUtf8OrDisagreeWithQuotedText() {
        val input=source("😀ABC")
        val item=chunks(input).single()
        assertEquals(PrivateCitationStatus.INVALID_SPAN,PrivateAnalysisEvidence.validateCitation(input,listOf(item),PrivateCitationProposal(item.id,"😀",1,5)).status)
        assertEquals(PrivateCitationStatus.INVALID_SPAN,PrivateAnalysisEvidence.validateCitation(input,listOf(item),PrivateCitationProposal(item.id,"ABC",4,null)).status)
        assertEquals(PrivateCitationStatus.QUOTE_MISMATCH,PrivateAnalysisEvidence.validateCitation(input,listOf(item),PrivateCitationProposal(item.id,"XYZ",4,7)).status)
        assertEquals(PrivateCitationStatus.MALFORMED_QUOTE,PrivateAnalysisEvidence.validateCitation(input,listOf(item),PrivateCitationProposal(item.id,"\uD800")).status)
    }
    @Test fun overlappingAndDuplicateChunksCountOnlyUniqueRetrievedAndProcessedBytes() {
        val input=source("가😀e\u0301".repeat(100))
        val coverage=PrivateEvidenceCoverage("synthetic-scope",1)
        assertTrue(coverage.register(input))
        val items=markAll(coverage,input)
        assertTrue(coverage.markRetrieved(input,items.first()));coverage.markModelProcessed(items.first().id)
        coverage.finishEnumeration()
        val report=coverage.report()
        assertEquals(1000L,report.knownTextBytes);assertEquals(1000L,report.retrievedBytes);assertEquals(1000L,report.modelProcessedBytes)
        assertTrue(report.completeWithinDeclaredScope);assertTrue(report.reasons.isEmpty())
        assertFalse(report.semanticAccuracyVerified);assertFalse(report.wholePhoneHistoryVerified)
    }
    @Test fun retrievedTextDoesNotImplyItWasProcessedByAModel() {
        val input=source("x".repeat(300))
        val coverage=PrivateEvidenceCoverage("synthetic-scope",1)
        coverage.register(input);chunks(input).forEach {coverage.markRetrieved(input,it)};coverage.finishEnumeration()
        val report=coverage.report()
        assertEquals(300L,report.retrievedBytes);assertEquals(0L,report.modelProcessedBytes)
        assertTrue(report.partial);assertTrue(PrivateCoverageReason.UNPROCESSED_TEXT in report.reasons)
        assertTrue(runCatching {coverage.markModelProcessed("not-observed")}.isFailure)
    }
    @Test fun completeByteCoverageCannotHideIncompleteEnumerationOrWrongExpectedCount() {
        val input=source("known text")
        val coverage=PrivateEvidenceCoverage("synthetic-scope",2)
        coverage.register(input);markAll(coverage,input)
        var report=coverage.report()
        assertTrue(PrivateCoverageReason.SOURCE_ENUMERATION_INCOMPLETE in report.reasons)
        assertTrue(PrivateCoverageReason.SOURCE_COUNT_MISMATCH in report.reasons)
        coverage.finishEnumeration();report=coverage.report()
        assertTrue(report.partial);assertTrue(PrivateCoverageReason.SOURCE_COUNT_MISMATCH in report.reasons)
    }
    @Test fun fullyProcessedPartialOrUnknownTextCannotImplyFullOriginalSourceCoverage() {
        for(completeness in listOf(PrivateTextCompleteness.PARTIAL_SELECTED_TEXT,PrivateTextCompleteness.UNKNOWN)) {
            val input=source("bounded excerpt",complete=completeness)
            val coverage=PrivateEvidenceCoverage("synthetic-scope",1)
            coverage.register(input);markAll(coverage,input);coverage.finishEnumeration()
            assertTrue(coverage.report().partial)
        }
        val default=PrivateAnalysisSource.create("synthetic-scope","default",PrivateEvidenceKind.AUDIO_TRANSCRIPT,"recognized words")
        assertEquals(PrivateTextCompleteness.UNKNOWN,default.completeness)
    }
    @Test fun cancellationAndAsrOmissionRemainVisibleAfterAllAvailableTextWasProcessed() {
        val input=source("all available text")
        val coverage=PrivateEvidenceCoverage("synthetic-scope",1)
        coverage.register(input);markAll(coverage,input);coverage.finishEnumeration()
        coverage.addReason(PrivateCoverageReason.CANCELLED);coverage.addReason(PrivateCoverageReason.ASR_OMISSION)
        val report=coverage.report()
        assertTrue(report.partial);assertTrue(PrivateCoverageReason.CANCELLED in report.reasons);assertTrue(PrivateCoverageReason.ASR_OMISSION in report.reasons)
    }
    @Test fun changedSourceVersionsCannotInflateCoverageOrReuseOldClaims() {
        val input=source("version one")
        val coverage=PrivateEvidenceCoverage("synthetic-scope",1)
        coverage.register(input);markAll(coverage,input)
        assertFalse(coverage.register(source("version two")))
        coverage.finishEnumeration()
        val report=coverage.report()
        assertEquals(1,report.registeredSourceCount);assertTrue(report.partial);assertTrue(PrivateCoverageReason.SOURCE_CHANGED in report.reasons)
    }
    @Test fun scopeMismatchSourceLimitsAndChunkLimitsFailExplicitly() {
        val input=source("x".repeat(400))
        val coverage=PrivateEvidenceCoverage("synthetic-scope",2,maxSources=1,maxObservedChunks=1)
        assertTrue(runCatching {coverage.register(source("private",scope="other"))}.isFailure)
        assertTrue(coverage.register(input));assertFalse(coverage.register(source("other",id="synthetic-2")))
        val items=chunks(input)
        assertTrue(coverage.markRetrieved(input,items[0]));assertFalse(coverage.markRetrieved(input,items[1]))
        coverage.markModelProcessed(items[0].id);coverage.finishEnumeration()
        val report=coverage.report()
        assertTrue(PrivateCoverageReason.SOURCE_LIMIT in report.reasons);assertTrue(PrivateCoverageReason.CHUNK_LIMIT in report.reasons);assertTrue(report.partial)
    }
    @Test fun emptySourceAndEmptyScopedCatalogHaveHonestZeroByteCoverage() {
        val input=source("")
        val page=PrivateAnalysisEvidence.page(input)
        assertTrue(page.sourceExhausted);assertNull(page.next);assertTrue(page.chunks.isEmpty())
        val coverage=PrivateEvidenceCoverage("synthetic-scope",1)
        coverage.register(input);coverage.finishEnumeration()
        assertTrue(coverage.report().completeWithinDeclaredScope);assertEquals(0L,coverage.report().knownTextBytes)
        val empty=PrivateEvidenceCoverage("synthetic-scope",0);empty.finishEnumeration()
        assertTrue(empty.report().completeWithinDeclaredScope)
        assertTrue(runCatching {empty.register(source("arrived after EOF"))}.isFailure)
    }
    @Test fun accidentalToStringDoesNotExposeSourceTextQuotesOrPrivateIdentifiers() {
        val privateText="SYNTHETIC-SENSITIVE-CONTENT"
        val input=source(privateText,id="SENSITIVE-ID")
        val item=chunks(input).single()
        val proposal=PrivateCitationProposal(item.id,privateText)
        val citation=PrivateAnalysisEvidence.validateCitation(input,listOf(item),proposal).citation!!
        for(value in listOf(input.toString(),input.ref.toString(),item.toString(),proposal.toString(),citation.toString())) {
            assertFalse(value.contains(privateText));assertFalse(value.contains("SENSITIVE-ID"))
        }
    }
}
