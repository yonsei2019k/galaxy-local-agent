package com.user.localagent.automation

import com.user.localagent.core.GoalDefinition
import com.user.localagent.core.GoalToolCatalog
import com.user.localagent.core.ToolCall
import kotlinx.serialization.json.*

/** Request-local delivery proof. Source contents and model-written claims cannot add permissions. */
class GoalReadAuthority {
    private var previews:List<JsonObject> = emptyList()

    /** Replace, never accumulate, the exact host previews delivered in this model generation. */
    @Synchronized
    fun accept(evidence:List<JsonObject>,actualModelQuery:String) {
        previews=emptyList()
        if(evidence.size>24||actualModelQuery.length>1_000_000)return
        val serialized=evidence.map {it.toString()}
        if(serialized.sumOf {it.length.toLong()}>256_000)return
        val snapshot=serialized.map {Json.parseToJsonElement(it).jsonObject}
        val wholeDelivered=actualModelQuery.contains(JsonArray(snapshot).toString())
        previews=snapshot.indices.mapNotNull {index->
            if(!wholeDelivered&&!actualModelQuery.contains(serialized[index]))return@mapNotNull null
            val item=snapshot[index]
            if(string(item,"tool") !in GoalToolCatalog.names||!flag(item,"success")||string(item,"verification")!="VERIFIED")return@mapNotNull null
            val preview=item["result"] as? JsonObject?:return@mapNotNull null
            if(!flag(preview,"scopeBound")||!flag(preview,"goalPresent")||!flag(preview,"toolSuccess")||string(preview,"verification")!="VERIFIED")return@mapNotNull null
            preview
        }.toList()
    }

    /** Only changed existing content needs proof; additions preserve the unread old fields. */
    @Synchronized
    fun requireEdit(call:ToolCall,goal:GoalDefinition) {
        if(call.name!=GoalToolCatalog.UPDATE)return
        GoalToolCatalog.validate(call)
        goal.validate()
        require(string(call.arguments,"goalId")==goal.id&&GoalToolCatalog.revision(call.arguments.getValue("expectedUpdatedAt"))==goal.updatedAt){"목표의 실제 식별자·버전이 변경되었습니다. 현재 목표를 다시 읽어주세요."}
        val current=previews.filter {bound(it,goal)}
        val edits=call.arguments["textEdits"] as? JsonArray
        if(edits!=null) {
            for(value in edits) {
                val edit=value.jsonObject
                val ref=checkNotNull(field(edit)){"수정할 목표 필드의 실제 식별자가 올바르지 않습니다."}
                val actual=checkNotNull(text(goal,ref)){"수정할 목표 필드가 현재 목표에 없습니다."}
                val old=checkNotNull(string(edit,"oldText"))
                val position=actual.indexOf(old)
                require(old.isNotEmpty()&&position>=0&&position==actual.lastIndexOf(old)){"기존 문구가 현재 목표 필드에 정확히 한 번 있어야 수정할 수 있습니다."}
                require(current.any {full(it,goal,ref,actual)||fragment(it,goal,ref,actual)?.let {read->
                    position>=read.offset&&position+old.length<=read.offset+read.text.length
                }==true}){"수정할 기존 문구를 이번 모델 문맥에 전달하지 않았습니다. 해당 목표 필드를 읽은 뒤 다시 수정해주세요."}
            }
            return
        }
        string(call.arguments,"outcome")?.takeIf {it!=goal.outcome}?.let {
            require(current.any {preview->full(preview,goal,Field("outcome"),goal.outcome)}){"목표 전체 내용을 바꾸려면 기존 전체 원문을 이번 문맥에서 읽어야 합니다. 일부 문구는 textEdits로 수정해주세요."}
        }
        (call.arguments["criteria"] as? JsonArray)?.forEach {value->
            val edit=value.jsonObject
            val id=string(edit,"id")?:return@forEach // A new criterion does not replace an old one.
            val actual=checkNotNull(goal.criteria.firstOrNull {it.id==id}){"수정할 실제 완료 기준이 없습니다."}
            if(string(edit,"text")!=actual.text)require(current.any {full(it,goal,Field("criterion",criterionId=id),actual.text)}){"수정할 완료 기준의 기존 전체 내용을 이번 문맥에서 읽어야 합니다. 일부 문구는 textEdits로 수정해주세요."}
        }
        (call.arguments["removeCriterionIds"] as? JsonArray)?.forEach {value->
            val id=value.jsonPrimitive.content
            val actual=checkNotNull(goal.criteria.firstOrNull {it.id==id}){"삭제할 실제 완료 기준이 없습니다."}
            require(current.any {full(it,goal,Field("criterion",criterionId=id),actual.text)}){"삭제할 완료 기준의 전체 내용을 이번 문맥에서 읽어야 합니다."}
        }
        (call.arguments["constraints"] as? JsonArray)?.map {it.jsonPrimitive.content}?.takeIf {it!=goal.constraints&&goal.constraints.isNotEmpty()}?.let {
            require(current.any {preview->
                val old=preview["constraints"] as? JsonArray
                old!=null&&old.all {value->value is JsonPrimitive&&value.isString}&&old.map {value->value.jsonPrimitive.content}==goal.constraints
            }){"조건 전체를 바꾸려면 기존 모든 조건을 이번 문맥에서 읽어야 합니다. 한 조건의 일부는 textEdits로 수정해주세요."}
        }
    }

