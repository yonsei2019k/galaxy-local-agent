package com.user.localagent.callfollowup

import com.user.localagent.core.*

/** Display only. No action is executed and source transcripts/citations remain immutable. */
internal object CallFollowUpReportText {
    fun render(job:CallPipelineJob,report:ValidatedCallFollowUpReport?,partial:Boolean):String=buildString {
        appendLine(if(partial)"통화 분석 중간 보고" else "통화 후속 보고")
        appendLine("로컬 처리 · 원본 녹음 유지 · 일정 등록·메일 발송 미실행")
        appendLine("녹음 길이 ${time(job.snapshot.durationMillis)} · 전사 ${job.chunkCount}구간 · 분석 ${job.analyzedChunks}구간")
        if(partial)appendLine("아직 전체 보고서가 아닙니다. 확인한 내용과 남은 범위를 나누어 보관했습니다.")
        if(job.errorCode!=null)appendLine("처리 상태: ${display(job.errorCode)} · 이어 분석 또는 원음 확인이 필요합니다.")
        if(report==null){
            appendLine("아직 검증 가능한 전사 구간이 없습니다. 아무 후속 조치도 실행하지 않았습니다.")
            return@buildString
        }
        val coverage=report.coverage
        appendLine("처리 범위: ${coverage.processedChunks}/${coverage.totalChunks}구간, ${time(coverage.processedAudioMillis)}/${time(coverage.durationMillis)}")
        appendLine(if(coverage.completeWithinDeclaredTranscript&&!partial)"저장된 전사 구간의 분석은 끝났습니다. 음성 인식과 의미 해석의 정확도가 보증된 것은 아닙니다."
            else "미처리·누락 또는 확인이 필요한 범위가 있습니다. 전체 통화를 분석했다고 확정하지 않습니다.")
        if(coverage.reasons.isNotEmpty())appendLine("범위 확인: ${coverage.reasons.sorted().joinToString(", "){display(it)}}")
        appendLine("발화자 신원·단어별 시각·인식 확신도는 확인되지 않았습니다. 아래 시간은 전사 조각의 범위입니다.")
        appendLine(if(report.source.callTimeAnchorKind==CallTimeAnchorKind.UNKNOWN)"실제 통화 날짜 근거가 없어 ‘내일·다음 주’ 같은 상대 날짜는 확인이 필요합니다."
            else "통화 종료 기준: ${report.source.callEndedAtIso} · 상대 날짜와 실제 약속 시각은 확인이 필요합니다.")
        appendLine()
        if(report.candidates.isEmpty())appendLine("확인 가능한 후속 후보가 추출되지 않았습니다. 할 일이 없다고 확정한 결과는 아닙니다.")
        report.candidates.forEachIndexed {index,validated->
            val candidate=validated.proposal
            appendLine("${index+1}. ${kind(candidate.kind)} · ${speech(candidate.speechAct)}")
            appendLine(display(candidate.summary))
            if(candidate.domain==CallFollowUpDomain.CLINICAL_REFERENCE)appendLine("임상 발언의 검토용 인용입니다. 임상 판단·처방·수행 지시로 사용하지 않습니다.")
            if(candidate.replacesCandidateIds.isNotEmpty())appendLine("정정·철회 관련 후보: ${candidate.replacesCandidateIds.joinToString(", "){display(it)}}")
            candidate.temporal?.let {temporal->
                appendLine("시각 해석: ${when(temporal.precision){CallTemporalPrecision.DATE_TIME->"날짜·시간 후보";CallTemporalPrecision.DATE_ONLY->"날짜만 확인";CallTemporalPrecision.AMBIGUOUS->"불확실";CallTemporalPrecision.MISSING->"미확인"}}")
                temporal.startIso?.let {appendLine("시작 후보: ${display(it)}")}
                temporal.endIso?.let {appendLine("종료 후보: ${display(it)}")}
                temporal.dateLocal?.let {appendLine("날짜 후보: ${display(it)}")}
                temporal.ambiguity?.let {appendLine("확인점: ${display(it)}")}
            }
            candidate.criticalFields.forEach {field->appendLine("${fieldLabel(field.kind)}: ${display(field.rawValue)} · ${uncertainty(field.uncertainty)}")}
            if(validated.missingCriticalFields.isNotEmpty())appendLine("빠진 필수 정보: ${validated.missingCriticalFields.joinToString(", ",transform=::fieldLabel)}")
            candidate.uncertainties.forEach {appendLine("확인점: ${display(it)}")}
            appendLine("검토: ${validated.reviewReasons.joinToString(", "){review(it)}}")
            appendLine(if(validated.canProposeAction)when(candidate.kind){
                CallFollowUpKind.SCHEDULE->"다음 단계: 원음의 날짜·시간·대상을 확인한 뒤 일정 등록을 요청하세요."
                CallFollowUpKind.NOTE->"다음 단계: 내용을 확인하고 정리할 메모를 선택하세요."
                CallFollowUpKind.EMAIL_FOLLOW_UP->"다음 단계: 실제 원문 메일과 수신자를 확인해 답장을 작성하고, 전체 내용을 검토한 뒤 발송하세요."
            } else "다음 단계: 질문·조건·취소·정정·불확실한 내용을 먼저 확인합니다. 실행할 약속으로 간주하지 않습니다.")
            candidate.citations.forEach {citation->
                appendLine("근거 ${display(citation.chunkId)} [${time(citation.audioStartMillis)}–${time(citation.audioEndMillis)}]")
                appendLine(display(citation.quote))
                appendLine("원문 바이트 ${citation.startByte}–${citation.endByte} · SHA-256 ${citation.textSha256}")
            }
            appendLine()
        }
        appendLine("원음 SHA-256: ${report.source.audioSha256}")
        appendLine("전사 버전: ${report.source.transcriptSha256}")
        append("인용·해시 일치는 출처 검증입니다. 전사 오류나 의도 해석까지 검증한 결과는 아닙니다. 실제 변경은 확인한 한 건만 실행합니다.")
    }.also {require(it.length<=2_000_000){"보고서가 저장 범위를 넘었습니다. 내용을 버리지 않고 분할 저장이 필요합니다."}}

