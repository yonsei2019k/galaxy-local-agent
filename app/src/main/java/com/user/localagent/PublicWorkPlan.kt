package com.user.localagent

import com.user.localagent.data.WorkTraceCodec

internal fun publicWorkPlanNotice(goal:String):String? {
    val summary=WorkTraceCodec.clip(WorkTraceCodec.redactPublicExplanation(goal),220)
        .takeIf {it.isNotBlank()}?:return null
    return "좋아요, 이렇게 진행할게요.\n\n$summary\n\n잠시만 기다려 주세요. 확인한 내용은 이 대화에 정리해 드릴게요."
}

/** Split the actual public plan into bounded trace rows without inventing an interpretation. */
internal fun publicWorkPlanParts(goal:String,constraints:List<String>):List<String> = buildList {
    fun addParts(text:String){
        WorkTraceCodec.redactPublicExplanation(text).codePoints().toArray().toList().chunked(100).forEach{points->
            add(String(points.toIntArray(),0,points.size))
        }
    }
    if(goal.isNotBlank())addParts(goal)
    constraints.filter{it.isNotBlank()}.forEach{addParts("지킬 조건: $it")}
}
