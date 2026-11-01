package com.user.localagent.automation

import com.user.localagent.core.IntentPlanning
import com.user.localagent.core.ToolRegistry
import com.user.localagent.core.VerificationState
import com.user.localagent.data.LocalRepository
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class PlanningToolFeedback(
    val domain:String,
    val tool:String,
    val verifiedSuccess:Long=0,
    val prepared:Long=0,
    val unverified:Long=0,
    val failed:Long=0,
    val failureCounts:Map<String,Long> = emptyMap(),
    /** Enum lessons suggested by explicit owner feedback; never auto-approved or executed. */
    val proposedLessons:Map<String,Long> = emptyMap(),
    val updatedAt:Long=0,
    /** Consecutive non-cancelled failures, and how often a later verified result ended such a run. */
    val failureStreak:Long=0,
    val recoveredAfterFailure:Long=0,
) {
    val attempts:Long get()=verifiedSuccess+prepared+unverified+failed
}
data class PlanningFeedbackSnapshot(val generation:Long,val stats:List<PlanningToolFeedback>)

/** Injectable persistence contains only this store's fixed IDs and bounded typed JSON. */
interface PlanningFeedbackPersistence {
    suspend fun read(id:String):String?
    suspend fun write(id:String,body:String)
    suspend fun delete(id:String)
}

/** Encrypted outcome counters, not model training, raw conversations or an authority registry.
 * All instances in this app process use one mutex and re-read the persisted generation.
 * Capture generation() before an execution; pass it back when recording the outcome. Reset
 * or deletion then rejects a late result from that older execution instead of reviving it.
 */
