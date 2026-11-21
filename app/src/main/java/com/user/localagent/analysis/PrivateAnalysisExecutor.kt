package com.user.localagent.analysis

import android.content.Context
import com.user.localagent.AppContainer
import com.user.localagent.core.*
import com.user.localagent.data.LocalRepository
import com.user.localagent.models.ChosenInference
import com.user.localagent.inference.preparePrivateAnalysis
import com.user.localagent.inference.privateAnalysisOptions
import com.user.localagent.skills.BundledSkillStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Private source readers and semantic extraction share the current request's authorized repository. */
class PrivateAnalysisExecutor(
    context:Context,private val app:AppContainer,private val repository:LocalRepository,private val scopeId:String,
    private val sources:LocalPrivateAnalysisSources,private val journal:RepositoryAnalysisJournal,
    private val choose:suspend(String)->ChosenInference?,private val progress:(String)->Unit={},
    private val requestEngine:com.user.localagent.inference.InferenceEngine=app.engine,
) {
    private val context=context.applicationContext
    private val skills=BundledSkillStore(this.context)

    suspend fun execute(call:ToolCall):ToolResult {
        PrivateAnalysisToolCatalog.validate(call)
        if(call.name=="list_analysis_sources") {
            val raw=Json.parseToJsonElement(sources.listSources(call.optional("kind"),call.integer("limit",20))).jsonObject
            val checkpoints=repository.notes.first().asSequence().filter {it.collection==RepositoryAnalysisJournal.COLLECTION}.mapNotNull {note->
                val meta=runCatching {Json.parseToJsonElement(note.body).jsonObject}.getOrNull()?:return@mapNotNull null
                if(meta["kind"]?.jsonPrimitive?.content!=RepositoryAnalysisJournal.ROOT_KIND||meta["scopeId"]?.jsonPrimitive?.content!=scopeId)return@mapNotNull null
                if(call.optional("kind")!=null&&meta["sourceLocator"]?.jsonObject?.get("kind")?.jsonPrimitive?.content!=call.optional("kind"))return@mapNotNull null
                buildJsonObject {
                    put("analysisId",note.id);put("question",meta["question"]?.jsonPrimitive?.content.orEmpty().takeSafe(120))
                    put("sourceKind",meta["source"]?.jsonObject?.get("kind")?:JsonNull)
                    put("processedChunks",meta["stepCount"]?:JsonPrimitive(0));put("sourceExhausted",meta["sourceExhausted"]?:JsonPrimitive(false))
                    put("contentAnalysisVerified",false)
                }
            }.take(10).toList()
            val scoped=raw+mapOf("analyses" to JsonArray(checkpoints),"analysesMayHaveMore" to JsonPrimitive(checkpoints.size==10))
            val reportRequested=call.arguments["includeSavedReports"]?.jsonPrimitive?.booleanOrNull==true
            val result=if(reportRequested)scoped+("savedReports" to savedReportPreviews())else scoped
            return ToolResult(call.id,true,JsonObject(result).toString(),VerificationState.VERIFIED,"현재 범위의 자료·저장된 분석 목록 확인 · 내용 분석 완료 아님")
        }
        if(call.name=="read_analysis_evidence")return readEvidence(call)
        require(call.name in PrivateAnalysisToolCatalog.analysisNames)
        val previous=call.optional("analysisId")?.let {journal.load(it)?:error("이 범위의 저장된 분석을 찾지 못했습니다.")}
        if(previous!=null)require(call.integer("expectedProcessedChunks",-1)==previous.processed.size){"저장된 분석 진행 상태가 달라졌습니다. 자료 목록에서 최신 처리 조각 수를 확인한 뒤 이어가세요."}
        val snapshot=if(previous!=null)reload(previous)else when(call.name) {
            "analyze_sms"->sources.sms(call.optional("start"),call.optional("end"))
            "analyze_audio_transcript"->sources.audioTranscript(call.arguments.getValue("transcriptId").jsonPrimitive.content)
            "analyze_conversation_import"->sources.conversationImport(call.arguments.getValue("contentSha256").jsonPrimitive.content.lowercase(java.util.Locale.ROOT))
            else->error("지원하지 않는 분석입니다.")
        }
        val expected=when(call.name){"analyze_sms"->PrivateEvidenceKind.SMS;"analyze_audio_transcript"->PrivateEvidenceKind.AUDIO_TRANSCRIPT;else->PrivateEvidenceKind.IMPORTED_CONVERSATION}
        require(snapshot.coreSource.ref.kind==expected&&snapshot.coreSource.ref.scopeId==scopeId){"이 분석 도구에 맞는 현재 범위의 원문이 필요합니다."}
        val question=previous?.question?:call.arguments.getValue("question").jsonPrimitive.content
        val original=choose(question)?:error("검증된 분석 모델을 선택해야 합니다.")
        val target=(requestEngine as? com.user.localagent.inference.AutomaticComputeEngine)
            ?.memoryAnalysisTarget(original.model,previous?.modelSha256)?:original.model
        val outputBudget=if(previous==null)768 else 1024
        val chosen=original.copy(model=target,options=original.options.privateAnalysisOptions(outputBudget))
        // The same request facade owns this preparation and the existing compute lease/teardown.
        val analysisModel=requestEngine.preparePrivateAnalysis(chosen.model,chosen.options)
        val skill=skills.read(if(expected==PrivateEvidenceKind.AUDIO_TRANSCRIPT)"recording-analysis"else"message-analysis")
        val workflow=PrivateRecordAnalysisWorkflow(LocalPrivateAnalysisDecider(app,chosen,skill,snapshot,requestEngine=requestEngine,analysisModel=analysisModel),journal)
        val locator=snapshot.metadata.getValue("sourceLocator").jsonObject
        progress("원문 범위를 확인했습니다 · ${snapshot.coreSource.utf8ByteLength}바이트")
        val result=workflow.run(snapshot.coreSource,locator,question,analysisModel.modelSha256,skill.sha256,
            analysisId=previous?.id,maxChunks=call.integer("maxChunks",16),progress={done,total->progress("원문 분석 · $done/$total 조각 · 근거와 진행 상태 저장 중")})
        // Output repair may return partial coverage; an actual preparation failure must still fail this run.
        analysisModel.requireUsable()
        val saved=journal.load(result.checkpoint.id)?:error("분석 저장 결과를 다시 읽지 못했습니다.")
        check(saved==result.checkpoint)
        progress("질문에 맞는 저장 근거를 검토 중")
        val overviewDecider=LocalPrivateAnalysisDecider(app,chosen,skills.read("evidence-synthesis"),snapshot,selectOverview=true,requestEngine=requestEngine,analysisModel=analysisModel)
        val overview=PrivateAnalysisOverview(PrivateOverviewDecider {input,schema->overviewDecider.decide(input,schema,false)}).select(saved)
        analysisModel.requireUsable()
        val artifact=writeReport(snapshot,result,overview)
        val findings=allFindings(saved)
        val timeIndex=PrivateTimeReferenceIndex(snapshot.timeRefs,snapshot.coreSource.utf8ByteLength)
        val payload=buildJsonObject {
            put("status",if(result.coverage.partial)"partial"else"complete_selected_text")
            put("analysisId",saved.id);put("analysisCompleteWithinSelectedText",result.coverage.completeWithinDeclaredScope)
            put("coverage",coverageJson(result.coverage));put("analysisScope",snapshot.sourceType)
            put("findings",buildJsonArray {overview.selected.take(2).forEach {add(findingJson(it,snapshot,timeIndex,160,180))}})
            put("overviewSelectionVerified",overview.verified);put("overviewIndexSampled",overview.sampled)
            put("overviewIndexedFindings",overview.indexedCount)
            put("totalSavedFindings",findings.size);put("detailedResultsAvailable",true)
            listOf("reportFindingCount","reportOmittedFindingCount","reportContainsAllFindings","reportLimitationsTruncated").forEach {key->put(key,artifact.getValue(key))}
            put("sourceMetadata",snapshot.metadata)
            put("artifact",artifact)
            put("canResumeStoredText",!saved.sourceExhausted&&saved.nextByte!=null)
            if(!saved.sourceExhausted&&saved.nextByte!=null){put("resumeWithAnalysisId",saved.id);put("resumeExpectedProcessedChunks",saved.processed.size);put("resumeArguments",buildJsonObject {put("analysisId",saved.id);put("expectedProcessedChunks",saved.processed.size)})}
            else put("resumeWithAnalysisId",JsonNull)
            put("interpretationAccuracyVerified",false);put("wholePhoneHistoryVerified",false)
            put("meaning","처리 범위·정확한 전사/본문 인용·저장 결과를 확인했습니다. 해석의 정확성이나 읽지 않은 원문·실제 음성 전체를 보증하지 않습니다.")
        }.toString()
        return ToolResult(call.id,true,payload,if(result.coverage.partial)VerificationState.UNVERIFIED else VerificationState.VERIFIED,
            "원문 ${result.coverage.knownTextBytes}바이트 중 ${result.coverage.modelProcessedBytes}바이트 분석 응답·정확 인용·상세 보고서 저장 확인"+if(result.coverage.partial)" · 부분 분석, 남은 범위 있음"else" · 선택한 저장 본문 범위 처리")
    }

    private suspend fun savedReportPreviews():JsonArray=withContext(Dispatchers.IO) {
        val notes=repository.analysisReportMessages()
        val exportDir=File(context.filesDir,"exports").canonicalFile
        val names=notes.asSequence().flatMap {message->
            REPORT_LINK.findAll(message.text).map {it.groupValues[1]}
        }.distinct()
        JsonArray(names.mapNotNull {name->
            val file=File(exportDir,name)
            if(!file.isFile||file.canonicalFile.parentFile!=exportDir||file.length() !in 1..20L*1024*1024)return@mapNotNull null
            val prefix=file.bufferedReader(Charsets.UTF_8).use {reader->
                val chars=CharArray(2200)
                val count=reader.read(chars)
                if(count>0)String(chars,0,count)else ""
            }
            if(!prefix.startsWith("개인 자료 분석 상세 결과\n"))return@mapNotNull null
            val coverage=prefix.lineSequence().firstOrNull {it.startsWith("처리: ")}.orEmpty()
            val excerpt=prefix.substringAfter("질문에 맞춰 검토할 항목\n","")
                .substringBefore("저장된 상세 결과")
                .lineSequence().filter {it.startsWith("- ")}.take(2).joinToString("\n")
            buildJsonObject {
                put("untrustedData",true)
                put("sourceType","historical_saved_analysis_report")
                put("coverage",coverage.takeSafe(140))
                put("selectedTextCompleteAsRecorded",coverage.endsWith(" · 선택 본문 범위 처리"))
                put("originalSourceReverified",false)
                put("findingsExcerpt",excerpt.takeSafe(440))
            }
        }.take(3).toList())
    }

    private suspend fun reload(checkpoint:PrivateAnalysisCheckpoint):PrivateSourceSnapshot {
        require(checkpoint.source.scopeId==scopeId)
        val locator=checkpoint.sourceLocator
        val snapshot=when(locator.getValue("kind").jsonPrimitive.content) {
            "sms"->sources.cachedSms(locator.getValue("snapshotId").jsonPrimitive.content)
            "audio_transcript"->sources.audioTranscript(locator.getValue("transcriptId").jsonPrimitive.content)
            "conversation_import"->sources.conversationImport(locator.getValue("contentSha256").jsonPrimitive.content)
            else->error("저장된 분석의 출처 종류를 확인하지 못했습니다.")
        }
        require(snapshot.coreSource.ref==checkpoint.source){"원문이 변경되어 예전 분석의 근거로 사용할 수 없습니다. 새 원문으로 분석을 시작해주세요."}
        return snapshot
    }

    private suspend fun readEvidence(call:ToolCall):ToolResult {
        val checkpoint=journal.load(call.arguments.getValue("analysisId").jsonPrimitive.content)?:error("이 범위의 저장된 분석을 찾지 못했습니다.")
        val snapshot=reload(checkpoint)
        val all=allFindings(checkpoint)
        // Recreate host chunks from the exact current source before returning a quote.
        val observed=mutableListOf<PrivateEvidenceChunk>();var cursor:PrivateChunkCursor?=null
        do {
            currentCoroutineContext().ensureActive()
            val page=PrivateAnalysisEvidence.page(snapshot.coreSource,checkpoint.maxChunkBytes,checkpoint.overlapBytes,64,cursor)
            observed+=page.chunks;cursor=page.next
        }while(cursor!=null)
        val evidenceId=call.optional("evidenceId")
        val selected=if(evidenceId!=null)listOf(all.firstOrNull {it.id==evidenceId}?:error("저장된 실제 근거 ID가 필요합니다."))
            else all.drop(call.integer("offset",0)).take(call.integer("limit",3))
        selected.forEach {finding->
            val cite=finding.citation
            check(PrivateAnalysisEvidence.validateCitation(snapshot.coreSource,observed,PrivateCitationProposal(cite.chunkId,cite.quote,cite.startByte,cite.endByte)).citation==cite)
        }
        val timeIndex=PrivateTimeReferenceIndex(snapshot.timeRefs,snapshot.coreSource.utf8ByteLength)
        return ToolResult(call.id,true,buildJsonObject {
            put("analysisId",checkpoint.id);put("question",checkpoint.question);put("findings",JsonArray(selected.map {findingJson(it,snapshot,timeIndex,500,500)}))
            put("status",if(checkpoint.sourceExhausted)"saved_snapshot_read"else"partial")
            put("analysisCompleteWithinSelectedText",checkpoint.sourceExhausted&&snapshot.coreSource.completeness==PrivateTextCompleteness.COMPLETE_SELECTED_TEXT)
            put("canResumeStoredText",!checkpoint.sourceExhausted&&checkpoint.nextByte!=null)
            if(!checkpoint.sourceExhausted&&checkpoint.nextByte!=null){put("resumeWithAnalysisId",checkpoint.id);put("resumeExpectedProcessedChunks",checkpoint.processed.size);put("resumeArguments",buildJsonObject {put("analysisId",checkpoint.id);put("expectedProcessedChunks",checkpoint.processed.size)})}
            put("offset",if(evidenceId==null)call.integer("offset",0)else 0);put("totalFindings",all.size)
            put("hasMore",evidenceId==null&&call.integer("offset",0)+selected.size<all.size)
            put("scope",snapshot.sourceType);put("sourceTextSha256",checkpoint.source.contentSha256)
            put("sourceExhausted",checkpoint.sourceExhausted);put("semanticAccuracyVerified",false)
        }.toString(),VerificationState.VERIFIED,"저장된 분석·현재 원문의 정확한 인용 범위 재확인 · 해석의 타당성 검증 아님")
    }

    private suspend fun writeReport(snapshot:PrivateSourceSnapshot,result:PrivateAnalysisWorkflowResult,overview:OverviewResult):JsonObject=withContext(Dispatchers.IO) {
        val findings=allFindings(result.checkpoint)
        val timeIndex=PrivateTimeReferenceIndex(snapshot.timeRefs,snapshot.coreSource.utf8ByteLength)
        val header=buildString {
            appendLine("개인 자료 분석 상세 결과");appendLine(result.checkpoint.question)
            appendLine("자료: ${snapshot.label} · 선택한 저장 본문")
            appendLine("처리: ${result.coverage.modelProcessedBytes}/${result.coverage.knownTextBytes} UTF-8바이트 · ${if(result.coverage.partial)"부분 분석"else"선택 본문 범위 처리"}")
            appendLine("원문 해시: ${snapshot.coreSource.ref.contentSha256}")
            appendLine("정확한 인용 범위 일치와 모델 해석의 타당성은 별도입니다. 전사문 인용은 실제 음성·화자·단어별 시각의 검증이 아닙니다.")
            if(result.coverage.reasons.isNotEmpty())appendLine("처리 제한: ${result.coverage.reasons.joinToString {reasonLabel(it)}}")
            appendLine()
            appendLine("질문에 맞춰 검토할 항목")
            if(!overview.verified)appendLine("질문에 맞는 항목 선택을 확인하지 못했습니다. 아래 상세 결과를 직접 확인해야 합니다.")
            if(overview.sampled)appendLine("저장된 ${overview.totalFindings}개 항목 중 고르게 추린 ${overview.indexedCount}개 색인에서 선택했습니다. 전체 상세 결과는 아래에 보존되어 있습니다.")
            overview.selected.forEach {finding->appendLine("- ${finding.text}");appendLine("  인용: ${finding.citation.quote}");appendLine("  근거 ID: ${finding.id}")}
            appendLine();appendLine("저장된 상세 결과 · 파일에 포함한 범위는 끝의 건수 표시 참조")
        }
        val originalJob=checkNotNull(currentCoroutineContext()[Job])
        val details=findings.asSequence().mapIndexed {index,finding->
            originalJob.ensureActive()
            buildString {
                appendLine("${index+1}. ${finding.text}")
                appendLine("원문: ${finding.citation.quote}")
                appendLine("근거 ID: ${finding.id} · 본문 UTF-8 ${finding.citation.startByte}–${finding.citation.endByte}")
                val refs=timeIndex.overlapping(finding.citation.startByte,finding.citation.endByte,8)
                refs.forEach {ref->
                    ref.atEpochMillis?.let {appendLine("기록 시각: ${Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Seoul"))}")}
                    if(ref.startMillis!=null&&ref.endMillis!=null)appendLine("음성 디코더 구간: ${ref.startMillis}–${ref.endMillis}ms (단어별 정렬 아님)")
                }
                appendLine()
            }
        }
        val footer=buildString {
            val limitations=result.checkpoint.processed.flatMap {it.limitations}.distinct()
            if(limitations.isNotEmpty()){appendLine("조각별 확인 한계");limitations.forEach {appendLine("- $it")}}
        }
        val budget=PrivateAnalysisReportBudget.build(header,details,findings.size,footer)
        val bytes=budget.text.toByteArray(Charsets.UTF_8);check(bytes.size==budget.utf8Bytes&&bytes.size<=20*1024*1024)
        val directory=File(context.filesDir,"exports").canonicalFile;check(directory.isDirectory||directory.mkdirs())
        val file=File(directory,"artifact-${UUID.randomUUID()}.txt");check(file.canonicalFile.parentFile==directory&&!file.exists())
        currentCoroutineContext().ensureActive()
        var complete=false
        try {
            file.writeBytes(bytes);currentCoroutineContext().ensureActive()
            check(file.length()==bytes.size.toLong()&&sha(file.readBytes())==sha(bytes))
            // Recheck current scope through the encrypted checkpoint after writing the private artifact.
            check(journal.load(result.checkpoint.id)==result.checkpoint)
            complete=true
            buildJsonObject {
                put("fileName",file.name);put("format","txt");put("mimeType","text/plain");put("bytes",bytes.size);put("sha256",sha(bytes));put("privateArtifact",true);put("exportOrShareRequiresUserAction",true)
                put("reportFindingCount",budget.includedFindings);put("reportOmittedFindingCount",budget.omittedFindings)
                put("reportContainsAllFindings",budget.omittedFindings==0);put("reportLimitationsTruncated",budget.footerTruncated)
            }
        }finally {if(!complete&&file.canonicalFile.parentFile==directory)file.delete()}
    }

    companion object {
        private val REPORT_LINK=Regex("""저장한 분석 근거와 상세 결과를 열 수 있습니다\. \[\[file:(artifact-[a-f0-9-]{36}\.txt)]]""")
        private fun ToolCall.optional(key:String)=arguments[key]?.jsonPrimitive?.content
        private fun ToolCall.integer(key:String,default:Int)=arguments[key]?.jsonPrimitive?.int?:default
        private fun allFindings(checkpoint:PrivateAnalysisCheckpoint)=checkpoint.processed.flatMap {it.findings}.distinctBy {it.id}
        private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
        private fun String.takeSafe(max:Int):String {var end=minOf(length,max);if(end>0&&end<length&&this[end-1].isHighSurrogate()&&this[end].isLowSurrogate())end--;return substring(0,end)}
        private fun coverageJson(report:PrivateCoverageReport)=buildJsonObject {
            put("knownTextBytes",report.knownTextBytes);put("processedBytes",report.modelProcessedBytes)
            put("retrievedBytes",report.retrievedBytes);put("completeSelectedText",report.completeWithinDeclaredScope)
            put("reasons",JsonArray(report.reasons.map {JsonPrimitive(it.name)}));put("understandingVerified",false)
        }
        private fun reasonLabel(reason:PrivateCoverageReason)=when(reason) {
            PrivateCoverageReason.SOURCE_ENUMERATION_INCOMPLETE->"자료 목록 읽기 미완료"
            PrivateCoverageReason.SOURCE_COUNT_MISMATCH->"예상 자료 수와 읽은 자료 수가 다름"
            PrivateCoverageReason.SOURCE_LIMIT->"자료 수집 상한 도달"
            PrivateCoverageReason.CHUNK_LIMIT->"이번 처리 상한 도달, 저장한 지점부터 재개 가능"
            PrivateCoverageReason.SOURCE_CHANGED->"원문 변경"
            PrivateCoverageReason.SOURCE_TEXT_PARTIAL->"저장된 원문이 일부 범위임"
            PrivateCoverageReason.SOURCE_TEXT_COMPLETENESS_UNKNOWN->"원문의 완전성 미확인"
            PrivateCoverageReason.UNRETRIEVED_TEXT->"아직 읽지 않은 본문 있음"
            PrivateCoverageReason.UNPROCESSED_TEXT->"아직 분석 응답을 확인하지 않은 본문 있음"
            PrivateCoverageReason.CANCELLED->"사용자 취소"
            PrivateCoverageReason.TIME_LIMIT->"처리 시간 상한 도달"
            PrivateCoverageReason.TOKEN_LIMIT->"모델 문맥 상한 도달"
            PrivateCoverageReason.PROVIDER_ERROR->"자료 제공자 조회 실패"
            PrivateCoverageReason.PERMISSION_REVOKED->"자료 접근 권한 해제"
            PrivateCoverageReason.ASR_OMISSION->"음성 인식 누락 가능"
            PrivateCoverageReason.MODEL_OUTPUT_INVALID->"분석 응답 또는 인용 근거 검증 미완료"
        }
        private fun findingJson(finding:AnalysisFinding,source:PrivateSourceSnapshot,timeIndex:PrivateTimeReferenceIndex,textLimit:Int,quoteLimit:Int)=buildJsonObject {
            put("evidenceId",finding.id);put("finding",finding.text.takeSafe(textLimit));put("quote",finding.citation.quote.takeSafe(quoteLimit))
            put("excerptTruncated",finding.text.length>textLimit||finding.citation.quote.length>quoteLimit)
            put("startByte",finding.citation.startByte);put("endByte",finding.citation.endByte)
            put("exactFullQuoteSaved",true);put("interpretationVerified",false)
            put("timeReferences",buildJsonArray {timeIndex.overlapping(finding.citation.startByte,finding.citation.endByte,3).forEach {ref->add(buildJsonObject {
                ref.atEpochMillis?.let {put("atEpochMillis",it)};ref.startMillis?.let {put("startMillis",it)};ref.endMillis?.let {put("endMillis",it)}
                ref.participantKey?.let {put("participantKey",it)};ref.threadKey?.let {put("threadKey",it)};ref.recordType?.let {put("recordType",it)}
                put("meaning",if(source.sourceType=="audio_transcript")"decoder_window_not_word_alignment"else"source_record_time")
            })}})
        }
    }
}
