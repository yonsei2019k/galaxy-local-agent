package com.user.localagent.automation

import com.user.localagent.core.GoalCriterionStatus
import com.user.localagent.core.GoalDefinition
import com.user.localagent.core.GoalStatus
import com.user.localagent.core.AgentWorkModePolicy
import com.user.localagent.core.ToolRegistry
import com.user.localagent.core.GoalToolCatalog
import com.user.localagent.data.ChatThreadRecord
import com.user.localagent.data.ChatAccessScope
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.NoteRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** One current goal per chat, using the existing encrypted Room note and captured scope. */
class ChatGoalStore(private val repository:LocalRepository) {
    private val codec=Json{ignoreUnknownKeys=true;encodeDefaults=true}
    private data class Bound(val thread:ChatThreadRecord,val scoped:LocalRepository,val note:NoteRecord?,val expectedAccess:ChatAccessScope?=null)

    suspend fun load(threadId:String):GoalDefinition?=loadInternal(threadId,null)
    suspend fun loadGuarded(expectedAccess:ChatAccessScope):GoalDefinition?=loadInternal(expectedAccess.threadId,expectedAccess)

    private suspend fun loadInternal(threadId:String,expectedAccess:ChatAccessScope?):GoalDefinition?=withContext(Dispatchers.IO){MUTATION.withLock{
        val bound=bind(threadId,expectedAccess)
        val goal=bound.note?.let{decode(it,bound.thread)}
        if(goal!=null){verifyRunLinks(goal);verifyEvidence(goal)}
        checkLive(bound)
        goal
    }}

    /** A loaded timestamp is an optimistic revision, not a caller-selected new clock value. */
    suspend fun save(threadId:String,goal:GoalDefinition):GoalDefinition=saveInternal(threadId,goal,null)
    suspend fun saveGuarded(expectedAccess:ChatAccessScope,goal:GoalDefinition):GoalDefinition=saveInternal(expectedAccess.threadId,goal,expectedAccess)

    private suspend fun saveInternal(threadId:String,goal:GoalDefinition,expectedAccess:ChatAccessScope?):GoalDefinition=withContext(Dispatchers.IO){MUTATION.withLock{
        goal.validate()
        val bound=bind(threadId,expectedAccess)
        check(goal.threadId==threadId&&goal.projectId==bound.thread.projectId){"목표의 대화·프로젝트 범위가 현재 대화와 다릅니다."}
        val previous=bound.note?.let{decode(it,bound.thread)}
        if(previous==null)check(goal.updatedAt==0L){"목표가 삭제되었거나 변경되었습니다. 새 목표로 다시 작성해주세요."}
        else check(previous.id==goal.id&&previous.updatedAt==goal.updatedAt){"목표 진행 상태가 변경되었습니다. 다시 불러온 뒤 수정해주세요."}
        verifyRunLinks(goal)
        verifyEvidence(goal)
        checkLive(bound)
        val saved=goal.copy(updatedAt=maxOf(System.currentTimeMillis(),(previous?.updatedAt?:0)+1))
        val body=codec.encodeToString(saved)
        require(body.length<=12000){"목표·조건·근거를 합한 저장 내용이 12,000자를 넘었습니다. 내용을 나누어주세요. 원문은 자르지 않았습니다."}
        bound.scoped.saveNote("대화 목표",body,COLLECTION,noteId(threadId))
        val readback=checkNotNull(bound.scoped.getNote(noteId(threadId))){"저장한 목표를 다시 읽지 못했습니다."}
        check(readback.body==body&&readback.collection==COLLECTION){"목표 저장 결과를 확인하지 못했습니다."}
        checkLive(bound)
        decode(readback,bound.thread).also{check(it==saved)}
    }}

    suspend fun delete(threadId:String) {deleteInternal(threadId,null,null,null)}
    suspend fun deleteGuarded(expectedAccess:ChatAccessScope,goalId:String,expectedUpdatedAt:Long):Boolean {
        require(goalId.isNotBlank()&&expectedUpdatedAt>0)
        return deleteInternal(expectedAccess.threadId,goalId,expectedUpdatedAt,expectedAccess)
    }

    private suspend fun deleteInternal(threadId:String,goalId:String?,expectedUpdatedAt:Long?,expectedAccess:ChatAccessScope?):Boolean=withContext(Dispatchers.IO){MUTATION.withLock{
        val bound=bind(threadId,expectedAccess)
        val previous=bound.note?.let {decode(it,bound.thread)}
        if(goalId!=null)check(previous!=null&&previous.id==goalId&&previous.updatedAt==expectedUpdatedAt){"목표가 변경되거나 삭제되었습니다. 최신 목표를 다시 읽어주세요."}
        checkLive(bound)
        if(bound.note!=null)bound.scoped.deleteNote(noteId(threadId))
        check(bound.scoped.notes.first().none{it.id==noteId(threadId)}){"목표 삭제를 확인하지 못했습니다."}
        checkLive(bound)
        previous!=null
    }}

