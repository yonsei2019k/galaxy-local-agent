package com.user.localagent

import com.user.localagent.core.ProjectPaths
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.*

/** Reconciles the intended file state without issuing or approving a second write. */
internal data class GoalFileReadback(val path:String,val sha256:String,val size:Long) {
    fun confirmed(call:ToolCall,actualPath:String,actualSha256:String,actualSize:Long):ToolResult? {
        if(path!=actualPath || sha256!=actualSha256 || size!=actualSize)return null
        return ToolResult(call.id,true,buildJsonObject {
            put("path",path);put("bytes",size);put("sha256",sha256);put("verified",true)
            put("executed",false);put("reconciledByReadback",true)
        }.toString(),VerificationState.VERIFIED,"프로젝트 파일을 다시 읽어 요청한 내용과 일치하는지 확인했어요.")
    }

    companion object {
        fun from(call:ToolCall,result:ToolResult):GoalFileReadback? {
            if(call.name!="write_project_file" || result.id!=call.id || result.success ||
                result.effectNotApplied || result.verification !in setOf(VerificationState.UNVERIFIED,VerificationState.FAILED))return null
            val path=(call.arguments["path"] as? JsonPrimitive)?.takeIf {it.isString}?.content?:return null
            val content=(call.arguments["content"] as? JsonPrimitive)?.takeIf {it.isString}?.content?:return null
            if(content.length>12_000 || '\u0000' in content ||
                runCatching { ProjectPaths.segments(path).joinToString("/")==path }.getOrNull()!=true)return null
            val hash=goalDigest(content)
            if(call.arguments["expectedSha256"]==JsonPrimitive(hash))return null
            return GoalFileReadback(path,hash,content.toByteArray(Charsets.UTF_8).size.toLong())
        }
    }
}
