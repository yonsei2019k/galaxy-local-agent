package com.user.localagent.automation

import com.user.localagent.core.*
import com.user.localagent.data.ChatAccessScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.Locale

/** Scoped effects only. The host supplies currently validated owner-request tool authorization. */
class ScopedGoalTools(private val scope:ChatAccessScope,private val store:GoalToolStore,
    private val authorizedTools:()->Set<String>,private val callerRunId:String?,private val lifecycle:GoalToolLifecycle,
    private val editReadGuard:(ToolCall,GoalDefinition)->Unit={_,_->error("실제 읽은 목표 본문 근거가 필요합니다.")}) {
    private val mutation=Mutex()

    suspend fun execute(call:ToolCall):ToolResult? {
        if(call.name !in GoalToolCatalog.names)return null
        GoalToolCatalog.validate(call)
        authorize(call.name)
        return mutation.withLock {
            authorize(call.name)
            val previous=store.load(scope)?.also(::scoped)
            if(call.name==GoalToolCatalog.GET) {
                if(call.arguments.containsKey("goalId"))version(call,checkNotNull(previous){"읽을 목표가 삭제되었습니다."})
                if(call.arguments.containsKey("textField"))return@withLock textResult(call,checkNotNull(previous))
                val offset=call.arguments["criteriaOffset"]?.jsonPrimitive?.int?:0
                val limit=call.arguments["criteriaLimit"]?.jsonPrimitive?.int?:8
                check(previous!=null||offset==0){"저장된 목표가 없습니다."}
                return@withLock result(call,"goal_read",previous,offset,limit)
            }
            if(call.name==GoalToolCatalog.CREATE) {
                check(previous==null){"현재 대화에 이미 목표가 있습니다. 실제 버전을 읽어 수정하거나 명시적으로 해제해주세요."}
                val draft=GoalDraftReducer.create(scope.threadId,scope.projectId,call.arguments.getValue("outcome").jsonPrimitive.content,
                    strings(call,"criteria")!!,strings(call,"constraints").orEmpty())
                authorize(call.name)
                val saved=store.save(scope,draft).also(::scoped)
                check(saved.id==draft.id&&saved.status==GoalStatus.DRAFT&&saved.criteria.all {it.status==GoalCriterionStatus.PENDING&&it.evidence.isEmpty()}&&saved.linkedRunIds.isEmpty())
                check(store.load(scope)==saved){"저장한 목표 초안을 다시 확인하지 못했습니다."}
                return@withLock result(call,"goal_created",saved)
            }
            val goal=checkNotNull(previous){"현재 대화에 저장된 목표가 없습니다."}
            version(call,goal)
            if(call.name==GoalToolCatalog.UPDATE) {
                val edits=(call.arguments["criteria"] as? JsonArray)?.map {item->item.jsonObject.let {
                    GoalCriterionEdit(it["id"]?.jsonPrimitive?.content,it.getValue("text").jsonPrimitive.content)
                }}
                val patch=(call.arguments["textEdits"] as? JsonArray)?.let {textPatch(goal,it)}
                    ?:GoalWorkUpdate(call.arguments["outcome"]?.jsonPrimitive?.content,edits,
                        strings(call,"removeCriterionIds").orEmpty(),strings(call,"constraints"))
                val updated=GoalDraftReducer.update(goal,patch)
                authorize(call.name)
                if(updated!=goal)editReadGuard(call,goal)
                val receipt=if(updated!=goal&&goal.linkedRunIds.isNotEmpty()) {
                    lifecycle.apply(GoalLifecycleRequest(GoalLifecycleAction.UPDATE,scope,goal,callerRunId)).also {quiesced(it,goal)}
                }else null
                authorize(call.name)
                val saved=if(updated==goal)goal else store.save(scope,updated).also(::scoped)
                check(store.load(scope)==saved){"수정한 목표를 다시 확인하지 못했습니다."}
                return@withLock result(call,if(updated==goal)"goal_unchanged"else"goal_updated",saved,receipt=receipt)
            }
            val action=when(call.name) {
                GoalToolCatalog.START->GoalLifecycleAction.START
                GoalToolCatalog.RESUME->GoalLifecycleAction.RESUME
                GoalToolCatalog.PAUSE->GoalLifecycleAction.PAUSE
                GoalToolCatalog.CLEAR->GoalLifecycleAction.CLEAR
                else->error("지원하지 않는 목표 동작입니다.")
            }
            if(action==GoalLifecycleAction.RESUME)check(goal.status==GoalStatus.PAUSED){"일시 정지한 목표만 재개할 수 있습니다."}
            authorize(call.name)
            val receipt=lifecycle.apply(GoalLifecycleRequest(action,scope,goal,callerRunId))
            authorize(call.name)
            if(action in setOf(GoalLifecycleAction.START,GoalLifecycleAction.RESUME)) {
                val runId=receipt.queuedRunId
                check(receipt.queuedOneBoundedRun&&!runId.isNullOrBlank()&&runId.length<=100&&runId.none {it.code<32}&&!receipt.deferredCallerStop){"실제 목표 실행 한 건의 대기열 저장을 확인하지 못했습니다."}
                val saved=checkNotNull(store.load(scope)).also(::scoped)
                check(saved.id==goal.id&&saved.updatedAt>goal.updatedAt&&saved.status==GoalStatus.ACTIVE&&runId in saved.linkedRunIds&&sameWork(goal,saved)){"실제 목표와 실행 연결의 저장 결과가 일치하지 않습니다."}
                return@withLock result(call,if(action==GoalLifecycleAction.START)"goal_started"else"goal_resumed",saved,receipt=receipt)
            }
            quiesced(receipt,goal)
            if(action==GoalLifecycleAction.PAUSE) {
                val saved=store.save(scope,goal.copy(status=GoalStatus.PAUSED)).also(::scoped)
                check(store.load(scope)==saved){"목표 일시 정지 저장을 확인하지 못했습니다."}
                result(call,"goal_paused",saved,receipt=receipt)
            } else {
                check(store.delete(scope,goal.id,goal.updatedAt)){"해제할 실제 목표를 확인하지 못했습니다."}
                check(store.load(scope)==null){"목표 해제 결과를 확인하지 못했습니다."}
                result(call,"goal_cleared",null,receipt=receipt,clearedId=goal.id)
            }
        }
    }

    private suspend fun authorize(name:String) {
        currentCoroutineContext().ensureActive()
        check(name in authorizedTools()){"현재 사용자가 요청한 목표 동작으로 승인되지 않았습니다."}
    }
    private fun scoped(goal:GoalDefinition) {goal.validate();check(goal.threadId==scope.threadId&&goal.projectId==scope.projectId&&goal.updatedAt>0){"요청한 원래 대화·프로젝트의 목표가 아닙니다."}}
    private fun version(call:ToolCall,goal:GoalDefinition) {
        check(call.arguments.getValue("goalId").jsonPrimitive.content==goal.id&&GoalToolCatalog.revision(call.arguments.getValue("expectedUpdatedAt"))==goal.updatedAt){"목표의 실제 버전이 바뀌었습니다. 현재 목표를 다시 읽어주세요."}
    }
    private fun strings(call:ToolCall,key:String)=(call.arguments[key] as? JsonArray)?.map {it.jsonPrimitive.content}
    private data class TextField(val field:String,val criterionId:String?,val constraintIndex:Int?,val text:String) {
        override fun toString()="TextField(<private>)"
    }
    private fun field(goal:GoalDefinition,name:String,arguments:JsonObject):TextField=when(name) {
        "outcome"->TextField(name,null,null,goal.outcome)
        "criterion"->{val id=arguments.getValue("criterionId").jsonPrimitive.content
            TextField(name,id,null,checkNotNull(goal.criteria.singleOrNull {it.id==id}){"현재 목표의 실제 기준을 찾지 못했습니다."}.text)}
        "constraint"->{val index=arguments.getValue("constraintIndex").jsonPrimitive.int
            require(index in goal.constraints.indices){"현재 목표의 실제 조건 위치가 아닙니다."}
            TextField(name,null,index,goal.constraints[index])}
        else->error("지원하지 않는 목표 텍스트 필드입니다.")
    }
    private fun boundary(text:String,offset:Int)=offset in 0..text.length&&
        !(offset>0&&offset<text.length&&text[offset-1].isHighSurrogate()&&text[offset].isLowSurrogate())
    private fun validUnicode(text:String):Boolean {
        var at=0
        while(at<text.length) {
            val c=text[at++]
            if(c.isHighSurrogate()){if(at>=text.length||!text[at].isLowSurrogate())return false;at++}
            else if(c.isLowSurrogate())return false
        }
        return true
    }
    private fun textPatch(goal:GoalDefinition,edits:JsonArray):GoalWorkUpdate {
        var outcome:String?=null
        val criteria=mutableListOf<GoalCriterionEdit>();val constraints=linkedMapOf<Int,String>()
        for(value in edits) {
            val edit=value.jsonObject;val selected=field(goal,edit.getValue("field").jsonPrimitive.content,edit)
            val old=edit.getValue("oldText").jsonPrimitive.content;val replacement=edit.getValue("newText").jsonPrimitive.content
            require(old.isNotEmpty()&&validUnicode(old)&&validUnicode(replacement)){"목표 텍스트 수정은 온전한 유니코드 문자열이어야 합니다."}
            val start=selected.text.indexOf(old)
            require(start>=0&&start==selected.text.lastIndexOf(old)){"oldText는 해당 실제 목표 필드에서 정확히 한 번 일치해야 합니다."}
            val end=start+old.length
            require(boundary(selected.text,start)&&boundary(selected.text,end)){"목표 문자의 중간을 바꿀 수 없습니다."}
            val updated=selected.text.substring(0,start)+replacement+selected.text.substring(end)
            when(selected.field) {
                "outcome"->outcome=updated
                "criterion"->criteria+=GoalCriterionEdit(selected.criterionId,updated)
                "constraint"->constraints[checkNotNull(selected.constraintIndex)]=updated
            }
        }
        return GoalWorkUpdate(outcome=outcome,criteria=criteria.takeIf {it.isNotEmpty()},constraintEdits=constraints)
    }
    private fun textResult(call:ToolCall,goal:GoalDefinition):ToolResult {
        val selected=field(goal,call.arguments.getValue("textField").jsonPrimitive.content,call.arguments)
        val offset=call.arguments["textOffset"]?.jsonPrimitive?.int?:0
        val limit=call.arguments["textLimit"]?.jsonPrimitive?.int?:900
        require(boundary(selected.text,offset)){"텍스트 시작 위치가 범위를 벗어났거나 유니코드 문자 중간입니다."}
        var end=minOf(selected.text.length,offset+limit)
        if(!boundary(selected.text,end))end--
        fun payload()=buildJsonObject {
            put("status","goal_text_read");put("goalPresent",true);put("scopeBound",true)
            put("threadId",scope.threadId);put("projectId",scope.projectId)
            put("goalId",goal.id);put("expectedUpdatedAt",goal.updatedAt.toString());put("goalStatus",goal.status.name.lowercase(Locale.ROOT))
            put("goalCompletionEvidence",false);put("grantsCapabilities",false);put("backgroundScheduled",false)
            put("textRead",buildJsonObject {
                put("field",selected.field);selected.criterionId?.let {put("criterionId",it)};selected.constraintIndex?.let {put("constraintIndex",it)}
                put("offset",offset);put("totalChars",selected.text.length);put("text",selected.text.substring(offset,end))
                put("nextOffset",if(end<selected.text.length)JsonPrimitive(end)else JsonNull)
                put("textComplete",offset==0&&end==selected.text.length)
            })
        }
        var value=payload()
        // textLimit is an upper bound. Choose the exact fragment before it is delivered; never clip it later.
        while(value.toString().length>1950&&end>offset) {
            end--;if(!boundary(selected.text,end))end--
            value=payload()
        }
        require(end>offset||offset==selected.text.length){"온전한 텍스트 조각을 담을 수 없습니다. textLimit을 늘려 다시 읽어주세요."}
        return ToolResult(call.id,true,value.toString(),VerificationState.VERIFIED,"원래 대화·목표 버전의 정확한 텍스트 범위 읽기 · 목표 완료 근거 아님")
    }
    private fun quiesced(receipt:GoalLifecycleReceipt,goal:GoalDefinition) {
        check(receipt.quiesced&&!receipt.queuedOneBoundedRun&&receipt.queuedRunId==null){"목표에 연결된 실행을 정지했는지 확인하지 못했습니다."}
        if(receipt.deferredCallerStop)check(callerRunId!=null&&callerRunId in goal.linkedRunIds){"현재 실행을 나중에 멈출 목표 연결 근거가 없습니다."}
    }
    private fun sameWork(a:GoalDefinition,b:GoalDefinition)=a.id==b.id&&a.threadId==b.threadId&&a.projectId==b.projectId&&a.outcome==b.outcome&&a.constraints==b.constraints&&a.criteria.map {it.id to it.text}==b.criteria.map {it.id to it.text}

    private fun result(call:ToolCall,status:String,goal:GoalDefinition?,offset:Int=0,limit:Int=8,
        receipt:GoalLifecycleReceipt?=null,clearedId:String?=null):ToolResult {
        require(goal==null||offset in 0..goal.criteria.size){"목표 기준 페이지가 현재 목록 범위를 벗어났습니다."}
        val selected=goal?.criteria?.drop(offset)?.take(limit).orEmpty()
        val deferred=receipt?.deferredCallerStop==true
        val payload=buildJsonObject {
            put("status",if(goal==null&&clearedId==null)"no_goal"else status)
            put("goalPresent",goal!=null);put("scopeBound",true);put("goalCompletionEvidence",false);put("grantsCapabilities",false)
            put("threadId",scope.threadId);put("projectId",scope.projectId)
            if(goal!=null) {
                put("goalId",goal.id);put("expectedUpdatedAt",goal.updatedAt.toString());put("goalStatus",goal.status.name.lowercase(Locale.ROOT))
                put("outcome",goal.outcome);put("constraints",JsonArray(goal.constraints.map(::JsonPrimitive)))
                put("totalConstraints",goal.constraints.size);put("constraintsComplete",true)
                put("totalCriteria",goal.criteria.size);put("criteriaOffset",offset)
                put("criteriaPartial",offset!=0||selected.size!=goal.criteria.size)
                put("nextCriteriaOffset",if(offset+selected.size<goal.criteria.size)JsonPrimitive(offset+selected.size)else JsonNull)
                put("criteria",buildJsonArray {selected.forEach {criterion->add(buildJsonObject {
                    put("id",criterion.id);put("text",criterion.text);put("status",criterion.status.name.lowercase(Locale.ROOT));put("evidenceCount",criterion.evidence.size)
                })}})
            }else clearedId?.let {put("goalId",it)}
            receipt?.let {
                it.queuedRunId?.let {id->put("queuedRunId",id)}
                put("queuedOneBoundedRun",it.queuedOneBoundedRun);put("quiesced",it.quiesced);put("deferredCallerStop",it.deferredCallerStop)
            }
            put("backgroundScheduled",false)
        }
        return ToolResult(call.id,true,payload.toString(),if(deferred)VerificationState.PREPARED else VerificationState.VERIFIED,
            if(deferred)"목표 상태 저장 · 현재 연결 실행 정지는 영수증 저장 후 처리 대기 · 목표 작업 완료 근거 아님"
            else"현재 대화의 목표 저장·조회·생명주기 결과 확인 · 외부 작업이나 목표 자체 완료를 뜻하지 않음")
    }
}
