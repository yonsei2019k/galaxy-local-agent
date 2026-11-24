package com.user.localagent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class LedgerKind {
    @SerialName("lecture") LECTURE,
    @SerialName("quiz") QUIZ,
    @SerialName("survey") SURVEY,
    @SerialName("certificate") CERTIFICATE,
    @SerialName("condition") CONDITION,
    @SerialName("other") OTHER,
}

@Serializable
enum class LedgerStatus {
    @SerialName("todo") TODO,
    @SerialName("active") ACTIVE,
    @SerialName("waiting") WAITING,
    @SerialName("done") DONE,
    @SerialName("blocked") BLOCKED,
}

@Serializable
data class LedgerItem(
    val id:String,val course:String,val unit:String,val kind:LedgerKind,
    val status:LedgerStatus=LedgerStatus.TODO,val note:String="",val evidence:List<String> = emptyList(),
)

/** Model-written progress checklist for one persistent goal. The host validates it; DONE needs observed audit evidence. */
@Serializable
data class GoalProgressLedger(
    val goalId:String,val requirements:String="",val dailyLimit:Int?=null,val deadline:String?=null,
    val items:List<LedgerItem> = emptyList(),val updatedAt:Long=0,
)

sealed interface LedgerOp {
    data class Add(val item:LedgerItem):LedgerOp
    data class Set(val id:String,val status:LedgerStatus,val note:String?,val evidence:List<String>):LedgerOp
    data class SetMeta(val requirements:String?,val dailyLimit:Int?,val deadline:String?):LedgerOp
}

object GoalProgressLimits {
    const val MAX_ITEMS=300
    const val MAX_NOTE=200
    const val MAX_REQUIREMENTS=2000
    const val MAX_BYTES=65_536
    const val MAX_ID=100
    const val MAX_COURSE=100
    const val MAX_UNIT=200
    const val MAX_EVIDENCE=10
    const val MAX_DEADLINE=40
    const val MAX_DAILY_LIMIT=1000
    val json=Json {encodeDefaults=true;ignoreUnknownKeys=false}
}

fun GoalProgressLedger.validate():GoalProgressLedger {
    val limit=GoalProgressLimits
    require(goalId.isNotBlank()&&goalId.length<=limit.MAX_ID){"장부 목표 식별자가 올바르지 않습니다."}
    require(requirements.length<=limit.MAX_REQUIREMENTS){"장부 요구사항은 ${limit.MAX_REQUIREMENTS}자 이하여야 합니다."}
    require(dailyLimit==null||dailyLimit in 1..limit.MAX_DAILY_LIMIT){"하루 한도는 1~${limit.MAX_DAILY_LIMIT} 범위여야 합니다."}
    require(deadline==null||deadline.length<=limit.MAX_DEADLINE){"마감 표기가 너무 깁니다."}
    require(items.size<=limit.MAX_ITEMS){"장부 항목은 ${limit.MAX_ITEMS}개 이하여야 합니다."}
    require(items.map {it.id}.toSet().size==items.size){"장부 항목 식별자가 중복됐습니다."}
    items.forEach {item->
        require(item.id.isNotBlank()&&item.id.length<=limit.MAX_ID){"장부 항목 식별자가 올바르지 않습니다."}
        require(item.course.isNotBlank()&&item.course.length<=limit.MAX_COURSE){"장부 과정명이 올바르지 않습니다."}
        require(item.unit.isNotBlank()&&item.unit.length<=limit.MAX_UNIT){"장부 단위 이름이 올바르지 않습니다."}
        require(item.note.length<=limit.MAX_NOTE){"장부 메모는 ${limit.MAX_NOTE}자 이하여야 합니다."}
        require(item.evidence.size<=limit.MAX_EVIDENCE&&item.evidence.all {it.isNotBlank()&&it.length<=limit.MAX_ID}){"장부 근거 목록이 올바르지 않습니다."}
        require(item.status!=LedgerStatus.DONE||item.evidence.isNotEmpty()){"근거 없이 완료로 표시할 수 없습니다."}
    }
    require(limit.json.encodeToString(this).toByteArray(Charsets.UTF_8).size<=limit.MAX_BYTES){"장부가 너무 큽니다."}
    return this
}

/** Applies ops in order, then validates. Pure: on any invalid op an exception is thrown and the receiver is unchanged. */
fun GoalProgressLedger.apply(ops:List<LedgerOp>,observedAuditIds:Set<String>,now:Long):GoalProgressLedger {
    fun requireObserved(status:LedgerStatus,evidence:List<String>) {
        if(status!=LedgerStatus.DONE)return
        require(evidence.isNotEmpty()){"근거 없이 완료로 표시할 수 없습니다."}
        require(evidence.all {it in observedAuditIds}){"이번 실행에서 관찰되지 않은 근거로 완료할 수 없습니다."}
    }
    var next=this
    for(op in ops) next=when(op) {
        is LedgerOp.Add->{
            require(next.items.none {it.id==op.item.id}){"이미 있는 장부 항목입니다."}
            requireObserved(op.item.status,op.item.evidence)
            next.copy(items=next.items+op.item)
        }
        is LedgerOp.Set->{
            val current=next.items.firstOrNull {it.id==op.id}?:throw IllegalArgumentException("없는 장부 항목입니다.")
            requireObserved(op.status,op.evidence)
            val updated=current.copy(status=op.status,note=op.note?:current.note,evidence=op.evidence.ifEmpty {current.evidence})
            next.copy(items=next.items.map {if(it.id==op.id)updated else it})
        }
        is LedgerOp.SetMeta->next.copy(
            requirements=op.requirements?:next.requirements,dailyLimit=op.dailyLimit?:next.dailyLimit,deadline=op.deadline?:next.deadline)
    }
    return next.copy(updatedAt=now).validate()
}

/** One-line chat card text: "<course> <done>/<total>" per course in first-appearance order. */
fun GoalProgressLedger.summary():String =
    items.groupBy {it.course}.entries.joinToString(" · ") {(course,list)->"$course ${list.count {it.status==LedgerStatus.DONE}}/${list.size}"}
