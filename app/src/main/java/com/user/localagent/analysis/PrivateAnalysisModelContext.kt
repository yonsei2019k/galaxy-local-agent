package com.user.localagent.analysis

import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.*

/** A model preview of private results. Never feed this back through the generic 1100-character cap. */
object PrivateAnalysisModelContext {
    private val UUID=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val SHA=Regex("[a-f0-9]{64}")
    private val FILE=Regex("artifact-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.txt")

    fun analysisId(result:ToolResult):String?=objectOf(result)?.string("analysisId")?.takeIf(UUID::matches)

    /** Shape only: the host must check the current run/scope, actual private file, bytes and SHA first. */
    fun trustedArtifactToken(result:ToolResult):String?=artifact(result)?.string("fileName")?.let {"[[file:$it]]"}

    fun build(result:ToolResult,maxChars:Int=2200):String {
        require(maxChars in 1600..2200){"개인 분석 문맥은1600~2200자 예산이 필요합니다."}
        val root=objectOf(result)?:return buildJsonObject {
            put("dataOnly",true);put("toolSuccess",result.success);put("verification",result.verification.name)
            put("status","unavailable");put("contextTruncated",true);put("sourceScopePartial",true)
            put("meaning","구조화된 개인 분석 결과를 확인하지 못했습니다. 원문 분석 완료를 주장하지 않습니다.")
        }.toString()
        val artifact=artifact(result)
        var textLimit=140;var quoteLimit=160;var labelLimit=60
        var sourceLimit=12;var analysisLimit=3;var reasonLimit=8;var includeMetadata=true
        var shortened=false
        fun clip(text:String,limit:Int):String {
            var end=minOf(text.length,limit)
            if(end>0&&end<text.length&&text[end-1].isHighSurrogate()&&text[end].isLowSurrogate())end--
            if(end<text.length)shortened=true
            return text.substring(0,end)
        }
        fun primitive(key:String,value:JsonObject=root):JsonPrimitive?=value[key] as? JsonPrimitive
        fun count(key:String,value:JsonObject=root):JsonPrimitive?=primitive(key,value)?.takeIf {!it.isString&&it.longOrNull!=null}
        fun flag(key:String,value:JsonObject=root):JsonPrimitive?=primitive(key,value)?.takeIf {!it.isString&&it.booleanOrNull!=null}
        fun previewFindings():JsonArray {
            val all=root["findings"] as? JsonArray?:return JsonArray(emptyList())
            val total=(root["totalSavedFindings"] as? JsonPrimitive)?.longOrNull?:(root["totalFindings"] as? JsonPrimitive)?.longOrNull
            if(all.size>2||total!=null&&total>minOf(all.size,2))shortened=true
            return buildJsonArray {all.take(2).forEach {item->
                val source=item as? JsonObject?:run {shortened=true;return@forEach}
                val id=source.string("evidenceId")?.takeIf(SHA::matches)?:run {shortened=true;return@forEach}
                add(buildJsonObject {
                    put("evidenceId",id)
                    source.string("finding")?.let {put("finding",clip(it,textLimit))}
                    source.string("quote")?.let {put("quote",clip(it,quoteLimit))}
                    count("startByte",source)?.let {put("startByte",it)};count("endByte",source)?.let {put("endByte",it)}
                    put("excerptTruncated",(source["excerptTruncated"] as? JsonPrimitive)?.booleanOrNull==true||
                        source.string("finding").orEmpty().length>textLimit||source.string("quote").orEmpty().length>quoteLimit)
                    val times=source["timeReferences"] as? JsonArray
                    if(times!=null) {
                        if(times.size>1)shortened=true
                        put("timeReferences",buildJsonArray {times.take(1).forEach {value->
                            val time=value as? JsonObject?:return@forEach
                            add(buildJsonObject {
                                listOf("atEpochMillis","startMillis","endMillis").forEach {key->count(key,time)?.let {put(key,it)}}
                                time.string("meaning")?.let {put("meaning",clip(it,50))}
                            })
                        }})
                    }
                })
            }}
        }
        fun catalogRows(key:String,limit:Int):JsonArray? {
            val rows=root[key] as? JsonArray?:return null
            if(rows.size>limit)shortened=true
            return buildJsonArray {rows.take(limit).forEach {element->
                val row=element as? JsonObject?:run {shortened=true;return@forEach}
                add(buildJsonObject {
                    listOf("sourceType","sourceKind").forEach {name->row.string(name)?.let {put(name,clip(it,30))}}
                    listOf("analysisId","snapshotId","transcriptId","contentSha256","id").forEach {name->
                        row.string(name)?.takeIf {UUID.matches(it)||SHA.matches(it)||it=="sms"||it.startsWith("legacy:")&&UUID.matches(it.removePrefix("legacy:"))}?.let {put(name,it)}
                    }
                    listOf("label","question").forEach {name->row.string(name)?.let {put(name,clip(it,labelLimit))}}
                    listOf("processedChunks","recordCount","chunkCount","recognizedMessages","durationMillis").forEach {name->count(name,row)?.let {put(name,it)}}
                    listOf("requiresPermission","bodyFetched","sourceExhausted","legacySingleNote").forEach {name->flag(name,row)?.let {put(name,it)}}
                })
            }}
        }
        fun render():String {
            val findings=previewFindings()
            val sourceRows=catalogRows("sources",sourceLimit)
            val analysisRows=catalogRows("analyses",analysisLimit)
            val coverage=root["coverage"] as? JsonObject
            val selected=buildJsonObject {
                put("dataOnly",true);put("toolSuccess",result.success);put("verification",result.verification.name)
                root.string("status")?.let {put("status",clip(it,50))}
                root.string("requiredPermission")?.takeIf {it in setOf("android.permission.READ_MEDIA_AUDIO","android.permission.READ_EXTERNAL_STORAGE","android.permission.READ_SMS")}?.let {put("requiredPermission",it)}
                analysisId(result)?.let {put("analysisId",it)}
                flag("analysisCompleteWithinSelectedText")?.let {put("analysisCompleteWithinSelectedText",it)}
                if(coverage!=null)put("coverage",buildJsonObject {
                    listOf("knownTextBytes","processedBytes","retrievedBytes").forEach {key->count(key,coverage)?.let {put(key,it)}}
                    flag("completeSelectedText",coverage)?.let {put("completeSelectedText",it)}
                    (coverage["reasons"] as? JsonArray)?.let {reasons->
                        put("reasons",JsonArray(reasons.take(reasonLimit).mapNotNull {(it as? JsonPrimitive)?.takeIf {part->part.isString&&part.content.matches(Regex("[A-Z_]{1,64}"))}}))
                        if(reasons.size>reasonLimit){shortened=true;put("reasonsOmitted",reasons.size-reasonLimit)}
                    }
                })
                val id=analysisId(result)
                val resumeId=root.string("resumeWithAnalysisId")?.takeIf {UUID.matches(it)&&it==id}
                val resumeVersion=count("resumeExpectedProcessedChunks")?.takeIf {it.intOrNull in 0..10000}
                val canResume=flag("canResumeStoredText")?.booleanOrNull!=false
                root.string("analysisScope")?.let {put("analysisScope",clip(it,40))}
                if(canResume&&resumeId!=null)put("resumeWithAnalysisId",resumeId)
                if(canResume&&resumeVersion!=null)put("resumeExpectedProcessedChunks",resumeVersion)
                val arguments=root["resumeArguments"] as? JsonObject
                if(canResume&&arguments!=null&&arguments.string("analysisId")==id&&id!=null) {
                    count("expectedProcessedChunks",arguments)?.takeIf {it.intOrNull in 0..10000}?.let {version->
                        put("resumeArguments",buildJsonObject {put("analysisId",id);put("expectedProcessedChunks",version)})
                    }
                }
                if(findings.isNotEmpty())put("findings",findings)
                listOf("totalSavedFindings","totalFindings","offset","chunkCount","durationMillis","overviewIndexedFindings","reportFindingCount","reportOmittedFindingCount").forEach {key->count(key)?.let {put(key,it)}}
                listOf("detailedResultsAvailable","hasMore","sourceExhausted","canResumeStoredText","overviewSelectionVerified","overviewIndexSampled","bodyFetched","fullTextPersisted","audioSentToServer","connectionRequired","originalSpeechCompletenessVerified","recognitionAccuracyVerified","wordTimestamps","speakerDiarization","speakerIdentity","reportContainsAllFindings","reportLimitationsTruncated").forEach {key->flag(key)?.let {put(key,it)}}
                if(artifact!=null)put("artifact",buildJsonObject {
                    // Integrity stays in the complete host result; the model only needs the basename.
                    put("fileName",artifact.getValue("fileName"));put("format","txt");put("privateArtifact",true)
                })
                if(sourceRows!=null) {put("sources",sourceRows);count("catalogEntries")?.let {put("catalogEntries",it)};put("catalogPreviewPartial",sourceRows.size<(root["sources"] as JsonArray).size||flag("hasMore")?.booleanOrNull==true)}
                if(analysisRows!=null) {put("analyses",analysisRows);put("analysesPreviewPartial",analysisRows.size<(root["analyses"] as JsonArray).size||flag("analysesMayHaveMore")?.booleanOrNull==true)}
                root.string("transcriptId")?.takeIf(UUID::matches)?.let {put("transcriptId",it)}
                root.string("nextTool")?.takeIf {it=="analyze_audio_transcript"}?.let {put("nextTool",it)}
                listOf("timestampPrecision","completionScope").forEach {key->root.string(key)?.let {put(key,clip(it,80))}}
                if(includeMetadata) {
                    val metadata=root["sourceMetadata"] as? JsonObject
                    if(metadata!=null)put("sourceMetadata",buildJsonObject {
                        listOf("includedRecordCount","providerRecordCount","chunkCount","durationMillis").forEach {key->count(key,metadata)?.let {put(key,it)}}
                        listOf("enumerationComplete","fullIncludedBodies","originalSpeechCompletenessVerified","recognitionAccuracyVerified","wordTimestamps","speakerDiarization","speakerIdentity").forEach {key->flag(key,metadata)?.let {put(key,it)}}
                        listOf("analysisScope","timestampPrecision","bodyFormat").forEach {key->metadata.string(key)?.let {put(key,clip(it,80))}}
                    })
                }
                put("interpretationAccuracyVerified",false);put("wholePhoneHistoryVerified",false)
            }
            return JsonObject(selected+mapOf("contextTruncated" to JsonPrimitive(shortened),"sourceScopePartial" to JsonPrimitive(shortened||
                root.string("status")=="partial"||flag("analysisCompleteWithinSelectedText")?.booleanOrNull==false||
                flag("sourceExhausted")?.booleanOrNull==false||coverage?.let {flag("completeSelectedText",it)}?.booleanOrNull==false||flag("hasMore")?.booleanOrNull==true))).toString()
        }
        var rendered=render()
        while(rendered.length>maxChars) {
            shortened=true
            when {
                textLimit>24||quoteLimit>24->{textLimit=maxOf(24,textLimit/2);quoteLimit=maxOf(24,quoteLimit/2)}
                includeMetadata->includeMetadata=false
                reasonLimit>1->reasonLimit=maxOf(1,reasonLimit/2)
                labelLimit>12->labelLimit=12
                sourceLimit>1->sourceLimit--
                analysisLimit>1->analysisLimit--
                else->error("개인 분석의 식별자·진행·근거 필수 문맥이 예산을 넘었습니다.")
            }
            rendered=render()
        }
        return rendered
    }

