package com.user.localagent.automation

import com.user.localagent.core.GoalToolCatalog
import com.user.localagent.core.ToolResult
import kotlinx.serialization.json.*

/** Goal identity/version and complete visible IDs survive; never reapply the generic1100 cap. */
object GoalModelContext {
    /** Keep execution IDs and audit history in the host journal across arbitrarily many segments. */
    fun snapshot(goal:com.user.localagent.core.GoalDefinition):JsonObject {
        val fields=goal.toJson()
        return buildJsonObject {
            for((key,value) in fields){
                when(key){
                    "linkedRunIds"->put("linkedRunCount",goal.linkedRunIds.size)
                    "criteria"->put(key,buildJsonArray {
                        for(criterion in value.jsonArray){
                            val row=criterion.jsonObject
                            add(JsonObject((row-"evidence")+("evidenceCount" to JsonPrimitive(row["evidence"]?.jsonArray?.size?:0))))
                        }
                    })
                    else->put(key,value)
                }
            }
        }
    }
    fun build(result:ToolResult,maxChars:Int=2200):String {
        require(maxChars in 1200..2200)
        val source=runCatching {require(result.content.length<=32000);Json.parseToJsonElement(result.content).jsonObject}.getOrNull()
            ?:return buildJsonObject {
                put("status","goal_result_unavailable");put("toolSuccess",result.success);put("verification",result.verification.name)
                put("contextPartial",true);put("goalCompletionEvidence",false);put("grantsCapabilities",false)
            }.toString()
        if(source["textRead"] is JsonObject)return textReadContext(result,source,maxChars)
        fun text(key:String)=source[key]?.let {it as? JsonPrimitive}?.takeIf {it.isString}?.content
        fun integer(key:String)=(source[key] as? JsonPrimitive)?.takeIf {!it.isString}?.intOrNull
        fun flag(key:String)=(source[key] as? JsonPrimitive)?.takeIf {!it.isString}?.booleanOrNull
        fun identity(value:String?)=value?.takeIf {it.isNotBlank()&&it.length<=100&&it.none {char->char.code<32}}
        val id=identity(text("goalId"))
        val revision=text("expectedUpdatedAt")?.takeIf {runCatching {GoalToolCatalog.revision(JsonPrimitive(it))}.isSuccess}
        val criteria=(source["criteria"] as? JsonArray).orEmpty()
        val offset=(integer("criteriaOffset")?:0).coerceAtLeast(0)
        val total=(integer("totalCriteria")?:criteria.size).coerceAtLeast(0)
        var rowLimit=minOf(16,criteria.size);var textLimit=100;var outcomeLimit=220;var constraintLimit=100;var constraintCount=2
        var truncated=false
        fun clip(value:String,limit:Int):String {
            var end=minOf(value.length,limit)
            if(end>0&&end<value.length&&value[end-1].isHighSurrogate()&&value[end].isLowSurrogate())end--
            if(end<value.length)truncated=true
            return value.substring(0,end)
        }
        fun render():String {
            val visible=buildJsonArray {criteria.take(rowLimit).forEach {value->
                val row=value as? JsonObject
                val criterionId=identity((row?.get("id") as? JsonPrimitive)?.takeIf {it.isString}?.content)
                // Production rows are contiguous and valid. Reject malformed rows as a whole preview.
                if(row==null||criterionId==null)throw IllegalArgumentException("목표 기준의 실제 식별자를 확인하지 못했습니다.")
                add(buildJsonObject {
                    put("id",criterionId)
                    (row["text"] as? JsonPrimitive)?.takeIf {it.isString}?.content?.let {if(textLimit>0)put("text",clip(it,textLimit))else if(it.isNotEmpty())truncated=true}
                    (row["status"] as? JsonPrimitive)?.takeIf {it.isString&&it.content in setOf("pending","verified","failed","user_confirmed")}?.let {put("status",it)}
                    (row["evidenceCount"] as? JsonPrimitive)?.takeIf {!it.isString&&it.intOrNull!=null}?.let {put("evidenceCount",it)}
                })
            }}
            val criteriaPartial=flag("criteriaPartial")==true||offset!=0||offset+visible.size<total||visible.size<criteria.size
            if(visible.size<criteria.size)truncated=true
            val constraints=source["constraints"] as? JsonArray
            val shownConstraints=constraints?.take(constraintCount).orEmpty()
            if(constraints!=null&&shownConstraints.size<constraints.size)truncated=true
            val payload=buildJsonObject {
                text("status")?.takeIf {it.matches(Regex("[a-z_]{1,64}"))}?.let {put("status",it)}
                put("toolSuccess",result.success);put("verification",result.verification.name)
                id?.let {put("goalId",it)};revision?.let {put("expectedUpdatedAt",it)}
                identity(text("threadId"))?.let {put("threadId",it)}
                source["projectId"]?.takeIf {it is JsonNull||it is JsonPrimitive&&it.isString&&identity(it.content)!=null}?.let {put("projectId",it)}
                text("goalStatus")?.takeIf {it in setOf("draft","active","waiting","paused","complete")}?.let {put("goalStatus",it)}
                text("outcome")?.let {if(outcomeLimit>0)put("outcome",clip(it,outcomeLimit))else if(it.isNotEmpty())truncated=true}
                if(source.containsKey("criteria")) {
                    put("criteria",visible);put("totalCriteria",total);put("criteriaOffset",offset);put("criteriaPartial",criteriaPartial)
                    put("nextCriteriaOffset",if(offset+visible.size<total)JsonPrimitive(offset+visible.size)else JsonNull)
                }
                if(shownConstraints.isNotEmpty()&&constraintLimit>0)put("constraints",JsonArray(shownConstraints.mapNotNull {
                    (it as? JsonPrimitive)?.takeIf {p->p.isString}?.content?.let {value->JsonPrimitive(clip(value,constraintLimit))}
                }))else if(!constraints.isNullOrEmpty())truncated=true
                integer("totalConstraints")?.let {put("totalConstraints",it)}
                put("constraintsComplete",constraints!=null&&shownConstraints.size==constraints.size&&constraintLimit>0&&
                    constraints.none {(it as? JsonPrimitive)?.content.orEmpty().length>constraintLimit}&&
                    (integer("totalConstraints")?:constraints.size)==constraints.size)
                listOf("goalPresent","queuedOneBoundedRun","quiesced","deferredCallerStop","scopeBound").forEach {key->flag(key)?.let {put(key,it)}}
                identity(text("queuedRunId"))?.let {put("queuedRunId",it)}
                put("backgroundScheduled",false);put("goalCompletionEvidence",false);put("grantsCapabilities",false)
            }
            return JsonObject(payload+mapOf("contextPartial" to JsonPrimitive(truncated||criteriaPartial),
                "constraintsPartial" to JsonPrimitive(constraints!=null&&(shownConstraints.size<constraints.size||constraintLimit==0||constraints.any {(it as? JsonPrimitive)?.content.orEmpty().length>constraintLimit})))).toString()
        }
        var output=render()
        while(output.length>maxChars) {
            truncated=true
            when {
                textLimit>16->textLimit=maxOf(16,textLimit/2)
                constraintCount>0->constraintCount=0
                outcomeLimit>32->outcomeLimit=maxOf(32,outcomeLimit/2)
                textLimit>0->textLimit=0
                outcomeLimit>0->outcomeLimit=0
                rowLimit>1->rowLimit--
                else->error("목표의 식별자·버전·실제 기준 ID 문맥이 예산을 넘었습니다.")
            }
            output=render()
        }
        return output
    }

