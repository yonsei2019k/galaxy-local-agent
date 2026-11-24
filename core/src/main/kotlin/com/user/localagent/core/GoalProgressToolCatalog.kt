package com.user.localagent.core

import kotlinx.serialization.json.*

/** Model-facing ledger tools. The host validates every op; evidence must come from audit ids observed in this run. */
object GoalProgressToolCatalog {
    const val READ="read_goal_progress"
    const val UPDATE="update_goal_progress"
    private val version=mapOf("goalId" to Argument("string",true,100),"expectedUpdatedAt" to Argument("string",true,19))
    val definitions=listOf(
        ToolDefinition(READ,Risk.LOW,mapOf("goalId" to Argument("string",true,100)),
            "Read the progress ledger (courses/units checklist) of a persistent goal. Private context only; never completion evidence."),
        ToolDefinition(UPDATE,Risk.LOW,version+mapOf("ops" to Argument("array",true,20_000)),
            "Update the goal's progress ledger with copied goalId/expectedUpdatedAt and ops1..20. Ops: {op:add,id,course,unit,kind:lecture|quiz|survey|certificate|condition|other,status?,note?,evidence?}, " +
                "{op:set,id,status:todo|active|waiting|done|blocked,note?,evidence?[audit ids]}, {op:setMeta,requirements?,dailyLimit?,deadline?}. " +
                "done requires evidence audit ids observed in this run; at most 300 items. Never grants permissions or runs work."),
    )
    val names=definitions.map {it.name}.toSet()

    fun parseOps(call:ToolCall):List<LedgerOp> {
        require(call.name==UPDATE){"장부 갱신 도구가 아닙니다."}
        val array=call.arguments["ops"]
        require(array is JsonArray&&array.size in 1..20){"장부 변경은 1~20개여야 합니다."}
        return array.map {element->
            require(element is JsonObject){"장부 변경 형식이 올바르지 않습니다."}
            when(val op=text(element["op"],20)) {
                "add"->{
                    keys(element,setOf("op","id","course","unit","kind","status","note","evidence"),setOf("op","id","course","unit","kind"))
                    LedgerOp.Add(LedgerItem(text(element["id"],100),text(element["course"],100),text(element["unit"],200),enum<LedgerKind>(element["kind"]),
                        element["status"]?.let {enum<LedgerStatus>(it)}?:LedgerStatus.TODO,element["note"]?.let {text(it,200,true)}?:"",evidenceList(element["evidence"])))
                }
                "set"->{
                    keys(element,setOf("op","id","status","note","evidence"),setOf("op","id","status"))
                    LedgerOp.Set(text(element["id"],100),enum<LedgerStatus>(element["status"]),element["note"]?.let {text(it,200,true)},evidenceList(element["evidence"]))
                }
                "setMeta"->{
                    keys(element,setOf("op","requirements","dailyLimit","deadline"),setOf("op"))
                    LedgerOp.SetMeta(element["requirements"]?.let {text(it,2000,true)},element["dailyLimit"]?.let {
                        require(it is JsonPrimitive&&!it.isString&&it.intOrNull!=null){"하루 한도에는 정수가 필요합니다."};it.jsonPrimitive.int},
                        element["deadline"]?.let {text(it,40)})
                }
                else->throw IllegalArgumentException("알 수 없는 장부 변경 종류입니다: $op")
            }
        }
    }

    private fun keys(obj:JsonObject,allowed:Set<String>,required:Set<String>) {
        require(obj.keys.all {it in allowed}){"장부 변경에 허용되지 않은 키가 있습니다."}
        require(obj.keys.containsAll(required)){"장부 변경의 필수 키가 없습니다."}
    }
    private fun text(value:JsonElement?,max:Int,allowBlank:Boolean=false):String {
        require(value is JsonPrimitive&&value.isString&&value.content.length<=max&&(allowBlank||value.content.isNotBlank())&&!value.content.contains('\u0000')){"장부 문자열의 형식이나 크기가 올바르지 않습니다."}
        return value.content
    }
    private inline fun <reified E:Enum<E>> enum(value:JsonElement?):E {
        val raw=text(value,20)
        return enumValues<E>().firstOrNull {it.name.lowercase()==raw}?:throw IllegalArgumentException("지원하지 않는 장부 값입니다: $raw")
    }
    private fun evidenceList(value:JsonElement?):List<String> {
        if(value==null)return emptyList()
        require(value is JsonArray&&value.size<=GoalProgressLimits.MAX_EVIDENCE){"장부 근거 목록이 올바르지 않습니다."}
        return value.map {text(it,100)}
    }
}
