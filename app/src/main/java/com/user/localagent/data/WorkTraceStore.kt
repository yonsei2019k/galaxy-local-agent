package com.user.localagent.data

import com.user.localagent.core.VerificationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import java.security.MessageDigest

// User-visible work metadata; no hidden reasoning, source bodies or tool authority.
enum class WorkTraceKind { RATIONALE, PLAN, CONTEXT, INFERENCE, APPROVAL, TOOL, VERIFICATION, FILE, ANSWER, RUN }
enum class WorkTraceCode {
    RUN_STARTED, SEMANTIC_STARTED, PLAN_READY, CONTEXT_READY, INFERENCE_STARTED, INFERENCE_FINISHED,
    APPROVAL_WAITING, APPROVAL_ACCEPTED, APPROVAL_DECLINED, TOOL_STARTED, TOOL_RESULT,
    VERIFICATION_RECORDED, FILE_CHANGE, ANSWER_STARTED, ANSWER_FINISHED, ANSWER_PAUSED,
    RUN_FINISHED, RUN_INTERRUPTED,
}
enum class WorkSummarySource { HOST, USER_GOAL, MODEL_PLAN }
enum class WorkFileOperation { CREATED, MODIFIED }
data class WorkTraceBinding(val runId:String,val threadId:String,val branchId:String,val projectId:String?,
    val threadRevision:Long,val projectRevision:Long)
data class WorkFileChange(val relativePath:String,val operation:WorkFileOperation,val beforeSha256:String?,
    val afterSha256:String,val bytes:Long,val workspaceBinding:String,val diffRef:String?=null)
data class WorkTraceDraft(val operationKey:String,val kind:WorkTraceKind,val code:WorkTraceCode,
    val toolName:String?=null,val invocationId:String?=null,val evidence:VerificationState?=null,
    val auditId:String?=null,val argsHash:String?=null,val scopeHash:String?=null,
    val count:Int?=null,val partial:Boolean?=null,val fileChange:WorkFileChange?=null,
    val publicSummary:String?=null,val summarySource:WorkSummarySource=WorkSummarySource.HOST)
data class WorkTraceRecord(val id:String,val binding:WorkTraceBinding,val sequence:Long,val atEpochMillis:Long,val event:WorkTraceDraft)
data class WorkTraceWindow(val runId:String,val events:List<WorkTraceRecord>,val totalCount:Long){val hasOlder:Boolean get()=totalCount>events.size}
data class WorkTraceCursor(val atEpochMillis:Long,val id:String)
data class WorkTracePage(val events:List<WorkTraceRecord>,val totalCount:Long,val nextCursor:WorkTraceCursor?)

internal interface WorkTraceStorage {
    suspend fun <T> transaction(block:suspend()->T):T
    suspend fun run(id:String):ChatRunRecord?
    suspend fun thread(id:String):ChatThreadRecord?
    suspend fun project(id:String):ProjectRecord?
    suspend fun row(id:String):ToolRunRecord?
    suspend fun rows(threadId:String,runId:String,limit:Int):List<ToolRunRecord>
    suspend fun page(threadId:String,runId:String,before:WorkTraceCursor?,limit:Int):List<ToolRunRecord>
    suspend fun count(threadId:String,runId:String):Long
    suspend fun insert(record:ToolRunRecord)
    fun observe(threadId:String,runId:String,limit:Int):Flow<List<ToolRunRecord>>
    fun observeCount(threadId:String,runId:String):Flow<Long>
    fun observeRuns(threadId:String,runIds:List<String>):Flow<List<ChatRunRecord>>
}

