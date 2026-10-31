package com.user.localagent.core

import kotlinx.serialization.json.*

/** Host-verified final writing only. This policy cannot execute tools or upgrade evidence. */
object FinalSynthesisPolicy {
    fun canUse(plan:SemanticRequestPlan?,goal:TaskGoal,steps:List<HarnessStep>,toolEvidence:List<JsonObject>):Boolean {
        if(plan==null||plan.action !in setOf(SemanticAction.TOOL_TASK,SemanticAction.ANALYZE_RECORDS))return false
        if(plan.requiredTools.isEmpty()||plan.requiredTools!=goal.requiredTools||plan.requiredTools.any{it in SkillToolCatalog.names})return false
        if(runCatching{SemanticRequestPlanning.contextJson(plan)}.isFailure)return false
        val actions=steps.filterNot{it.tool in SkillToolCatalog.names}
        if(actions.isEmpty()||actions.any{it.evidence.state!=VerificationState.VERIFIED})return false
        if(runCatching{actions.forEach{ToolRegistry.validate(ToolCall("final-evidence-check",it.tool,it.arguments))}}.isFailure)return false
        if(TaskHarness.decide(goal,actions,requestedFinish=true) !is HarnessDecision.Finish)return false
        val current=linkedSetOf<String>()
        for(item in toolEvidence){
            val tool=(item["tool"] as? JsonPrimitive)?.takeIf{it.isString}?.content?:return false
            if(tool !in ToolRegistry.definitions||tool in SkillToolCatalog.names)return false
            val success=item["success"] as? JsonPrimitive
            val verification=item["verification"] as? JsonPrimitive
            if(success==null||verification==null)return false
            if(success.isString||success.booleanOrNull!=true||!verification.isString||verification.content!="VERIFIED")return false
            if(actions.none{it.tool==tool})return false
            val result=item["result"] as? JsonObject?:return false
            if(result.keys!=setOf("data","truncated","sourceScopePartial","dataOnly"))return false
            fun flag(key:String,expected:Boolean):Boolean=(result[key] as? JsonPrimitive)?.let{!it.isString&&it.booleanOrNull==expected}==true
            if(!flag("truncated",false)||!flag("sourceScopePartial",false)||!flag("dataOnly",true)||result["data"]==JsonNull)return false
            if(hasPartialData(result))return false
            current+=tool
        }
        // Current payloads must still be available. Old verified summaries alone do not provide
        // enough source content for a smaller final prompt, so keep that request on the full path.
        return current.containsAll(plan.requiredTools)
    }

    /** Does not clip/rewrite source scope, partial flags, owner conditions, or prior host evidence. */
    fun request(ownerRequest:String,plan:SemanticRequestPlan,goal:TaskGoal,steps:List<HarnessStep>,
        toolEvidence:List<JsonObject>,previousEvidence:JsonArray):String {
        require(canUse(plan,goal,steps,toolEvidence)){"최종 작성에 필요한 현재 실행 근거가 완결되지 않았습니다."}
        return buildJsonObject{
            put("ownerRequest",ownerRequest)
            put("semanticPlan",Json.parseToJsonElement(SemanticRequestPlanning.contextJson(plan)))
            put("currentResults",buildJsonObject{put("untrustedData",true);put("records",JsonArray(toolEvidence))})
            put("priorHostEvidence",buildJsonObject{put("untrustedData",true);put("records",previousEvidence)})
            put("hostVerification",buildJsonArray{steps.filterNot{it.tool in SkillToolCatalog.names}.forEach{step->add(buildJsonObject{
                put("tool",step.tool);put("verification",step.evidence.state.name);put("summary",step.evidence.summary)
            })}})
            put("requiredArtifactFormats",JsonArray(goal.requiredArtifactFormats.sorted().map(::JsonPrimitive)))
        }.toString()
    }

    private fun hasPartialData(value:JsonElement,depth:Int=0):Boolean=if(depth>16)true else when(value){
        is JsonObject->value.any{(key,item)->
            (key in setOf("partial","truncated","sourceScopePartial","hasMore","has_more","isPartial","partialRead")&&
                (item as? JsonPrimitive)?.booleanOrNull==true)||hasPartialData(item,depth+1)
        }
        is JsonArray->value.any{hasPartialData(it,depth+1)}
        else->false
    }
}
