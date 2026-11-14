package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Consumes only this run's unabridged host outcomes, never model tool feedback or prose. */
internal object ChatReadbackEvidence {
    data class Collected(val mutations:List<RequiredReadbackPolicy.Mutation>,val reads:List<RequiredReadbackPolicy.Read>,val aligned:Boolean)
    fun assess(binding:RequiredReadbackPolicy.Binding,required:Set<String>,allowed:Set<String>,
        steps:List<HarnessStep>,outcomes:List<ObservedToolOutcome>,remainingRounds:Int,stepBudget:Int=WorkSegments.STEP_BUDGET):RequiredReadbackPolicy.Assessment {
        val evidence=collect(binding,required,steps,outcomes)
        val assessment=RequiredReadbackPolicy.assess(binding,required,allowed,steps,evidence.mutations,evidence.reads,
            (stepBudget-steps.size).coerceAtLeast(0),remainingRounds.coerceAtLeast(0))
        return if(!evidence.aligned&&assessment.applicable)assessment.copy(blockers=assessment.blockers+RequiredReadbackPolicy.Blocker.INVALID_HOST_PROOF)else assessment
    }

    /** Pure collection for both finish checks and a future durable obligation snapshot. */
    fun collect(binding:RequiredReadbackPolicy.Binding,required:Set<String>,steps:List<HarnessStep>,outcomes:List<ObservedToolOutcome>):Collected {
        val mutations=mutableListOf<RequiredReadbackPolicy.Mutation>()
        val reads=mutableListOf<RequiredReadbackPolicy.Read>()
        val relations=mapOf("save_note" to "search_notes","update_note" to "search_notes",
            "create_reminder" to "list_reminders","update_reminder" to "list_reminders","write_project_file" to "read_project_file")
            .filter{(mutation,read)->mutation in required&&read in required}
        val tracked=relations.keys+relations.values
        val offset=steps.size-outcomes.size
        val aligned=offset>=0&&outcomes.withIndex().all{(index,observed)->steps.getOrNull(offset+index)?.let{
            it.tool==observed.call.name&&it.arguments==observed.call.arguments&&observed.result.id==observed.call.id
        }==true}
        if(!aligned)return Collected(emptyList(),emptyList(),false)
        // Host-effective typed arguments only: no interpretation of natural-language query meaning.
        val queryTargets=mutableMapOf<Pair<String,JsonObject>,Set<RequiredReadbackPolicy.Resource>>()
        outcomes.forEachIndexed {index,observed->
            val stepIndex=offset+index
            val step=steps.getOrNull(stepIndex)?:return@forEachIndexed
            val call=observed.call;val result=observed.result
            if(call.name !in tracked)return@forEachIndexed
            if(step.tool!=call.name||step.arguments!=call.arguments||step.evidence.state!=VerificationState.VERIFIED||
                !result.success||result.verification!=VerificationState.VERIFIED||result.effectNotApplied||result.id!=call.id)return@forEachIndexed
            val json=runCatching{require(result.content.length<=40_000);Json.parseToJsonElement(result.content).jsonObject}.getOrNull()
            if(call.name in relations.values){
                val key=runCatching{queryKey(call)}.getOrElse{return Collected(emptyList(),emptyList(),false)}
                val read=collectRead(binding,stepIndex,call,json,queryTargets[key].orEmpty())
                reads+=read
                queryTargets[key]=queryTargets[key].orEmpty()+read.returned.map{it.resource}+read.incompleteTargets
                return@forEachIndexed
            }
            if(json==null)return@forEachIndexed
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
                }
            } // Missing/legacy/malformed receipts leave the criterion unproved, never invent a target.
        }
        return Collected(mutations.toList(),reads.toList(),true)
    }

    /** Mirrors ToolExecutor/LocalRepository defaults only; query spelling/case/spacing remain exact. */
    fun querySignature(tool:String,args:JsonObject):String? = runCatching {
        require(tool in setOf("search_notes","list_reminders","read_project_file"))
        val key=queryKey(ToolCall("host-read-signature",tool,args))
        digest("ziphap.readback-query.v1\n"+buildJsonObject{put("tool",key.first);put("arguments",key.second)})
    }.getOrNull()

    private fun queryKey(call:ToolCall):Pair<String,JsonObject> = call.name to buildJsonObject {
        fun text(key:String,default:String="")=call.arguments[key]?.let{(it as? JsonPrimitive)?.takeIf{it.isString}?.content?:error("Invalid host argument")}?:default
        fun integer(key:String,default:Int)=call.arguments[key]?.let{(it as? JsonPrimitive)?.takeIf{!it.isString}?.intOrNull?:error("Invalid host argument")}?:default
        when(call.name){
            "search_notes"->{put("query",text("query"));put("scope",text("scope").takeIf{it.isNotBlank()}.orEmpty());put("limit",integer("limit",5).coerceIn(1,5))}
            "list_reminders"->{put("scope",text("scope"));put("limit",integer("limit",20));put("includeCompleted",call.arguments["includeCompleted"]?.let{(it as? JsonPrimitive)?.takeIf{!it.isString}?.booleanOrNull?:error("Invalid host argument")}?:false)}
            "read_project_file"->{val path=text("path");put("path",ProjectPaths.segments(path).joinToString("/"))}
        }
    }

    private fun collectRead(binding:RequiredReadbackPolicy.Binding,index:Int,call:ToolCall,json:JsonObject?,previousQueryTargets:Set<RequiredReadbackPolicy.Resource>):RequiredReadbackPolicy.Read {
        val returned=mutableListOf<RequiredReadbackPolicy.Snapshot>()
        val incomplete=linkedSetOf<RequiredReadbackPolicy.Resource>()
        val queried=previousQueryTargets.toMutableSet()
        var complete=true
        fun identity(kind:String,row:JsonObject?)=runCatching{
            RequiredReadbackPolicy.Resource(kind,checkNotNull(row).string("id").also{require(it.isNotBlank())})
        }.getOrNull()
        fun path(value:String?)=runCatching{
            val actual=checkNotNull(value);ProjectPaths.segments(actual)
            RequiredReadbackPolicy.Resource("project_file",actual)
        }.getOrNull()
        // A file call explicitly names its target, even when the host receipt is malformed.
        if(call.name=="read_project_file")path((call.arguments["path"] as? JsonPrimitive)?.takeIf{it.isString}?.content)?.let{queried+=it}
        runCatching {
            val source=checkNotNull(json)
            when(call.name){
                "search_notes"->{
                    if(runCatching{source.string("status")}.getOrNull()!="ok")complete=false
                    val details=source.getValue("details").jsonObject
                    if(source.flag("partial")==true||source.flag("truncated")==true||details.flag("partial")==true||details.flag("truncated")==true)complete=false
                    details.getValue("notes").jsonArray.forEach {item->
                        val row=item as? JsonObject
                        val target=identity("note",row)
                        target?.let{queried+=it}
                        val snapshot=runCatching{noteSnapshot(checkNotNull(row))}.getOrNull()
                        if(snapshot!=null)returned+=snapshot else if(target!=null)incomplete+=target else complete=false
                    }
                }
                "list_reminders"->{
                    if(runCatching{source.string("status")}.getOrNull()!="ok")complete=false
                    if(source.flag("truncated")!=false||source.flag("partial")==true)complete=false
                    source.getValue("reminders").jsonArray.forEach {item->
                        val row=item as? JsonObject
                        val target=identity("reminder",row)
                        target?.let{queried+=it}
                        val snapshot=runCatching{reminderSnapshot(checkNotNull(row),true)}.getOrNull()
                        if(snapshot!=null)returned+=snapshot else if(target!=null)incomplete+=target else complete=false
                    }
                }
                "read_project_file"->{
                    val hostPath=path((source["path"] as? JsonPrimitive)?.takeIf{it.isString}?.content)
                    hostPath?.let{queried+=it}
                    require(source.flag("truncated")==false&&source.flag("partial")!=true)
                    returned+=fileSnapshot(source,call)
                }
            }
        }.onFailure{complete=false}
        if(!complete)incomplete+=queried
        return RequiredReadbackPolicy.Read(binding,index,call.name,returned.toList(),complete,incomplete.toSet(),queried.toSet())
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
