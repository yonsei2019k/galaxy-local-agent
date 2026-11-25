package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises production registry, generated native schema, mode and privacy boundaries together. */
class GoalRegistryIntegrationTest {
    private fun args(vararg values:Pair<String,JsonElement>)=JsonObject(mapOf(*values))
    private fun strings(vararg values:String)=JsonArray(values.map(::JsonPrimitive))
    private fun version()=args("goalId" to JsonPrimitive("synthetic-goal"),"expectedUpdatedAt" to JsonPrimitive("12"))
    private fun validate(name:String,arguments:JsonObject)=ToolRegistry.parseAndValidate(buildJsonObject {
        put("type","tool_call");put("tool",name);put("arguments",arguments)
    }.toString())
    private fun reject(name:String,arguments:JsonObject){assertTrue(name,runCatching{validate(name,arguments)}.isFailure)}
    private fun decision(tools:Set<String>,public:Boolean=false)=buildJsonObject {
        put("action",if(public)"PUBLIC_RESEARCH"else"TOOL_TASK");put("goal","현재 대화의 목표 관리 요청");put("targetType","GENERAL")
        put("requiredTools",JsonArray(tools.map(::JsonPrimitive)))
        if(public)put("publicQuery","공개 조사 주제")
    }.toString()
    private fun properties(name:String)=Json.parseToJsonElement(ToolRegistry.schemaJson(listOf(name))).jsonArray.single().jsonObject
        .getValue("parameters").jsonObject.getValue("properties").jsonObject

    @Test fun allSevenGoalToolsAreRegisteredExactlyOnceWithTheExistingTools(){
        val expected=listOf("get_goal","create_goal","update_goal","start_goal","pause_goal","resume_goal","clear_goal")
        assertEquals(expected.sorted(),GoalToolCatalog.definitions.map { it.name }.sorted())
        // Registry construction rejects duplicates across all catalogs. Check the callable schema as well.
        val exported=Json.parseToJsonElement(ToolRegistry.schemaJson(expected)).jsonArray
        assertEquals(expected.sorted(),exported.map { it.jsonObject.getValue("name").jsonPrimitive.content }.sorted())
        assertEquals(Risk.HIGH,ToolRegistry.definitions.getValue(GoalToolCatalog.CLEAR).risk)
        assertFalse(CapabilityPolicy.canPreapprove(GoalToolCatalog.CLEAR))
    }

    @Test fun registryAcceptsGoalTextArraysAndPatchesThroughThePublicEnvelope(){
        validate(GoalToolCatalog.CREATE,args("outcome" to JsonPrimitive("합성 결과"),"criteria" to strings("원문을 확인"),"constraints" to strings("공개 전송 금지")))
        validate(GoalToolCatalog.UPDATE,JsonObject(version()+mapOf(
            "criteria" to JsonArray(listOf(args("id" to JsonPrimitive("criterion-old"),"text" to JsonPrimitive("수정한 기준")),args("text" to JsonPrimitive("새 기준")))),
            "removeCriterionIds" to strings("criterion-remove"))))
    }

    @Test fun registryRejectsForgedGoalProofAndMalformedGoalArrays(){
        reject(GoalToolCatalog.CREATE,args("outcome" to JsonPrimitive("합성 결과"),"criteria" to strings()))
        reject(GoalToolCatalog.CREATE,args("outcome" to JsonPrimitive("합성 결과"),"criteria" to JsonArray(List(17){JsonPrimitive("기준 $it")})))
        reject(GoalToolCatalog.CREATE,args("outcome" to JsonPrimitive("합성 결과"),"criteria" to JsonArray(listOf(args("text" to JsonPrimitive("기준"),"status" to JsonPrimitive("verified"))))))
        reject(GoalToolCatalog.UPDATE,JsonObject(version()+mapOf("status" to JsonPrimitive("complete"))))
        val forgedCriterion=args("text" to JsonPrimitive("기준"),"evidence" to strings("forged"))
        reject(GoalToolCatalog.UPDATE,JsonObject(version()+mapOf("criteria" to JsonArray(listOf(forgedCriterion)))))
    }

    @Test fun registryChecksGoalPagingAndActualPositiveLongRevisions(){
        validate(GoalToolCatalog.GET,args())
        validate(GoalToolCatalog.GET,JsonObject(version()+mapOf("criteriaOffset" to JsonPrimitive(8),"criteriaLimit" to JsonPrimitive(16))))
        reject(GoalToolCatalog.GET,args("criteriaOffset" to JsonPrimitive(1)))
        reject(GoalToolCatalog.GET,args("criteriaLimit" to JsonPrimitive(17)))
        reject(GoalToolCatalog.GET,args("goalId" to JsonPrimitive("synthetic-goal")))
        for(revision in listOf("0","-1","9223372036854775808","1.5"))reject(GoalToolCatalog.PAUSE,JsonObject(version()+mapOf("expectedUpdatedAt" to JsonPrimitive(revision))))
        reject(GoalToolCatalog.PAUSE,JsonObject(version()+mapOf("expectedUpdatedAt" to JsonPrimitive(12))))
    }

    @Test fun goalArrayHookDoesNotRelaxAlarmDays(){
        reject("create_alarm",args("hour" to JsonPrimitive(9),"minute" to JsonPrimitive(30),"days" to strings("1")))
        validate("create_alarm",args("hour" to JsonPrimitive(9),"minute" to JsonPrimitive(30),"days" to JsonArray(listOf(JsonPrimitive(1),JsonPrimitive(7)))))
    }

