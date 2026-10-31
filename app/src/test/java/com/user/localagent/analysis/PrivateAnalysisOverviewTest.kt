package com.user.localagent.analysis

import com.user.localagent.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PrivateAnalysisOverviewTest {
    private val ref=PrivateSourceRef("synthetic-scope","synthetic-source",PrivateEvidenceKind.SMS,"a".repeat(64))
    private fun finding(index:Int,text:String="합성 결과 $index")=AnalysisFinding(index.toString(16).padStart(64,'0'),text,
        ValidatedPrivateCitation(ref,"b".repeat(64),index*100,index*100+10,"합성 원문 $index","c".repeat(64)))
    private fun checkpoint(count:Int,text:(Int)->String={"합성 결과 $it"})=PrivateAnalysisCheckpoint(UUID.randomUUID().toString(),ref,
        buildJsonObject {put("kind","sms");put("snapshotId",UUID.randomUUID().toString())},"전체 질문과 변형된 조건을 그대로 보존하는 합성 질문","d".repeat(64),"e".repeat(64),
        processed=(0 until count).map {index->AnalysisChunkResult("b".repeat(64),index*100,index*100+100,listOf(finding(index,text(index))),emptyList())},nextByte=null,sourceExhausted=true)

    @Test fun all48CandidatesAreIndexedAndLateGoalRelevantChoiceReturnsCanonicalFindings()=runBlocking {
        val checkpoint=checkpoint(48);val all=checkpoint.processed.flatMap {it.findings};var calls=0
        val result=PrivateAnalysisOverview(PrivateOverviewDecider {input,schema->
            calls++;val value=Json.parseToJsonElement(input).jsonObject
            assertEquals(checkpoint.question,value.getValue("question").jsonPrimitive.content)
            val candidates=value.getValue("sources").jsonArray
            assertEquals(48,candidates.size);assertEquals("F47",candidates.last().jsonObject.getValue("id").jsonPrimitive.content)
            assertTrue(schema.contains("F47"));"""{"selectedIds":["F47","F3"]}"""
        }).select(checkpoint)
        assertEquals(1,calls);assertTrue(result.verified);assertFalse(result.sampled);assertEquals(48,result.indexedCount);assertEquals(48,result.totalFindings)
        assertSame(all[47],result.selected[0]);assertSame(all[3],result.selected[1]);assertEquals(all[47].citation,result.selected[0].citation)
    }
    @Test fun largeCheckpointUsesStableUniformSampleIncludingBothEndsRatherThanAPrefix()=runBlocking {
        val checkpoint=checkpoint(1000);val observed=mutableListOf<List<String>>()
        val overview=PrivateAnalysisOverview(PrivateOverviewDecider {input,_->
            val value=Json.parseToJsonElement(input).jsonObject
            val aliases=value.getValue("sources").jsonArray.map {it.jsonObject.getValue("id").jsonPrimitive.content}
            observed+=aliases;assertTrue(value.getValue("sampled").jsonPrimitive.boolean)
            """{"selectedIds":["F999"]}"""
        })
        val first=overview.select(checkpoint);val second=overview.select(checkpoint)
        assertEquals(observed[0],observed[1]);assertEquals(48,observed[0].size)
        assertEquals("F0",observed[0].first());assertEquals("F999",observed[0].last())
        val positions=observed[0].map {it.removePrefix("F").toInt()}
        assertTrue(positions.zipWithNext().all {(left,right)->right-left in 21..22})
        assertTrue(first.sampled);assertTrue(first.verified);assertEquals(1000,first.totalFindings);assertEquals(first,second)
        assertSame(checkpoint.processed.last().findings.single(),first.selected.single())
    }
    @Test fun unknownDuplicateWrongTypedOversizedOrExtraSelectionsNeverBecomeImportanceClaims()=runBlocking {
        val checkpoint=checkpoint(8)
        for(raw in listOf(
            """{"selectedIds":["F999"]}""","""{"selectedIds":["F1","F1"]}""",
            """{"selectedIds":["F0","F1","F2","F3","F4"]}""","""{"selectedIds":[1]}""",
            """{"selectedIds":"F0"}""","""{"selectedIds":["F0"],"reason":"invented explanation"}""",
            """{"selectedIds":["F0"],"selectedIds":["F1"]}""","not JSON"
        )) {
            var calls=0
            val result=PrivateAnalysisOverview(PrivateOverviewDecider {_,_->calls++;raw}).select(checkpoint)
            assertEquals(1,calls);assertFalse(result.verified);assertTrue(result.selected.isEmpty());assertEquals(8,result.indexedCount)
        }
    }
    @Test fun modelFailureReturnsUnverifiedEmptyAndCancellationStillPropagates()=runBlocking {
        val checkpoint=checkpoint(2)
        val failed=PrivateAnalysisOverview(PrivateOverviewDecider {_,_->error("synthetic failure")}).select(checkpoint)
        assertFalse(failed.verified);assertTrue(failed.selected.isEmpty())
        try {PrivateAnalysisOverview(PrivateOverviewDecider {_,_->throw CancellationException("synthetic cancel")}).select(checkpoint);fail("cancel swallowed")}
        catch(_:CancellationException){ }
    }
    @Test fun deciderOwnTimeoutIsOptionalFailureButCancelledParentStillStops()=runBlocking {
        val checkpoint=checkpoint(2)
        val timed=PrivateAnalysisOverview(PrivateOverviewDecider {_,_->withTimeout(1){delay(100);"""{"selectedIds":["F0"]}"""}}).select(checkpoint)
        assertFalse(timed.verified);assertTrue(timed.selected.isEmpty())
        try {
            withTimeout(1){PrivateAnalysisOverview(PrivateOverviewDecider {_,_->delay(100);"""{"selectedIds":["F0"]}"""}).select(checkpoint)}
            fail("parent cancellation swallowed")
        }catch(_:TimeoutCancellationException){ }
    }
    @Test fun excerptsAreSurrogateSafeMarkedTruncatedAndNeverReplaceCanonicalQuotes()=runBlocking {
        val checkpoint=checkpoint(1){"가".repeat(39)+"😀"+"후반 원문"}
        val original=checkpoint.processed.single().findings.single()
        val result=PrivateAnalysisOverview(PrivateOverviewDecider {input,_->
            val item=Json.parseToJsonElement(input).jsonObject.getValue("sources").jsonArray.single().jsonObject
            val excerpt=item.getValue("excerpt").jsonPrimitive.content
            assertEquals(39,excerpt.length);assertFalse(excerpt.last().isHighSurrogate())
            assertTrue(item.getValue("truncated").jsonPrimitive.boolean)
            """{"selectedIds":["F0"]}"""
        }).select(checkpoint)
        assertSame(original,result.selected.single());assertEquals(original.text,result.selected.single().text);assertEquals(original.citation.quote,result.selected.single().citation.quote)
    }
    @Test fun emptyFindingsAndInvalidDuplicateInputDoNotSpendAModelCall()=runBlocking {
        var calls=0
        val selector=PrivateAnalysisOverview(PrivateOverviewDecider {_,_->calls++;"""{"selectedIds":[]}"""})
        val empty=selector.select(checkpoint(0));assertTrue(empty.verified);assertEquals(0,empty.totalFindings)
        val valid=checkpoint(2);val duplicate=valid.copy(processed=valid.processed.map {it.copy(findings=listOf(valid.processed.first().findings.single()))})
        assertFalse(selector.select(duplicate).verified);assertEquals(0,calls)
        assertFalse(selector.select(valid.copy(question="q".repeat(2001))).verified);assertEquals(0,calls)
    }
    @Test fun outputToStringOmitsPrivateFindingTextAndQuotes()=runBlocking {
        val privateText="SYNTHETIC-PRIVATE-FINDING"
        val result=PrivateAnalysisOverview(PrivateOverviewDecider {_,_->"""{"selectedIds":["F0"]}"""}).select(checkpoint(1){privateText})
        assertFalse(result.toString().contains(privateText));assertFalse(result.toString().contains("합성 원문"))
    }
}
