package com.user.localagent

import com.user.localagent.core.ProjectPaths
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.*

/** Reconciles the intended file state without issuing or approving a second write. */
internal data class GoalFileReadback(val path:String,val sha256:String,val size:Long) {
    fun matches(actualPath:String,actualSha256:String,actualSize:Long):Boolean =
        path==actualPath&&sha256==actualSha256&&size==actualSize

    fun confirmed(call:ToolCall,actualPath:String,actualSha256:String,actualSize:Long):ToolResult? {
        if(!matches(actualPath,actualSha256,actualSize))return null
        return ToolResult(call.id,true,buildJsonObject {
            put("path",path);put("bytes",size);put("sha256",sha256);put("verified",true)
            put("executed",false);put("reconciledByReadback",true)
        }.toString(),VerificationState.VERIFIED,"프로젝트 파일을 다시 읽어 요청한 내용과 일치하는지 확인했어요.")
    }

    companion object {
        fun from(call:ToolCall,result:ToolResult):GoalFileReadback? {
            if(call.name!="write_project_file" || result.id!=call.id || result.success ||
                result.effectNotApplied || result.verification !in setOf(VerificationState.UNVERIFIED,VerificationState.FAILED))return null
            return intended(call)
        }

        fun intended(call:ToolCall):GoalFileReadback? {
            if(call.name!="write_project_file")return null
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

/** The approved target only. Stored with the encrypted run, never in model or diagnostic output. */
internal object InterruptedGoalFileCheck {
    const val KEY="hostDispatchedGoalFileV1"
    const val MATCHED="hostInterruptedGoalFileMatches"
    private val hashPattern=Regex("[a-f0-9]{64}")

    fun marker(call:ToolCall,file:GoalFileReadback)=buildJsonObject {
        put("callId",call.id);put("path",file.path);put("sha256",file.sha256);put("bytes",file.size)
    }

    fun activeCallMatches(native:JsonObject?,id:JsonElement?):Boolean =
        id is JsonPrimitive&&id.isString&&id.content.isNotBlank()&&
            native?.get("inFlight")==id&&native?.get("inFlightTool")==JsonPrimitive("write_project_file")

    fun load(context:JsonObject,binding:JsonObject):GoalFileReadback? {
        val native=context["sharedEngineState"] as? JsonObject?:return null
        if(native["binding"]!=binding||native["closed"]!=JsonPrimitive(false)||native["uncertain"]!=JsonPrimitive(false))return null
        val saved=context[KEY] as? JsonObject?:return null
        if(saved.keys!=setOf("callId","path","sha256","bytes")||!activeCallMatches(native,saved["callId"]))return null
        fun field(key:String)=(saved[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
        val path=field("path")?.takeIf {runCatching {ProjectPaths.segments(it).joinToString("/")==it}.getOrDefault(false)}?:return null
        val hash=field("sha256")?.takeIf(hashPattern::matches)?:return null
        val bytes=(saved["bytes"] as? JsonPrimitive)?.takeUnless{it.isString}?.longOrNull?.takeIf{it in 0..48_000L}?:return null
        return GoalFileReadback(path,hash,bytes)
    }
}
