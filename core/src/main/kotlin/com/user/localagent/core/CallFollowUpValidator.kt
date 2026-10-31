package com.user.localagent.core

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Exact provenance and typed consistency only. Does NOT certify ASR, speaker identity, or model interpretation. */
object CallFollowUpValidator {
    private val json=Json { ignoreUnknownKeys=false;isLenient=false;coerceInputValues=false }
    fun validateJson(source:CallFollowUpSource,coverage:CallProcessingCoverage,raw:String):ValidatedCallFollowUpReport {
        return validate(source,coverage,parseProposal(raw))
    }
    /** Prior candidates must come from the same source's HOST-validated encrypted ledger, never from model context. */
    fun validateDeltaJson(source:CallFollowUpSource,coverage:CallProcessingCoverage,
        previousCandidates:List<CallFollowUpCandidate>,raw:String):ValidatedCallFollowUpReport {
        val delta=parseProposal(raw)
        require(previousCandidates.size+delta.candidates.size<=64){"누적 후속 제안이 지원 범위를 넘었습니다."}
        return validate(source,coverage,delta.copy(candidates=previousCandidates+delta.candidates))
    }
    fun validateModelDraftDelta(source:CallFollowUpSource,coverage:CallProcessingCoverage,
        previousCandidates:List<CallFollowUpCandidate>,raw:String,deliveredChunkIds:Set<String>):ValidatedCallFollowUpReport {
        val delta=CallFollowUpModelContract.resolveModelDraft(source,raw,deliveredChunkIds,previousCandidates.map {it.id}.toSet())
        require(previousCandidates.size+delta.candidates.size<=64)
        return validate(source,coverage,delta.copy(candidates=previousCandidates+delta.candidates))
    }
    private fun parseProposal(raw:String):CallFollowUpProposal {
        require(raw.length<=262_144&&PrivateAnalysisEvidence.strictUtf8(raw).size<=262_144){"후속 제안 JSON이 지원 범위를 넘었습니다."}
        return runCatching {rejectDuplicateKeys(raw);json.decodeFromString<CallFollowUpProposal>(raw)}
            .getOrElse {throw IllegalArgumentException("후속 제안 JSON의 필드·열거값·중복·형식을 확인해주세요.")}
    }
    fun validate(source:CallFollowUpSource,coverage:CallProcessingCoverage,proposal:CallFollowUpProposal):ValidatedCallFollowUpReport {
        require(proposal.schemaVersion==1&&proposal.scopeId==source.scopeId&&proposal.audioRelativePath==source.audioRelativePath&&
            proposal.audioSha256==source.audioSha256&&proposal.transcriptSha256==source.transcriptSha256){"후속 제안의 출처·범위·버전이 원문과 다릅니다."}
        require(proposal.candidates.size<=64){"후속 제안 개수가 지원 범위를 넘었습니다."}
        val byChunk=source.chunks.associateBy {it.id}
        val processed=coverage.processedChunkIds.toSet()
        require(processed.all {it in byChunk}){"처리 기록에 알 수 없는 전사 조각이 있습니다."}
        val covered=source.chunks.sumOf {it.endMillis-it.startMillis}
        val gaps=source.chunks.first().startMillis!=0L||source.chunks.last().endMillis!=source.durationMillis||
            source.chunks.zipWithNext().any {(a,b)->a.endMillis!=b.startMillis}
        val problems=linkedSetOf<String>()
        if(gaps)problems+="MISSING_AUDIO_RANGE"
        if(!source.transcriptionExhausted)problems+="TRANSCRIPTION_NOT_EXHAUSTED"
        if(source.knownAsrOmission)problems+="KNOWN_ASR_OMISSION"
        if(source.chunks.any {it.text.isBlank()})problems+="EMPTY_TRANSCRIPT_CHUNK"
        if(processed.size!=source.chunks.size)problems+="UNPROCESSED_TRANSCRIPT"
        if(coverage.interrupted)problems+="INTERRUPTED"
        val coverageReport=CallFollowUpCoverageReport(source.chunks.size,processed.size,
            covered,source.durationMillis,problems.isEmpty(),problems.toSet(),source.chunks.filter {it.id in processed}.sumOf {it.endMillis-it.startMillis})
        require(!proposal.claimsComplete||coverageReport.completeWithinDeclaredTranscript){"누락·미처리 구간이 있어 전체 처리로 표시할 수 없습니다."}
        val candidates=proposal.candidates.map(::freezeCandidate)
        require(candidates.map {it.id}.distinct().size==candidates.size){"후속 제안 ID가 중복되었습니다."}
        val allIds=hashSetOf<String>()
        val byCandidate=linkedMapOf<String,CallFollowUpCandidate>()
        val superseded=hashSetOf<String>()
        candidates.forEach {candidate->
            CallFollowUpChecks.identifier(candidate.id);CallFollowUpChecks.text(candidate.summary,1000)
            require(candidate.domain!=CallFollowUpDomain.CLINICAL_JUDGMENT){"임상 판단이나 새 치료 지시는 후속 작업으로 만들 수 없습니다."}
            require(candidate.citations.size in 1..8&&candidate.criticalFields.size<=16&&candidate.replacesCandidateIds.size<=8)
            require(candidate.uncertainties.size<=8);candidate.uncertainties.forEach {CallFollowUpChecks.text(it,512)}
            val localCitations=linkedMapOf<String,CallFollowUpCitation>()
            candidate.citations.forEach {citation->
                CallFollowUpChecks.identifier(citation.id);require(allIds.add(citation.id)){"인용 ID가 중복되었습니다."}
                val chunk=byChunk[citation.chunkId]?:errorInvalid("인용할 전사 조각이 없습니다.")
                require(chunk.id in processed){"처리하지 않은 전사 조각을 인용할 수 없습니다."}
                require(citation.textSha256==chunk.textSha256){"인용의 전사 해시가 원문과 다릅니다."}
                require(citation.audioStartMillis==chunk.startMillis&&citation.audioEndMillis==chunk.endMillis){"인용의 음성 시간 범위가 전사 조각과 다릅니다."}
                CallFollowUpChecks.text(citation.quote,2000)
                val bytes=PrivateAnalysisEvidence.strictUtf8(chunk.text)
                require(citation.startByte>=0&&citation.endByte>citation.startByte&&citation.endByte<=bytes.size)
                val exact=runCatching {bytes.copyOfRange(citation.startByte,citation.endByte).decodeToString(throwOnInvalidSequence=true)}
                    .getOrElse {errorInvalid("인용 위치가 UTF-8 문자 경계와 다릅니다.")}
                require(exact==citation.quote){"인용문이 실제 전사 구간과 다릅니다."}
                localCitations[citation.id]=citation
            }
            if(candidate.domain==CallFollowUpDomain.CLINICAL_REFERENCE)require(candidate.kind==CallFollowUpKind.NOTE&&
                candidate.citations.any {it.quote==candidate.summary}){"임상 참고 항목은 새 판단 대신 정확한 원문 인용으로만 보존해주세요."}
            candidate.speakerId?.let {speaker->
                require(speaker in source.verifiedSpeakerIds&&candidate.citations.all {byChunk.getValue(it.chunkId).speakerId==speaker}){
                    "확인되지 않았거나 인용과 다른 화자입니다."}
            }
            candidate.criticalFields.forEach {field->
                if(field.uncertainty==CallFieldUncertainty.MISSING) {
                    require(field.rawValue.isEmpty()&&field.citationIds.isEmpty()){"누락된 필드에 값을 추정할 수 없습니다."}
                }else {
                    CallFollowUpChecks.text(field.rawValue,512)
                    require(field.citationIds.size in 1..8&&field.citationIds.distinct().size==field.citationIds.size)
                    require(field.citationIds.all {id->localCitations[id]?.quote?.contains(field.rawValue)==true}){"중요 필드 원문이 인용 구간에 없습니다."}
                }
            }
            validateTemporal(source,candidate,localCitations)
            require(candidate.replacesCandidateIds.distinct().size==candidate.replacesCandidateIds.size)
            if(candidate.speechAct==CallSpeechAct.CORRECTION)require(candidate.replacesCandidateIds.isNotEmpty()){"정정에는 앞선 제안 참조가 필요합니다."}
            if(candidate.replacesCandidateIds.isNotEmpty()) {
                require(candidate.speechAct in setOf(CallSpeechAct.CORRECTION,CallSpeechAct.NEGATED))
                candidate.replacesCandidateIds.forEach {oldId->
                    val old=byCandidate[oldId]?:errorInvalid("정정 대상은 실제 앞선 제안이어야 합니다.")
                    require(old.kind==candidate.kind&&laterThan(candidate,old,source)){"정정이 기존 발언보다 뒤에 있거나 같은 작업 종류여야 합니다."}
                    superseded+=oldId
                }
            }
            byCandidate[candidate.id]=candidate
        }
        val validated=candidates.map {candidate->
            val reasons=linkedSetOf(CallFollowUpReviewReason.ASR_ACCURACY_UNVERIFIED)
            if(candidate.citations.any {byChunk.getValue(it.chunkId).asrQuality.kind==CallAsrConfidenceKind.UNKNOWN})reasons+=CallFollowUpReviewReason.ASR_CONFIDENCE_UNKNOWN
            if(candidate.speakerId==null)reasons+=CallFollowUpReviewReason.SPEAKER_UNKNOWN
            val present=candidate.criticalFields.filter {it.uncertainty!=CallFieldUncertainty.MISSING}.map {it.kind}.toSet()
            val missing=linkedSetOf<CallCriticalFieldKind>()
            if(candidate.kind==CallFollowUpKind.SCHEDULE){
                if(CallCriticalFieldKind.DATE !in present)missing+=CallCriticalFieldKind.DATE
                if(CallCriticalFieldKind.TIME !in present)missing+=CallCriticalFieldKind.TIME
            }
            if(candidate.kind==CallFollowUpKind.EMAIL_FOLLOW_UP&&CallCriticalFieldKind.RECIPIENT !in present&&CallCriticalFieldKind.EMAIL_ADDRESS !in present)
                missing+=CallCriticalFieldKind.RECIPIENT
            if(candidate.kind!=CallFollowUpKind.NOTE&&candidate.speakerId==null&&CallCriticalFieldKind.COUNTERPARTY !in present)
                missing+=CallCriticalFieldKind.COUNTERPARTY
            if(candidate.criticalFields.isNotEmpty()||missing.isNotEmpty())reasons+=CallFollowUpReviewReason.CRITICAL_FIELD_CONFIRMATION
            if(!coverageReport.completeWithinDeclaredTranscript)reasons+=CallFollowUpReviewReason.TRANSCRIPT_PARTIAL
            when(candidate.speechAct) {
                CallSpeechAct.QUESTION->reasons+=CallFollowUpReviewReason.QUESTION_ONLY
                CallSpeechAct.NEGATED->reasons+=CallFollowUpReviewReason.NEGATED
                CallSpeechAct.CONDITIONAL->reasons+=CallFollowUpReviewReason.CONDITIONAL
                CallSpeechAct.CORRECTION->reasons+=CallFollowUpReviewReason.CORRECTION_REQUIRES_REVIEW
                CallSpeechAct.UNKNOWN->reasons+=CallFollowUpReviewReason.UNKNOWN_SPEECH_ACT
                else->Unit
            }
            if(candidate.id in superseded)reasons+=CallFollowUpReviewReason.SUPERSEDED
            if(candidate.kind==CallFollowUpKind.SCHEDULE)when(candidate.temporal?.precision) {
                CallTemporalPrecision.DATE_TIME->Unit
                CallTemporalPrecision.AMBIGUOUS->reasons+=CallFollowUpReviewReason.TIME_AMBIGUOUS
                else->reasons+=CallFollowUpReviewReason.TIME_MISSING
            }
            if(candidate.temporal?.basis==CallTemporalBasis.RELATIVE_TO_CALL)reasons+=CallFollowUpReviewReason.RELATIVE_DATE_ANCHOR
            if(candidate.temporal?.basis==CallTemporalBasis.RELATIVE_TO_CALL&&source.callTimeAnchorKind==CallTimeAnchorKind.UNKNOWN)
                reasons+=CallFollowUpReviewReason.CALL_TIME_ANCHOR_UNKNOWN
            if(candidate.domain==CallFollowUpDomain.CLINICAL_REFERENCE)reasons+=CallFollowUpReviewReason.CLINICAL_REFERENCE_ONLY
            val actionable=candidate.domain==CallFollowUpDomain.ADMINISTRATIVE&&candidate.id !in superseded&&
                candidate.speechAct !in setOf(CallSpeechAct.QUESTION,CallSpeechAct.NEGATED,CallSpeechAct.CONDITIONAL,CallSpeechAct.UNKNOWN)&&
                (candidate.kind==CallFollowUpKind.NOTE||candidate.speechAct!=CallSpeechAct.INFORMATION)
            ValidatedCallFollowUpCandidate(candidate,actionable,reasons.toSet(),missing.toSet())
        }
        return ValidatedCallFollowUpReport(source,validated,coverageReport)
    }

