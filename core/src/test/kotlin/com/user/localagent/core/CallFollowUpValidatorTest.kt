package com.user.localagent.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** All inputs are synthetic; no recording, contact, mail or provider access. */
class CallFollowUpValidatorTest {
    private fun hash(s:String)=PrivateAnalysisEvidence.sha256(PrivateAnalysisEvidence.strictUtf8(s))
    private fun chunk(text:String,id:String="chunk-1",start:Long=0,end:Long=15_000,speaker:String?=null)=
        CallTranscriptChunk(id,start,end,text,hash(text),speaker)
    private fun source(text:String="자료를 메일로 보내겠습니다.",items:List<CallTranscriptChunk> = listOf(chunk(text)),duration:Long=15_000,
        exhausted:Boolean=true,omission:Boolean=false,speakers:Set<String> = emptySet(),anchor:CallTimeAnchorKind=CallTimeAnchorKind.CALL_END_EVENT)=
        CallFollowUpSource.create("scope-a","recordings/synthetic.m4a",hash("synthetic-audio"),duration,
            "2026-10-31T23:59:00+09:00",items,exhausted,omission,speakers,anchor)
    private fun cite(s:CallFollowUpSource,text:String=s.chunks.first().text,chunkIndex:Int=0,id:String="citation-1"):CallFollowUpCitation {
        val c=s.chunks[chunkIndex];val at=c.text.indexOf(text);require(at>=0)
        val start=c.text.substring(0,at).toByteArray().size
        return CallFollowUpCitation(id,c.id,c.textSha256,start,start+text.toByteArray().size,text,c.startMillis,c.endMillis)
    }
    private fun candidate(s:CallFollowUpSource,act:CallSpeechAct=CallSpeechAct.COMMITMENT,kind:CallFollowUpKind=CallFollowUpKind.EMAIL_FOLLOW_UP)=
        CallFollowUpCandidate("candidate-1",kind,act,"행정 후속 확인",listOf(cite(s)))
    private fun proposal(s:CallFollowUpSource,items:List<CallFollowUpCandidate> = listOf(candidate(s)),complete:Boolean=true)=
        CallFollowUpProposal(scopeId=s.scopeId,audioRelativePath=s.audioRelativePath,audioSha256=s.audioSha256,
            transcriptSha256=s.transcriptSha256,claimsComplete=complete,candidates=items)
    private fun coverage(s:CallFollowUpSource)=CallProcessingCoverage(s.chunks.map {it.id}.toSet())
    private fun validate(s:CallFollowUpSource,p:CallFollowUpProposal=proposal(s),c:CallProcessingCoverage=coverage(s))=
        CallFollowUpValidator.validate(s,c,p)
    private fun rejected(action:()->Unit){assertTrue(runCatching(action).isFailure)}

