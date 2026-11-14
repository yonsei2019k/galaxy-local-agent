package com.user.localagent.core

import kotlinx.serialization.json.*

/** JSON-lines bridge to real core contracts. Synthetic results supplied by the evaluator are never device evidence. */
object ScenarioBridge {
    private fun names(value:JsonElement?)=value?.jsonArray?.map{it.jsonPrimitive.content}?.toSet()?:emptySet()
    private fun steps(value:JsonElement?)=value?.jsonArray?.map { item ->
        val row=item.jsonObject
        val tool=row.getValue("tool").jsonPrimitive.content
        HarnessStep(tool,row.getValue("arguments").jsonObject,StepEvidence(tool,
            VerificationState.valueOf(row.getValue("verification").jsonPrimitive.content),"synthetic fixture"))
    }?:emptyList()

    fun respond(input:JsonObject):JsonObject {
        val offered=if("offered" in input)names(input["offered"]) else ToolRegistry.definitions.keys-SkillToolCatalog.names
        return when(input.getValue("op").jsonPrimitive.content) {
            "catalog"->buildJsonObject {
                put("tools",Json.parseToJsonElement(ToolUsePolicy.compactCatalog(offered)))
                put("instructions",SemanticPlannerInstructions.build(offered))
                put("schema",Json.parseToJsonElement(ModelDecisionContract.planningSchemaJson(offered)))
                put("turnReminder",ToolUsePolicy.TURN_REMINDER)
            }
            "plan"->{
                val plan=ModelDecisionContract.parse(input.getValue("raw").jsonPrimitive.content,offered)
                val allowed=ToolUsePolicy.withVerification(SemanticRequestPlanning.allowedTools(plan,offered),plan.action,offered)
                val structured=allowed.filter {it!="generate_image"&&(it in plan.requiredTools||ToolReadPolicy.isReadOnly(it))}.toSet()
                val now=input.getValue("now").jsonPrimitive.content
                buildJsonObject {
                    put("plan",Json.parseToJsonElement(SemanticRequestPlanning.contextJson(plan)))
                    put("allowed",JsonArray(allowed.sorted().map(::JsonPrimitive)))
                    put("system",AssistantPolicy.build(allowed,now)+
                        "\n현재 제공한 도구 인자 정의: "+ToolUsePolicy.compactCatalog(allowed))
                    put("actionSystem",AssistantPolicy.buildToolSelection(allowed,now)+"\n현재 제공한 도구 인자 정의: "+ToolUsePolicy.compactCatalog(allowed))
                    if(structured.isNotEmpty())put("actionSchema",Json.parseToJsonElement(ToolCallSchema.forTools(structured)))
                }
            }
            "call","finish"->{
                val plan=ModelDecisionContract.parse(input.getValue("planRaw").jsonPrimitive.content,offered)
                val goal=SemanticRequestPlanning.toTaskGoal(plan)
                val previous=steps(input["steps"])
                val decision=if(input.getValue("op").jsonPrimitive.content=="call") {
                    val allowed=ToolUsePolicy.withVerification(SemanticRequestPlanning.allowedTools(plan,offered),plan.action,offered)
                    val call=ToolRegistry.validateForRequest(ToolRegistry.parseAndValidate(input.getValue("raw").jsonPrimitive.content),allowed)
                    TaskHarness.beforeStep(goal,previous,call)
                } else TaskHarness.decide(goal,previous,true)
                buildJsonObject {
                    put("decision",when(decision){is HarnessDecision.Advance->"advance";is HarnessDecision.Finish->"finish";is HarnessDecision.Stop->"stop"})
                    put("reason",when(decision){is HarnessDecision.Advance->decision.reason;is HarnessDecision.Finish->decision.summary;is HarnessDecision.Stop->decision.reason})
                    put("prepared",(decision as? HarnessDecision.Stop)?.pendingExternalCompletion==true)
                }
            }
            else->error("Unknown bridge operation")
        }
    }
    @JvmStatic fun main(args:Array<String>) {
        System.`in`.bufferedReader(Charsets.UTF_8).lineSequence().forEach {line->
            val answer=try {buildJsonObject{put("ok",true);put("data",respond(Json.parseToJsonElement(line).jsonObject))}}
            catch(error:Exception){buildJsonObject{put("ok",false);put("error",if(error is ModelDecisionException)error.code else "contract_rejected");put("hint",error.message.orEmpty().take(500))}}
            println(answer)
        }
    }
}