    private fun validateTemporal(source:CallFollowUpSource,candidate:CallFollowUpCandidate,citations:Map<String,CallFollowUpCitation>) {
        val t=candidate.temporal?:return
        require(t.timezone=="Asia/Seoul"&&t.citationIds.size<=8&&t.citationIds.distinct().size==t.citationIds.size)
        require(t.citationIds.all {it in citations}){"시각 근거 인용이 없습니다."}
        t.ambiguity?.let {CallFollowUpChecks.text(it,512)}
        val date=t.dateLocal?.let {runCatching {LocalDate.parse(it)}.getOrElse {errorInvalid("날짜 형식이 올바르지 않습니다.")}}
        if(t.basis==CallTemporalBasis.RELATIVE_TO_CALL&&t.precision in setOf(CallTemporalPrecision.DATE_TIME,CallTemporalPrecision.DATE_ONLY))
            require(source.callTimeAnchorKind!=CallTimeAnchorKind.UNKNOWN){"실제 통화 시각이 확인되지 않아 상대 날짜를 확정할 수 없습니다."}
        when(t.precision) {
            CallTemporalPrecision.DATE_TIME->{
                require(t.basis!=CallTemporalBasis.UNKNOWN&&t.startIso!=null&&t.citationIds.isNotEmpty())
                val start=runCatching {OffsetDateTime.parse(t.startIso)}.getOrElse {errorInvalid("시작 시각 형식이 올바르지 않습니다.")}
                require(start.offset==ZoneId.of("Asia/Seoul").rules.getOffset(start.toInstant())){"서울 시각 오프셋을 확인해주세요."}
                require(date==null||date==start.toLocalDate())
                t.endIso?.let {value->
                    val end=runCatching {OffsetDateTime.parse(value)}.getOrElse {errorInvalid("종료 시각 형식이 올바르지 않습니다.")}
                    require(end.isAfter(start)&&end.offset==start.offset)
                    require(candidate.criticalFields.any {it.kind==CallCriticalFieldKind.END_TIME&&it.citationIds.any(t.citationIds::contains)}){"종료 시각을 추정할 수 없습니다. 별도 원문 근거가 필요합니다."}
                }
                require(candidate.criticalFields.any {it.kind==CallCriticalFieldKind.DATE&&it.citationIds.any(t.citationIds::contains)}&&
                    candidate.criticalFields.any {it.kind==CallCriticalFieldKind.TIME&&it.citationIds.any(t.citationIds::contains)}){"시작 날짜와 시각 각각의 원문 근거가 필요합니다."}
            }
            CallTemporalPrecision.DATE_ONLY->{
                require(date!=null&&t.startIso==null&&t.endIso==null&&t.basis!=CallTemporalBasis.UNKNOWN&&t.citationIds.isNotEmpty())
                require(candidate.criticalFields.any {it.kind==CallCriticalFieldKind.DATE&&it.citationIds.any(t.citationIds::contains)})
            }
            CallTemporalPrecision.AMBIGUOUS->{require(t.startIso==null&&t.endIso==null&&date==null&&t.ambiguity!=null)}
            CallTemporalPrecision.MISSING->{require(t.startIso==null&&t.endIso==null&&date==null)}
        }
    }
    private fun laterThan(new:CallFollowUpCandidate,old:CallFollowUpCandidate,source:CallFollowUpSource):Boolean {
        val index=source.chunks.mapIndexed {i,c->c.id to i}.toMap()
        val oldLast=old.citations.maxWith(compareBy({index.getValue(it.chunkId)},{it.endByte}))
        val newFirst=new.citations.minWith(compareBy({index.getValue(it.chunkId)},{it.startByte}))
        return index.getValue(newFirst.chunkId)>index.getValue(oldLast.chunkId)||
            index.getValue(newFirst.chunkId)==index.getValue(oldLast.chunkId)&&newFirst.startByte>=oldLast.endByte
    }
    private fun freezeCandidate(c:CallFollowUpCandidate):CallFollowUpCandidate=c.copy(
        citations=java.util.Collections.unmodifiableList(c.citations.toList()),
        criticalFields=java.util.Collections.unmodifiableList(c.criticalFields.map {it.copy(citationIds=java.util.Collections.unmodifiableList(it.citationIds.toList()))}),
        temporal=c.temporal?.let {it.copy(citationIds=java.util.Collections.unmodifiableList(it.citationIds.toList()))},
        replacesCandidateIds=java.util.Collections.unmodifiableList(c.replacesCandidateIds.toList()),
        sensitivity=java.util.Collections.unmodifiableSet(c.sensitivity.toSet()),
        uncertainties=java.util.Collections.unmodifiableList(c.uncertainties.toList()),
    )
    private fun errorInvalid(message:String):Nothing=throw IllegalArgumentException(message)
}
