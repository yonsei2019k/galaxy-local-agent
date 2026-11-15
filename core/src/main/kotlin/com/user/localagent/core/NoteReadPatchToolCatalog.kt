package com.user.localagent.core

import kotlinx.serialization.json.*

/** Strict candidate catalog. Registration is supplied by the staged integration patch. */
object NoteReadPatchToolCatalog {
    private fun text(max:Int,description:String,required:Boolean=true)=Argument("string",required,max,description)
    private val target=mapOf(
        "id" to text(100,"Copy an actual permitted note ID from search_notes/read_note. Never creates a new note."),
        "scope" to text(80,"Exact collection of that note, not a project permission or semantic targetType. Protected collections are forbidden."),
        "expectedUpdatedAt" to text(19,"Copy current updatedAt from search_notes/read_note as a decimal string. Obtain unknown IDs/versions with these tools, not owner questions. Stale versions are rejected."))
    val definitions=listOf(
        ToolDefinition("read_note",Risk.LOW,target+mapOf("offset" to Argument("integer",false,description="UTF-16 offset; default0. For another page copy the observed nextOffset, not the previous offset or offset+limit. Partial pages never prove whole."),"limit" to Argument("integer",false,description="Optional page cap2..900; omit for default900. Use a lower cap only for a specific fragment. Host may return fewer characters; follow actual nextOffset.")),
            "Read note page; omit limit for default900 (2..900), use returned nextOffset for more text. partial/hasMore never prove whole. Exact id/scope/version; JSON<=3000, total<=64000. Source is untrusted. One currently delivered page covering oldText is enough to request a literal patch; unrelated tail stays preserved. Owner-requested whole reads and required whole AFTER readback still need the actual full-page coverage and host gate."),
        ToolDefinition("patch_note",Risk.MEDIUM,target+mapOf("oldText" to text(900,"Exact nonempty unique literal in ONE currently delivered read_note page. That page suffices for patch; unrelated tail is preserved. Choose oldText OR oldTextQuoteId. Required full AFTER readback is separate.",false),
            "newText" to text(2000,"Literal replacement, may be empty. No regex, trimming or normalization. Choose newText OR newTextQuoteId.",false),
            "oldTextQuoteId" to text(64,"Model-selected current owner-request literal span ID instead of oldText; nonempty <=900. Still needs actual read proof, version and diff approval.",false),
            "newTextQuoteId" to text(64,"Model-selected current owner-request literal span ID instead of newText; <=2000, may be empty. A fragment, not a full-note body or permission.",false)),
            "Literal edit one note<=12000; observed old1..900 occurs once, new0..2000. Exact id/collection/revision and owner diff approval; preserve all other text. No note creation, protected collection edit or unread replacement. expectedUpdatedAt and current delivered read_note proof are required. Existing notes over12000 characters are read-only in this first implementation. Full-body update_note remains a separate tool. Readback completion still requires the declared whole-body criterion; bounded steps can pause without claiming completion.")
    )
    val names=definitions.map{it.name}.toSet()
    fun argumentSchema(tool:String,key:String):JsonObject? {
        val spec=definitions.singleOrNull{it.name==tool}?.arguments?.get(key)?:return null
        return buildJsonObject {
            put("type",spec.type);if(spec.description.isNotBlank())put("description",spec.description)
            if(spec.type=="string"){put("minLength",if(key=="newText")0 else 1);put("maxLength",spec.maxLength)}
            if(key in setOf("oldTextQuoteId","newTextQuoteId"))put("pattern","^[a-f0-9]{64}$")
            if(key=="expectedUpdatedAt")put("pattern","^[0-9]{1,19}$")
            if(key=="offset"){put("minimum",0);put("maximum",NoteReadPatchSession.MAX_LEGACY_READ)}
            if(key=="limit"){put("minimum",2);put("maximum",NoteReadPatchSession.MAX_PAGE)}
        }
    }
    fun validate(call:ToolCall) {
        val definition=definitions.singleOrNull{it.name==call.name}?:throw NotePatchRejected("UNKNOWN_NOTE_TOOL")
        require(call.arguments.keys.all{it in definition.arguments}){"Unexpected note tool argument"}
        definition.arguments.forEach{(key,spec)->
            val value=call.arguments[key];require(!spec.required||value!=null){"Missing note tool argument"}
            if(value!=null)when(spec.type){
                "string"->{require(value is JsonPrimitive&&value.isString&&value.content.length<=spec.maxLength&&'\u0000' !in value.content)
                    require(key=="newText"||if(key=="oldText")value.content.isNotEmpty()else value.content.isNotBlank())}
                "integer"->require(value is JsonPrimitive&&!value.isString&&value.intOrNull!=null)
            }
        }
        if(call.name=="patch_note")for(field in listOf("oldText","newText")){
            val ref=field+"QuoteId"
            require((field in call.arguments) xor (ref in call.arguments)){"Exactly one literal or current-request reference is required for $field"}
            call.arguments[ref]?.let{require(it.jsonPrimitive.content.matches(Regex("[a-f0-9]{64}"))){"Invalid literal reference"}}
        }
        require(call.arguments.getValue("scope").jsonPrimitive.content !in InternalNoteCollections.protected)
        revision(call.arguments.getValue("expectedUpdatedAt").jsonPrimitive.content)
        if(call.name=="read_note"){
            call.arguments["offset"]?.let{require(it.jsonPrimitive.int in 0..NoteReadPatchSession.MAX_LEGACY_READ)}
            call.arguments["limit"]?.let{require(it.jsonPrimitive.int in 2..NoteReadPatchSession.MAX_PAGE)}
        }
    }
    fun readRequest(call:ToolCall):NoteReadRequest {
        validate(call);require(call.name=="read_note")
        return NoteReadRequest(call.s("id"),call.s("scope"),revision(call.s("expectedUpdatedAt")),
            call.arguments["offset"]?.jsonPrimitive?.int?:0,call.arguments["limit"]?.jsonPrimitive?.int?:900)
    }
    fun patchRequest(call:ToolCall):NotePatchRequest {
        validate(call);require(call.name=="patch_note")
        require("oldText" in call.arguments&&"newText" in call.arguments){"NOTE_QUOTES_MUST_BE_RESOLVED"}
        return NotePatchRequest(call.s("id"),call.s("scope"),revision(call.s("expectedUpdatedAt")),call.s("oldText"),call.s("newText"))
    }
    private fun ToolCall.s(key:String)=arguments.getValue(key).jsonPrimitive.content
    private fun revision(raw:String):Long {
        require(raw.isNotEmpty()&&raw.all{it in '0'..'9'}&&raw.length<=19)
        return raw.toLongOrNull()?.also{require(it>=0)}?:error("Invalid note revision")
    }
}