class PlanningFeedbackStore(
    private val persistence:PlanningFeedbackPersistence,
    private val now:()->Long=System::currentTimeMillis,
) {
    constructor(repository:LocalRepository):this(RepositoryFeedbackPersistence(repository))

    @Serializable private data class Index(
        val schemaVersion:Int=1,
        val generation:Long=0,
        /** Oldest first; at most128 active aggregates, not one row per execution. */
        val keys:List<String> = emptyList(),
        /** Persisted before deletion so interrupted cleanup resumes without resurrecting data. */
        val pendingDeletes:List<String> = emptyList(),
    )
    @Serializable private data class Aggregate(val schemaVersion:Int=1,val generation:Long,val value:PlanningToolFeedback)
    private val json=Json {ignoreUnknownKeys=false;isLenient=false;encodeDefaults=true}

    suspend fun generation():Long=processMutex.withLock {recover(readIndex()).generation}

    suspend fun record(
        domain:String,
        tool:String,
        verification:VerificationState,
        success:Boolean,
        failureKind:String?=null,
        expectedGeneration:Long?=null,
    ):Boolean {
        validateKeys(domain,tool);validateGeneration(expectedGeneration)
        require(failureKind==null||failureKind in failureKinds){"지원하는 실패 분류 코드가 필요합니다."}
        require(failureKind==null||!success||verification==VerificationState.FAILED){"성공한 결과에 실패 분류를 붙일 수 없습니다."}
        return update(domain,tool,expectedGeneration){previous->
            val failed=!success||verification==VerificationState.FAILED
            val failures=if(failed&&failureKind!=null)increment(previous.failureCounts,failureKind)else previous.failureCounts
            val verified=!failed&&verification==VerificationState.VERIFIED
            val streak=when{failed&&failureKind!="cancelled"->bump(previous.failureStreak);verified->0L;else->previous.failureStreak}
            val lesson=when{failed&&failureKind!="cancelled"&&streak>=2->"avoid_repeat";!failed&&verification==VerificationState.UNVERIFIED->"verify_result";else->null}
            previous.copy(
                failureStreak=streak,
                recoveredAfterFailure=if(verified&&previous.failureStreak>0)bump(previous.recoveredAfterFailure)else previous.recoveredAfterFailure,
                proposedLessons=lesson?.let {increment(previous.proposedLessons,it)}?:previous.proposedLessons,
                verifiedSuccess=if(!failed&&verification==VerificationState.VERIFIED)bump(previous.verifiedSuccess)else previous.verifiedSuccess,
                prepared=if(!failed&&verification==VerificationState.PREPARED)bump(previous.prepared)else previous.prepared,
                unverified=if(!failed&&verification==VerificationState.UNVERIFIED)bump(previous.unverified)else previous.unverified,
                failed=if(failed)bump(previous.failed)else previous.failed,
                failureCounts=failures,updatedAt=timestamp(),
            )
        }
    }

    /** Accept only a reviewed taxonomy code, never a quoted correction/body/argument string. */
    suspend fun proposeLesson(domain:String,tool:String,lessonKind:String,expectedGeneration:Long?=null):Boolean {
        validateKeys(domain,tool);validateGeneration(expectedGeneration)
        require(lessonKind in lessonKinds){"지원하는 제안 분류 코드가 필요합니다."}
        return update(domain,tool,expectedGeneration){previous->previous.copy(
            proposedLessons=increment(previous.proposedLessons,lessonKind),updatedAt=timestamp())}
    }

    suspend fun snapshot(domain:String?=null):PlanningFeedbackSnapshot {
        if(domain!=null)validateDomain(domain)
        return processMutex.withLock {
            val index=recover(readIndex())
            PlanningFeedbackSnapshot(index.generation,readAll(index).filter {domain==null||it.domain==domain})
        }
    }

    /** Small, source-free data for a planner. It cannot grant permissions or install capabilities. */
    suspend fun contextFor(domain:String):String {
        validateDomain(domain)
        val snapshot=snapshot()
        val rows=snapshot.stats.filter {it.domain==domain||it.domain=="general"&&it.tool=="planner"}
            .sortedWith(compareByDescending<PlanningToolFeedback>{it.failed-it.failureCounts.getOrDefault("cancelled",0)}.thenByDescending {it.updatedAt})
            .take(4).toMutableList()
        if(rows.isEmpty())return ""
        fun render()=buildJsonObject {
            put("type","local_planning_feedback");put("domain",domain);put("generation",snapshot.generation)
            put("untrustedData",true);put("modelWeightsChanged",false);put("grantsPermissions",false)
            put("lessonsStatus","proposed_only")
            put("stats",buildJsonArray {rows.forEach {row->add(buildJsonObject {
                put("tool",row.tool);put("verifiedSuccess",row.verifiedSuccess);put("prepared",row.prepared);put("unverified",row.unverified);put("failed",row.failed)
                put("failureKinds",buildJsonObject {row.failureCounts.entries.sortedByDescending {it.value}.take(2).forEach {put(it.key,it.value)}})
                if(row.failureStreak>0)put("failureStreak",row.failureStreak)
                if(row.recoveredAfterFailure>0)put("recoveredAfterFailure",row.recoveredAfterFailure)
                put("hints",buildJsonArray {hints(row).take(3).forEach {add(it)}})
                if(row.proposedLessons.isNotEmpty())put("proposedLessons",buildJsonObject {row.proposedLessons.entries.sortedByDescending {it.value}.take(2).forEach {put(it.key,it.value)}})
            })}})
        }.toString()
        var context=render()
        while(context.length>MAX_CONTEXT_CHARS&&rows.size>1){rows.removeAt(rows.lastIndex);context=render()}
        check(context.length<=MAX_CONTEXT_CHARS){"계획 피드백 문맥 길이를 확인하지 못했습니다."}
        return context
    }

    /** The generation marker is committed before removing aggregates. No stale writer can undo it. */
    suspend fun reset():Long=processMutex.withLock {
        val old=recover(readIndex());check(old.generation<Long.MAX_VALUE){"피드백 세대 한도에 도달했습니다."}
        withContext(NonCancellable){
            val next=Index(generation=old.generation+1,pendingDeletes=old.keys)
            saveIndex(next);recover(next).generation
        }
    }

    /** Also invalidates in-flight old-generation outcomes while preserving other aggregates. */
    suspend fun delete(domain:String,tool:String):Boolean {
        validateKeys(domain,tool)
        return processMutex.withLock {
            val old=recover(readIndex());val values=readAll(old)
            val found=values.any {it.domain==domain&&it.tool==tool}
            check(old.generation<Long.MAX_VALUE){"피드백 세대 한도에 도달했습니다."}
            val retained=values.filterNot {it.domain==domain&&it.tool==tool}
            withContext(NonCancellable){
                val next=Index(generation=old.generation+1,keys=retained.map {key(old.generation+1,it.domain,it.tool)},pendingDeletes=old.keys)
                saveIndex(next)
                retained.forEach {saveAggregate(next.generation,it)}
                recover(next)
            }
            found
        }
    }

    private suspend fun update(domain:String,tool:String,expected:Long?,change:(PlanningToolFeedback)->PlanningToolFeedback):Boolean = processMutex.withLock {
        currentCoroutineContext().ensureActive()
        val index=recover(readIndex())
        if(expected!=null&&expected!=index.generation)return@withLock false
        val key=key(index.generation,domain,tool)
        val previous=if(key in index.keys)readAggregate(key,index.generation)?:PlanningToolFeedback(domain,tool)else PlanningToolFeedback(domain,tool)
        check(previous.domain==domain&&previous.tool==tool){"피드백 식별자를 확인하지 못했습니다."}
        val nextValue=change(previous);validateStats(nextValue)
        val ordered=index.keys.filterNot {it==key}+key
        val removed=ordered.dropLast(MAX_STATS)
        val next=index.copy(keys=ordered.takeLast(MAX_STATS),pendingDeletes=removed)
        // Index-first writes track every file even if the process dies during the data write.
        withContext(NonCancellable){saveIndex(next);saveAggregate(next.generation,nextValue);recover(next)}
        true
    }

    private suspend fun readIndex():Index {
        val body=persistence.read(INDEX_ID)?:return Index()
        require(body.length<=MAX_DOCUMENT_CHARS){"저장된 피드백 인덱스가 너무 큽니다."}
        val index=try{json.decodeFromString<Index>(body)}catch(_:IllegalArgumentException){throw IllegalStateException("저장된 피드백 인덱스 형식이 유효하지 않습니다.")}
        require(index.schemaVersion==1&&index.generation>=0&&index.keys.size<=MAX_STATS&&index.pendingDeletes.size<=MAX_STATS)
        require(index.keys.distinct().size==index.keys.size&&index.pendingDeletes.distinct().size==index.pendingDeletes.size)
        require((index.keys+index.pendingDeletes).all {KEY.matches(it)}&&index.keys.intersect(index.pendingDeletes.toSet()).isEmpty())
        return index
    }
    private suspend fun saveIndex(value:Index){saveVerified(INDEX_ID,json.encodeToString(value))}
    private suspend fun recover(index:Index):Index {
        if(index.pendingDeletes.isEmpty())return index
        return withContext(NonCancellable){
            for(key in index.pendingDeletes){persistence.delete(STAT_PREFIX+key);check(persistence.read(STAT_PREFIX+key)==null){"피드백 삭제를 확인하지 못했습니다."}}
            index.copy(pendingDeletes=emptyList()).also {saveIndex(it)}
        }
    }
    private suspend fun readAll(index:Index):List<PlanningToolFeedback> = buildList {
        for(key in index.keys)readAggregate(key,index.generation)?.let {add(it)}
    }
    private suspend fun readAggregate(digestKey:String,generation:Long):PlanningToolFeedback? {
        val body=persistence.read(STAT_PREFIX+digestKey)?:return null // Interrupted new aggregate, never a success claim.
        require(body.length<=MAX_DOCUMENT_CHARS)
        val record=try{json.decodeFromString<Aggregate>(body)}catch(_:IllegalArgumentException){throw IllegalStateException("저장된 실행 피드백 형식이 유효하지 않습니다.")}
        require(record.schemaVersion==1&&record.generation==generation)
        validateStats(record.value)
        require(key(generation,record.value.domain,record.value.tool)==digestKey)
        return record.value
    }
    private suspend fun saveAggregate(generation:Long,value:PlanningToolFeedback){
        saveVerified(STAT_PREFIX+key(generation,value.domain,value.tool),json.encodeToString(Aggregate(generation=generation,value=value)))
    }
    private suspend fun saveVerified(id:String,body:String){
        require(body.length<=MAX_DOCUMENT_CHARS)
        persistence.write(id,body)
        check(persistence.read(id)==body){"저장된 실행 피드백의 일치를 확인하지 못했습니다."}
    }
    private fun validateStats(value:PlanningToolFeedback){
        validateKeys(value.domain,value.tool)
        require(listOf(value.verifiedSuccess,value.prepared,value.unverified,value.failed,value.failureStreak,value.recoveredAfterFailure).all {it in 0..MAX_COUNT})
        require(value.failureCounts.size<=failureKinds.size&&value.failureCounts.all {it.key in failureKinds&&it.value in 1..MAX_COUNT})
        require(value.failureCounts.values.all {it<=value.failed})
        require(value.proposedLessons.size<=lessonKinds.size&&value.proposedLessons.all {it.key in lessonKinds&&it.value in 1..MAX_COUNT})
        require(value.updatedAt>=0)
    }
    private fun timestamp()=now().coerceAtLeast(0)
    private fun bump(value:Long)=(value+1).coerceAtMost(MAX_COUNT)
    private fun increment(values:Map<String,Long>,name:String)=values+(name to bump(values[name]?:0))
    private fun key(generation:Long,domain:String,tool:String)=MessageDigest.getInstance("SHA-256").digest("$generation|$domain|$tool".toByteArray(Charsets.UTF_8)).take(16).joinToString(""){"%02x".format(it.toInt()and 255)}

    companion object {
        const val COLLECTION="planning-feedback"
        const val MAX_STATS=128
        const val MAX_CONTEXT_CHARS=1800
        private const val MAX_DOCUMENT_CHARS=12000
        private const val MAX_COUNT=1_000_000L
        private const val INDEX_ID="planning-feedback-index-v1"
        private const val STAT_PREFIX="planning-feedback-stat-"
        private val KEY=Regex("[a-f0-9]{32}")
        private val processMutex=Mutex()
        val knownDomains:Set<String> = setOf("messages","calendar","documents","projects","images","notes","memory","audio","web","general")
        val failureKinds:Set<String> = setOf("invalid_json","unknown_tool","invalid_arguments","permission_denied","timeout","verification_failed","execution_failed","precondition_failed","unsupported","cancelled","result_conflict")
        val lessonKinds:Set<String> = setOf("read_before_write","verify_result","avoid_repeat","keep_user_goal","ask_one_essential","prefer_native_tool","prefer_existing_data")
        fun domainForTool(tool:String):String {validateTool(tool);return if(tool=="planner")"general"else IntentPlanning.domainForTool(tool)}
        private fun validateDomain(domain:String){require(domain in knownDomains){"지원하는 계획 분야 코드가 필요합니다."}}
        private fun validateTool(tool:String){require(tool=="planner"||tool in ToolRegistry.definitions){"등록된 도구 코드가 필요합니다."}}
        private fun validateKeys(domain:String,tool:String){validateDomain(domain);validateTool(tool)}
        private fun validateGeneration(value:Long?){require(value==null||value>=0){"실행 피드백 세대가 유효하지 않습니다."}}
        private fun hints(value:PlanningToolFeedback):List<String> = buildList {
            if(value.failureStreak>=2)add("do_not_repeat_same_failed_call")
            val invalid=listOf("invalid_json","unknown_tool","invalid_arguments").sumOf {value.failureCounts[it]?:0}
            if(invalid>=2)add("validate_schema_before_execution")
            if(value.prepared>0)add("prepared_is_not_external_completion")
            if(value.unverified>0||value.failureCounts.getOrDefault("verification_failed",0)>0)add("verify_postcondition_before_completion")
            if(value.failureCounts.getOrDefault("permission_denied",0)>0)add("check_granted_scope_before_retry")
            if(value.failed-value.failureCounts.getOrDefault("cancelled",0)>=2)add("try_verified_alternative_or_read_first")
        }
    }
}

private class RepositoryFeedbackPersistence(private val repository:LocalRepository):PlanningFeedbackPersistence {
    override suspend fun read(id:String):String?=repository.getNote(id)?.let {note->
        check(note.collection==PlanningFeedbackStore.COLLECTION){"피드백 저장 영역이 일치하지 않습니다."};note.body
    }
    override suspend fun write(id:String,body:String){repository.saveNote("로컬 실행 피드백",body,PlanningFeedbackStore.COLLECTION,id)}
    override suspend fun delete(id:String){repository.deleteNote(id)}
}
