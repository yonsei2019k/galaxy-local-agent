package com.user.localagent.ui

import kotlinx.serialization.json.*

internal data class ReactUiCommand(val action:String,val threadId:String,val branchId:String,val payload:JsonObject)

/** A UI input boundary, never a tool or permission bridge. Unknown commands fail closed. */
internal object ReactBridgePolicy {
    private val actions=setOf("ready","send","draft","selectThread","renameThread","openOffice","dismissOfficeGuide","requestOfficeCalendar","selectModel","stop","steer","attach","removeAttachment","navigate","copy","messageFiles","openPdfEmail","messageMenu","stayPhoto","manageThreads","cancelPreparation","openWaitingApproval","collabRefresh","collabSend","stopGoalWait","reopenOwnerLogin","startVoicePrompt","finishVoicePrompt","cancelVoicePrompt","setCharacter","characterPickerShown","chooseReply","answerGoalQuestion","openVoiceTalk","closeVoiceTalk","listenVoiceTalk","sendVoiceTalk","toggleAnswerSpeech","openBookingPayment","checkBookingPayment","checkBookingHistory")
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
            "copy","messageFiles","openPdfEmail","messageMenu","toggleAnswerSpeech"->setOf("messageId")
            "stayPhoto"->if("kind" in payload)setOf("messageId","index","kind")else setOf("messageId","index")
            "chooseReply"->setOf("runId","index")
            "answerGoalQuestion"->setOf("runId","text")
            "renameThread"->setOf("title")
            "collabSend"->setOf("clientMessageId","text","recipient","bindingEpoch")
            "collabRefresh"->setOf("bindingEpoch")
            "selectThread","selectModel","removeAttachment","openBookingPayment","checkBookingPayment","checkBookingHistory"->setOf("id")
            "stopGoalWait","reopenOwnerLogin"->setOf("runId")
            "stop"->setOf("runId","actionEpoch")
            "steer"->setOf("runId","actionEpoch","text")
            "navigate"->setOf("panel")
            "setCharacter"->setOf("field","value")
            "characterPickerShown"->setOf("epoch")
            else->emptySet()
        }
        require(payload.keys==allowed)
        if(action=="stayPhoto"&&"kind" in payload)require(payload["kind"]==JsonPrimitive("map"))
        if(action=="navigate")require(payload.getValue("panel").jsonPrimitive.content in
            setOf("chat","settings","images","tools","projects","history","collab","capabilities","connections","privacy","contacts","historySearch","services","email","maps"))
        for((key,value) in payload){
            val p=value.jsonPrimitive
            if(key=="index")require(!p.isString&&p.int in 0..(if(action=="chooseReply")3 else 5))
            else if(key in setOf("actionEpoch","bindingEpoch","epoch"))require(!p.isString&&p.long in 0..9007199254740991L)
            else {require(p.isString);require(p.content.length<=if(key=="text")when(action){"steer","answerGoalQuestion"->4000;"collabSend"->8000;else->12000}else 128);require(!p.content.contains('\u0000'))}
        }
        if(action in setOf("steer","collabSend"))require(payload.getValue("text").jsonPrimitive.content.isNotBlank())
        if(action=="setCharacter")require(CharacterPreference.accepts(
            payload.getValue("field").jsonPrimitive.content,payload.getValue("value").jsonPrimitive.content))
        if(action=="renameThread")require(payload.getValue("title").jsonPrimitive.content.let{it.isNotBlank()&&it.none{c->c.isISOControl()}})
        if(action=="collabSend"){
            val id=payload.getValue("clientMessageId").jsonPrimitive.content
            require(java.util.UUID.fromString(id).toString()==id)
            require(payload.getValue("recipient").jsonPrimitive.content in setOf("user","codex","claude","all"))
        }
        ReactUiCommand(action,thread,branch,payload)
    }.getOrNull()
}
