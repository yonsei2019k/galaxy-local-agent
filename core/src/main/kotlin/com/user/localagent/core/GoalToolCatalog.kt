package com.user.localagent.core

import kotlinx.serialization.json.*

/** Explicit conversation goal operations. Guidance/source text never authorizes these effects. */
object GoalToolCatalog {
    const val GET="get_goal"
    const val CREATE="create_goal"
    const val UPDATE="update_goal"
    const val START="start_goal"
    const val PAUSE="pause_goal"
    const val RESUME="resume_goal"
    const val CLEAR="clear_goal"
    // A running segment already owns a started goal. Only an owner turn can start another run.
    fun unavailableForRun(activeSegment: Boolean): Set<String> =
        if (activeSegment) setOf(CREATE, START, RESUME, CLEAR)
        else GoalProgressToolCatalog.names + GoalWaitToolCatalog.WAIT

    val textFields=setOf("outcome","criterion","constraint")
    private val textReadArguments=setOf("criterionId","constraintIndex","textOffset","textLimit")
    private val replacementArguments=setOf("outcome","criteria","constraints","removeCriterionIds")
    private fun text(required:Boolean=true,max:Int=6000)=Argument("string",required,max)
    private val version=mapOf("goalId" to text(max=100),"expectedUpdatedAt" to text(max=19))
    val definitions=listOf(
        ToolDefinition(GET,Risk.LOW,mapOf("criteriaOffset" to Argument("integer",false),"criteriaLimit" to Argument("integer",false),"goalId" to text(false,100),"expectedUpdatedAt" to text(false,19),
            "textField" to text(false,10),"criterionId" to text(false,100),"constraintIndex" to Argument("integer",false),"textOffset" to Argument("integer",false),"textLimit" to Argument("integer",false)),
            "Read this conversation's goal and criteria page (offset0/limit8, limit1..16), or one exact text fragment using textField=outcome|criterion|constraint. Field reads require copied goalId/expectedUpdatedAt; criterion requires criterionId, constraint requires constraintIndex0..12000 and the host checks the actual list. textOffset0..12000/default0, textLimit1..900/default900 is an upper bound; follow the actual returned next offset if fewer exact characters fit the context. Field reads cannot mix criteriaOffset/criteriaLimit. Read continuations for hidden text; a preview does not authorize a whole-field replacement. Private context only, never goal completion evidence."),
        ToolDefinition(CREATE,Risk.LOW,mapOf("outcome" to text(),"criteria" to Argument("array"),"constraints" to Argument("array",false),"proofTargets" to Argument("array",false)),
            "Create a draft goal when the owner asks for a result requiring multiple dependent steps or an automatic wait. Preserve the exact requested scope. No existing goal may be replaced. Optional proofTargets link a zero-based criterion index to a known exact PUBLIC_PDF_SOURCE HTTPS URL or PROJECT_FILE relative path. Use only an exact target from the request or observed sources. This grants no new permissions."),
        ToolDefinition(UPDATE,Risk.LOW,version+mapOf("outcome" to text(false),"criteria" to Argument("array",false),"removeCriterionIds" to Argument("array",false),"constraints" to Argument("array",false),"textEdits" to Argument("array",false)),
            "Edit the actual goal using copied goalId/expectedUpdatedAt. For partial changes use textEdits1..8: {field,criterionId? only criterion,constraintIndex? only constraint,oldText1..900,newText0..2000}. One edit per actual field identity; oldText must match uniquely in that field and occur in its actual model-delivered fragment. Preserve all other text. textEdits cannot mix outcome/criteria/constraints/removeCriterionIds. Whole-field replacements additionally require host proof of the actual complete field delivered to this model; previews are insufficient. criteria patches preserve omitted IDs; missing id adds a criterion. Changed work quiesces linked runs and resets proof/status, identical work preserves real evidence. No status/evidence/run links accepted."),
        ToolDefinition(START,Risk.LOW,version,
            "For the owner's current instruction to carry out the saved goal, including when a fresh draft was made for this same multi-step request: queue one bounded run through the host lifecycle. Once the host confirms it is queued, finish this dispatch. Carry out the actual work in the separate goal run. A queued run is not goal completion and grants no permissions."),
        ToolDefinition(PAUSE,Risk.LOW,version,
            "Only on an explicit pause request: quiesce this goal's linked runs, then save PAUSED. If stopping the caller must wait until its receipt is durable, report PREPARED/deferredCallerStop instead of claiming it already stopped."),
        ToolDefinition(RESUME,Risk.LOW,version,
            "Only on an explicit owner request to resume a paused goal: queue one bounded run using actual same-work evidence. Once the host confirms it is queued, finish this dispatch. Resume the actual work in the separate goal run. Grants no new permission."),
        ToolDefinition(CLEAR,Risk.HIGH,version,
            "Only on the owner's explicit clear request: quiesce linked goal runs and CAS-delete this saved goal. Does not undo completed external actions or delete conversation history. A deferred caller stop is PREPARED, not a verified stop.")
    )
    val names=definitions.map {it.name}.toSet()
    val contextReadNames=setOf(GET)
    val mutationNames=names-contextReadNames
    private val byName=definitions.associateBy {it.name}

