package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Only reconstruct reads with an explicit target or bounded scope from a settled mutation. */
internal fun goalReadbackCalls(
    steps:List<HarnessStep>,pending:List<RequiredReadbackPolicy.Pending>
):List<ToolCall>? {
    if(pending.size !in 1..6)return null
    return pending.map { obligation ->
        val source=steps.getOrNull(obligation.mutationStepIndex)?:return null
        val target=obligation.expected.resource
        val args=when(obligation.readTool) {
            "read_project_file"->buildJsonObject {put("path",target.stableId)}
            "search_notes"->{
                val query=source.arguments["title"]?.jsonPrimitive?.contentOrNull?.takeIf {it.isNotBlank()}
                    ?:source.arguments["content"]?.jsonPrimitive?.contentOrNull?.takeIf {it.isNotBlank()}?.take(100)
                    ?:return null
                buildJsonObject {
                    put("query",query);put("limit",50)
                    source.arguments["scope"]?.let {put("scope",it)}
                }
            }
            "list_reminders"->buildJsonObject {
                put("limit",50);put("includeCompleted",true)
                source.arguments["scope"]?.let {put("scope",it)}
            }
            else->return null
        }
        val call=ToolCall(UUID.randomUUID().toString(),obligation.readTool,args)
        if(!ToolReadPolicy.isReadOnly(call.name)||runCatching {ToolRegistry.validate(call)}.isFailure)return null
        call
    }.distinctBy {it.name to it.arguments}
}

internal fun assessGoalReadback(request:QueuedChatRequest,scopeKey:String):RequiredReadbackPolicy.Assessment {
    val context=RequiredReadbackObligations.Context(request.runId,request.threadId,request.projectId,scopeKey,request.workspaceBinding)
    return RequiredReadbackObligations.assess(request.readbackObligations,context,request.previousSteps,
        request.previousSteps.size,ToolRegistry.definitions.keys,1,1)
}

internal data class GoalReadbackSeed(
    val oldContext:RequiredReadbackObligations.Context,
    val steps:List<HarnessStep>,
    val state:RequiredReadbackObligations.State,
) {
    fun forRun(id:String)=state.copy(context=oldContext.copy(runId=id))

    fun matches(request:QueuedChatRequest):Boolean=
        request.goalReadbackSeedCount==steps.size&&request.readbackObligations==forRun(request.runId)&&
            matchesSteps(request)

    fun tracksSameMutations(request:QueuedChatRequest):Boolean {
        val actual=request.readbackObligations?:return false
        val expected=forRun(request.runId)
        return matchesSteps(request)&&actual.context==expected.context&&actual.originRunId==expected.originRunId&&
            actual.selected==expected.selected&&actual.applied.map {it.source to it.after}==
                expected.applied.map {it.source to it.after}
    }

    private fun matchesSteps(request:QueuedChatRequest):Boolean=
        request.goalReadbackSeedCount==steps.size&&request.previousSteps.take(steps.size).zip(steps).all { (a,b)->
            a.tool==b.tool&&a.arguments==b.arguments&&a.evidence==b.evidence
        }

    companion object {
        fun from(request:QueuedChatRequest,assessment:RequiredReadbackPolicy.Assessment):GoalReadbackSeed? {
            if(assessment.blockers.isNotEmpty()||goalReadbackCalls(request.previousSteps,assessment.pending)==null)return null
            val state=request.readbackObligations?:return null
            val pendingIndices=assessment.pending.map {it.mutationStepIndex}.toSet()
            val indexed=state.applied.filter {it.source.index in pendingIndices}.sortedBy {it.source.index}
            if(indexed.size!=pendingIndices.size)return null
            val steps=indexed.map {request.previousSteps[it.source.index]}
            val compact=state.copy(selected=state.selected.filter {pair->indexed.any {it.source.tool==pair.mutation}}.toSet(),
                applied=indexed.map {it.copy(latestRead=null,coverage=emptyList())})
            val indexMap=indexed.mapIndexed {i,entry->entry.source.index to i}.toMap()
            val carried=RequiredReadbackObligations.carry(compact,state.context,state.context,request.previousSteps,steps,indexMap)
            return GoalReadbackSeed(state.context,steps,carried)
        }
    }
}
