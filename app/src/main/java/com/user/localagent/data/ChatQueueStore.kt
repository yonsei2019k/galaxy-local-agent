package com.user.localagent.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.security.MessageDigest

data class BranchSummaryRecord(val id:String,val threadId:String,val branchId:String,val text:String,
    val sourceMessageIds:List<String>,val updatedAt:Long,val checkpointMessageId:String?=null)

/** Exact run/thread IDs, never a timestamp association. Legacy unbound audits are deliberately omitted. */
fun LocalRepository.toolRunsForThread(threadId:String,runId:String?=null):Flow<List<ToolRunRecord>> = chat.toolRunsForThread(threadId,runId)

/** Queue metadata and summaries reuse encrypted v3 rows; no schema or database-key change. */
internal class ChatQueueStore(private val database:LocalDatabase) {
    private val dao=database.chatDao()
    private val local=database.dao()

    suspend fun queuedRuns():List<ChatRunRecord> = sorted(dao.queueCandidates().filter {it.status=="queued"||isResumeQueued(it)})

    suspend fun editQueuedRun(runId:String,text:String,contextJson:String):ChatRunRecord=database.withTransaction {
        require(text.isNotBlank()&&text.length<=1_000_000&&'\u0000' !in text)
        require(contextJson.length<=2_000_000)
        val run=queued(runId);val old=payload(run);val replacement=Json.parseToJsonElement(contextJson).jsonObject
        check(!com.user.localagent.core.ScheduledReadPolicy.isId(runId)){"예약한 원래 요청을 수정할 수 없습니다. 예약을 취소하고 다시 등록해주세요."}
        require(old["goal"]==null||old["goal"]==JsonNull){"목표 연결 요청은 취소 후 목표 관리에서 수정하고 다시 추진해주세요."}
        require(replacement["text"]?.jsonPrimitive?.contentOrNull==text){"대기 요청 본문과 저장 문맥이 일치하지 않습니다."}
        requireString(replacement,"runId",run.id);requireString(replacement,"threadId",run.threadId)
        requireString(replacement,"branchId",run.branchId);requireString(replacement,"userMessageId",requireNotNull(run.userMessageId))
        require(replacement["projectId"]?.jsonPrimitive?.contentOrNull==run.projectId)
        for(key in listOf("version","modelId","thinking","attachments","allowTools","continuation","goal")){
            require(replacement[key]==old[key]){"대기 요청 편집은 본문만 변경할 수 있습니다: $key"}
        }
        require(old["continuation"]==null||old["continuation"]==JsonNull){"이어쓰기 작업의 원래 요청을 변경하지 않습니다."}
        val source=local.messageById(run.userMessageId)?:error("대기 요청의 사용자 메시지가 없습니다.")
        check(source.role=="user"&&source.threadId==run.threadId&&source.branchId==run.branchId)
        check(dao.refs(run.branchId).any {it.messageId==source.id})
        check(dao.runsForThread(run.threadId).none {it.id!=run.id&&it.userMessageId==source.id}){"이미 실행된 메시지를 공유하는 요청은 원문을 수정하지 않습니다."}
        check(dao.editQueuedUserMessage(run.id,source.id,text)==1){"이미 시작된 요청은 대기열에서 수정할 수 없습니다."}
        val merged=buildJsonObject {old.forEach {(key,value)->put(key,value)};put("text",text)}.toString()
        run.copy(contextJson=merged,updatedAt=System.currentTimeMillis()).also {dao.run(it)}
    }

    /** Reordering a stale snapshot fails atomically rather than losing a newly queued request. */
    suspend fun reorderQueuedRuns(ids:List<String>):List<ChatRunRecord> = database.withTransaction {
        require(ids.size<=2000&&ids.distinct().size==ids.size)
        val current=dao.queueCandidates().filter {it.status=="queued"||isResumeQueued(it)}
        check(ids.toSet()==current.map {it.id}.toSet()){"대기열이 변경되었습니다. 새 대기열을 확인한 뒤 다시 정렬해주세요."}
        val byId=current.associateBy {it.id};val now=System.currentTimeMillis()
        ids.mapIndexed {index,id->
            val run=byId.getValue(id)
            val context=buildJsonObject {payload(run).forEach {(key,value)->put(key,value)};put("queueOrder",index.toLong())}.toString()
            run.copy(contextJson=context,updatedAt=now).also {dao.run(it)}
        }
    }