    fun isArrayArgument(tool:String,key:String)=when(tool) {
        CREATE->key in setOf("criteria","constraints","proofTargets")
        UPDATE->key in setOf("criteria","constraints","removeCriterionIds","textEdits")
        else->false
    }

    fun validate(call:ToolCall) {
        val definition=byName[call.name]?:error("알 수 없는 목표 도구입니다.")
        require(call.arguments.toString().length<=24000){"목표 편집 요청이 너무 큽니다."}
        require(call.arguments.keys.all {it in definition.arguments}){"목표 도구에 허용되지 않은 인자가 있습니다."}
        for((key,spec)in definition.arguments) {
            val value=call.arguments[key]
            require(value!=null||!spec.required){"목표 도구의 필수 인자가 없습니다."}
            if(value==null)continue
            when(spec.type) {
                "string"->string(value,spec.maxLength)
                "integer"->require(value is JsonPrimitive&&!value.isString&&value.intOrNull!=null){"목표 목록 범위에는 정수가 필요합니다."}
                "array"->validateArray(call.name,key,value)
                else->error("지원하지 않는 목표 인자 형식입니다.")
            }
        }
        if(call.name==CREATE&&"proofTargets" in call.arguments)parseProofTargets(call)
        if(call.name in mutationNames-CREATE) {
            id(call.arguments.getValue("goalId"));revision(call.arguments.getValue("expectedUpdatedAt"))
        }
        if(call.name==GET) {
            val field=call.arguments["textField"]?.jsonPrimitive?.content
            if(field!=null) {
                require(field in textFields){"지원하지 않는 목표 텍스트 필드입니다."}
                require(call.arguments.keys.none {it in setOf("criteriaOffset","criteriaLimit")}){"텍스트 조각과 기준 페이지를 함께 읽을 수 없습니다."}
                require(call.arguments.containsKey("goalId")&&call.arguments.containsKey("expectedUpdatedAt")){"텍스트 조각에는 실제 목표와 버전이 필요합니다."}
                fieldIdentity(field,call.arguments)
                require((call.arguments["textOffset"]?.jsonPrimitive?.int?:0) in 0..12000){"텍스트 시작 위치가 범위를 벗어났습니다."}
                require((call.arguments["textLimit"]?.jsonPrimitive?.int?:900) in 1..900){"텍스트 조각은1~900자 범위로 읽어주세요."}
            } else require(call.arguments.keys.none {it in textReadArguments}){"텍스트 읽기 인자에는 textField가 필요합니다."}
            val offset=call.arguments["criteriaOffset"]?.jsonPrimitive?.int?:0
            require(offset in 0..1000)
            require((call.arguments["criteriaLimit"]?.jsonPrimitive?.int?:8) in 1..16)
            require(call.arguments.containsKey("goalId")==call.arguments.containsKey("expectedUpdatedAt")){"목표 페이지에는 실제 목표와 버전을 함께 전달해주세요."}
            if(call.arguments.containsKey("goalId")){id(call.arguments.getValue("goalId"));revision(call.arguments.getValue("expectedUpdatedAt"))}
            require(offset==0||call.arguments.containsKey("goalId")){"다음 기준 페이지에는 읽은 목표와 버전이 필요합니다."}
        }
        if(call.name==UPDATE) {
            require(call.arguments.keys.any {it in replacementArguments||it=="textEdits"}){"수정할 목표 내용이 필요합니다."}
            if("textEdits" in call.arguments)require(call.arguments.keys.none {it in replacementArguments}){"부분 텍스트 수정과 전체 교체 인자를 함께 사용할 수 없습니다."}
        }
    }