    private fun textReadContext(result:ToolResult,source:JsonObject,maxChars:Int):String {
        fun string(key:String)=source[key]?.jsonPrimitive?.takeIf {it.isString}?.content
        fun id(value:String?)=value?.takeIf {it.isNotBlank()&&it.length<=100&&it.none {char->char.code<32}}
        val goalId=checkNotNull(id(string("goalId"))){"실제 목표 식별자가 없는 텍스트 조각입니다."}
        val revision=checkNotNull(source["expectedUpdatedAt"]);GoalToolCatalog.revision(revision)
        val thread=checkNotNull(id(string("threadId"))){"원래 대화 범위가 없는 텍스트 조각입니다."}
        val project=checkNotNull(source["projectId"]){"프로젝트 범위가 없는 텍스트 조각입니다."}
        require(project is JsonNull||project is JsonPrimitive&&project.isString&&id(project.content)!=null)
        val read=source.getValue("textRead").jsonObject
        val field=read.getValue("field").jsonPrimitive.takeIf {it.isString}?.content
        require(field in GoalToolCatalog.textFields)
        val expected=setOf("field","offset","totalChars","text","nextOffset","textComplete")+
            when(field){"criterion"->setOf("criterionId");"constraint"->setOf("constraintIndex");else->emptySet()}
        require(read.keys==expected){"목표 텍스트 조각의 필드 식별자가 올바르지 않습니다."}
        if(field=="criterion")require(id(read["criterionId"]?.jsonPrimitive?.takeIf {it.isString}?.content)!=null)
        if(field=="constraint")require(read.getValue("constraintIndex").jsonPrimitive.let {!it.isString&&it.intOrNull in 0..12000})
        fun integer(key:String)=read.getValue(key).jsonPrimitive.also {require(!it.isString&&it.intOrNull!=null)}.int
        val offset=integer("offset");val total=integer("totalChars")
        val text=read.getValue("text").jsonPrimitive.also {require(it.isString&&it.content.length<=900)}.content
        val end=offset+text.length
        require(total in 0..12000&&offset in 0..total&&end<=total&&(text.isNotEmpty()||offset==total))
        require(if(end<total)read["nextOffset"]?.jsonPrimitive?.let {!it.isString&&it.intOrNull==end}==true else read["nextOffset"] is JsonNull)
        require(read.getValue("textComplete").jsonPrimitive.let {!it.isString&&it.booleanOrNull==(offset==0&&end==total)})
        val payload=buildJsonObject {
            put("status","goal_text_read");put("toolSuccess",result.success);put("verification",result.verification.name)
            put("goalPresent",(source["goalPresent"] as? JsonPrimitive)?.takeIf {!it.isString}?.booleanOrNull==true)
            put("scopeBound",(source["scopeBound"] as? JsonPrimitive)?.takeIf {!it.isString}?.booleanOrNull==true)
            put("threadId",thread);put("projectId",project);put("goalId",goalId);put("expectedUpdatedAt",revision)
            put("textRead",read)
            put("goalCompletionEvidence",false);put("grantsCapabilities",false);put("backgroundScheduled",false)
            put("contextPartial",offset!=0||end!=total)
        }.toString()
        require(payload.length<=maxChars){"정확한 목표 텍스트 조각이 모델 문맥 예산을 넘었습니다. 더 작은 textLimit으로 다시 읽어주세요. 원문은 자르지 않았습니다."}
        return payload
    }
}
