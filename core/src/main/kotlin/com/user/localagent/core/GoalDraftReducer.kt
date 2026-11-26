package com.user.localagent.core

import java.util.UUID

data class GoalCriterionEdit(val id:String?=null,val text:String) {
    override fun toString()="GoalCriterionEdit(<private>)"
}
data class GoalWorkUpdate(val outcome:String?=null,val criteria:List<GoalCriterionEdit>?=null,
    val removeCriterionIds:List<String> = emptyList(),val constraints:List<String>?=null,
    val constraintEdits:Map<Int,String> = emptyMap()) {
    override fun toString()="GoalWorkUpdate(<private>)"
}

/** Draft construction only. No request interpretation, execution, evidence creation, or confirmation. */
object GoalDraftReducer {
    fun create(threadId:String,projectId:String?,outcome:String,criteria:List<String>,constraints:List<String> = emptyList(),
        newId:()->String={UUID.randomUUID().toString()}):GoalDefinition {
        require(criteria.size in 1..16&&constraints.size<=16)
        return GoalDefinition(id=newId(),threadId=threadId,projectId=projectId,outcome=outcome,
            criteria=criteria.map {GoalCriterion(id=newId(),text=it)},constraints=constraints).validate().also(::bounded)
    }

    fun update(previous:GoalDefinition,patch:GoalWorkUpdate,newId:()->String={UUID.randomUUID().toString()}):GoalDefinition {
        previous.validate()
        require((patch.criteria?.size?:0)<=16&&(patch.constraints?.size?:0)<=16)
        require(patch.constraintEdits.size<=8&&patch.constraintEdits.keys.all {it in previous.constraints.indices}){"현재 조건의 실제 위치만8개 이하로 수정할 수 있습니다."}
        require(patch.constraints==null||patch.constraintEdits.isEmpty()){"조건 전체 교체와 부분 수정을 함께 사용할 수 없습니다."}
        require(patch.removeCriterionIds.size<=16&&patch.removeCriterionIds.distinct().size==patch.removeCriterionIds.size)
        val known=previous.criteria.associateBy {it.id}
        val edits=patch.criteria.orEmpty()
        require(edits.mapNotNull {it.id}.distinct().size==edits.count {it.id!=null})
        require(edits.all {it.id==null||it.id in known}&&patch.removeCriterionIds.all {it in known}){"현재 목표의 실제 완료 기준만 수정·삭제할 수 있습니다."}
        require(edits.none {it.id in patch.removeCriterionIds}){"같은 완료 기준을 동시에 수정·삭제할 수 없습니다."}
        val byId=edits.filter {it.id!=null}.associateBy {it.id!!}
        val criteria=previous.criteria.filterNot {it.id in patch.removeCriterionIds}.map {criterion->
            byId[criterion.id]?.let {criterion.copy(text=it.text,proofTarget=criterion.proofTarget.takeIf {_ -> it.text==criterion.text})}?:criterion
        }+edits.filter {it.id==null}.map {GoalCriterion(id=newId(),text=it.text)}
        require(criteria.isNotEmpty()){"완료 기준을 하나 이상 남겨주세요."}
        val constraints=patch.constraints?:previous.constraints.mapIndexed {index,old->patch.constraintEdits[index]?:old}
        val candidate=previous.copy(outcome=patch.outcome?:previous.outcome,criteria=criteria,constraints=constraints)
        val changed=candidate.outcome!=previous.outcome||candidate.constraints!=previous.constraints||
            candidate.criteria.map {it.id to it.text}!=previous.criteria.map {it.id to it.text}
        val result=if(changed)candidate.copy(status=GoalStatus.DRAFT,
            criteria=candidate.criteria.map {it.copy(status=GoalCriterionStatus.PENDING,evidence=emptyList())})else previous
        return result.validate().also(::bounded)
    }

    private fun bounded(goal:GoalDefinition) {
        require(goal.toJson().toString().length<=12000){"목표·기준·근거의 저장 내용이12,000자를 넘었습니다. 원문은 자르지 않았습니다."}
    }
}
