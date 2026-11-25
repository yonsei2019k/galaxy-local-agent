package com.user.localagent

import com.user.localagent.core.AgentWorkMode
import com.user.localagent.data.*
import com.user.localagent.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.time.Instant

internal data class WorkActivityTarget(val messageId:String,val runId:String)

/** Read-only projection of actual run/audit rows. Body deltas never recreate its observers. */
class ChatWorkActivityController(private val repository:LocalRepository,private val scope:CoroutineScope) {
    private data class Selection(val threadId:String,val branchId:String,val version:Long)
    private val mutable=MutableStateFlow<Map<String,UiRunActivity>>(emptyMap())
    val activities=mutable.asStateFlow()
    private var selected:Selection?=null
    private var version=0L
    private var observationVersion=0L
    private var observation:Job?=null
    private var targets=emptyList<WorkActivityTarget>()
    private var canonical=emptyMap<String,String>()
    private var runs=emptyMap<String,ChatRunRecord>()
    private var windows=emptyMap<String,WorkTraceWindow>()
    private val older=linkedMapOf<String,MutableMap<String,WorkTraceRecord>>()
    private val pageTotals=mutableMapOf<String,Long>()
    private val olderJobs=mutableMapOf<String,Job>()
    private val unavailable=linkedSetOf<String>()

    fun select(threadId:String,branchId:String) {
        if(selected?.let{it.threadId==threadId&&it.branchId==branchId}==true)return
        selected=Selection(threadId,branchId,++version);observationVersion++
        observation?.cancel();observation=null;olderJobs.values.forEach{it.cancel()};olderJobs.clear()
        targets=emptyList();canonical=emptyMap();runs=emptyMap();windows=emptyMap();older.clear();pageTotals.clear();mutable.value=emptyMap()
    }

    fun acceptMessages(threadId:String,branchId:String,records:List<MessageRecord>) {
        val selection=selected?.takeIf{it.threadId==threadId&&it.branchId==branchId}?:return
        val seen=linkedSetOf<String>()
        val next=records.asReversed().asSequence().filter{it.threadId==threadId&&it.role=="assistant"&&!it.runId.isNullOrBlank()}
            .filter{seen.add(requireNotNull(it.runId))}.take(60).map{WorkActivityTarget(it.id,requireNotNull(it.runId))}.toList().asReversed()
        if(next==targets)return
        targets=next
        val token=++observationVersion;observation?.cancel()
        val ids=next.map{it.runId}.toSet()
        older.keys.retainAll(ids);pageTotals.keys.retainAll(ids)
        olderJobs.keys.toList().filter{it !in ids}.forEach{olderJobs.remove(it)?.cancel()}
        canonical=canonical.filterKeys{it in ids};runs=runs.filterKeys{it in ids};windows=windows.filterKeys{it in ids}
        publish()
        if(next.isEmpty())return
        observation=scope.launch {
            try {
                val verified=linkedMapOf<String,String>();val initialRuns=linkedMapOf<String,ChatRunRecord>()
                for(target in next){
                    currentCoroutineContext().ensureActive()
                    val run=repository.chat.run(target.runId)?:continue
                    if(run.threadId!=threadId)continue
                    val assistant=repository.chat.assistantForRun(run.id)?:continue
                    if(assistant.id!=target.messageId||assistant.threadId!=threadId||assistant.role!="assistant")continue
                    verified[run.id]=assistant.id;initialRuns[run.id]=run
                }
                if(!current(selection,token))return@launch
                canonical=verified;runs=initialRuns;publish()
                val runIds=verified.keys.toList()
                if(runIds.isEmpty())return@launch
                val batchFlows=runIds.chunked(32).map{repository.workTrace.observeWindows(threadId,it)}
                val allWindows=combine(batchFlows){batches->batches.flatMap{it}}
                combine(repository.workTrace.observeRunRows(threadId,runIds),allWindows){rows,trace->rows to trace}
                    .collect{(rows,trace)->
                        if(!current(selection,token))return@collect
                        runs=rows.filter{it.threadId==threadId&&it.id in verified}.associateBy{it.id}
                        windows=trace.filter{it.runId in verified}.associateBy{it.runId}
                        windows.values.filter{it.totalCount==0L&&it.events.isEmpty()}.forEach{older.remove(it.runId);pageTotals.remove(it.runId)}
                        publish()
                    }
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){if(current(selection,token)){next.forEach{markUnavailableNow(it.runId)};publish()}}
        }
    }

