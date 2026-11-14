package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Consumes only this run's unabridged host outcomes, never model tool feedback or prose. */
internal object ChatReadbackEvidence {
    fun assess(binding:RequiredReadbackPolicy.Binding,required:Set<String>,allowed:Set<String>,
        steps:List<HarnessStep>,outcomes:List<ObservedToolOutcome>,remainingRounds:Int,stepBudget:Int=WorkSegments.STEP_BUDGET):RequiredReadbackPolicy.Assessment {
        val mutations=mutableListOf<RequiredReadbackPolicy.Mutation>()
        val reads=mutableListOf<RequiredReadbackPolicy.Read>()
        val relations=mapOf("save_note" to "search_notes","update_note" to "search_notes",
            "create_reminder" to "list_reminders","update_reminder" to "list_reminders","write_project_file" to "read_project_file")
            .filter{(mutation,read)->mutation in required&&read in required}
        val tracked=relations.keys+relations.values
        val offset=steps.size-outcomes.size
        if(offset>=0)outcomes.forEachIndexed {index,observed->
            val stepIndex=offset+index
            val step=steps.getOrNull(stepIndex)?:return@forEachIndexed
            val call=observed.call;val result=observed.result
            if(call.name !in tracked)return@forEachIndexed
            if(step.tool!=call.name||step.arguments!=call.arguments||step.evidence.state!=VerificationState.VERIFIED||
                !result.success||result.verification!=VerificationState.VERIFIED||result.effectNotApplied||result.id!=call.id)return@forEachIndexed
            val json=runCatching{require(result.content.length<=40_000);Json.parseToJsonElement(result.content).jsonObject}.getOrNull()?:return@forEachIndexed
            runCatching {
                when(call.name){
                    "save_note","update_note"->{
                        require(json.string("status")=="ok"&&json.flag("storedAndReadBack")==true)
                        val note=json.getValue("note").jsonObject
                        require(json.string("id")==note.string("id"))
                        if(call.name=="update_note")require(call.arguments["id"]?.jsonPrimitive?.content==note.string("id"))
                        require(note.string("text")==call.arguments["content"]?.jsonPrimitive?.content)
                        mutations+=RequiredReadbackPolicy.Mutation(binding,stepIndex,call.name,noteSnapshot(note))
                    }
                    "create_reminder","update_reminder"->{
                        require(json.string("status")=="ok"&&json.flag("storedAndReadBack")==true&&json.flag("queueVerified")==true)
                        val reminder=json.getValue("reminder").jsonObject
                        require(json.string("id")==reminder.string("id"))
                        if(call.name=="update_reminder")require(call.arguments["id"]?.jsonPrimitive?.content==reminder.string("id"))
                        mutations+=RequiredReadbackPolicy.Mutation(binding,stepIndex,call.name,reminderSnapshot(reminder,false))
                    }
                    "write_project_file"->{
                        require(json.flag("verified")==true&&json.flag("executed")==false)
                        mutations+=RequiredReadbackPolicy.Mutation(binding,stepIndex,call.name,fileSnapshot(json,call))
                    }
                    "search_notes"->{
                        require(json.string("status")=="ok")
                        val details=json.getValue("details").jsonObject
                        val notes=details.getValue("notes").jsonArray
                        val returned=notes.mapNotNull {item->runCatching{noteSnapshot(item.jsonObject)}.getOrNull()}
                        reads+=RequiredReadbackPolicy.Read(binding,stepIndex,call.name,returned)
                    }
                    "list_reminders"->{
                        require(json.string("status")=="ok"&&json.flag("truncated")==false)
                        val returned=json.getValue("reminders").jsonArray.map{reminderSnapshot(it.jsonObject,true)}
                        reads+=RequiredReadbackPolicy.Read(binding,stepIndex,call.name,returned)
                    }
                    "read_project_file"->{
                        require(json.flag("truncated")==false)
                        reads+=RequiredReadbackPolicy.Read(binding,stepIndex,call.name,listOf(fileSnapshot(json,call)))
                    }
                }
            } // Missing/legacy/malformed receipts leave the criterion unproved, never invent a target.
        }
        return RequiredReadbackPolicy.assess(binding,required,allowed,steps,mutations,reads,
            (stepBudget-steps.size).coerceAtLeast(0),remainingRounds.coerceAtLeast(0))
    }

    private fun noteSnapshot(note:JsonObject):RequiredReadbackPolicy.Snapshot {
        require(note.flag("truncated")==false)
        return snapshot("note",note.string("id"),note.revision(),buildJsonObject {
            put("id",note.string("id"));put("title",note.string("title"));put("collection",note.string("collection"));put("text",note.string("text"))
        })
    }
    private fun reminderSnapshot(reminder:JsonObject,listing:Boolean):RequiredReadbackPolicy.Snapshot =
        snapshot("reminder",reminder.string("id"),reminder.revision(),buildJsonObject {
            put("id",reminder.string("id"));put("title",reminder.string("title"));put("group",reminder.string(if(listing)"scope"else"group"))
            val at=reminder["atEpochMillis"] as? JsonPrimitive
            require(at!=null&&!at.isString&&at.longOrNull!=null);put("atEpochMillis",at.long)
        })
    private fun fileSnapshot(json:JsonObject,call:ToolCall):RequiredReadbackPolicy.Snapshot {
        val path=json.string("path");ProjectPaths.segments(path)
        require(path==call.arguments["path"]?.jsonPrimitive?.content)
        val sha=json.string("sha256");require(sha.matches(Regex("[a-f0-9]{64}")))
        if(call.name=="read_project_file")require(digest(json.string("text"))==sha)
        if(call.name=="write_project_file")require(digest(call.arguments.getValue("content").jsonPrimitive.content)==sha)
        return RequiredReadbackPolicy.Snapshot(RequiredReadbackPolicy.Resource("project_file",path),sha,sha)
    }
    private fun snapshot(kind:String,id:String,revision:String,state:JsonObject)=
        RequiredReadbackPolicy.Snapshot(RequiredReadbackPolicy.Resource(kind,id),revision,digest(state.toString()))
    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun JsonObject.string(key:String):String=(getValue(key) as? JsonPrimitive)?.takeIf{it.isString}?.content?:error("Missing host field")
    private fun JsonObject.flag(key:String):Boolean?=(get(key) as? JsonPrimitive)?.takeIf{!it.isString}?.booleanOrNull
    private fun JsonObject.revision():String=(get("updatedAt") as? JsonPrimitive)?.takeIf{!it.isString&&it.longOrNull!=null&&it.long>=0}?.content?:error("Missing host revision")
}
