package com.user.localagent.core

import kotlinx.serialization.json.*
import java.time.OffsetDateTime

/** Explicit local/private effects and typed capabilities; never a natural-language intent router. */
object PrivateAnalysisToolCatalog {
    private fun text(required:Boolean=true,max:Int=100)=Argument("string",required,max)
    private val resume=mapOf("analysisId" to text(false),"expectedProcessedChunks" to Argument("integer",false),"question" to text(false,2000),"maxChunks" to Argument("integer",false))
    val analysisNames=setOf("analyze_sms","analyze_audio_transcript","analyze_conversation_import")
    val definitions=listOf(
        ToolDefinition("list_analysis_sources",Risk.LOW,mapOf("kind" to text(false,32),"limit" to Argument("integer",false)),
            "List bounded metadata and actual source IDs for scoped imported conversations, saved audio transcripts and SMS snapshot capabilities. kind: sms/audio_transcript/conversation_import. Listing is not content analysis and grants no extra access."),
        ToolDefinition("analyze_sms",Risk.LOW,resume+mapOf("start" to text(false,80),"end" to text(false,80)),
            "Analyze a user-requested SMS question using an immutable snapshot of Android-permitted message bodies, not five recent excerpts. New analysis requires question; optional start/end are explicit ISO offsets (end exclusive). Resume with analysisId and expectedProcessedChunks from the actual result. Bounded chunk extraction retains exact source quotations and processed coverage in encrypted checkpoints. No MMS, messenger DB, sending, calendar writes or automatic memory creation.",3_600_000),
        ToolDefinition("analyze_audio_transcript",Risk.LOW,resume+mapOf("transcriptId" to text(false)),
            "Analyze one actual saved transcript selected from list_analysis_sources. New run needs transcriptId and question; resume with analysisId and expectedProcessedChunks from the actual result. Keeps exact transcript quote/time-window references and reports unprocessed text. ASR text is not verified speech, speaker identity or word alignment. Does not transcribe an unprocessed recording; use transcribe_recording first.",3_600_000),
        ToolDefinition("analyze_conversation_import",Risk.LOW,resume+mapOf("contentSha256" to text(false,64)),
            "Analyze the saved text of one owner-imported conversation file by its actual contentSha256 and question. Reads source chunks sequentially with evidence and coverage, not just lexical top matches. Resume with analysisId and expectedProcessedChunks from the actual result. Cannot read app-private messenger history or sources outside the current repository scope.",3_600_000),
        ToolDefinition("read_analysis_evidence",Risk.LOW,mapOf("analysisId" to text(),"evidenceId" to text(false,64),"offset" to Argument("integer",false),"limit" to Argument("integer",false)),
            "Read a saved analysis result page or one exact evidenceId, rechecking the current authorized source/hash and quote span. Omit evidenceId for paged detailed findings (offset0+, limit1..10). Returns source byte/time scope and limitations. A quote match does not verify the model's interpretation; changed/deleted sources stay unavailable.")
    )
    val names=definitions.map {it.name}.toSet()
    val privateReadNames=names
    private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val sha=Regex("[a-fA-F0-9]{64}")

    fun validate(call:ToolCall) {
        val definition=definitions.firstOrNull {it.name==call.name}?:error("등록되지 않은 개인 분석 도구입니다.")
        require(call.arguments.keys.all {it in definition.arguments}){"허용되지 않은 분석 인자입니다."}
        definition.arguments.forEach {(key,spec)->
            val value=call.arguments[key];require(!spec.required||value!=null){"필수 분석 인자가 없습니다: $key"}
            if(value!=null)when(spec.type) {
                "string"->require(value is JsonPrimitive&&value.isString&&value.content.isNotBlank()&&value.content.length<=spec.maxLength&&'\u0000' !in value.content){"분석 문자열 형식 또는 길이 오류"}
                "integer"->require(value is JsonPrimitive&&!value.isString&&value.intOrNull!=null){"분석 정수 인자가 필요합니다."}
            }
        }
        fun string(key:String)=call.arguments[key]?.jsonPrimitive?.content
        if(call.name in analysisNames) {
            val id=string("analysisId")
            if(id!=null) {
                require(uuid.matches(id)){"실제로 반환된 분석 ID가 필요합니다."}
                require(call.arguments.keys.all {it in setOf("analysisId","expectedProcessedChunks","maxChunks")}){"재개에는 반환된 분석 ID·진행 버전을 사용하고 새 질문·출처·기간은 별도 분석으로 시작해주세요."}
                require(call.arguments["expectedProcessedChunks"]?.jsonPrimitive?.int in 0..10_000){"재개에는 실제 반환된 처리 조각 수가 필요합니다."}
            } else {
                require("expectedProcessedChunks" !in call.arguments)
                require(!string("question").isNullOrBlank()){"새 분석에는 현재 사용자의 질문이 필요합니다."}
                if(call.name=="analyze_audio_transcript")require(string("transcriptId")?.let {uuid.matches(it)||it.startsWith("legacy:")&&uuid.matches(it.removePrefix("legacy:"))}==true){"전사 목록의 실제 ID가 필요합니다."}
                if(call.name=="analyze_conversation_import")require(string("contentSha256")?.let(sha::matches)==true){"가져온 대화 목록의 정확한 해시가 필요합니다."}
            }
            call.arguments["maxChunks"]?.let {require(it.jsonPrimitive.int in 1..32){"한 번의 분석은1~32조각입니다. 남은 범위는 재개할 수 있습니다."}}
            listOf("start","end").forEach {key->string(key)?.let(OffsetDateTime::parse)}
            if(string("start")!=null&&string("end")!=null)require(OffsetDateTime.parse(string("end")).isAfter(OffsetDateTime.parse(string("start"))))
        }
        if(call.name=="list_analysis_sources") {
            string("kind")?.let {require(it in setOf("sms","audio_transcript","conversation_import"))}
            call.arguments["limit"]?.let {require(it.jsonPrimitive.int in 1..50)}
        }
        if(call.name=="read_analysis_evidence") {
            require(string("analysisId")?.let(uuid::matches)==true)
            string("evidenceId")?.let {require(sha.matches(it)&&"offset" !in call.arguments&&"limit" !in call.arguments){"하나의 근거 ID 또는 결과 페이지 중 하나를 선택해주세요."}}
            call.arguments["offset"]?.let {require(it.jsonPrimitive.int in 0..100_000)}
            call.arguments["limit"]?.let {require(it.jsonPrimitive.int in 1..10)}
        }
    }

    fun argumentSchema(name:String,key:String):JsonObject?=when {
        name in names&&key=="maxChunks"->range(1,32)
        name in analysisNames&&key=="expectedProcessedChunks"->range(0,10_000)
        name=="read_analysis_evidence"&&key=="offset"->range(0,100_000)
        name=="read_analysis_evidence"&&key=="limit"->range(1,10)
        name=="list_analysis_sources"&&key=="kind"->buildJsonObject {put("type","string");put("enum",JsonArray(listOf("sms","audio_transcript","conversation_import").map(::JsonPrimitive)))}
        else->null
    }
    private fun range(min:Int,max:Int)=buildJsonObject {put("type","integer");put("minimum",min);put("maximum",max)}
}
