package com.user.localagent

import com.user.localagent.data.WorkTraceCodec

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