/** Progress metadata is separate from action evidence, consent and assistant reasoning. */
class WorkTraceStore internal constructor(private val storage:WorkTraceStorage) {
    /** Host calls once per actual execute/resume; stage keys cannot collide with a prior execution. */
    fun executionKey(stage:String,executionId:String):String {
        require(stage.matches(Regex("[a-z][a-z0-9_.-]{0,79}"))&&executionId.matches(Regex("[a-f0-9-]{36}")))
        return "$stage.$executionId"
    }
    suspend fun open(runId:String,threadId:String):WorkTraceBinding=storage.transaction {
        val run=storage.run(runId)?:error("진행 기록의 실제 실행이 없습니다.")
        check(run.threadId==threadId)
        WorkTraceBinding(run.id,run.threadId,run.branchId,run.projectId,run.threadRevision,run.projectRevision).also{checkLive(it)}
    }
    suspend fun append(binding:WorkTraceBinding,draft:WorkTraceDraft):WorkTraceRecord=storage.transaction {
        val run=checkLive(binding)
        val safe=WorkTraceCodec.prepare(draft)
        checkLifecycle(run,safe.code)
        val id=eventId(binding.runId,safe.operationKey)
        storage.row(id)?.let{existing->
            val parsed=WorkTraceCodec.decode(existing)
            check(parsed.binding==binding&&parsed.event==safe){"같은 진행 이벤트에 다른 내용을 덮어쓰지 않습니다."}
            return@transaction parsed
        }
        val last=storage.rows(binding.threadId,binding.runId,1).firstOrNull()?.let(WorkTraceCodec::decode)
        verifyEvidence(binding,safe)
        val nextSequence=Math.addExact(last?.sequence?:0L,1L)
        val nextAt=maxOf(System.currentTimeMillis(),Math.addExact(last?.atEpochMillis?:0L,1L))
        val record=WorkTraceRecord(id,binding,nextSequence,nextAt,safe)
        val body=WorkTraceCodec.encode(record)
        storage.insert(ToolRunRecord(id=id,name=ROW_NAME,success=false,summary=body,at=record.atEpochMillis,runId=binding.runId,threadId=binding.threadId))
        val persisted=storage.row(id)?:error("진행 기록 저장을 다시 확인하지 못했습니다.")
        check(persisted.summary==body&&!persisted.success&&persisted.name==ROW_NAME)
        WorkTraceCodec.decode(persisted)
    }
    /** Actual host observation only. Writes its audit and immutable invocation receipt atomically. */
    suspend fun recordOutcome(binding:WorkTraceBinding,startEventId:String,success:Boolean,state:VerificationState,
        summary:String,fileChange:WorkFileChange?=null):WorkOutcomeReceipt=storage.transaction {
        val run=checkLive(binding);check(run.startedAt!=null&&run.status!="queued")
        require(summary.length<=4096)
        require(state !in setOf(VerificationState.VERIFIED,VerificationState.PREPARED)||success)
        require(state!=VerificationState.FAILED||!success)
        val start=storage.row(startEventId)?.let(WorkTraceCodec::decode)?:error("실제 도구 시작 기록이 없습니다.")
        check(start.binding==binding&&start.event.code==WorkTraceCode.TOOL_STARTED)
        val invocation=start.event
        fileChange?.let{
            require(state==VerificationState.VERIFIED&&invocation.toolName=="write_project_file")
            WorkTraceCodec.prepare(WorkTraceDraft("receipt.file",WorkTraceKind.FILE,WorkTraceCode.FILE_CHANGE,
                invocation.toolName,invocation.invocationId,state,"placeholder-audit",invocation.argsHash,invocation.scopeHash,fileChange=it))
        }
        val auditId=WorkTraceReceiptCodec.auditId(startEventId)
        val receipt=WorkTraceReceipt(binding,auditId,startEventId,requireNotNull(invocation.toolName),requireNotNull(invocation.invocationId),requireNotNull(invocation.argsHash),requireNotNull(invocation.scopeHash),state,fileChange?.let(WorkTraceReceiptCodec::fileDigest))
        val receiptId=WorkTraceReceiptCodec.receiptId(auditId)
        val safeSummary="$state · "+WorkTraceCodec.maskPreview(summary)
        storage.row(receiptId)?.let{existing->
            check(WorkTraceReceiptCodec.decode(existing)==receipt){"같은 도구 실행의 결과 영수증을 바꾸지 않습니다."}
            val prior=storage.row(auditId)?:error("실제 실행 감사 기록이 없습니다.")
            check(prior.success==success&&prior.summary==WorkTraceCodec.clip(safeSummary,200)&&prior.runId==binding.runId&&prior.threadId==binding.threadId&&prior.name==invocation.toolName)
            return@transaction WorkOutcomeReceipt(prior,receiptId)
        }
        check(storage.row(auditId)==null){"기존 감사 기록에 새 호출 영수증을 붙이지 않습니다."}
        val audit=ToolRunRecord(id=auditId,name=receipt.toolName,success=success,summary=WorkTraceCodec.clip(safeSummary,200),runId=binding.runId,threadId=binding.threadId)
        val receiptRow=ToolRunRecord(id=receiptId,name=WorkTraceReceiptCodec.ROW_NAME,success=false,summary=WorkTraceReceiptCodec.encode(receipt),at=audit.at,runId=binding.runId,threadId=binding.threadId)
        storage.insert(audit);storage.insert(receiptRow)
        check(storage.row(audit.id)==audit&&storage.row(receiptId)==receiptRow){"실행 감사·영수증 저장을 다시 확인하지 못했습니다."}
        WorkOutcomeReceipt(audit,receiptId)
    }
    fun observeWindows(threadId:String,runIds:List<String>,limit:Int=MAX_EVENTS):Flow<List<WorkTraceWindow>> {
        require(runIds.size<=32&&runIds.distinct().size==runIds.size&&limit in 1..MAX_EVENTS)
        if(runIds.isEmpty())return flowOf(emptyList())
        return combine(runIds.map{runId->combine(storage.observe(threadId,runId,limit),storage.observeCount(threadId,runId)){rows,total->
            val events=rows.filter{it.name==ROW_NAME&&it.threadId==threadId&&it.runId==runId&&it.summary.isNotBlank()}
                .mapNotNull{runCatching{WorkTraceCodec.decode(it)}.getOrNull()}.sortedBy{it.sequence}
            WorkTraceWindow(runId,events,total)
        }}){it.toList()}
    }
    fun observe(threadId:String,runIds:List<String>,limit:Int=MAX_EVENTS):Flow<List<WorkTraceRecord>> =
        observeWindows(threadId,runIds,limit).map{windows->windows.flatMap{it.events}.sortedWith(compareBy<WorkTraceRecord>{it.atEpochMillis}.thenBy{it.sequence}.thenBy{it.id})}
    fun observeRunRows(threadId:String,runIds:List<String>):Flow<List<ChatRunRecord>> {
        require(runIds.size<=60&&runIds.distinct().size==runIds.size)
        return if(runIds.isEmpty())flowOf(emptyList())else storage.observeRuns(threadId,runIds)
    }
    suspend fun events(threadId:String,runId:String):List<WorkTraceRecord> =
        storage.rows(threadId,runId,MAX_EVENTS).filter{it.summary.isNotBlank()}.map(WorkTraceCodec::decode).sortedBy{it.sequence}
    suspend fun page(threadId:String,runId:String,before:WorkTraceCursor?=null,limit:Int=MAX_EVENTS):WorkTracePage {
        require(limit in 1..MAX_EVENTS)
        val found=storage.page(threadId,runId,before,limit+1);val selected=found.take(limit)
        val next=if(found.size>limit)selected.lastOrNull()?.let{WorkTraceCursor(it.at,it.id)}else null
        return WorkTracePage(selected.map(WorkTraceCodec::decode).sortedBy{it.sequence},storage.count(threadId,runId),next)
    }