    private data class Field(val name:String,val criterionId:String?=null,val constraintIndex:Int?=null)
    private data class Fragment(val offset:Int,val text:String,val complete:Boolean)

    private fun bound(preview:JsonObject,goal:GoalDefinition):Boolean {
        val project=preview["projectId"]
        return string(preview,"goalId")==goal.id&&string(preview,"expectedUpdatedAt")?.toLongOrNull()==goal.updatedAt&&
            string(preview,"threadId")==goal.threadId&&preview.containsKey("projectId")&&
            (if(goal.projectId==null)project==JsonNull else project is JsonPrimitive&&project.isString&&project.content==goal.projectId)&&
            optionalCount(preview,"totalCriteria",goal.criteria.size)&&optionalCount(preview,"totalConstraints",goal.constraints.size)
    }

    private fun optionalCount(preview:JsonObject,key:String,actual:Int):Boolean =
        !preview.containsKey(key)||integer(preview,key)==actual

    private fun full(preview:JsonObject,goal:GoalDefinition,ref:Field,actual:String):Boolean {
        val normal=when(ref.name) {
            "outcome"->string(preview,"outcome")==actual
            "criterion"->(preview["criteria"] as? JsonArray)?.any {value->
                val row=value as? JsonObject
                row!=null&&string(row,"id")==ref.criterionId&&string(row,"text")==actual
            }==true
            "constraint"->(preview["constraints"] as? JsonArray)?.let {array->
                array.size==goal.constraints.size&&array.getOrNull(ref.constraintIndex!!)?.let {value->value is JsonPrimitive&&value.isString&&value.content==actual}==true
            }==true
            else->false
        }
        if(normal)return true
        return fragment(preview,goal,ref,actual)?.let {it.complete&&it.offset==0&&it.text==actual}==true
    }

    private fun fragment(preview:JsonObject,goal:GoalDefinition,ref:Field,actual:String):Fragment? {
        val read=preview["textRead"] as? JsonObject?:return null
        if(field(read)!=ref)return null
        val offset=integer(read,"offset")?:return null
        val total=integer(read,"totalChars")?:return null
        val body=string(read,"text")?:return null
        if(total!=actual.length||offset !in 0..actual.length||body.length>900||body.length>actual.length-offset)return null
        val end=offset+body.length
        if(actual.substring(offset,end)!=body)return null
        val complete=boolean(read,"textComplete")?:return null
        if(complete!=(offset==0&&end==total))return null
        if(!read.containsKey("nextOffset")||(if(end==total)read["nextOffset"]!=JsonNull else integer(read,"nextOffset")!=end))return null
        // Resolve the current field again rather than treating a selector or source claim as authority.
        if(text(goal,ref)!=actual)return null
        return Fragment(offset,body,complete)
    }

    private fun field(value:JsonObject):Field? = when(string(value,"field")) {
        "outcome"->if(value.containsKey("criterionId")||value.containsKey("constraintIndex"))null else Field("outcome")
        "criterion"->if(value.containsKey("constraintIndex"))null else string(value,"criterionId")?.takeIf {it.isNotBlank()}?.let {Field("criterion",criterionId=it)}
        "constraint"->if(value.containsKey("criterionId"))null else integer(value,"constraintIndex")?.takeIf {it in 0..12000}?.let {Field("constraint",constraintIndex=it)}
        else->null
    }

    private fun text(goal:GoalDefinition,ref:Field):String? = when(ref.name) {
        "outcome"->goal.outcome
        "criterion"->goal.criteria.firstOrNull {it.id==ref.criterionId}?.text
        "constraint"->goal.constraints.getOrNull(ref.constraintIndex!!)
        else->null
    }

    private fun string(value:JsonObject,key:String)=(value[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
    private fun integer(value:JsonObject,key:String)=(value[key] as? JsonPrimitive)?.takeIf {!it.isString}?.intOrNull
    private fun boolean(value:JsonObject,key:String)=(value[key] as? JsonPrimitive)?.takeIf {!it.isString}?.booleanOrNull
    private fun flag(value:JsonObject,key:String)=boolean(value,key)==true
}
