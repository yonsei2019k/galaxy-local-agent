package com.user.localagent.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Exports the real conversation policy for the PC evaluation loop (pc-worker/eval/agent_eval.py), so
 * the loop always tests the prompts the app actually sends. {{NOW}} is filled in by the loop.
 * - prompts.json: policy for the three local verification tools (quick loop)
 * - tools.json: every registered tool with its risk, read-only flag and definition
 * - planner.json: the semantic planner instructions over all tools (scenario loop)
 */
class EvalPromptExportTest {
    private val out=File("build/eval").apply{mkdirs()}

    @Test fun exportsClosedGoalToolSchemasForPairedWorker() {
        val names = GoalProgressToolCatalog.names + GoalWaitToolCatalog.WAIT
        val catalog = Json.parseToJsonElement(ToolRegistry.schemaJson(names)).jsonArray
        fun verify(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    if (node["type"] == JsonPrimitive("object")) {
                        assertEquals(JsonPrimitive(false), node["additionalProperties"])
                        assertTrue(node["properties"] is JsonObject)
                    }
                    node.values.forEach(::verify)
                }
                is JsonArray -> node.forEach(::verify)
                else -> Unit
            }
        }
        verify(catalog)
        File(out, "goal-execution-schemas.json").writeText(catalog.toString())
    }

    @Test fun exportsCurrentPolicyForLocalVerificationTools(){
        val tools=ToolUsePolicy.verificationTools
        val data=buildJsonObject {
            put("tools",JsonArray(tools.sorted().map(::JsonPrimitive)))
            put("policy",AssistantPolicy.build(tools,"{{NOW}}"))
            put("catalog",ToolUsePolicy.compactCatalog(tools))
            put("afterToolSuffix","검증된 결과로 답하거나 다음 필요한 도구를 요청하라. PREPARED는 작성 화면·전송 요청 단계이며 외부 작업 완료가 아니다. FAILED·UNVERIFIED를 성공이라고 말하지 마라. 같은 인자로 쓰기 작업을 반복하지 마라.")
            put("gateSystem",ToolUsePolicy.gateSystem(tools))
            put("turnReminder",ToolUsePolicy.TURN_REMINDER)
            put("gateSchema",AutonomousReviewPolicy.schema(tools))
        }
        val file=File(out,"prompts.json").apply{writeText(data.toString())}
        assertTrue(file.length()>500)
        assertTrue(data.getValue("policy").jsonPrimitive.content.contains("{{NOW}}"))
    }

    @Test fun exportsEveryToolAndThePlannerForScenarioEvaluation(){
        val names=ToolRegistry.definitions.keys
        val tools=buildJsonArray {names.sorted().forEach {name->
            val definition=ToolRegistry.definitions.getValue(name)
            add(buildJsonObject {
                put("name",name);put("risk",definition.risk.name);put("readOnly",ToolReadPolicy.isReadOnly(name))
                put("description",definition.description)
                put("arguments",buildJsonObject {definition.arguments.forEach {(key,spec)->put(key,"${spec.type}${if(spec.required)" required" else " optional"}")}})
            })
        }}
        File(out,"tools.json").writeText(tools.toString())
        File(out,"planner.json").writeText(buildJsonObject {
            put("instructions",SemanticPlannerInstructions.build(names-SkillToolCatalog.names))
        }.toString())
        assertTrue(tools.size>20)
    }

    /** Policy text for an arbitrary tool set, written on demand by the scenario loop's tool sets. */
    @Test fun exportsPolicyPerScenarioToolSet(){
        val sets=File("../pc-worker/eval/scenario-toolsets.json").takeIf{it.isFile}?:return
        val requested=Json.parseToJsonElement(sets.readText()).jsonObject
        val policies=buildJsonObject {requested.forEach {(id,value)->
            val tools=value.jsonArray.map{it.jsonPrimitive.content}.filter{it in ToolRegistry.definitions}.toSet()+ToolUsePolicy.verificationTools
            put(id,buildJsonObject {
                put("tools",JsonArray(tools.sorted().map(::JsonPrimitive)))
                put("policy",AssistantPolicy.build(tools,"{{NOW}}"))
                put("catalog",ToolUsePolicy.compactCatalog(tools))
                put("unknown",JsonArray(value.jsonArray.filter{it.jsonPrimitive.content !in ToolRegistry.definitions}))
            })
        }}
        File(out,"scenario-policies.json").writeText(policies.toString())
    }
}
