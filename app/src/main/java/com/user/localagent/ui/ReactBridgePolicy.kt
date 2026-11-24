package com.user.localagent.ui

import kotlinx.serialization.json.*

internal data class ReactUiCommand(val action:String,val threadId:String,val branchId:String,val payload:JsonObject)

/** A UI input boundary, never a tool or permission bridge. Unknown commands fail closed. */
internal object ReactBridgePolicy {
    private val actions=setOf("ready","send","draft","selectThread","renameThread","openOffice","dismissOfficeGuide","requestOfficeCalendar","selectModel","stop","steer","attach","removeAttachment","navigate","copy","messageMenu","manageThreads","cancelPreparation","openWaitingApproval","collabRefresh","collabSend","saveOfficeProfile","clearOfficeProfile","startVoicePrompt","finishVoicePrompt","cancelVoicePrompt")
    private val officeSeats=setOf("CHAIR","DIRECTOR","ASSOCIATE_GYUMIN","ASSOCIATE_SEOYUL")
    private fun requireOfficeEntries(element:JsonElement){
        val entries=element.jsonObject
        require(entries.keys.all{it in officeSeats})
        for(value in entries.values){
            val entry=value.jsonObject
            require(entry.keys.all{it=="birthDate"||it=="schoolGrade"})
            for((field,raw) in entry){
                val p=raw.jsonPrimitive;require(p.isString)
                require(p.content.none{it.isISOControl()})
                require(p.content.length<=if(field=="birthDate")10 else 20)
            }
        }
    }
    fun parse(raw:String):ReactUiCommand?=runCatching {
        require(raw.toByteArray(Charsets.UTF_8).size<=64*1024)
        val o=Json.parseToJsonElement(raw).jsonObject
        require(o.keys==setOf("version","threadId","branchId","action","payload"))
        val version=o.getValue("version").jsonPrimitive;require(!version.isString&&version.int==1)
        fun text(key:String)=o.getValue(key).jsonPrimitive.also{require(it.isString)}.content
        val action=text("action");require(action in actions)
        val thread=text("threadId");val branch=text("branchId")
        require(thread.length<=128&&branch.length<=128)
        val payload=o.getValue("payload").jsonObject
        val allowed=when(action){
            "send"->setOf("text","submitToken")
            "draft"->setOf("text")
            "copy","messageMenu"->setOf("messageId")
            "renameThread"->setOf("title")
            "collabSend"->setOf("clientMessageId","text","recipient","bindingEpoch")
            "collabRefresh"->setOf("bindingEpoch")
            "selectThread","selectModel","removeAttachment"->setOf("id")
            "stop"->setOf("runId","actionEpoch")
            "steer"->setOf("runId","actionEpoch","text")
            "navigate"->setOf("panel")
            "saveOfficeProfile"->setOf("entries")
            else->emptySet()
        }
        require(payload.keys==allowed)
        if(action=="navigate")require(payload.getValue("panel").jsonPrimitive.content in
            setOf("chat","settings","images","tools","projects","history","collab","capabilities","connections","privacy","contacts","historySearch"))
        if(action=="saveOfficeProfile")requireOfficeEntries(payload.getValue("entries"))
        else for((key,value) in payload){
            val p=value.jsonPrimitive
            if(key in setOf("actionEpoch","bindingEpoch"))require(!p.isString&&p.long in 0..9007199254740991L)
            else {require(p.isString);require(p.content.length<=if(key=="text")when(action){"steer"->4000;"collabSend"->8000;else->12000}else 128);require(!p.content.contains('\u0000'))}
        }
        if(action in setOf("steer","collabSend"))require(payload.getValue("text").jsonPrimitive.content.isNotBlank())
        if(action=="renameThread")require(payload.getValue("title").jsonPrimitive.content.let{it.isNotBlank()&&it.none{c->c.isISOControl()}})
        if(action=="collabSend"){
            val id=payload.getValue("clientMessageId").jsonPrimitive.content
            require(java.util.UUID.fromString(id).toString()==id)
            require(payload.getValue("recipient").jsonPrimitive.content in setOf("user","codex","claude","all"))
        }
        ReactUiCommand(action,thread,branch,payload)
    }.getOrNull()
}