    fun validateArray(tool:String,key:String,value:JsonElement) {
        if(tool==UPDATE&&key=="textEdits") {
            require(value is JsonArray&&value.size in 1..8){"부분 텍스트 수정은1~8개여야 합니다."}
            val identities=mutableSetOf<List<String>>()
            value.forEach {element->
                require(element is JsonObject&&element.keys.containsAll(setOf("field","oldText","newText"))&&
                    element.keys.all {it in setOf("field","criterionId","constraintIndex","oldText","newText")}){"부분 텍스트 수정 인자가 올바르지 않습니다."}
                val field=string(element.getValue("field"),10)
                require(field in textFields){"지원하지 않는 목표 텍스트 필드입니다."}
                val identity=fieldIdentity(field,element)
                require(identities.add(listOf(field,identity))){"같은 텍스트 필드를 두 번 수정할 수 없습니다."}
                fragment(element.getValue("oldText"),1,900)
                fragment(element.getValue("newText"),0,2000)
            }
            return
        }
        require(isArrayArgument(tool,key)&&value is JsonArray&&value.size<=16){"목표 배열은16개 이하여야 합니다."}
        val array=value as JsonArray
        if(tool==CREATE&&key=="proofTargets"){
            require(array.map {readProofTarget(it).first}.distinct().size==array.size)
            return
        }
        if(tool==CREATE&&key=="criteria")require(array.isNotEmpty()){"완료 기준을 하나 이상 작성해주세요."}
        if(tool==UPDATE&&key=="criteria") {
            val ids=mutableSetOf<String>()
            array.forEach {element->
                require(element is JsonObject&&element.keys.contains("text")&&element.keys.all {it in setOf("id","text")}){"목표 기준 편집에는 id와 text만 허용합니다."}
                string(element.getValue("text"),2000)
                element["id"]?.let {require(ids.add(id(it))){"같은 완료 기준을 두 번 수정할 수 없습니다."}}
            }
        } else if(key=="removeCriterionIds") {
            val ids=array.map(::id)
            require(ids.distinct().size==ids.size){"삭제할 완료 기준 식별자가 중복됐습니다."}
        } else array.forEach {string(it,2000)}
    }

    fun parseProofTargets(call:ToolCall):Map<Int,GoalProofTarget> {
        require(call.name==CREATE)
        val criteria=call.arguments.getValue("criteria").jsonArray
        val targets=(call.arguments["proofTargets"] as? JsonArray).orEmpty().map(::readProofTarget)
        require(targets.all {it.first in criteria.indices}&&targets.map {it.first}.distinct().size==targets.size)
        return targets.toMap()
    }

    private fun readProofTarget(value:JsonElement):Pair<Int,GoalProofTarget> {
        require(value is JsonObject&&value.keys==setOf("criterionIndex","kind","target"))
        val i=value.getValue("criterionIndex").jsonPrimitive.let {require(!it.isString);it.int}
        require(i in 0..15)
        return i to GoalProofTarget(GoalProofKind.valueOf(string(value.getValue("kind"),32)),
            string(value.getValue("target"),500)).validate()
    }

    private fun fieldIdentity(field:String,arguments:JsonObject):String {
        require((field=="criterion")==arguments.containsKey("criterionId")){"기준 텍스트에만 실제 criterionId가 필요합니다."}
        require((field=="constraint")==arguments.containsKey("constraintIndex")){"조건 텍스트에만 실제 constraintIndex가 필요합니다."}
        return when(field) {
            "criterion"->id(arguments.getValue("criterionId"))
            "constraint"->{val index=arguments.getValue("constraintIndex");require(index is JsonPrimitive&&!index.isString&&index.intOrNull in 0..12000){"조건 위치는0~12000의 정수여야 합니다."};index.content}
            else->""
        }
    }

