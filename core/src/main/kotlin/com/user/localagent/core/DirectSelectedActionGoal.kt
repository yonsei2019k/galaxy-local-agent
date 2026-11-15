package com.user.localagent.core

import kotlinx.serialization.json.jsonPrimitive

/** Experimental direct-loop criteria only. Never supplies allowed tools or interprets request words. */
object DirectSelectedActionGoal {
    fun enabled(semanticAbsent:Boolean,directLoop:Boolean,actionMode:Boolean,allowTools:Boolean)=
        semanticAbsent&&directLoop&&actionMode&&allowTools
    private fun action(name:String)=name in ToolRegistry.definitions&&name !in SkillToolCatalog.names&&!ToolReadPolicy.isReadOnly(name)
    private fun criteria(actions:Set<String>)=actions+RequiredReadbackObligations.Pair.entries.filter{it.mutation in actions}.map{it.read}
    fun initial(ownerRequest:String,stepBudget:Int,previousSteps:List<HarnessStep>):TaskGoal {
        val selected=previousSteps.map{it.tool}.filter(::action).toSet()
        return TaskGoal(ownerRequest,listOf("원래 요청의 조건을 유지하고 선택한 행동의 실제 결과와 필요한 재조회를 검증한다."),stepBudget,criteria(selected))
    }
    /** Current-call registry/allowed validation and literal-reference resolution precede this checkpoint. */
    fun select(goal:TaskGoal,call:ToolCall,allowed:Set<String>):TaskGoal {
        ToolRegistry.validateForRequest(call,allowed)
        if(!action(call.name))return goal
        val linked=RequiredReadbackObligations.Pair.entries.filter{it.mutation==call.name}.map{it.read}.toSet()
        require(allowed.containsAll(linked)){"DIRECT_ACTION_READBACK_NOT_ALLOWED"}
        val required=goal.requiredTools+call.name+linked
        val formats=goal.requiredArtifactFormats+if(call.name=="create_document")setOf(call.arguments.getValue("format").jsonPrimitive.content)else emptySet()
        if(required==goal.requiredTools&&formats==goal.requiredArtifactFormats)return goal
        return TaskGoal(goal.ownerRequest,goal.successCriteria,goal.stepBudget,required,formats)
    }
}
