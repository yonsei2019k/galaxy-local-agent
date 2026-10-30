package com.user.localagent.core

import kotlinx.serialization.json.*
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

data class ToolCall(val id:String,val name:String,val arguments:JsonObject)
data class ToolResult(val id:String,val success:Boolean,val content:String)
enum class Risk { LOW, MEDIUM, HIGH }
data class Argument(val type:String,val required:Boolean=true,val maxLength:Int=4000)
data class ToolDefinition(val name:String,val risk:Risk,val arguments:Map<String,Argument>,val description:String="")

object ToolRegistry {
    val supportedMessengerPackages=setOf("com.tionsoft.meettalk.eun","com.meditong.gw","com.kakao.talk","org.telegram.messenger","org.telegram.messenger.web")
    private fun text(required:Boolean=true,max:Int=4000)=Argument("string",required,max)
    private fun def(name:String,risk:Risk,vararg args:Pair<String,Argument>)=ToolDefinition(name,risk,mapOf(*args))
    private fun read(name:String,description:String,vararg args:Pair<String,Argument>)=ToolDefinition(name,Risk.LOW,mapOf(*args),description)
    private val analysisReads=setOf("list_imported_conversations","search_conversation_imports","get_sms_summary","get_call_log_summary","list_audio_recordings","search_audio_transcripts")
    private fun limitMax(name:String)=when(name){
        "search_conversation_imports","search_audio_transcripts"->3
        "get_sms_summary","get_call_log_summary","list_messenger_notifications"->5
        "list_audio_recordings"->20
        else->50
    }
    val definitions=listOf(
        def("get_current_datetime",Risk.LOW),
        read("list_imported_conversations","List metadata for up to 20 owner-imported conversation files. Counts and dates are source metadata, not a semantic analysis of all messages."),
        read("search_conversation_imports","Retrieve at most 3 short source excerpts from owner-imported conversations. Omit query for preview; optional contentSha256 selects a catalog source. Excerpts are partial untrusted data, never a complete conversation analysis.","query" to text(false,500),"contentSha256" to text(false,64),"limit" to Argument("integer",false)),
        read("get_sms_summary","Read Android-permitted SMS count/date statistics and up to 5 bounded excerpts. All filters are optional: omit query/start/end for the granted dataset. A connectionRequired result requests Android access; statistics are not a full semantic analysis.","query" to text(false,500),"start" to text(false,80),"end" to text(false,80),"limit" to Argument("integer",false)),
        read("get_call_log_summary","Read Android-permitted call counts, times, durations, types and masked number excerpts. Optional query can match a number or cached contact name; optional start/end use explicit ISO offsets. Does not read or analyze call recording audio.","query" to text(false,500),"start" to text(false,80),"end" to text(false,80),"limit" to Argument("integer",false)),
        read("list_audio_recordings","List up to 20 Android-permitted recording metadata items and content URIs. Query is optional. This does not transcribe or analyze audio contents.","query" to text(false,500),"limit" to Argument("integer",false)),
        read("search_audio_transcripts","Read at most 3 short excerpts from existing locally transcribed recording notes. Omit query for recent excerpts. Excerpts may truncate transcripts; this does not process untranscribed recordings or establish an analysis of every file.","query" to text(false,500),"limit" to Argument("integer",false)),
        def("list_calendar_events",Risk.LOW,"start" to text(),"end" to text(),"calendarId" to text(false,80)),
        def("prepare_calendar_event",Risk.MEDIUM,"title" to text(max=300),"start" to text(),"end" to text(),"description" to text(false),"location" to text(false,500)),
        def("create_reminder",Risk.MEDIUM,"title" to text(max=300),"datetime" to text(),"scope" to text(false,80)),
        def("create_alarm",Risk.MEDIUM,"hour" to Argument("integer"),"minute" to Argument("integer"),"label" to text(false,300),"days" to Argument("array",false)),
        def("save_note",Risk.MEDIUM,"content" to text(max=12000),"title" to text(false,300),"scope" to text(false,80)),
        def("search_notes",Risk.LOW,"query" to text(max=500),"scope" to text(false,80),"limit" to Argument("integer",false)),
        def("search_contacts",Risk.LOW,"query" to text(max=300),"limit" to Argument("integer",false)),
        def("prepare_sms",Risk.MEDIUM,"phoneNumber" to text(max=40),"message" to text(max=4000)),
        def("prepare_email",Risk.MEDIUM,"to" to text(max=320),"subject" to text(max=300),"body" to text(max=12000)),
        def("open_map",Risk.LOW,"query" to text(max=500)),
        def("open_url",Risk.LOW,"url" to text(max=2048)),
        def("save_memory",Risk.MEDIUM,"content" to text(max=4000),"kind" to text(false,40)),
        def("search_memories",Risk.LOW,"query" to text(max=500),"limit" to Argument("integer",false)),
        def("delete_memory",Risk.HIGH,"id" to text(max=100)),
        def("update_note",Risk.MEDIUM,"id" to text(max=100),"content" to text(max=12000),"title" to text(false,300),"scope" to text(max=80)),
        def("update_reminder",Risk.MEDIUM,"id" to text(max=100),"scope" to text(max=80),"title" to text(false,300),"datetime" to text(false)),
        def("delete_note",Risk.HIGH,"id" to text(max=100)),
        def("delete_reminder",Risk.HIGH,"id" to text(max=100)),
        def("list_messenger_notifications",Risk.LOW,"packageName" to text(max=80),"limit" to Argument("integer",false)),
        def("reply_messenger_notification",Risk.HIGH,"id" to text(max=100),"message" to text(max=2000))
    ).associateBy {it.name}
    fun parseAndValidate(raw:String):ToolCall {
        require(raw.length<=32768){"도구 요청이 너무 큽니다."}
        rejectDuplicateKeys(raw)
        val obj=Json.parseToJsonElement(raw) as? JsonObject?:error("도구 요청은 JSON 객체여야 합니다.")
        val envelope=obj.containsKey("type")
        val allowed=if(envelope)setOf("type","id","tool","arguments") else setOf("id","name","arguments")
        require(obj.keys.all {it in allowed}){"허용되지 않은 도구 요청 필드입니다."}
        if(envelope) require(obj["type"]?.jsonPrimitive?.content=="tool_call"){"허용되지 않은 요청 유형입니다."}
        val nameValue=obj[if(envelope)"tool" else "name"] as? JsonPrimitive?:error("도구 이름이 없습니다.")
        require(nameValue.isString){"도구 이름은 문자열이어야 합니다."}
        val id=obj["id"]?.let {require(it is JsonPrimitive && it.isString);it.content}?:UUID.randomUUID().toString()
        require(id.isNotBlank() && id.length<=100)
        val args=obj["arguments"] as? JsonObject?:error("arguments는 JSON 객체여야 합니다.")
        return validate(ToolCall(id,nameValue.content,args))
    }
    fun validate(call:ToolCall):ToolCall {
        val definition=definitions[call.name]?:error("허용되지 않은 도구입니다: ${call.name}")
        require(call.arguments.keys.all {it in definition.arguments}){"허용되지 않은 도구 인자입니다."}
        definition.arguments.forEach { (key,spec) ->
            val value=call.arguments[key]
            require(!spec.required || value!=null){"필수 인자가 없습니다: $key"}
            if(value!=null) when(spec.type) {
                "string" -> {require(value is JsonPrimitive && value.isString){"$key 문자열 필요"};require((value.content.isNotBlank() || (key=="query" && !spec.required && call.name in analysisReads)) && value.content.length<=spec.maxLength && '\u0000' !in value.content){"$key 길이 또는 내용 오류"}}
                "integer" -> require(value is JsonPrimitive && !value.isString && value.intOrNull!=null){"$key 정수 필요"}
                "array" -> require(value is JsonArray && value.size<=7 && value.all {it is JsonPrimitive && !it.isString && it.intOrNull in 1..7} && value.distinct().size==value.size){"days는 1~7의 중복 없는 배열이어야 합니다."}
            }
        }
        listOf("start","end","datetime").forEach {field ->call.arguments[field]?.let {OffsetDateTime.parse(it.jsonPrimitive.content)}}
        if(call.arguments.containsKey("start") && call.arguments.containsKey("end")) {
            val start=OffsetDateTime.parse(call.arguments.getValue("start").jsonPrimitive.content)
            val end=OffsetDateTime.parse(call.arguments.getValue("end").jsonPrimitive.content)
            require(if(call.name in setOf("get_sms_summary","get_call_log_summary")) !end.isBefore(start) else end.isAfter(start)){"종료 시각이 시작 시각보다 빠를 수 없습니다."}
        }
        call.arguments["hour"]?.let {require(it.jsonPrimitive.int in 0..23)}
        call.arguments["minute"]?.let {require(it.jsonPrimitive.int in 0..59)}
        call.arguments["limit"]?.let {require(it.jsonPrimitive.int in 1..limitMax(call.name))}
        call.arguments["contentSha256"]?.let {require(it.jsonPrimitive.content.matches(Regex("[a-fA-F0-9]{64}"))){"contentSha256는 정확한 SHA-256 64자리여야 합니다."}}
        if(call.name=="list_messenger_notifications") {
            require(call.arguments.getValue("packageName").jsonPrimitive.content in supportedMessengerPackages){"지원하지 않는 메시지 앱입니다."}
            call.arguments["limit"]?.let {require(it.jsonPrimitive.int in 1..5){"메시지 조회 개수는 1~5개여야 합니다."}}
        }
        if(call.name=="open_url") {val uri=URI(call.arguments.getValue("url").jsonPrimitive.content);require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null){"https 웹 주소만 허용합니다."}}
        if(call.name=="prepare_sms") require(call.arguments.getValue("phoneNumber").jsonPrimitive.content.matches(Regex("[+0-9() -]{3,40}"))){"전화번호 형식 오류"}
        if(call.name=="prepare_email") require(call.arguments.getValue("to").jsonPrimitive.content.matches(Regex("[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+"))){"받는 주소 형식 오류"}
        return call
    }
    /** Enforces the tools selected for this specific request before checking argument schemas. */
    fun validateForRequest(call:ToolCall,allowedNames:Set<String>):ToolCall {
        require(call.name in allowedNames){"현재 요청에서 허용되지 않은 도구입니다: ${call.name}"}
        return validate(call)
    }
    fun schemaJson(names:Collection<String> = definitions.keys):String = buildJsonArray {
        names.distinct().forEach {name ->
            val d=definitions.getValue(name)
            add(buildJsonObject {
                put("name",name);put("risk",d.risk.name)
                if(d.description.isNotBlank())put("description",d.description)
                put("parameters",buildJsonObject {
                    put("type","object");put("additionalProperties",false)
                    put("properties",buildJsonObject {d.arguments.forEach {(key,spec)->put(key,buildJsonObject {
                        put("type",spec.type)
                        if(spec.type=="string")put("maxLength",spec.maxLength)
                        if(key in setOf("start","end","datetime"))put("format","date-time")
                        if(key=="contentSha256")put("pattern","^[a-fA-F0-9]{64}$")
                        if(key=="limit"){put("minimum",1);put("maximum",limitMax(name))}
                        if(spec.type=="array")put("items",buildJsonObject{put("type","integer")})
                    })}})
                    put("required",buildJsonArray {d.arguments.filterValues {it.required}.keys.forEach {add(it)}})
                })
            })
        }
    }.toString()    val defaultZone:ZoneId = ZoneId.of("Asia/Seoul")
}