    private fun fragment(value:JsonElement,min:Int,max:Int) {
        require(value is JsonPrimitive&&value.isString&&value.content.length in min..max&&'\u0000' !in value.content){"부분 텍스트 길이 또는 형식이 올바르지 않습니다."}
    }

    fun revision(value:JsonElement):Long {
        val raw=string(value,19)
        return raw.toLongOrNull()?.takeIf {it>0&&it.toString()==raw}
            ?:throw IllegalArgumentException("목표에서 읽은 실제 버전 문자열이 필요합니다.")
    }
    private fun id(value:JsonElement)=string(value,100).also {require(it.none {c->c.code<32}){"목표 식별자가 올바르지 않습니다."}}
    private fun string(value:JsonElement,max:Int):String {
        require(value is JsonPrimitive&&value.isString&&value.content.isNotBlank()&&value.content.length<=max&&'\u0000' !in value.content){"목표 문자열의 형식이나 크기가 올바르지 않습니다."}
        return value.content
    }

    fun argumentSchema(tool:String,key:String):JsonObject? {
        if(tool !in names)return null
        fun textSchema(max:Int,min:Int=1)=buildJsonObject {put("type","string");put("minLength",min);put("maxLength",max)}
        fun fieldSchema(field:String?=null)=buildJsonObject {put("type","string");put("enum",JsonArray((field?.let {listOf(it)}?:textFields.toList()).map(::JsonPrimitive)))}
        fun indexSchema()=buildJsonObject {put("type","integer");put("minimum",0);put("maximum",12000)}
        return when {
            key=="expectedUpdatedAt"->buildJsonObject {put("type","string");put("maxLength",19);put("pattern","^[1-9][0-9]{0,18}$")}
            key=="criteriaOffset"->buildJsonObject {put("type","integer");put("minimum",0);put("maximum",1000)}
            key=="criteriaLimit"->buildJsonObject {put("type","integer");put("minimum",1);put("maximum",16)}
            key=="textField"->fieldSchema()
            key=="constraintIndex"->indexSchema()
            key=="textOffset"->buildJsonObject {put("type","integer");put("minimum",0);put("maximum",12000)}
            key=="textLimit"->buildJsonObject {put("type","integer");put("minimum",1);put("maximum",900)}
            tool==UPDATE&&key=="textEdits"->buildJsonObject {
                put("type","array");put("minItems",1);put("maxItems",8)
                put("items",buildJsonObject {put("oneOf",buildJsonArray {textFields.forEach {field->add(buildJsonObject {
                    put("type","object");put("additionalProperties",false)
                    put("properties",buildJsonObject {
                        put("field",fieldSchema(field));put("oldText",textSchema(900));put("newText",textSchema(2000,0))
                        if(field=="criterion")put("criterionId",textSchema(100))
                        if(field=="constraint")put("constraintIndex",indexSchema())
                    })
                    put("required",buildJsonArray {add("field");add("oldText");add("newText");if(field=="criterion")add("criterionId");if(field=="constraint")add("constraintIndex")})
                })}})})
            }
            tool==CREATE&&key=="proofTargets"->buildJsonObject {
                put("type","array");put("maxItems",16)
                put("items",buildJsonObject {
                    put("type","object");put("additionalProperties",false)
                    put("properties",buildJsonObject {
                        put("criterionIndex",buildJsonObject {put("type","integer");put("minimum",0);put("maximum",15)})
                        put("kind",buildJsonObject {put("type","string");put("enum",JsonArray(GoalProofKind.entries.map {JsonPrimitive(it.name)}))})
                        put("target",textSchema(500))
                    })
                    put("required",JsonArray(listOf("criterionIndex","kind","target").map(::JsonPrimitive)))
                })
            }
            isArrayArgument(tool,key)->buildJsonObject {
                put("type","array");put("maxItems",16);put("minItems",if(tool==CREATE&&key=="criteria")1 else 0)
                put("items",if(tool==UPDATE&&key=="criteria")buildJsonObject {
                    put("type","object");put("additionalProperties",false)
                    put("properties",buildJsonObject {put("id",textSchema(100));put("text",textSchema(2000))})
                    put("required",buildJsonArray {add("text")})
                } else textSchema(if(key=="removeCriterionIds")100 else 2000))
            }
            else->null
        }
    }
}