    fun markUnavailable(runId:String) {
        if(runId.isBlank()||runId.length>128)return
        scope.launch{markUnavailableNow(runId)}
    }
    private fun markUnavailableNow(runId:String){
        unavailable+=runId;while(unavailable.size>256)unavailable.remove(unavailable.first())
        if(targets.any{it.runId==runId})publish()
    }

    fun loadOlder(runId:String) {
        val selection=selected?:return
        if(olderJobs[runId]?.isActive==true||canonical[runId]==null||targets.none{it.runId==runId})return
        val known=combinedRecords(runId)
        // Fill a gap immediately below the current window before jumping to an old cached page.
        val cursor=contiguousOlderCursor(known)
        val job=scope.launch(start=CoroutineStart.LAZY){
            try {
                val page=repository.workTrace.page(selection.threadId,runId,cursor)
                if(selected!=selection||canonical[runId]==null||targets.none{it.runId==runId})return@launch
                val actualRun=runs[runId]?:return@launch
                val valid=page.events.filter{traceMatchesRun(it,actualRun,selection.threadId)}
                val cache=older.getOrPut(runId){linkedMapOf()};valid.forEach{cache[it.id]=it}
                pageTotals[runId]=page.totalCount
                if(valid.isEmpty()&&page.totalCount>combinedRecords(runId).size)markUnavailableNow(runId)
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){if(selected==selection)markUnavailableNow(runId)}
            finally {
                if(selected==selection){if(olderJobs[runId]===currentCoroutineContext()[Job])olderJobs.remove(runId);publish()}
            }
        }
        olderJobs[runId]=job;job.start();publish()
    }

    private fun current(selection:Selection,token:Long)=selected==selection&&observationVersion==token
    private fun combinedRecords(runId:String):List<WorkTraceRecord> =
        (older[runId].orEmpty().values+windows[runId]?.events.orEmpty()).associateBy{it.id}.values.sortedBy{it.sequence}
    private fun publish(){
        val selection=selected?:return
        val trace=targets.associate{target->target.runId to combinedRecords(target.runId)}
        val totals=targets.associate{target->target.runId to maxOf(windows[target.runId]?.totalCount?:0,pageTotals[target.runId]?:0)}
        mutable.value=mapWorkActivities(selection.threadId,targets,canonical,runs,trace,totals,olderJobs.filterValues{it.isActive}.keys,unavailable)
    }
}

internal fun contiguousOlderCursor(records:List<WorkTraceRecord>):WorkTraceCursor? {
    val newest=records.maxByOrNull{it.sequence}?:return null
    var oldest=newest;var expected=newest.sequence
    for(record in records.sortedByDescending{it.sequence}){
        if(record.sequence!=expected)break
        oldest=record;expected--
    }
    return WorkTraceCursor(oldest.atEpochMillis,oldest.id)
}
internal fun traceMatchesRun(trace:WorkTraceRecord,run:ChatRunRecord,threadId:String):Boolean =
    trace.binding.runId==run.id&&trace.binding.threadId==threadId&&run.threadId==threadId&&
        trace.binding.branchId==run.branchId&&trace.binding.projectId==run.projectId