    private suspend fun checkLive(binding:WorkTraceBinding):ChatRunRecord {
        val run=storage.run(binding.runId)?:error("진행 기록의 실행을 찾을 수 없습니다.")
        val thread=storage.thread(binding.threadId)?.takeIf{it.deletedAt==null}?:error("대화가 삭제되었습니다.")
        check(run.threadId==binding.threadId&&run.branchId==binding.branchId&&run.projectId==binding.projectId&&run.threadRevision==binding.threadRevision&&run.projectRevision==binding.projectRevision)
        check(thread.revision==binding.threadRevision&&thread.projectId==binding.projectId&&thread.activeBranchId==binding.branchId){"대화 범위가 바뀐 뒤 이전 진행 기록을 되살리지 않습니다."}
        val revision=binding.projectId?.let{storage.project(it)?.revision?:error("프로젝트가 없습니다.")}?:0L
        check(revision==binding.projectRevision)
        return run
    }
    private suspend fun verifyEvidence(binding:WorkTraceBinding,event:WorkTraceDraft){
        val state=event.evidence
        if(state==null){require(event.auditId==null&&event.fileChange==null);return}
        require(event.code in setOf(WorkTraceCode.TOOL_RESULT,WorkTraceCode.VERIFICATION_RECORDED,WorkTraceCode.FILE_CHANGE))
        val audit=event.auditId?.let{storage.row(it)}?:error("실제 실행 감사 기록이 없는 검증 상태는 저장하지 않습니다.")
        check(audit.name!=ROW_NAME&&audit.runId==binding.runId&&audit.threadId==binding.threadId&&audit.name==event.toolName)
        check(if(state==VerificationState.FAILED)!audit.success else audit.summary.matches(Regex("^${state.name}(?:$|[\\s·:]).*",RegexOption.DOT_MATCHES_ALL))&&(state!=VerificationState.VERIFIED&&state!=VerificationState.PREPARED||audit.success))
        require(event.invocationId!=null)
        val receipt=storage.row(WorkTraceReceiptCodec.receiptId(audit.id))?.let(WorkTraceReceiptCodec::decode)?:error("실제 도구 호출에 결합된 실행 영수증이 없습니다.")
        check(receipt.binding==binding&&receipt.auditId==audit.id&&receipt.toolName==event.toolName&&receipt.invocationId==event.invocationId&&receipt.argsHash==event.argsHash&&receipt.scopeHash==event.scopeHash&&receipt.state==state)
        val start=storage.row(receipt.startEventId)?.let(WorkTraceCodec::decode)?:error("실제 도구 시작 기록이 없습니다.")
        check(start.binding==binding&&start.event.code==WorkTraceCode.TOOL_STARTED&&start.event.invocationId==receipt.invocationId&&start.event.toolName==receipt.toolName&&start.event.argsHash==receipt.argsHash&&start.event.scopeHash==receipt.scopeHash)
        event.fileChange?.let{file->
            require(state==VerificationState.VERIFIED&&event.toolName=="write_project_file"&&event.code==WorkTraceCode.FILE_CHANGE)
            require(file.diffRef==null){"실제 diff 저장소와 readback이 연결되기 전에는 diff 참조를 만들지 않습니다."}
            check(receipt.fileDigest==WorkTraceReceiptCodec.fileDigest(file)){"검증된 실제 파일 결과와 변경 참조가 일치하지 않습니다."}
        }
    }
    private fun checkLifecycle(run:ChatRunRecord,code:WorkTraceCode){
        when(code){
            WorkTraceCode.RUN_STARTED->check(run.status=="running"&&run.startedAt!=null)
            WorkTraceCode.RUN_FINISHED->check(run.status=="completed")
            WorkTraceCode.RUN_INTERRUPTED->check(run.status in setOf("interrupted","cancelled","failed"))
            WorkTraceCode.ANSWER_PAUSED->check(run.status=="paused")
            else->if(run.status in ChatStore.TERMINAL)require(code in CLOSING_CODES)
        }
    }
    companion object {
        const val ROW_NAME="work_trace_v1";const val MAX_EVENTS=128
        private val CLOSING_CODES=setOf(WorkTraceCode.TOOL_RESULT,WorkTraceCode.VERIFICATION_RECORDED,WorkTraceCode.FILE_CHANGE,WorkTraceCode.ANSWER_PAUSED,WorkTraceCode.ANSWER_FINISHED,WorkTraceCode.RUN_FINISHED,WorkTraceCode.RUN_INTERRUPTED)
        private fun eventId(runId:String,operationKey:String)="trace-"+MessageDigest.getInstance("SHA-256").digest("$runId\u0000$operationKey".toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}

/** Only validated public-facing objective previews, never hidden model reasoning or raw tool payloads. */
object WorkTraceCodec {
    private val identity=Regex("[a-zA-Z0-9_.:-]{1,180}")
    private val sha=Regex("[a-f0-9]{64}")
    private val hidden=Regex("(?is)<\\s*/?\\s*(?:think|analysis|reasoning)\\b|\\[/?(?:analysis|reasoning)\\]|```(?:analysis|reasoning)")
    private val secret=Regex("""(?i)((?:["']?)(?:password|passwd|비밀번호|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|client[ _-]?secret|token|secret|authorization|cookie|csrf|sessionid)(?:["']?)\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|Bearer\s+[^\s,;}\]]+|[^\s,;}\]]+)""")
    fun prepare(event:WorkTraceDraft):WorkTraceDraft {
        require(identity.matches(event.operationKey))
        val requiredKind=when(event.code){
            WorkTraceCode.SEMANTIC_STARTED->WorkTraceKind.RATIONALE
            WorkTraceCode.PLAN_READY->WorkTraceKind.PLAN
            WorkTraceCode.CONTEXT_READY->WorkTraceKind.CONTEXT
            WorkTraceCode.INFERENCE_STARTED,WorkTraceCode.INFERENCE_FINISHED->WorkTraceKind.INFERENCE
            WorkTraceCode.APPROVAL_WAITING,WorkTraceCode.APPROVAL_ACCEPTED,WorkTraceCode.APPROVAL_DECLINED->WorkTraceKind.APPROVAL
            WorkTraceCode.TOOL_STARTED,WorkTraceCode.TOOL_RESULT->WorkTraceKind.TOOL
            WorkTraceCode.VERIFICATION_RECORDED->WorkTraceKind.VERIFICATION
            WorkTraceCode.FILE_CHANGE->WorkTraceKind.FILE
            WorkTraceCode.ANSWER_STARTED,WorkTraceCode.ANSWER_FINISHED,WorkTraceCode.ANSWER_PAUSED->WorkTraceKind.ANSWER
            else->WorkTraceKind.RUN
        }
        require(event.kind==requiredKind)
        require(event.summarySource!=WorkSummarySource.MODEL_PLAN||event.code==WorkTraceCode.PLAN_READY)
        require(event.summarySource!=WorkSummarySource.USER_GOAL||event.code in setOf(WorkTraceCode.PLAN_READY,WorkTraceCode.RUN_STARTED))
        event.toolName?.let{require(Regex("[a-z][a-z0-9_]{0,79}").matches(it)&&it in com.user.localagent.core.ToolRegistry.definitions)};event.invocationId?.let{require(identity.matches(it))}
        listOf(event.argsHash,event.scopeHash).filterNotNull().forEach{require(sha.matches(it))}
        if(event.code in setOf(WorkTraceCode.APPROVAL_WAITING,WorkTraceCode.APPROVAL_ACCEPTED,WorkTraceCode.APPROVAL_DECLINED,WorkTraceCode.TOOL_STARTED,WorkTraceCode.TOOL_RESULT,WorkTraceCode.VERIFICATION_RECORDED,WorkTraceCode.FILE_CHANGE)){
            require(event.toolName!=null&&event.invocationId!=null&&event.argsHash!=null&&event.scopeHash!=null)
        }
        if(event.code==WorkTraceCode.VERIFICATION_RECORDED)require(event.evidence!=null&&event.auditId!=null)
        if(event.code==WorkTraceCode.FILE_CHANGE)require(event.fileChange!=null&&event.evidence==VerificationState.VERIFIED&&event.auditId!=null)
        require(event.count==null||event.count>=0)
        event.auditId?.let{require(identity.matches(it))}
        event.publicSummary?.let{require(it.length<=4096&&!hidden.containsMatchIn(it))}
        if(event.fileChange!=null){val f=event.fileChange;require(f.relativePath.length in 1..512);com.user.localagent.core.ProjectPaths.segments(f.relativePath);require(sha.matches(f.afterSha256)&&sha.matches(f.workspaceBinding)&&f.bytes>=0);f.beforeSha256?.let{require(sha.matches(it))};require((f.operation==WorkFileOperation.CREATED)==(f.beforeSha256==null));require(f.diffRef==null)}
        return event.copy(publicSummary=event.publicSummary?.let(::maskPreview))
    }
    fun maskPreview(input:String):String {
        var value=input.replace(Regex("[\\p{Cntrl}]")," ")
        value=secret.replace(value){it.groupValues[1]+"\"[비공개]\""}
        value=value.replace(Regex("(?i)\\bBearer\\s+[^\\s]+"),"Bearer [비공개]")
            .replace(Regex("(?i)[a-z][a-z0-9+.-]*://[^\\s]+"),"[링크]")
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),"[이메일]")
            .replace(Regex("(?<!\\d)(?:\\+?82[- ]?)?0?1[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)"),"[전화번호]")
            .replace(Regex("[A-Za-z0-9_+/=-]{40,}"),"[비공개 식별값]").trim()
        return clip(value,280)
    }
    internal fun clip(value:String,limit:Int):String {var end=minOf(value.length,limit);if(end<value.length&&end>0&&value[end-1].isHighSurrogate()&&value[end].isLowSurrogate())end--;return value.substring(0,end)}
    fun encode(record:WorkTraceRecord):String {
        val event=prepare(record.event);require(record.sequence>=1L)
        val b=record.binding
        val json=buildJsonObject {
            put("v",1);put("seq",record.sequence);put("branch",b.branchId);put("project",b.projectId);put("threadRevision",b.threadRevision);put("projectRevision",b.projectRevision)
            put("event",buildJsonObject {
                put("key",event.operationKey);put("kind",event.kind.name);put("code",event.code.name);put("tool",event.toolName);put("call",event.invocationId);put("evidence",event.evidence?.name);put("audit",event.auditId);put("argsHash",event.argsHash);put("scopeHash",event.scopeHash)
                event.count?.let{put("count",it)};event.partial?.let{put("partial",it)};put("summary",event.publicSummary);put("summarySource",event.summarySource.name)
                event.fileChange?.let{f->put("file",buildJsonObject {put("path",f.relativePath);put("operation",f.operation.name);put("before",f.beforeSha256);put("after",f.afterSha256);put("bytes",f.bytes);put("workspace",f.workspaceBinding);put("diffRef",f.diffRef)})}
            })
        }.toString()
        require(json.length<=4096){"진행 기록이 저장 상한을 넘었습니다. JSON을 잘라 저장하지 않았습니다."};return json
    }
    fun decode(row:ToolRunRecord):WorkTraceRecord {
        require(row.name==WorkTraceStore.ROW_NAME&&!row.success&&row.summary.length in 1..4096)
        rejectDuplicateKeys(row.summary)
        val o=Json.parseToJsonElement(row.summary).jsonObject
        fun number(data:JsonObject,key:String):Long {val p=data.getValue(key).jsonPrimitive;require(!p.isString);return p.longOrNull?:error("숫자 형식이 올바르지 않습니다.")}
        fun text(data:JsonObject,key:String):String {val p=data.getValue(key).jsonPrimitive;require(p.isString);return p.content}
        require(o.keys==setOf("v","seq","branch","project","threadRevision","projectRevision","event")&&number(o,"v")==1L)
        val e=o.getValue("event").jsonObject
        val base=setOf("key","kind","code","tool","call","evidence","audit","argsHash","scopeHash","summary","summarySource")
        require(e.keys.containsAll(base)&&e.keys.all{it in base+setOf("count","partial","file")})
        fun nullable(data:JsonObject,key:String)=data[key]?.takeUnless{it is JsonNull}?.jsonPrimitive?.also{require(it.isString)}?.content
        val file=e["file"]?.jsonObject?.let{f->require(f.keys==setOf("path","operation","before","after","bytes","workspace","diffRef"));WorkFileChange(text(f,"path"),WorkFileOperation.valueOf(text(f,"operation")),nullable(f,"before"),text(f,"after"),number(f,"bytes"),text(f,"workspace"),nullable(f,"diffRef"))}
        val count=if("count" in e)number(e,"count").also{require(it in 0..Int.MAX_VALUE.toLong())}.toInt() else null
        val partial=e["partial"]?.jsonPrimitive?.let{require(!it.isString);it.booleanOrNull?:error("참거짓 형식이 올바르지 않습니다.")}
        val event=WorkTraceDraft(text(e,"key"),WorkTraceKind.valueOf(text(e,"kind")),WorkTraceCode.valueOf(text(e,"code")),nullable(e,"tool"),nullable(e,"call"),nullable(e,"evidence")?.let(VerificationState::valueOf),nullable(e,"audit"),nullable(e,"argsHash"),nullable(e,"scopeHash"),count,partial,file,nullable(e,"summary"),WorkSummarySource.valueOf(text(e,"summarySource")))
        val sequence=number(o,"seq").also{require(it>=1L)}
        val record=WorkTraceRecord(row.id,WorkTraceBinding(requireNotNull(row.runId),requireNotNull(row.threadId),text(o,"branch"),nullable(o,"project"),number(o,"threadRevision"),number(o,"projectRevision")),sequence,row.at,event)
        require(prepare(event)==event&&record.sequence>=1L)
        return record
    }
    internal fun rejectDuplicateKeys(raw:String){
        var index=0
        fun space(){while(index<raw.length&&raw[index].isWhitespace())index++}
        fun string():String {val start=index;require(raw.getOrNull(index)=='"');index++;var escaped=false;while(index<raw.length){val c=raw[index++];if(c=='"'&&!escaped)return Json.decodeFromString(raw.substring(start,index));if(c=='\\'&&!escaped)escaped=true else escaped=false};error("JSON 문자열이 닫히지 않았습니다.")}
        lateinit var value:(Int)->Unit
        value={depth->require(depth<=10);space();when(raw.getOrNull(index)){
            '{'->{index++;space();val keys=mutableSetOf<String>();if(raw.getOrNull(index)=='}')index++ else while(true){space();require(keys.add(string())){"중복 JSON 필드는 허용하지 않습니다."};space();require(raw.getOrNull(index++)==':');value(depth+1);space();val separator=raw.getOrNull(index++);if(separator=='}')break;require(separator==',')}}
            '['->{index++;space();if(raw.getOrNull(index)==']')index++ else while(true){value(depth+1);space();val separator=raw.getOrNull(index++);if(separator==']')break;require(separator==',')}}
            '"'->{string();Unit}
            else->{val start=index;while(index<raw.length&&raw[index] !in ",]} \t\r\n")index++;require(index>start)}
        }}
        value(0);space();require(index==raw.length)
    }
}