    /** Caller quiesces active writes first. Room rolls back the entire move on any failure. */
    suspend fun moveThread(threadId:String,targetProjectId:String?):ChatThreadRecord=withContext(Dispatchers.IO){MUTATION.withLock{
        val bound=bind(threadId)
        if(bound.thread.projectId==targetProjectId)return@withLock bound.thread
        val original=bound.note?.let{decode(it,bound.thread)}
        if(original!=null){verifyRunLinks(original);verifyEvidence(original)}
        val replacement=original?.copy(projectId=targetProjectId,status=GoalStatus.DRAFT,linkedRunIds=emptyList(),
            criteria=original.criteria.map{criterion->criterion.copy(status=if(criterion.status in setOf(GoalCriterionStatus.VERIFIED,GoalCriterionStatus.USER_CONFIRMED))GoalCriterionStatus.PENDING else criterion.status,evidence=emptyList())},
            updatedAt=maxOf(System.currentTimeMillis(),original.updatedAt+1))
        val body=replacement?.let{codec.encodeToString(it.validate())}
        require(body==null||body.length<=12000){"이동할 목표의 저장 내용이 12,000자를 넘었습니다. 원문은 자르지 않았습니다."}
        repository.moveThreadWithGoal(threadId,targetProjectId,bound.thread.revision,bound.thread.projectId,bound.note?.body,body)
    }}

    private suspend fun bind(threadId:String,expectedAccess:ChatAccessScope?=null):Bound {
        require(threadId.isNotBlank()&&noteId(threadId).length<=100){"대화 식별자가 올바르지 않습니다."}
        if(expectedAccess!=null){check(expectedAccess.threadId==threadId);checkExpectedAccess(expectedAccess)}
        val thread=repository.chat.thread(threadId)?:error("대화가 삭제되었거나 존재하지 않습니다.")
        val scoped=repository.forThread(threadId)
        // Scoped getNote intentionally rejects an unbound missing ID; the scoped observer
        // distinguishes absence without probing another project's note.
        val result=Bound(thread,scoped,scoped.notes.first().firstOrNull{it.id==noteId(threadId)},expectedAccess)
        checkLive(result)
        return result
    }
    private fun decode(note:NoteRecord,thread:ChatThreadRecord):GoalDefinition {
        check(note.collection==COLLECTION&&note.body.length<=12000){"현재 대화의 목표 저장 형식이 올바르지 않습니다."}
        return codec.decodeFromString<GoalDefinition>(note.body).validate().also{
            check(it.threadId==thread.id&&it.projectId==thread.projectId&&it.updatedAt>0){"저장된 목표의 대화·프로젝트 범위가 변경되었습니다."}
        }
    }
    private suspend fun checkLive(bound:Bound){
        val before=bound.thread
        val live=repository.chat.thread(before.id)?:error("목표 처리 중 대화가 삭제되었습니다.")
        check(live.projectId==before.projectId&&live.revision==before.revision){"목표 처리 중 대화 버전이나 프로젝트가 변경되었습니다."}
        bound.expectedAccess?.let {checkExpectedAccess(it)}
    }
    private suspend fun checkExpectedAccess(expected:ChatAccessScope) {
        check(repository.chat.accessScope(expected.threadId)==expected){"목표 요청의 대화·프로젝트·자료 접근 설정이 변경되었습니다. 현재 대화에서 다시 요청해주세요."}
    }
    private suspend fun verifyRunLinks(goal:GoalDefinition){
        for(id in goal.linkedRunIds){
            val run=repository.chat.run(id)?:error("목표에 연결할 실제 실행 기록이 없습니다.")
            check(run.threadId==goal.threadId&&run.projectId==goal.projectId){"다른 대화·프로젝트의 실행을 목표에 연결할 수 없습니다."}
        }
    }
    private suspend fun verifyEvidence(goal:GoalDefinition){
        val verified=goal.criteria.filter{it.status==GoalCriterionStatus.VERIFIED}
        if(verified.isEmpty())return
        val scoped=repository.forThread(goal.threadId)
        for(criterion in verified)for(reference in criterion.evidence){
            val id=reference.removePrefix("tool-run:")
            require(id.isNotBlank()&&id.length<=128&&id.none{it.code<32}){"검증 근거 식별자가 올바르지 않습니다."}
            val record=scoped.toolRunEvidence(id)?:error("실제 실행 근거를 찾지 못해 완료 기준을 검증됨으로 저장하지 않았습니다.")
            check(record.name in ToolRegistry.definitions&&record.name !in AgentWorkModePolicy.contextReadTools&&record.name !in GoalToolCatalog.names){"목표 관리·문맥 조회가 아닌 등록된 실제 작업 도구의 근거만 완료 기준에 연결합니다."}
            val runId=record.runId?:error("목표의 실행과 연결되지 않은 근거입니다.")
            val run=repository.chat.run(runId)?:error("검증 근거의 실제 실행 기록이 없습니다.")
            check(record.threadId==goal.threadId&&runId in goal.linkedRunIds&&run.threadId==goal.threadId&&run.projectId==goal.projectId){"검증 근거가 이 목표의 실행 범위에 속하지 않습니다."}
            check(record.success&&VERIFIED_PREFIX.containsMatchIn(record.summary)){"준비·미확인·실패 기록은 완료 기준의 검증 근거가 아닙니다."}
        }
    }
    companion object {
        private const val COLLECTION="chat-goals"
        private val MUTATION=Mutex()
        private val VERIFIED_PREFIX=Regex("^VERIFIED(?:$|[\\s·:])")
        fun noteId(threadId:String)="chat-goal:$threadId"
    }
}