internal fun mapWorkActivities(threadId:String,targets:List<WorkActivityTarget>,canonical:Map<String,String>,
    runs:Map<String,ChatRunRecord>,traces:Map<String,List<WorkTraceRecord>>,totals:Map<String,Long>,
    loading:Set<String> = emptySet(),unavailable:Set<String> = emptySet()):Map<String,UiRunActivity> = buildMap {
    for(target in targets){
        val run=runs[target.runId]?.takeIf{it.id==target.runId&&it.threadId==threadId}?:continue
        if(canonical[run.id]!=target.messageId)continue
        val records=traces[run.id].orEmpty().filter{traceMatchesRun(it,run,threadId)}.distinctBy{it.id}.sortedBy{it.sequence}
        val toolGroups=records.filter{it.event.kind==WorkTraceKind.TOOL&&it.event.invocationId!=null}.groupBy{it.event.invocationId!!}
        val chosen=toolGroups.mapValues{(_,events)->events.lastOrNull{it.event.code==WorkTraceCode.TOOL_RESULT}?:events.last()}
        val events=records.mapNotNull{record->
            if(record.event.publicSummary?.contains("판단 과정: ")==true)return@mapNotNull null
            if(record.event.kind==WorkTraceKind.INFERENCE)return@mapNotNull null
            // Older builds recorded a mechanical loop counter under the same code as the plan.
            if(record.event.code==WorkTraceCode.PLAN_READY&&record.event.summarySource==WorkSummarySource.HOST&&
                record.event.publicSummary?.let{legacyToolCounter.containsMatchIn(it)}==true)return@mapNotNull null
            if(record.event.kind==WorkTraceKind.TOOL){
                val invocation=record.event.invocationId?:return@mapNotNull null
                if(chosen[invocation]?.id!=record.id)return@mapNotNull null
                val start=toolGroups[invocation]?.firstOrNull{it.event.code==WorkTraceCode.TOOL_STARTED}
                activityEvent(record,run,start)
            }else activityEvent(record,run,null)
        }.toMutableList()
        if(run.id in unavailable)events+=UiRunActivityEvent("unavailable:${run.id}",UiWorkEventKind.CONTEXT,"일부 진행 기록의 저장·조회를 확인하지 못했습니다.",evidence=UiWorkEvidence.UNVERIFIED)
        val total=maxOf(totals[run.id]?:0,records.size.toLong())
        val waitingForReply=run.status=="paused"&&run.errorCode in setOf(
            com.user.localagent.core.NextActionQuestion.PAUSE_CODE,com.user.localagent.core.NextActionQuestion.CLOUD_PAUSE_CODE)
        val choices=if(waitingForReply)runCatching {
            val metadata=Json.parseToJsonElement(run.contextJson).jsonObject
            com.user.localagent.core.NextActionQuestion.readChoices(metadata[com.user.localagent.core.NextActionQuestion.CHOICES_KEY])
        }.getOrDefault(emptyList()) else emptyList()
        val mode=runCatching{val value=Json.parseToJsonElement(run.contextJson).jsonObject["mode"]?.jsonPrimitive?.content?:"act";AgentWorkMode.parse(value).wireName}.getOrDefault("act")
        put(target.messageId,UiRunActivity(run.id,threadId,target.messageId,run.status,mode,run.startedAt?:0L,
            maxOf(run.updatedAt,records.maxOfOrNull{it.atEpochMillis}?:0L),events,totalEvents=total,hasOlder=total>records.size,loadingOlder=run.id in loading,
            waitingForReply=waitingForReply,replyChoices=choices,waitingForGoal=run.status=="paused"&&run.errorCode=="waiting_goal_condition"&&
                runCatching{Json.parseToJsonElement(run.contextJson).jsonObject["hostGoalWake"]==null}.getOrDefault(false),
            goalWaitDetail=runCatching { Json.parseToJsonElement(run.contextJson).jsonObject["hostGoalWaitDetail"]?.jsonPrimitive?.content.orEmpty() }.getOrDefault(""),
            userMessageId=run.userMessageId,finishedAt=run.finishedAt,
            canOpenOwnerLogin=run.status=="paused" && run.errorCode in setOf("browser_auth_on_phone","waiting_browser_auth") &&
                runCatching { BrowserAuthContinuation.ownerLoginPage(QueuedChatRequest.fromJson(run.contextJson).previousSteps)!=null }.getOrDefault(false)))
    }
}

private val legacyToolCounter=Regex("^단계 [0-9]+/[0-9]+ · 선택:")

