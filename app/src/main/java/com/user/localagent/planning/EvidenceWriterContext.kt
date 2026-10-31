package com.user.localagent.planning

import kotlinx.serialization.json.*

/** Bounded structured evidence, without converting abstracts or DOI records into opaque strings. */
internal object EvidenceWriterContext {
    fun build(observations:List<JsonObject>,limitations:Collection<String>,unverifiedTools:Set<String>,maxChars:Int=4500):String {
        require(maxChars>=1000)
        var excerptLimit=1000
        var sourceLimit=3
        val included=observations.toMutableList()
        var shortened=false
        fun clip(value:String,max:Int):String {
            var end=minOf(value.length,max)
            if(end>0&&end<value.length&&value[end-1].isHighSurrogate()&&value[end].isLowSurrogate())end--
            if(end<value.length)shortened=true
            return value.substring(0,end)
        }
        fun summarize(raw:JsonObject):JsonObject {
            val report=raw["evidence"] as? JsonObject
            return buildJsonObject {
                put("tool",raw.getValue("tool"));put("verified",raw.getValue("verified"));put("untrusted",true)
                if(report==null) {
                    put("text",clip(raw["excerpt"]?.jsonPrimitive?.content.orEmpty(),excerptLimit))
                } else {
                    val keys=listOf("status","provider","fetchedAt","matchType","doi","exactCitationMatch","requiresAssessment","meaning","query","transmittedQuery","hitCount","kind","url","lookupUrl","title","text","truncated","fullTextFetched","peerReviewAssessed")
                    keys.forEach {key->report[key]?.let {value->
                        if(value is JsonPrimitive)put(key,if(value.isString&&key !in setOf("url","lookupUrl"))JsonPrimitive(clip(value.content,if(key=="text")excerptLimit else 350))else value)
                    }}
                    val sources=report["sources"] as? JsonArray
                    if(sources!=null) {
                        if(sources.size>sourceLimit)shortened=true
                        put("sources",buildJsonArray {sources.take(sourceLimit).forEach {item->
                            val source=item as? JsonObject?:return@forEach
                            add(buildJsonObject {
                                listOf("url","title","doi","doiUrl","id","source","pmid","pmcid","date","journal","publisher","type","openAccess","license","fullTextFetched","peerReviewAssessed","abstractTruncated").forEach {key->
                                    source[key]?.let {value->if(value is JsonPrimitive)put(key,if(value.isString&&key !in setOf("url","doiUrl"))JsonPrimitive(clip(value.content,if(key=="title")220 else 300))else value)}
                                }
                                source["abstractExcerpt"]?.jsonPrimitive?.content?.let {put("abstractExcerpt",clip(it,excerptLimit));put("abstractContextPartial",it.length>excerptLimit||source["abstractTruncated"]?.jsonPrimitive?.booleanOrNull==true)}
                                source["snippet"]?.jsonPrimitive?.content?.let {put("snippet",clip(it,excerptLimit));put("kind","search_snippet")}
                                (source["updates"] as? JsonArray)?.let {updates->
                                    if(updates.size>3)shortened=true
                                    put("updates",buildJsonArray {updates.take(3).forEach {update->
                                        val obj=update as? JsonObject?:return@forEach
                                        add(buildJsonObject {listOf("relation","type","label","doi","url").forEach {key->obj[key]?.let {value->if(value is JsonPrimitive)put(key,if(value.isString&&key!="url")JsonPrimitive(clip(value.content,250))else value)}}})
                                    }})
                                }
                                for(key in listOf("authors","publicationTypes"))source[key]?.let {value->
                                    when(value) {
                                        is JsonArray->{if(value.size>4)shortened=true;put(key,JsonArray(value.take(4).map {(it as? JsonPrimitive)?.let {part->JsonPrimitive(clip(part.content,150))}?:JsonNull}))}
                                        is JsonPrimitive->put(key,JsonPrimitive(clip(value.content,250)))
                                        else->Unit
                                    }
                                }
                            })
                        }})
                    }
                }
            }
        }
        fun render():String {
            val results=included.map(::summarize)
            val notes=limitations.take(8).map {clip(it,250)}
            return buildJsonObject {
                put("type","public_research_writer_evidence");put("dataOnly",true);put("sourceScopePartial",shortened||limitations.isNotEmpty()||unverifiedTools.isNotEmpty())
                put("contextTruncated",shortened)
                put("unverifiedPlannedTools",JsonArray(unverifiedTools.map(::JsonPrimitive)))
                put("limitations",buildJsonArray {notes.forEach {add(it)};if(shortened)add("문맥 예산으로 일부 근거 발췌·항목을 줄였습니다. 원래 요청·전체 계획의 조건은 별도 제공됩니다.")})
                put("observations",JsonArray(results))
            }.toString()
        }
        var result=render()
        while(result.length>maxChars) {
            shortened=true
            when {
                excerptLimit>125->excerptLimit/=2
                sourceLimit>1->sourceLimit--
                included.size>1->included.removeAt(0)
                included.isNotEmpty()->included.clear()
                else->error("근거의 필수 메타데이터가 문맥 예산을 넘었습니다.")
            }
            result=render()
        }
        return result
    }
}