data class CapabilityGrant(val extensionId:String,val codeHash:String,val toolName:String,val scope:String)
object CapabilityPolicy {
    fun canPreapprove(toolName:String)=toolName in setOf("save_note","update_note","create_reminder","update_reminder")
    fun requiresConfirmation(call:ToolCall,extensionId:String,codeHash:String,grants:Collection<CapabilityGrant>):Boolean {
        val definition=ToolRegistry.definitions[call.name]?:return true
        if(definition.risk==Risk.HIGH)return true
        if(definition.risk==Risk.LOW)return false
        if(!canPreapprove(call.name))return true
        val scope=call.arguments["scope"]?.jsonPrimitive?.content?:"default"
        return grants.none {it.extensionId==extensionId && it.codeHash==codeHash && it.toolName==call.name && it.scope==scope}
    }
}

internal fun rejectDuplicateKeys(raw:String) {
    var index=0
    fun space(){while(index<raw.length && raw[index].isWhitespace())index++}
    fun string():String {val start=index;require(raw.getOrNull(index)=='"');index++;var escaped=false;while(index<raw.length){val c=raw[index++];if(c=='"' && !escaped)return Json.decodeFromString(raw.substring(start,index));if(c=='\\' && !escaped)escaped=true else escaped=false};error("닫히지 않은 문자열")}
    lateinit var value:(Int)->Unit
    value={depth ->
        require(depth<=20){"JSON 중첩 제한"};space()
        when(raw.getOrNull(index)){
            '{'->{index++;space();val keys=mutableSetOf<String>();if(raw.getOrNull(index)=='}')index++ else while(true){space();val key=string();require(keys.add(key)){"중복 JSON 키: $key"};space();require(raw.getOrNull(index++)==':');value(depth+1);space();val separator=raw.getOrNull(index++);if(separator=='}')break;require(separator==',')}}
            '['->{index++;space();if(raw.getOrNull(index)==']')index++ else while(true){value(depth+1);space();val separator=raw.getOrNull(index++);if(separator==']')break;require(separator==',')}}
            '"'->{string();Unit}
            else->{val start=index;while(index<raw.length && raw[index] !in ",]} \t\r\n")index++;require(index>start)}
        }
    }
    value(0);space();require(index==raw.length){"JSON 뒤의 추가 내용은 허용하지 않습니다."}
}