    private fun artifact(result:ToolResult):JsonObject? {
        if(!result.success||result.verification !in setOf(VerificationState.VERIFIED,VerificationState.UNVERIFIED))return null
        val root=objectOf(result)?:return null
        if(root.string("status") !in setOf("partial","complete_selected_text")||root.string("analysisId")?.let(UUID::matches)!=true)return null
        val value=root["artifact"] as? JsonObject?:return null
        if(value.string("fileName")?.let(FILE::matches)!=true||value.string("format")!="txt"||value.string("mimeType")!="text/plain"||value.string("sha256")?.let(SHA::matches)!=true)return null
        for(key in listOf("privateArtifact","exportOrShareRequiresUserAction")) {
            val flag=value[key] as? JsonPrimitive?:return null
            if(flag.isString||flag.booleanOrNull!=true)return null
        }
        val size=value["bytes"] as? JsonPrimitive?:return null
        if(size.isString||size.longOrNull !in 1..20L*1024*1024)return null
        return value
    }
    private fun objectOf(result:ToolResult):JsonObject?=runCatching {require(result.content.length<=256*1024);Json.parseToJsonElement(result.content) as? JsonObject}.getOrNull()
    private fun JsonObject.string(key:String)=(get(key) as? JsonPrimitive)?.takeIf {it.isString}?.content
}
