package com.user.localagent.analysis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

fun interface PrivateOverviewDecider { suspend fun select(input:String,schema:String):String }

/** verified refers only to an actual offered-ID selection, not importance, truth or complete coverage. */
data class OverviewResult(val selected:List<AnalysisFinding>,val indexedCount:Int,val totalFindings:Int,val sampled:Boolean,val verified:Boolean) {
    override fun toString()="OverviewResult(selectedCount=${selected.size},indexedCount=$indexedCount,totalFindings=$totalFindings,sampled=$sampled,verified=$verified,privateFindingsOmitted=true)"
}

/** One bounded local-model choice from observed findings; never invents or rewrites a finding. */
class PrivateAnalysisOverview(private val decider:PrivateOverviewDecider) {
    suspend fun select(checkpoint:PrivateAnalysisCheckpoint):OverviewResult {
        currentCoroutineContext().ensureActive()
        val total=checkpoint.processed.sumOf {it.findings.size.toLong()}
        if(total>40_000||checkpoint.processed.size>10_000)return OverviewResult(emptyList(),0,total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),true,false)
        val all=checkpoint.processed.flatMap {it.findings}
        val sampled=all.size>MAX_INDEXED
        fun failed(indexed:Int)=OverviewResult(emptyList(),indexed,all.size,sampled,false)
        if(checkpoint.question.isBlank()||checkpoint.question.length>2000||all.map {it.id}.distinct().size!=all.size||
            all.any {it.text.isBlank()||it.text.length>500||it.citation.source!=checkpoint.source})return failed(0)
        if(all.isEmpty())return OverviewResult(emptyList(),0,0,false,true)
        val positions=if(!sampled)all.indices.toList() else (0 until MAX_INDEXED).map {index->
            (index.toLong()*(all.size-1)/(MAX_INDEXED-1)).toInt()
        }
        val offered=positions.associate {index->"F$index" to all[index]}
        val input=buildJsonObject {
            put("question",checkpoint.question)
            put("purpose","select_up_to_four_existing_goal_relevant_findings_without_rewriting")
            put("indexedCount",offered.size);put("totalFindings",all.size);put("sampled",sampled)
            put("excerptMaxChars",MAX_EXCERPT_CHARS);put("untrustedFindingText",true)
            put("sources",buildJsonArray {offered.forEach {(alias,finding)->add(buildJsonObject {
                val excerpt=excerpt(finding.text)
                put("id",alias);put("excerpt",excerpt);put("truncated",excerpt.length<finding.text.length)
            })}})
        }.toString()
        val schema=buildJsonObject {
            put("type","object");put("additionalProperties",false)
            put("properties",buildJsonObject {put("selectedIds",buildJsonObject {
                put("type","array");put("maxItems",4);put("uniqueItems",true)
                put("items",buildJsonObject {put("type","string");put("enum",JsonArray(offered.keys.map(::JsonPrimitive)))})
            })})
            put("required",buildJsonArray {add("selectedIds")})
        }.toString()
        return try {
            val raw=decider.select(input,schema)
            currentCoroutineContext().ensureActive()
            require(raw.length in 1..2048&&raw.count {it==':'}==1){"개요 선택은 하나의 필드만 가진 짧은 객체여야 합니다."}
            val value=Json.parseToJsonElement(raw) as? JsonObject?:error("개요 선택 객체 필요")
            require(value.keys==setOf("selectedIds"))
            val array=value.getValue("selectedIds") as? JsonArray?:error("기존 결과 ID 배열 필요")
            require(array.size<=4)
            val aliases=array.map {entry->require(entry is JsonPrimitive&&entry.isString&&entry.content in offered);entry.content}
            require(aliases.distinct().size==aliases.size)
            OverviewResult(aliases.map {offered.getValue(it)},offered.size,all.size,sampled,true)
        }catch(_:TimeoutCancellationException){currentCoroutineContext().ensureActive();failed(offered.size)}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){failed(offered.size)}
    }
    companion object {
        const val MAX_INDEXED=48
        const val MAX_EXCERPT_CHARS=40
        private fun excerpt(text:String):String {
            var end=minOf(text.length,MAX_EXCERPT_CHARS)
            if(end>0&&end<text.length&&text[end-1].isHighSurrogate()&&text[end].isLowSurrogate())end--
            return text.substring(0,end)
        }
    }
}