    @Test fun generatedRegistryAndNativeSchemasKeepGoalObjectsAndRevisionBounds(){
        val create=properties(GoalToolCatalog.CREATE).getValue("criteria").jsonObject
        assertEquals("string",create.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(1,create.getValue("minItems").jsonPrimitive.int)
        val update=properties(GoalToolCatalog.UPDATE)
        val criterion=update.getValue("criteria").jsonObject.getValue("items").jsonObject
        assertEquals("object",criterion.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("id","text"),criterion.getValue("properties").jsonObject.keys)
        assertFalse(criterion.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(listOf("text"),criterion.getValue("required").jsonArray.map{it.jsonPrimitive.content})
        assertEquals("string",update.getValue("removeCriterionIds").jsonObject.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        val native=Json.parseToJsonElement(ToolCallSchema.forTools(GoalToolCatalog.names)).jsonObject.getValue("oneOf").jsonArray
        assertEquals(7,native.size)
        val get=native.single{it.jsonObject.getValue("properties").jsonObject.getValue("tool").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content==GoalToolCatalog.GET}
            .jsonObject.getValue("properties").jsonObject.getValue("arguments").jsonObject.getValue("properties").jsonObject
        assertEquals(16,get.getValue("criteriaLimit").jsonObject.getValue("maximum").jsonPrimitive.int)
        assertEquals("^[1-9][0-9]{0,18}$",get.getValue("expectedUpdatedAt").jsonObject.getValue("pattern").jsonPrimitive.content)
    }

    @Test fun allGoalOperationsDerivePrivateScopeIncludingHighRiskClear(){
        for(name in GoalToolCatalog.names){
            val plan=ModelDecisionContract.parse(decision(setOf(name)))
            assertTrue(name,plan.usesPrivateData)
            assertEquals(SemanticSourceKind.NONE,plan.sourceKind)
            assertEquals("",plan.publicQuery)
        }
    }

    @Test fun privateGoalOperationsCannotBeCombinedWithPublicSearch(){
        for(name in GoalToolCatalog.names){
            assertTrue(name,runCatching{ModelDecisionContract.parse(decision(setOf(name,"search_web"),public=true))}.isFailure)
            val mixed=Json.parseToJsonElement(decision(setOf(name,"search_web"))).jsonObject
            assertTrue(name,runCatching{ModelDecisionContract.parse(JsonObject(mixed+mapOf("publicQuery" to JsonPrimitive("공개 주제"))).toString())}.isFailure)
        }
    }

    @Test fun canonicalPlansCannotMislabelGoalReadsOrMutationsAsPublic(){
        for(name in GoalToolCatalog.names){
            val privatePlan=ModelDecisionContract.parse(decision(setOf(name)))
            assertTrue(name,runCatching{SemanticRequestPlanning.parse(SemanticRequestPlanning.contextJson(privatePlan.copy(usesPrivateData=false)))}.isFailure)
        }
    }

    @Test fun mutationClosureOffersGetGoalWithoutGrantingUnrequestedMutations(){
        for(name in GoalToolCatalog.mutationNames){
            val plan=ModelDecisionContract.parse(decision(setOf(name)))
            assertEquals(setOf(GoalToolCatalog.GET,name),SemanticRequestPlanning.allowedTools(plan))
            assertTrue(name,runCatching{ModelDecisionContract.parse(decision(setOf(name)),setOf(name))}.isFailure)
        }
        val read=ModelDecisionContract.parse(decision(setOf(GoalToolCatalog.GET)))
        assertEquals(setOf(GoalToolCatalog.GET),SemanticRequestPlanning.allowedTools(read))
        assertTrue(ToolReadPolicy.isReadOnly(GoalToolCatalog.GET))
        GoalToolCatalog.mutationNames.forEach{assertFalse(it,ToolReadPolicy.isReadOnly(it))}
    }

    @Test fun legacyReadOnlyModesKeepMutationsBlockedAndResearchRejectsPrivateGoals(){
        for(mode in AgentWorkMode.entries){
            assertEquals(mode.wireName,mode!=AgentWorkMode.RESEARCH,AgentWorkModePolicy.allows(mode,GoalToolCatalog.GET))
            for(name in GoalToolCatalog.mutationNames)assertEquals("${mode.wireName}:$name",mode in setOf(AgentWorkMode.ACT,AgentWorkMode.DEBUG),AgentWorkModePolicy.allows(mode,name))
        }
    }

    @Test fun goalCapabilitiesDescribeLocalStateWithoutInventingPermissionsOrCompletion(){
        val snapshot=ToolCapabilityCatalog.snapshot().associateBy{it.id}
        for(name in GoalToolCatalog.names){
            val capability=snapshot.getValue(name)
            assertEquals(CapabilityAvailability.INSTALLED,capability.availability)
            assertTrue(name,capability.offline)
            assertTrue(name,capability.requirements.contains("현재 대화의 전체 접근 범위"))
            assertEquals(name,name==GoalToolCatalog.CLEAR,capability.requiresConfirmation)
            assertFalse(name,capability.requirements.any{it.startsWith("android.permission.")||it.startsWith("Android handler:")||it.startsWith("Verified local model:")})
        }
        assertTrue(snapshot.getValue(GoalToolCatalog.START).limitation.contains("대기열 저장은 목표 완료"))
        assertTrue(snapshot.getValue(GoalToolCatalog.PAUSE).limitation.contains("PREPARED"))
    }
}
