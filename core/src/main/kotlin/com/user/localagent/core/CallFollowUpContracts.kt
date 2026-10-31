package com.user.localagent.core

import kotlinx.serialization.Serializable

/** HOST input only: the audio hash must be read from the selected recording's actual bytes. */
data class CallTranscriptChunk(
    val id:String,val startMillis:Long,val endMillis:Long,val text:String,val textSha256:String,
    val speakerId:String?=null,val asrQuality:CallAsrQuality=CallAsrQuality(),
) {
    override fun toString()="CallTranscriptChunk(startMillis=$startMillis,endMillis=$endMillis,privateContentOmitted=true)"
}

enum class CallAsrConfidenceKind { UNKNOWN, ENGINE_REPORTED }
enum class CallTimeAnchorKind { CALL_END_EVENT, USER_CONFIRMED, UNKNOWN }
/** Never populate this with an LLM self-score; even a real ASR score is not semantic verification. */
data class CallAsrQuality(val kind:CallAsrConfidenceKind=CallAsrConfidenceKind.UNKNOWN,
    val confidence:Double?=null,val metric:String?=null) {
    init {
        if(kind==CallAsrConfidenceKind.UNKNOWN)require(confidence==null&&metric==null)
        else require(confidence!=null&&confidence.isFinite()&&confidence in 0.0..1.0&&
            metric!=null&&metric.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
    }
}

/** Keeps exact full transcript text. It neither reads audio nor persists a shortened replacement. */
class CallFollowUpSource private constructor(
    val scopeId:String,val audioRelativePath:String,val audioSha256:String,val durationMillis:Long,
    val callEndedAtIso:String?,val chunks:List<CallTranscriptChunk>,val transcriptSha256:String,
    val transcriptionExhausted:Boolean,val knownAsrOmission:Boolean,val verifiedSpeakerIds:Set<String>,
    val callTimeAnchorKind:CallTimeAnchorKind,
) {
    override fun toString()="CallFollowUpSource(chunkCount=${chunks.size},privateSourceOmitted=true)"
    companion object {
        fun create(scopeId:String,audioRelativePath:String,audioSha256:String,durationMillis:Long,
            callEndedAtIso:String?,chunks:List<CallTranscriptChunk>,transcriptionExhausted:Boolean,
            knownAsrOmission:Boolean=false,verifiedSpeakerIds:Set<String> = emptySet(),
            callTimeAnchorKind:CallTimeAnchorKind=CallTimeAnchorKind.UNKNOWN):CallFollowUpSource {
            CallFollowUpChecks.identifier(scopeId)
            CallFollowUpChecks.relativePath(audioRelativePath)
            CallFollowUpChecks.hash(audioSha256)
            require(durationMillis in 1..86_400_000L&&chunks.size in 1..10_000){"통화 원문 범위를 확인해주세요."}
            require(callEndedAtIso!=null||callTimeAnchorKind==CallTimeAnchorKind.UNKNOWN){"실제 통화 시각 근거가 필요합니다."}
            callEndedAtIso?.let {runCatching {java.time.OffsetDateTime.parse(it)}.getOrElse {throw IllegalArgumentException("통화 시각 형식이 올바르지 않습니다.")}}
            require(verifiedSpeakerIds.size<=32);verifiedSpeakerIds.forEach(CallFollowUpChecks::identifier)
            val frozen=chunks.map {it.copy()}
            var bytes=0L
            val ids=hashSetOf<String>()
            frozen.forEachIndexed {index,c->
                CallFollowUpChecks.identifier(c.id);require(ids.add(c.id)){"전사 조각 ID가 중복되었습니다."}
                require(c.startMillis>=0&&c.endMillis>c.startMillis&&c.endMillis<=durationMillis)
                if(index>0)require(c.startMillis>=frozen[index-1].endMillis){"겹치거나 역순인 전사 시간 범위입니다."}
                c.speakerId?.let(CallFollowUpChecks::identifier)
                val encoded=PrivateAnalysisEvidence.strictUtf8(c.text)
                require(encoded.size<=64_000){"전사 조각 원문이 지원 범위를 넘었습니다."}
                bytes+=encoded.size;require(bytes<=PrivateAnalysisSource.MAX_SOURCE_BYTES)
                CallFollowUpChecks.hash(c.textSha256)
                require(PrivateAnalysisEvidence.sha256(encoded)==c.textSha256){"전사 조각 해시가 원문과 다릅니다."}
            }
            val digest=CallFollowUpChecks.fingerprint(listOf("call-followup-transcript-v1",scopeId,audioRelativePath,audioSha256,
                durationMillis.toString(),callEndedAtIso.orEmpty(),callTimeAnchorKind.name,transcriptionExhausted.toString(),
                knownAsrOmission.toString(),verifiedSpeakerIds.size.toString(),frozen.size.toString())+verifiedSpeakerIds.sorted()+frozen.flatMap {listOf(it.id,it.startMillis.toString(),
                it.endMillis.toString(),it.textSha256,it.speakerId.orEmpty(),it.asrQuality.kind.name,
                it.asrQuality.confidence?.toString().orEmpty(),it.asrQuality.metric.orEmpty())})
            return CallFollowUpSource(scopeId,audioRelativePath,audioSha256,durationMillis,callEndedAtIso,
                java.util.Collections.unmodifiableList(frozen),digest,transcriptionExhausted,knownAsrOmission,
                java.util.Collections.unmodifiableSet(verifiedSpeakerIds.toSet()),callTimeAnchorKind)
        }
    }
}

/** Supplied by the HOST after successfully validating each model-processed chunk, never by the model. */
data class CallProcessingCoverage(val processedChunkIds:Set<String>,val interrupted:Boolean=false)

@Serializable enum class CallFollowUpKind { SCHEDULE, NOTE, EMAIL_FOLLOW_UP }
@Serializable enum class CallSpeechAct { COMMITMENT, REQUEST, QUESTION, NEGATED, CONDITIONAL, CORRECTION, INFORMATION, UNKNOWN }
@Serializable enum class CallFollowUpDomain { ADMINISTRATIVE, CLINICAL_REFERENCE, CLINICAL_JUDGMENT }
@Serializable enum class CallFollowUpSensitivity { PERSONAL, HEALTH, FINANCIAL, WORK_CONFIDENTIAL }
@Serializable enum class CallFieldUncertainty { UNKNOWN, EXPLICIT_BUT_ASR_UNVERIFIED, CONFLICTING, MISSING }
@Serializable enum class CallCriticalFieldKind { PERSON_NAME, PHONE_NUMBER, EMAIL_ADDRESS, AMOUNT, DATE, TIME, END_TIME, LOCATION, COUNTERPARTY, RECIPIENT, DEADLINE }
@Serializable enum class CallTemporalBasis { EXPLICIT_CALENDAR, RELATIVE_TO_CALL, UNKNOWN }
@Serializable enum class CallTemporalPrecision { DATE_TIME, DATE_ONLY, AMBIGUOUS, MISSING }

/** Byte positions are UTF-8 within exactly this transcript chunk; audio span is chunk-level, not word alignment. */
@Serializable data class CallFollowUpCitation(
    val id:String,val chunkId:String,val textSha256:String,val startByte:Int,val endByte:Int,val quote:String,
    val audioStartMillis:Long,val audioEndMillis:Long,
) { override fun toString()="CallFollowUpCitation(startByte=$startByte,endByte=$endByte,privateQuoteOmitted=true)" }

@Serializable data class CallCriticalField(val kind:CallCriticalFieldKind,val rawValue:String,val citationIds:List<String>,
    val uncertainty:CallFieldUncertainty=CallFieldUncertainty.UNKNOWN) {
    override fun toString()="CallCriticalField(kind=$kind,privateValueOmitted=true)"
}
@Serializable data class CallTemporalProposal(
    val precision:CallTemporalPrecision=CallTemporalPrecision.MISSING,val basis:CallTemporalBasis=CallTemporalBasis.UNKNOWN,
    val startIso:String?=null,val endIso:String?=null,val dateLocal:String?=null,val timezone:String="Asia/Seoul",
    val citationIds:List<String> = emptyList(),val ambiguity:String?=null,
) { override fun toString()="CallTemporalProposal(precision=$precision,basis=$basis,privateTimesOmitted=true)" }

@Serializable data class CallFollowUpCandidate(
    val id:String,val kind:CallFollowUpKind,val speechAct:CallSpeechAct,val summary:String,
    val citations:List<CallFollowUpCitation>,val criticalFields:List<CallCriticalField> = emptyList(),
    val temporal:CallTemporalProposal?=null,val speakerId:String?=null,
    val replacesCandidateIds:List<String> = emptyList(),val domain:CallFollowUpDomain=CallFollowUpDomain.ADMINISTRATIVE,
    val sensitivity:Set<CallFollowUpSensitivity> = emptySet(),val uncertainties:List<String> = emptyList(),
) { override fun toString()="CallFollowUpCandidate(kind=$kind,speechAct=$speechAct,privateContentOmitted=true)" }

@Serializable data class CallFollowUpProposal(
    val schemaVersion:Int=1,val scopeId:String,val audioRelativePath:String,val audioSha256:String,
    val transcriptSha256:String,val claimsComplete:Boolean=false,val candidates:List<CallFollowUpCandidate>,
) { override fun toString()="CallFollowUpProposal(candidateCount=${candidates.size},privateContentOmitted=true)" }

enum class CallFollowUpReviewReason {
    ASR_CONFIDENCE_UNKNOWN, ASR_ACCURACY_UNVERIFIED, SPEAKER_UNKNOWN, CRITICAL_FIELD_CONFIRMATION,
    TIME_MISSING, TIME_AMBIGUOUS, RELATIVE_DATE_ANCHOR, TRANSCRIPT_PARTIAL,
    QUESTION_ONLY, NEGATED, CONDITIONAL, SUPERSEDED, CORRECTION_REQUIRES_REVIEW,
    UNKNOWN_SPEECH_ACT, CLINICAL_REFERENCE_ONLY,
    CALL_TIME_ANCHOR_UNKNOWN,
}
data class ValidatedCallFollowUpCandidate(val proposal:CallFollowUpCandidate,val canProposeAction:Boolean,
    val reviewReasons:Set<CallFollowUpReviewReason>,val missingCriticalFields:Set<CallCriticalFieldKind> = emptySet()) {
    val requiresApproval:Boolean get()=true
    val semanticAccuracyVerified:Boolean get()=false
    val criticalFieldsRequiringConfirmation:Set<CallCriticalFieldKind> get()=proposal.criticalFields.map {it.kind}.toSet()+missingCriticalFields
    override fun toString()="ValidatedCallFollowUpCandidate(kind=${proposal.kind},canProposeAction=$canProposeAction,privateContentOmitted=true)"
}
data class CallFollowUpCoverageReport(val totalChunks:Int,val processedChunks:Int,val coveredAudioMillis:Long,
    val durationMillis:Long,val completeWithinDeclaredTranscript:Boolean,val reasons:Set<String>,val processedAudioMillis:Long=0) {
    val asrAccuracyVerified:Boolean get()=false
    val wholeCallMeaningVerified:Boolean get()=false
}
data class ValidatedCallFollowUpReport(val source:CallFollowUpSource,val candidates:List<ValidatedCallFollowUpCandidate>,
    val coverage:CallFollowUpCoverageReport) {
    val proposalOnly:Boolean get()=true
    val canExecuteAutomatically:Boolean get()=false
    override fun toString()="ValidatedCallFollowUpReport(candidateCount=${candidates.size},partial=${!coverage.completeWithinDeclaredTranscript},privateContentOmitted=true)"
}

internal object CallFollowUpChecks {
    fun identifier(value:String){require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))){"식별자 형식이 올바르지 않습니다."}}
    fun hash(value:String){require(value.matches(Regex("[a-f0-9]{64}"))){"원문 해시 형식이 올바르지 않습니다."}}
    fun relativePath(value:String){require(value.length in 1..1024&&!value.startsWith('/')&&
        value.none {it.code<32||it in "\\:?#"}&&value.split('/').none {it.isEmpty()||it=="."||it==".."}){"앱 내 상대 녹음 경로가 필요합니다."}}
    fun text(value:String,max:Int){require(value.isNotBlank()&&value.length<=max&&value.none {it.code<32&&it!='\n'&&it!='\t'})
        PrivateAnalysisEvidence.strictUtf8(value)}
    fun fingerprint(fields:List<String>):String {
        val bytes=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use {out->fields.forEach {s->val b=PrivateAnalysisEvidence.strictUtf8(s);out.writeInt(b.size);out.write(b)}}
        return PrivateAnalysisEvidence.sha256(bytes.toByteArray())
    }
}