    // Source text must not create trusted file/image tokens in the conversation renderer.
    private fun display(value:String)=value.replace("[[","［［").replace("]]","］］")
    private fun time(millis:Long):String {val seconds=millis.coerceAtLeast(0)/1000;return "%02d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)}
    private fun kind(value:CallFollowUpKind)=when(value){CallFollowUpKind.SCHEDULE->"일정 후보";CallFollowUpKind.NOTE->"메모 후보";CallFollowUpKind.EMAIL_FOLLOW_UP->"메일 후속 후보"}
    private fun speech(value:CallSpeechAct)=when(value){CallSpeechAct.COMMITMENT->"약속으로 해석";CallSpeechAct.REQUEST->"요청으로 해석";CallSpeechAct.QUESTION->"질문";CallSpeechAct.NEGATED->"부정·취소";CallSpeechAct.CONDITIONAL->"조건부";CallSpeechAct.CORRECTION->"정정";CallSpeechAct.INFORMATION->"정보 전달";CallSpeechAct.UNKNOWN->"의도 미확인"}
    private fun uncertainty(value:CallFieldUncertainty)=when(value){CallFieldUncertainty.UNKNOWN->"불확실";CallFieldUncertainty.EXPLICIT_BUT_ASR_UNVERIFIED->"발언 표기는 있으나 원음 대조 필요";CallFieldUncertainty.CONFLICTING->"서로 다른 값 확인 필요";CallFieldUncertainty.MISSING->"미확인"}
    private fun fieldLabel(value:CallCriticalFieldKind)=when(value){
        CallCriticalFieldKind.PERSON_NAME->"이름";CallCriticalFieldKind.PHONE_NUMBER->"전화번호";CallCriticalFieldKind.EMAIL_ADDRESS->"메일 주소"
        CallCriticalFieldKind.AMOUNT->"금액·수량";CallCriticalFieldKind.DATE->"날짜";CallCriticalFieldKind.TIME->"시각";CallCriticalFieldKind.END_TIME->"종료 시각"
        CallCriticalFieldKind.LOCATION->"장소";CallCriticalFieldKind.COUNTERPARTY->"상대방";CallCriticalFieldKind.RECIPIENT->"수신자";CallCriticalFieldKind.DEADLINE->"기한"
    }
    private fun review(value:CallFollowUpReviewReason)=when(value){
        CallFollowUpReviewReason.ASR_CONFIDENCE_UNKNOWN->"인식 확신도 없음";CallFollowUpReviewReason.ASR_ACCURACY_UNVERIFIED->"원음 대조 필요"
        CallFollowUpReviewReason.SPEAKER_UNKNOWN->"화자 미확인";CallFollowUpReviewReason.CRITICAL_FIELD_CONFIRMATION->"중요 정보 확인"
        CallFollowUpReviewReason.TIME_MISSING->"시각 미확인";CallFollowUpReviewReason.TIME_AMBIGUOUS->"시각 불확실";CallFollowUpReviewReason.RELATIVE_DATE_ANCHOR->"상대 날짜 확인"
        CallFollowUpReviewReason.TRANSCRIPT_PARTIAL->"전사 일부";CallFollowUpReviewReason.QUESTION_ONLY->"질문";CallFollowUpReviewReason.NEGATED->"부정·취소"
        CallFollowUpReviewReason.CONDITIONAL->"조건부";CallFollowUpReviewReason.SUPERSEDED->"뒤 발언으로 정정됨";CallFollowUpReviewReason.CORRECTION_REQUIRES_REVIEW->"정정 확인"
        CallFollowUpReviewReason.UNKNOWN_SPEECH_ACT->"의도 불확실";CallFollowUpReviewReason.CLINICAL_REFERENCE_ONLY->"임상 인용 검토"
        CallFollowUpReviewReason.CALL_TIME_ANCHOR_UNKNOWN->"통화 날짜 근거 없음"
    }
}