    suspend fun cancelQueuedRun(runId:String):ChatRunRecord=database.withTransaction {
        val run=dao.run(runId)?:error("대기 요청을 찾을 수 없습니다.")
        if(isResumeQueued(run))return@withTransaction run.copy(contextJson=withoutResumeFlag(run.contextJson),updatedAt=System.currentTimeMillis()).also {dao.run(it)}
        if(run.status=="cancelled"&&run.startedAt==null)return@withTransaction run
        check(run.status=="queued"&&run.startedAt==null){"이미 시작된 작업은 실행 중단 기능으로 취소해주세요."}
        run.copy(status="cancelled",errorCode="owner_cancelled_queued",updatedAt=System.currentTimeMillis(),finishedAt=System.currentTimeMillis()).also {dao.run(it);discardEmptyCancelledAssistant(it.id)}
    }

    /** Remove only an unused reserved slot; never remove generated/host text or a resumable segment. */
    suspend fun discardEmptyCancelledAssistant(runId:String):Boolean=database.withTransaction {
        val run=dao.run(runId)?:return@withTransaction false
        if(run.status!="cancelled"||run.startedAt!=null)return@withTransaction false
        val message=dao.assistantForRun(runId)?:return@withTransaction false
        if(message.text.isNotEmpty()||dao.chunks(message.id).isNotEmpty()||dao.segmentsForMessage(message.id).isNotEmpty())return@withTransaction false
        dao.deleteRefs(message.id);dao.deleteSourceScope("message",message.id);local.deleteMessage(message.id);true
    }

    suspend fun saveBranchSummary(
        chat:ChatStore,threadId:String,branchId:String,text:String,sourceMessageIds:List<String>,
        checkpointMessageId:String,sourceTexts:Map<String,String>?,
    ):ChatRunRecord=database.withTransaction {
        require(text.isNotBlank()&&text.length<=12000&&'\u0000' !in text)
        require(sourceMessageIds.isNotEmpty()&&sourceMessageIds.size<=2000&&sourceMessageIds.distinct().size==sourceMessageIds.size)
        val thread=activeBranch(threadId,branchId)
        val boundary=dao.position(branchId,checkpointMessageId)?:error("요약 기준점이 현재 분기에 없습니다.")
        check(local.messageById(checkpointMessageId)?.threadId==threadId)
        val hashes=mutableMapOf<String,JsonElement>()
        for(id in sourceMessageIds){
            check(local.messageById(id)?.threadId==threadId && dao.position(branchId,id)?.let {it<=boundary}==true)
            val current=chat.fullMessageText(id)
            if(sourceTexts!=null)check(sourceTexts[id]==current){"요약 원문이 변경되었습니다."}
            hashes[id]=JsonPrimitive(digest(current))
        }
        val context=buildJsonObject {
            put("kind","branch_summary");put("untrustedData",true);put("text",text)
            put("sourceMessageIds",buildJsonArray {sourceMessageIds.forEach {add(JsonPrimitive(it))}})
            put("sourceHashes",JsonObject(hashes))
            put("checkpointMessageId",checkpointMessageId)
        }.toString()
        val now=System.currentTimeMillis()
        ChatRunRecord(id=summaryId(branchId),threadId=threadId,branchId=branchId,projectId=thread.projectId,
            threadRevision=thread.revision,projectRevision=thread.projectId?.let {dao.project(it)?.revision}?:0,
            modelId="branch-summary",status="completed",contextJson=context,createdAt=now,updatedAt=now,finishedAt=now).also {dao.run(it)}
    }

