package com.user.localagent.planning

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EvidenceWriterContextTest {
    private fun observation(abstract:String)=buildJsonObject {
        put("tool","search_publications");put("verified",true)
        put("evidence",buildJsonObject {
            put("status","ok");put("provider","EuropePMC");put("fetchedAt","2026-10-31T00:00:00Z")
            put("sources",buildJsonArray {add(buildJsonObject {
                put("url","https://europepmc.org/article/MED/12345");put("doi","10.1000/example")
                put("title","Observed synthetic study");put("abstractExcerpt",abstract);put("license","cc by")
                put("fullTextFetched",false);put("peerReviewAssessed",false)
            })})
        })
    }
    @Test fun structuredWriterEvidenceRetainsAbstractBeyondTheGenericThreeHundredCharacterCap() {
        val abstract="A".repeat(320)+" end-of-abstract finding"
        val result=EvidenceWriterContext.build(listOf(observation(abstract)),emptyList(),emptySet())
        assertTrue(result.length<=4500)
        val report=Json.parseToJsonElement(result).jsonObject.getValue("observations").jsonArray.single().jsonObject
        val source=report.getValue("sources").jsonArray.single().jsonObject
        assertEquals(abstract,source.getValue("abstractExcerpt").jsonPrimitive.content)
        assertEquals("10.1000/example",source.getValue("doi").jsonPrimitive.content)
        assertEquals("cc by",source.getValue("license").jsonPrimitive.content)
        assertFalse(source.getValue("fullTextFetched").jsonPrimitive.boolean)
    }
    @Test fun boundedJsonKeepsUnicodeValidAndMarksReducedContextAndUnperformedTools() {
        val result=EvidenceWriterContext.build(List(4){observation("한글🙂 ".repeat(1000))},listOf("원문 확인 제한"),setOf("open_url"),maxChars=2000)
        assertTrue(result.length<=2000)
        val parsed=Json.parseToJsonElement(result).jsonObject
        assertTrue(parsed.getValue("sourceScopePartial").jsonPrimitive.boolean)
        assertTrue(parsed.getValue("contextTruncated").jsonPrimitive.boolean)
        assertEquals("open_url",parsed.getValue("unverifiedPlannedTools").jsonArray.single().jsonPrimitive.content)
        assertEquals(result,result.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }
    @Test fun successfulNegativeReferenceLookupRetainsItsMeaningWithoutBecomingAFabricationClaim() {
        val observation=buildJsonObject {put("tool","verify_reference");put("verified",true);put("evidence",buildJsonObject {
            put("status","not_found");put("matchType","not_found");put("meaning","Crossref has no record; this does not prove fabrication.")
            put("sources",JsonArray(emptyList()))
        })}
        val result=EvidenceWriterContext.build(listOf(observation),emptyList(),emptySet())
        assertTrue(result.contains("not_found"));assertTrue(result.contains("does not prove fabrication"))
        assertTrue(Json.parseToJsonElement(result).jsonObject.getValue("dataOnly").jsonPrimitive.boolean)
    }
}