    @Test fun exactQuoteIsOnlyProvenanceNeverAutomaticApprovalOrAsrVerification() {
        val s=source();val report=validate(s);val c=report.candidates.single()
        assertTrue(report.coverage.completeWithinDeclaredTranscript);assertTrue(report.proposalOnly)
        assertFalse(report.canExecuteAutomatically);assertFalse(report.coverage.asrAccuracyVerified)
        assertFalse(report.coverage.wholeCallMeaningVerified);assertFalse(c.semanticAccuracyVerified)
        assertTrue(c.requiresApproval);assertTrue(c.canProposeAction)
        assertTrue(CallFollowUpReviewReason.ASR_CONFIDENCE_UNKNOWN in c.reviewReasons)
        assertTrue(CallCriticalFieldKind.RECIPIENT in c.missingCriticalFields)
        assertTrue(CallCriticalFieldKind.COUNTERPARTY in c.missingCriticalFields)
    }
    @Test fun rejectsChangedRecordingPathHashScopeOrTranscriptVersion() {
        val s=source();val p=proposal(s)
        listOf(p.copy(scopeId="other"),p.copy(audioSha256=hash("other")),p.copy(audioRelativePath="recordings/other.m4a"),
            p.copy(transcriptSha256=hash("old")),p.copy(schemaVersion=2)).forEach {rejected {validate(s,it)}}
    }
    @Test fun exactUtf8OffsetsRejectFalseQuotePartialEmojiAndInventedWordTimings() {
        val s=source("가😀 자료를 보냅니다.");val c=candidate(s);val q=c.citations.single()
        listOf(q.copy(quote="메일을 보냅니다."),q.copy(startByte=4),q.copy(textSha256=hash("other")),
            q.copy(audioStartMillis=1),q.copy(audioEndMillis=1000),q.copy(chunkId="unknown")).forEach {
            rejected {validate(s,proposal(s,listOf(c.copy(citations=listOf(it)))))}
        }
        assertEquals("😀",validate(s,proposal(s,listOf(c.copy(citations=listOf(cite(s,"😀")))))).candidates.single().proposal.citations.single().quote)
    }
    @Test fun hostCoverageCannotBeInventedOrDoubledByModel() {
        val s=source(items=listOf(chunk("첫 발언",end=5000),chunk("뒤 발언","chunk-2",5000,10_000)),duration=10_000)
        rejected {validate(s,c=CallProcessingCoverage(setOf("chunk-1")))}
        rejected {validate(s,c=CallProcessingCoverage(setOf("chunk-1","chunk-2","made-up")))}
        val p=proposal(s,complete=false)
        val partial=validate(s,p,CallProcessingCoverage(setOf("chunk-1")))
        assertFalse(partial.coverage.completeWithinDeclaredTranscript)
        assertEquals(10_000L,partial.coverage.coveredAudioMillis)
        assertEquals(5000L,partial.coverage.processedAudioMillis)
        rejected {validate(s,proposal(s,listOf(candidate(s).copy(citations=listOf(cite(s,"뒤 발언",1))))),CallProcessingCoverage(setOf("chunk-1")))}
    }
    @Test fun overlappingOrReverseChunksAreRejectedAndGapsCannotClaimWholeCoverage() {
        rejected {source(items=listOf(chunk("첫 발언",end=6000),chunk("뒤 발언","chunk-2",5000,10_000)),duration=10_000)}
        rejected {source(items=listOf(chunk("늦음",start=6000,end=10_000),chunk("이름","chunk-2",0,6000)),duration=10_000)}
        val gap=source(items=listOf(chunk("확인",start=1000,end=5000)),duration=6000)
        rejected {validate(gap)}
        assertTrue("MISSING_AUDIO_RANGE" in validate(gap,proposal(gap,complete=false)).coverage.reasons)
    }
    @Test fun cancellationEmptyChunksAsrOmissionAndIncompleteEnumerationRemainPartial() {
        val s=source()
        rejected {validate(s,c=coverage(s).copy(interrupted=true))}
        rejected {validate(source(exhausted=false))};rejected {validate(source(omission=true))}
        val empty=source("");rejected {validate(empty,proposal(empty,emptyList()))}
        assertTrue("EMPTY_TRANSCRIPT_CHUNK" in validate(empty,proposal(empty,emptyList(),false)).coverage.reasons)
    }
    @Test fun questionNegationConditionalAndUnknownNeverBecomeActionable() {
        val samples=mapOf(CallSpeechAct.QUESTION to "메일로 보낼까요?",CallSpeechAct.NEGATED to "메일을 보내지 마세요.",
            CallSpeechAct.CONDITIONAL to "승인되면 메일로 보내겠습니다.",CallSpeechAct.UNKNOWN to "메일은 그때 봅시다.")
        samples.forEach {(act,text)->val s=source(text);val c=validate(s,proposal(s,listOf(candidate(s,act)))).candidates.single()
            assertFalse(c.canProposeAction);assertTrue(c.requiresApproval)}
    }
    @Test fun correctionRequiresLaterActualEvidenceAndSupersedesOriginalWithoutDeletingIt() {
        val s=source("처음 약속. 아니요 정정합니다.")
        val old=candidate(s).copy(citations=listOf(cite(s,"처음 약속.",id="old-cite")))
        val correction=old.copy(id="candidate-2",speechAct=CallSpeechAct.CORRECTION,
            citations=listOf(cite(s,"아니요 정정합니다.",id="new-cite")),replacesCandidateIds=listOf(old.id))
        val result=validate(s,proposal(s,listOf(old,correction)))
        assertEquals(2,result.candidates.size);assertFalse(result.candidates[0].canProposeAction)
        assertTrue(CallFollowUpReviewReason.SUPERSEDED in result.candidates[0].reviewReasons)
        assertTrue(CallFollowUpReviewReason.CORRECTION_REQUIRES_REVIEW in result.candidates[1].reviewReasons)
        rejected {validate(s,proposal(s,listOf(correction,old)))}
        rejected {validate(s,proposal(s,listOf(old,correction.copy(replacesCandidateIds=listOf("unknown")))))}
        rejected {validate(s,proposal(s,listOf(old,correction.copy(citations=listOf(cite(s,"처음 약속.",id="new-cite"))))))}
    }
    @Test fun dateOnlyAmbiguousAndMissingTimeDoNotInventMidnightOrEndTime() {
        val s=source("11월 2일에 방문하겠습니다.");val c=candidate(s,kind=CallFollowUpKind.SCHEDULE).copy(
            criticalFields=listOf(CallCriticalField(CallCriticalFieldKind.DATE,"11월 2일",listOf("citation-1"))),
            temporal=CallTemporalProposal(CallTemporalPrecision.DATE_ONLY,CallTemporalBasis.EXPLICIT_CALENDAR,
                dateLocal="2026-11-02",citationIds=listOf("citation-1")))
        val result=validate(s,proposal(s,listOf(c))).candidates.single()
        assertNull(result.proposal.temporal!!.startIso);assertTrue(CallFollowUpReviewReason.TIME_MISSING in result.reviewReasons)
        rejected {validate(s,proposal(s,listOf(c.copy(temporal=c.temporal!!.copy(startIso="2026-11-02T00:00:00+09:00")))))}
        val ambiguous=c.copy(temporal=CallTemporalProposal(CallTemporalPrecision.AMBIGUOUS,ambiguity="오전/오후 확인 필요"))
        assertTrue(CallFollowUpReviewReason.TIME_AMBIGUOUS in validate(s,proposal(s,listOf(ambiguous))).candidates.single().reviewReasons)
    }
    @Test fun normalizedDateTimeHasSourceFieldsSeoulOffsetAndNoInventedEnd() {
        val s=source("내일 오후 3시에 만나요.");val c=candidate(s,kind=CallFollowUpKind.SCHEDULE).copy(
            criticalFields=listOf(CallCriticalField(CallCriticalFieldKind.DATE,"내일",listOf("citation-1")),CallCriticalField(CallCriticalFieldKind.TIME,"오후 3시",listOf("citation-1"))),
            temporal=CallTemporalProposal(CallTemporalPrecision.DATE_TIME,CallTemporalBasis.RELATIVE_TO_CALL,
                startIso="2026-11-01T15:00:00+09:00",citationIds=listOf("citation-1")))
        val checked=validate(s,proposal(s,listOf(c))).candidates.single()
        assertTrue(CallFollowUpReviewReason.RELATIVE_DATE_ANCHOR in checked.reviewReasons)
        assertTrue(CallCriticalFieldKind.TIME in checked.criticalFieldsRequiringConfirmation)
        rejected {validate(s,proposal(s,listOf(c.copy(temporal=c.temporal!!.copy(timezone="UTC")))))}
        rejected {validate(s,proposal(s,listOf(c.copy(temporal=c.temporal!!.copy(startIso="2026-11-01T15:00:00Z")))))}
        rejected {validate(s,proposal(s,listOf(c.copy(temporal=c.temporal!!.copy(endIso="2026-11-01T16:00:00+09:00")))))}
        rejected {validate(s,proposal(s,listOf(c.copy(criticalFields=emptyList()))))}
        val unknown=source(s.chunks.single().text,anchor=CallTimeAnchorKind.UNKNOWN)
        rejected {validate(unknown,proposal(unknown,listOf(c)))}
        val unresolved=c.copy(temporal=CallTemporalProposal(CallTemporalPrecision.AMBIGUOUS,CallTemporalBasis.RELATIVE_TO_CALL,ambiguity="통화 날짜 확인 필요"))
        assertTrue(CallFollowUpReviewReason.CALL_TIME_ANCHOR_UNKNOWN in validate(unknown,proposal(unknown,listOf(unresolved))).candidates.single().reviewReasons)
    }
    @Test fun namesNumbersRecipientsAndSpeakersRequireActualQuoteAndHostIdentity() {
        val s=source("담당자에게 010-0000-0000을 확인하세요.")
        val c=candidate(s).copy(criticalFields=listOf(CallCriticalField(CallCriticalFieldKind.PHONE_NUMBER,"010-0000-0000",listOf("citation-1"))))
        assertTrue(CallCriticalFieldKind.PHONE_NUMBER in validate(s,proposal(s,listOf(c))).candidates.single().criticalFieldsRequiringConfirmation)
        rejected {validate(s,proposal(s,listOf(c.copy(criticalFields=listOf(c.criticalFields.single().copy(rawValue="010-1111-1111"))))))}
        rejected {validate(s,proposal(s,listOf(c.copy(speakerId="owner"))))}
        val verified=source(items=listOf(chunk("확인했습니다.",speaker="owner")),speakers=setOf("owner"))
        assertFalse(CallFollowUpReviewReason.SPEAKER_UNKNOWN in validate(verified,proposal(verified,listOf(candidate(verified).copy(speakerId="owner")))).candidates.single().reviewReasons)
    }
    @Test fun missingCriticalValueHasNoInventedEvidence() {
        val s=source();val field=CallCriticalField(CallCriticalFieldKind.RECIPIENT,"",emptyList(),CallFieldUncertainty.MISSING)
        val c=candidate(s).copy(criticalFields=listOf(field))
        assertTrue(CallCriticalFieldKind.RECIPIENT in validate(s,proposal(s,listOf(c))).candidates.single().missingCriticalFields)
        rejected {validate(s,proposal(s,listOf(c.copy(criticalFields=listOf(field.copy(rawValue="누군가"))))))}
    }
    @Test fun clinicalQuotesAreRetainedForReviewWithoutNewClinicalOrders() {
        val s=source("검사 수치는 담당자에게 확인하세요.");val c=candidate(s,kind=CallFollowUpKind.NOTE).copy(
            summary=s.chunks.single().text,domain=CallFollowUpDomain.CLINICAL_REFERENCE,sensitivity=setOf(CallFollowUpSensitivity.HEALTH))
        val checked=validate(s,proposal(s,listOf(c))).candidates.single()
        assertEquals(s.chunks.single().text,checked.proposal.summary);assertFalse(checked.canProposeAction)
        assertTrue(CallFollowUpReviewReason.CLINICAL_REFERENCE_ONLY in checked.reviewReasons)
        rejected {validate(s,proposal(s,listOf(c.copy(summary="새 치료 지시"))))}
        rejected {validate(s,proposal(s,listOf(c.copy(domain=CallFollowUpDomain.CLINICAL_JUDGMENT))))}
        val administration=c.copy(domain=CallFollowUpDomain.ADMINISTRATIVE,summary="검사 예약 확인",speechAct=CallSpeechAct.REQUEST)
        assertTrue(validate(s,proposal(s,listOf(administration))).candidates.single().canProposeAction)
    }
    @Test fun jsonRejectsDuplicateUnknownFieldEnumAndLlmConfidenceInsteadOfUsingThem() {
        val s=source();val raw=Json.encodeToString(proposal(s))
        assertEquals(1,CallFollowUpValidator.validateJson(s,coverage(s),raw).candidates.size)
        val unknown=raw.dropLast(1)+",\"llmConfidence\":0.99}"
        rejected {CallFollowUpValidator.validateJson(s,coverage(s),unknown)}
        rejected {CallFollowUpValidator.validateJson(s,coverage(s),raw.dropLast(1)+",\"audioSha256\":\"${s.audioSha256}\"}")}
        rejected {CallFollowUpValidator.validateJson(s,coverage(s),raw.replace("COMMITMENT","MAYBE_AUTO_SEND"))}
        rejected {CallFollowUpValidator.validateJson(s,coverage(s),raw+" trailing")}
    }
    @Test fun asrEngineScoreIsNotLlmScoreOrVerifiedTruth() {
        rejected {CallAsrQuality(confidence=.99)}
        rejected {CallAsrQuality(CallAsrConfidenceKind.ENGINE_REPORTED,Double.NaN,"synthetic")}
        val c=chunk("확인 발언").copy(asrQuality=CallAsrQuality(CallAsrConfidenceKind.ENGINE_REPORTED,.99,"synthetic_score"))
        val s=source(items=listOf(c));val checked=validate(s).candidates.single()
        assertFalse(CallFollowUpReviewReason.ASR_CONFIDENCE_UNKNOWN in checked.reviewReasons)
        assertTrue(CallFollowUpReviewReason.ASR_ACCURACY_UNVERIFIED in checked.reviewReasons)
        assertFalse(checked.semanticAccuracyVerified)
    }
    @Test fun sourcePreservesFullTailAndRejectsWrongHashUnsafePathOrMalformedUnicode() {
        val text="원문".repeat(4000)+"TAIL-UNCHANGED";val s=source(text)
        assertEquals(text,s.chunks.single().text);assertTrue(s.chunks.single().text.endsWith("TAIL-UNCHANGED"))
        assertNotEquals(s.transcriptSha256,source(text+"changed").transcriptSha256)
        rejected {source(items=listOf(chunk("원문").copy(textSha256=hash("other"))))}
        rejected {source("\uD800")}
        listOf("../recording.m4a","/root/audio.m4a","content://provider/file","a\\b","a//b").forEach {path->
            rejected {CallFollowUpSource.create("scope",path,hash("audio"),15_000,"2026-10-31T12:00:00+09:00",listOf(chunk("확인")),true)}}
        assertFalse(s.toString().contains("TAIL-UNCHANGED"));assertFalse(s.chunks.single().toString().contains("TAIL-UNCHANGED"))
    }
    @Test fun modelInputKeepsExactFullChunkOrFailsRatherThanPretendingItWasDelivered() {
        val text="정확한 원문 ".repeat(300)+"SYNTHETIC-TAIL";val s=source(text)
        val input=CallFollowUpModelContract.promptInput(s,listOf("chunk-1"))
        val root=Json.parseToJsonElement(input).jsonObject
        assertEquals(text,root.getValue("chunks").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("UNKNOWN",root.getValue("chunks").jsonArray.single().jsonObject.getValue("asrConfidenceKind").jsonPrimitive.content)
        rejected {CallFollowUpModelContract.promptInput(s,listOf("chunk-1"),1024)}
        rejected {CallFollowUpModelContract.promptInput(s,listOf("missing"))}
        assertEquals(text,s.chunks.single().text)
        assertFalse(CallFollowUpModelContract.responseSchema.getValue("additionalProperties").jsonPrimitive.boolean)
    }
    @Test fun deltaCanCorrectEarlierChunkOnlyWithoutRedefiningOrChangingOldCandidate() {
        val s=source(items=listOf(chunk("메일로 보냅니다."),chunk("그 메일은 보내지 마세요.","chunk-2",15_000,30_000)),duration=30_000)
        val old=candidate(s)
        val delta=old.copy(id="cancel-2",speechAct=CallSpeechAct.NEGATED,replacesCandidateIds=listOf(old.id),
            citations=listOf(cite(s,"그 메일은 보내지 마세요.",1,"citation-2")))
        val raw=Json.encodeToString(proposal(s,listOf(delta)))
        val result=CallFollowUpValidator.validateDeltaJson(s,coverage(s),listOf(old),raw)
        assertEquals(old,result.candidates.first().proposal);assertFalse(result.candidates.first().canProposeAction)
        assertFalse(result.candidates.last().canProposeAction)
        rejected {CallFollowUpValidator.validateDeltaJson(s,coverage(s),listOf(old),Json.encodeToString(proposal(s,listOf(old))))}
    }
    @Test fun modelDraftResolvesOnlyUniqueExactDeliveredQuoteWithoutModelHashOrByteArithmetic() {
        val s=source("가😀 이제 메일을 보내겠습니다.")
        fun draft(quote:String="이제 메일을 보내겠습니다.",chunkId:String="chunk-1")="""{"candidates":[{"id":"candidate-1","kind":"EMAIL_FOLLOW_UP","speechAct":"COMMITMENT","summary":"자료 송부 제안","domain":"ADMINISTRATIVE","citations":[{"id":"citation-1","chunkId":"$chunkId","quote":"$quote"}]}]}"""
        val p=CallFollowUpModelContract.resolveModelDraft(s,draft(),setOf("chunk-1"))
        assertEquals(s.transcriptSha256,p.transcriptSha256);assertEquals(s.audioSha256,p.audioSha256)
        assertFalse(p.claimsComplete)
        assertEquals("가😀 ".toByteArray().size,p.candidates.single().citations.single().startByte)
        assertEquals(15_000L,p.candidates.single().citations.single().audioEndMillis)
        assertTrue(validate(s,p).candidates.single().requiresApproval)
        rejected {CallFollowUpModelContract.resolveModelDraft(s,draft("보내지 않겠습니다."),setOf("chunk-1"))}
        rejected {CallFollowUpModelContract.resolveModelDraft(s,draft(),emptySet())}
        rejected {CallFollowUpModelContract.resolveModelDraft(s,draft(chunkId="other"),setOf("chunk-1"))}
        val repeated=source("확인합니다. 확인합니다.")
        rejected {CallFollowUpModelContract.resolveModelDraft(repeated,draft("확인합니다."),setOf("chunk-1"))}
        rejected {CallFollowUpModelContract.resolveModelDraft(s,draft().replace("\"quote\":","\"startByte\":7,\"quote\":"),setOf("chunk-1"))}
    }
    @Test fun minimalModelDraftInputPreservesExactCurrentAndPreviousTextAndKeepsHostMetadataOutside() {
        val text="원문 😀 ".repeat(200)+"SYNTHETIC-TAIL"
        val s=source(items=listOf(chunk("이전 발언"),chunk(text,"chunk-2",15_000,30_000)),duration=30_000)
        val minimal=CallFollowUpModelContract.modelDraftInput(s,listOf("chunk-1","chunk-2"))
        val full=CallFollowUpModelContract.promptInput(s,listOf("chunk-1","chunk-2"))
        val root=Json.parseToJsonElement(minimal).jsonObject
        assertEquals(setOf("type","untrustedSourceData","currentChunkId","callTimeAnchorKind","callEndedAtIso","chunks"),root.keys)
        assertEquals("chunk-2",root.getValue("currentChunkId").jsonPrimitive.content)
        assertEquals(text,root.getValue("chunks").jsonArray.last().jsonObject.getValue("text").jsonPrimitive.content)
        root.getValue("chunks").jsonArray.forEach {assertEquals(setOf("id","text"),it.jsonObject.keys)}
        assertFalse(minimal.contains(s.audioSha256));assertFalse(minimal.contains(s.transcriptSha256));assertFalse(minimal.contains(s.audioRelativePath))
        assertTrue(minimal.length<full.length);assertEquals(text,s.chunks.last().text)
        rejected {CallFollowUpModelContract.modelDraftInput(s,listOf("chunk-1","chunk-2"),1024)}
        rejected {CallFollowUpModelContract.modelDraftInput(s,listOf("unknown"))}
        rejected {CallFollowUpModelContract.modelDraftInput(s,listOf("chunk-1","chunk-1"))}
    }
    @Test fun hostAssignsIdsAndRemapsCriticalEvidenceWithoutChangingRawQuote() {
        val s=source("2030년 1월 8일에 회의합니다.")
        val raw="""{"candidates":[{"id":"model-label","kind":"SCHEDULE","speechAct":"COMMITMENT","summary":"회의 일정 제안","domain":"ADMINISTRATIVE","citations":[{"id":"chunk-1","chunkId":"chunk-1","quote":"2030년 1월 8일에 회의합니다."}],"criticalFields":[{"kind":"DATE","rawValue":"2030년 1월 8일","citationIds":["chunk-1"],"uncertainty":"EXPLICIT_BUT_ASR_UNVERIFIED"}]}]}"""
        val p=CallFollowUpModelContract.resolveModelDraft(s,raw,setOf("chunk-1"))
        val item=p.candidates.single()
        assertEquals("c0_1",item.id);assertEquals("c0_1_q1",item.citations.single().id)
        assertEquals(listOf("c0_1_q1"),item.criticalFields.single().citationIds)
        assertEquals(s.chunks.single().text,item.citations.single().quote)
        assertEquals(s.chunks.single().textSha256,item.citations.single().textSha256)
        assertTrue(raw.contains("\"id\":\"chunk-1\""))
        assertTrue(validate(s,p).candidates.single().requiresApproval)
        rejected {CallFollowUpModelContract.resolveModelDraft(s,raw.replace("\"citationIds\":[\"chunk-1\"]","\"citationIds\":[\"missing\"]"),setOf("chunk-1"))}
    }
    @Test fun citationLabelsAreLocalButDuplicatesInsideOneCandidateStillFail() {
        val s=source("자료를 검토합니다. 메일 초안만 준비합니다.")
        val one="""{"id":"first","kind":"NOTE","speechAct":"INFORMATION","summary":"검토","domain":"ADMINISTRATIVE","citations":[{"id":"same-label","chunkId":"chunk-1","quote":"자료를 검토합니다."}]}"""
        val two="""{"id":"second","kind":"EMAIL_FOLLOW_UP","speechAct":"REQUEST","summary":"메일 초안","domain":"ADMINISTRATIVE","citations":[{"id":"same-label","chunkId":"chunk-1","quote":"메일 초안만 준비합니다."}]}"""
        val raw="""{"candidates":[$one,$two]}"""
        val p=CallFollowUpModelContract.resolveModelDraft(s,raw,setOf("chunk-1"))
        assertEquals(2,p.candidates.map {it.citations.single().id}.distinct().size)
        assertTrue(validate(s,p).candidates.all {it.requiresApproval})
        rejected {CallFollowUpModelContract.resolveModelDraft(s,"""{"candidates":[$one,$one]}""",setOf("chunk-1"))}
        val duplicated=one.replace("\"quote\":\"자료를 검토합니다.\"}","\"quote\":\"자료를 검토합니다.\"},{\"id\":\"same-label\",\"chunkId\":\"chunk-1\",\"quote\":\"메일 초안만 준비합니다.\"}")
        rejected {CallFollowUpModelContract.resolveModelDraft(s,"""{"candidates":[$duplicated]}""",setOf("chunk-1"))}
    }
    @Test fun canonicalIdsPreservePriorCorrectionReferencesAndRejectLabelCollision() {
        val s=source(items=listOf(chunk("메일을 보냅니다."),chunk("그 메일은 보내지 마세요.","chunk-2",15_000,30_000)),duration=30_000)
        val first="""{"candidates":[{"id":"original-label","kind":"EMAIL_FOLLOW_UP","speechAct":"COMMITMENT","summary":"메일 제안","domain":"ADMINISTRATIVE","citations":[{"id":"quote-label","chunkId":"chunk-1","quote":"메일을 보냅니다."}]}]}"""
        val old=CallFollowUpModelContract.resolveModelDraft(s,first,setOf("chunk-1")).candidates.single()
        val change="""{"candidates":[{"id":"new-label","kind":"EMAIL_FOLLOW_UP","speechAct":"NEGATED","summary":"메일 발송 취소","domain":"ADMINISTRATIVE","citations":[{"id":"quote-label","chunkId":"chunk-2","quote":"그 메일은 보내지 마세요."}],"replacesCandidateIds":["${old.id}"]}]}"""
        val result=CallFollowUpValidator.validateModelDraftDelta(s,coverage(s),listOf(old),change,setOf("chunk-1","chunk-2"))
        assertEquals(old,result.candidates.first().proposal)
        assertEquals("c1_1",result.candidates.last().proposal.id)
        assertEquals(listOf(old.id),result.candidates.last().proposal.replacesCandidateIds)
        assertFalse(result.candidates.first().canProposeAction);assertFalse(result.candidates.last().canProposeAction)
        rejected {CallFollowUpValidator.validateModelDraftDelta(s,coverage(s),listOf(old),change.replace("new-label",old.id),setOf("chunk-1","chunk-2"))}
    }
    @Test fun minimalInputHidesUntrustedTimeAnchorAndRejectsNonadjacentChunksWithoutTruncating() {
        val chunks=listOf(chunk("발언1"),chunk("발언2","chunk-2",15_000,30_000),chunk("발언3","chunk-3",30_000,45_000))
        val s=source(items=chunks,duration=45_000,anchor=CallTimeAnchorKind.UNKNOWN)
        val root=Json.parseToJsonElement(CallFollowUpModelContract.modelDraftInput(s,listOf("chunk-3"))).jsonObject
        assertEquals(JsonNull,root.getValue("callEndedAtIso"));assertEquals("UNKNOWN",root.getValue("callTimeAnchorKind").jsonPrimitive.content)
        rejected {CallFollowUpModelContract.modelDraftInput(s,listOf("chunk-1","chunk-3"))}
        rejected {CallFollowUpModelContract.modelDraftInput(s,listOf("chunk-1","chunk-2","chunk-3"))}
        assertEquals(listOf("발언1","발언2","발언3"),s.chunks.map {it.text})
    }
}