private fun activityEvent(record:WorkTraceRecord,run:ChatRunRecord,start:WorkTraceRecord?):UiRunActivityEvent {
    val event=record.event
    val kind=when(event.kind){
        WorkTraceKind.RATIONALE->UiWorkEventKind.RATIONALE;WorkTraceKind.PLAN->UiWorkEventKind.PLAN
        WorkTraceKind.TOOL->UiWorkEventKind.TOOL;WorkTraceKind.FILE->UiWorkEventKind.FILE;WorkTraceKind.VERIFICATION->UiWorkEventKind.VERIFICATION
        WorkTraceKind.ANSWER,WorkTraceKind.INFERENCE->UiWorkEventKind.ANSWER
        else->UiWorkEventKind.CONTEXT // Approval is preserved without inflating tool counts.
    }
    val tool=event.toolName?.let{ name->com.user.localagent.ui.toolLabel(name).takeUnless{it==name} }?:"도구 작업"
    val label=when(event.code){
        WorkTraceCode.RUN_STARTED->"요청 실행 시작";WorkTraceCode.SEMANTIC_STARTED->"요청과 자료 범위 확인"
        WorkTraceCode.PLAN_READY->if(event.summarySource==WorkSummarySource.MODEL_PLAN)"요청을 이렇게 이해했어요"else"진행 안내"
        WorkTraceCode.DECISION_EXPLAINED->"이 방법을 고른 이유"
        WorkTraceCode.CONTEXT_READY->"관련 지침·문맥 준비"
        WorkTraceCode.INFERENCE_STARTED->"모델 추론 시작";WorkTraceCode.INFERENCE_FINISHED->"모델 추론 구간 종료"
        WorkTraceCode.APPROVAL_WAITING->"실행 전 사용자 확인 대기";WorkTraceCode.APPROVAL_ACCEPTED->"사용자 실행 확인 반영";WorkTraceCode.APPROVAL_DECLINED->"사용자가 실행을 취소함"
        WorkTraceCode.TOOL_STARTED->if(run.status in setOf("running","waiting_approval","queued"))"$tool · 실행 중"else"$tool · 후속 결과 미확인"
        WorkTraceCode.TOOL_RESULT->"$tool · 실행 결과 기록"
        WorkTraceCode.VERIFICATION_RECORDED->"후속 조건 검증 기록";WorkTraceCode.FILE_CHANGE->"실제 프로젝트 파일 변경"
        WorkTraceCode.ANSWER_STARTED->"답변 작성 시작";WorkTraceCode.ANSWER_FINISHED->"답변 구간 저장 확인";WorkTraceCode.ANSWER_PAUSED->"답변 이어쓰기 대기"
        WorkTraceCode.RUN_FINISHED->"응답 실행 종료";WorkTraceCode.RUN_INTERRUPTED->"실행 중단 상태 기록"
    }
    val evidence=when {
        event.evidence!=null->UiWorkEvidence.valueOf(event.evidence.name)
        event.code==WorkTraceCode.TOOL_STARTED&&run.status !in setOf("running","waiting_approval","queued")->UiWorkEvidence.UNVERIFIED
        event.code in setOf(WorkTraceCode.TOOL_STARTED,WorkTraceCode.APPROVAL_WAITING)->UiWorkEvidence.PENDING
        else->UiWorkEvidence.NONE
    }
    val detail=buildList {
        event.publicSummary?.takeIf{it.isNotBlank()}?.let{summary->add(if(event.summarySource==WorkSummarySource.USER_GOAL)"사용자 목표: $summary"else summary)}
        event.count?.let{add("관찰 항목 수: $it")};if(event.partial==true)add("일부 범위만 확인한 결과입니다.")
        if(start!=null&&record.event.code==WorkTraceCode.TOOL_RESULT){
            add("도구 시작: "+runCatching{Instant.ofEpochMilli(start.atEpochMillis).toString()}.getOrDefault("시각 미기록"))
            add("결과 기록: "+runCatching{Instant.ofEpochMilli(record.atEpochMillis).toString()}.getOrDefault("시각 미기록"))
        }
        event.fileChange?.let{add("저장 후 확인한 파일 크기: ${it.bytes}바이트 · 프로그램 실행·테스트 결과는 별도입니다.")}
    }.joinToString("\n")
    val files=event.fileChange?.let{file->listOf(UiRunFileChange(file.relativePath,if(file.operation==WorkFileOperation.CREATED)UiFileChangeOperation.CREATED else UiFileChangeOperation.UPDATED,
        file.beforeSha256,file.afterSha256,file.diffRef,record.binding.projectId,file.workspaceBinding))}.orEmpty()
    val id=if(event.kind==WorkTraceKind.TOOL)"tool:${record.binding.runId}:${event.invocationId}"else record.id
    return UiRunActivityEvent(id,kind,label,detail,start?.atEpochMillis?:record.atEpochMillis,evidence,files)
}