    suspend fun branchSummary(chat:ChatStore,threadId:String,branchId:String,beforeUserId:String?):BranchSummaryRecord?=database.withTransaction {
        val thread=dao.thread(threadId)?.takeIf {it.deletedAt==null}?:return@withTransaction null
        if(dao.branch(branchId)?.let {it.threadId==threadId&&it.deletedAt==null}!=true)return@withTransaction null
        val run=dao.run(summaryId(branchId))?:return@withTransaction null
        if(run.status!="completed"||run.threadId!=threadId||run.branchId!=branchId||run.projectId!=thread.projectId)return@withTransaction null
        if(run.projectRevision!=(thread.projectId?.let {dao.project(it)?.revision}?:0))return@withTransaction null
        val content=runCatching {payload(run)}.getOrNull()?:return@withTransaction null
        if(content["kind"]?.jsonPrimitive?.contentOrNull!="branch_summary")return@withTransaction null
        val sources=runCatching {content.getValue("sourceMessageIds").jsonArray.map {it.jsonPrimitive.content}}.getOrNull()?:return@withTransaction null
        val hashes=content["sourceHashes"]?.jsonObject?:return@withTransaction null
        if(sources.isEmpty()||sources.size>2000||sources.distinct().size!=sources.size)return@withTransaction null
        val anchor=if(beforeUserId==null)null else
            chat.userAnchorPosition(threadId,branchId,beforeUserId)?:return@withTransaction null
        val checkpoint=(content["checkpointMessageId"] as? JsonPrimitive)?.contentOrNull
        val checkpointId=checkpoint?:sources.last()
        if(local.messageById(checkpointId)?.threadId!=threadId)return@withTransaction null
        val checkpointPosition=dao.position(branchId,checkpointId)?:return@withTransaction null
        if(anchor!=null&&checkpointPosition>=anchor)return@withTransaction null
        for(id in sources){
            if(local.messageById(id)?.threadId!=threadId ||
                dao.position(branchId,id)?.let {it<=checkpointPosition}!=true)return@withTransaction null
            if(hashes[id]?.jsonPrimitive?.contentOrNull!=digest(chat.fullMessageText(id)))return@withTransaction null
        }
        val text=content["text"]?.jsonPrimitive?.contentOrNull?:return@withTransaction null
        BranchSummaryRecord(run.id,threadId,branchId,text,sources,run.updatedAt,checkpoint)
    }

    private suspend fun queued(id:String):ChatRunRecord {
        val run=dao.run(id)?:error("대기 요청을 찾을 수 없습니다.")
        check(run.status=="queued"&&run.startedAt==null){"대기 상태인 요청만 수정할 수 있습니다."}
        val thread=activeBranch(run.threadId,run.branchId)
        check(thread.revision==run.threadRevision&&thread.projectId==run.projectId)
        check((thread.projectId?.let {dao.project(it)?.revision}?:0)==run.projectRevision){"프로젝트 설정이 변경되었습니다."}
        return run
    }
    private suspend fun activeBranch(threadId:String,branchId:String):ChatThreadRecord {
        val thread=dao.thread(threadId)?.takeIf {it.deletedAt==null}?:error("대화방이 삭제되었습니다.")
        check(dao.branch(branchId)?.let {it.threadId==threadId&&it.deletedAt==null}==true){"현재 대화방의 분기가 아닙니다."}
        return thread
    }
    private fun payload(run:ChatRunRecord)=Json.parseToJsonElement(run.contextJson).jsonObject
    private fun requireString(value:JsonObject,key:String,expected:String){require(value[key]?.jsonPrimitive?.contentOrNull==expected){"대기 요청 연결이 일치하지 않습니다: $key"}}
    private fun sorted(runs:List<ChatRunRecord>)=runs.sortedWith(compareBy<ChatRunRecord> {run->
        runCatching {(payload(run)["queueOrder"] as? JsonPrimitive)?.longOrNull?.takeIf {it>=0}}.getOrNull()?:run.createdAt
    }.thenBy {it.createdAt}.thenBy {it.id})
    private fun summaryId(branchId:String)="branch-summary:$branchId"
    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    companion object {
        fun isResumeQueued(run:ChatRunRecord):Boolean=run.status in setOf("paused","interrupted","cancelled","failed")&&runCatching {
            (Json.parseToJsonElement(run.contextJson).jsonObject["resumeQueued"] as? JsonPrimitive)?.booleanOrNull==true
        }.getOrDefault(false)
        fun withoutResumeFlag(contextJson:String):String {
            val payload=runCatching {Json.parseToJsonElement(contextJson).jsonObject}.getOrNull()?:return contextJson
            if("resumeQueued" !in payload)return contextJson
            return JsonObject(payload.filterKeys {it!="resumeQueued"}).toString()
        }
    }
}
