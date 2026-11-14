package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StorageModelDeliveryTest {
    private fun source(extra:JsonObject=buildJsonObject{})=buildJsonObject{
        put("status","ok");put("scope","selected_project_only");put("path","");put("readOnly",true)
        put("untrustedSourceData",true);put("partial",false);put("enumerationComplete",true)
        put("issues",JsonArray(emptyList()));put("observedFiles",6);put("observedDirectories",0);put("visitedDirectories",1)
        put("limits",buildJsonObject{put("depth",4);put("entries",500);put("entriesPerDirectory",100);put("hashBytesPerFile",16_777_216);put("hashBytesTotal",67_108_864)})
        put("filesChanged",false);put("deletedFiles",0);put("movedFiles",0)
        put("comparison","exact_bytes_at_observation");put("hashedFiles",6);put("verifiedHashBytes",24);put("hashBudgetChargedBytes",30);put("skippedHashCandidates",0)
        put("duplicateGroups",buildJsonArray{add(buildJsonObject{
            put("sha256","0".repeat(64));put("bytesEach",4);put("observedCopies",5)
            put("paths",buildJsonArray{repeat(5){add("demo-$it.bin")}});put("pathsOmitted",0)
        })})
        put("observedDuplicateGroups",1);put("duplicateGroupsOmitted",0);put("duplicatePathsOmitted",0)
        put("potentialDuplicateBytes",16);put("visualSimilarityAnalyzed",false);put("doesNotAuthorizeDeletion",true);put("concurrentChangesNotExcluded",true)
        extra.forEach{(key,value)->put(key,value)}
    }
    private fun result(source:JsonObject,max:Int=4096)=Json.parseToJsonElement(StorageModelDelivery.modelResult(source.toString(),max)).jsonObject
    @Test fun smallReceiptKeepsEveryFieldAndMoreThanThreePathsIntact(){
        val original=source();val wrapped=result(original)
        assertEquals(original,wrapped.getValue("data"))
        assertFalse(wrapped.getValue("truncated").jsonPrimitive.boolean)
        assertFalse(wrapped.getValue("sourceScopePartial").jsonPrimitive.boolean)
        assertEquals(setOf("data","truncated","sourceScopePartial","dataOnly"),wrapped.keys)
        assertEquals(5,wrapped.getValue("data").jsonObject.getValue("duplicateGroups").jsonArray.single().jsonObject.getValue("paths").jsonArray.size)
    }
    @Test fun actualSourcePartialSizeCapsSkipsAndOmissionsAreNeverMadeComplete(){
        for(extra in listOf(buildJsonObject{put("partial",true)},buildJsonObject{put("enumerationComplete",false)},
            buildJsonObject{put("sizeTotalCapped",true)},buildJsonObject{put("skippedHashCandidates",2)},
            buildJsonObject{put("duplicateGroupsOmitted",1)},buildJsonObject{put("duplicatePathsOmitted",4)},
            buildJsonObject{put("unknownSizeFiles",1)})){
            val original=source(extra);val wrapped=result(original)
            assertEquals(original,wrapped.getValue("data"))
            assertTrue(wrapped.getValue("sourceScopePartial").jsonPrimitive.boolean)
        }
    }
    @Test fun bigReceiptFallbackIsBoundedAndAlwaysMarksDeliveryLoss(){
        val original=source(buildJsonObject{put("extraLongField","x".repeat(20_000))})
        val wrapped=result(original)
        assertTrue(wrapped.toString().length<=4096)
        assertTrue(wrapped.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(wrapped.getValue("sourceScopePartial").jsonPrimitive.boolean)
        assertEquals(16,wrapped.getValue("data").jsonObject.getValue("potentialDuplicateBytes").jsonPrimitive.int)
        assertEquals(6,wrapped.getValue("data").jsonObject.getValue("observedFiles").jsonPrimitive.int)
    }
    @Test fun constrainedFallbackDoesNotTurnOriginalPartialIntoFalse(){
        val original=source(buildJsonObject{put("partial",true);put("duplicateGroupsOmitted",3)})
        val wrapped=result(original,512)
        assertTrue(wrapped.toString().length<=512)
        assertTrue(wrapped.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(wrapped.getValue("sourceScopePartial").jsonPrimitive.boolean)
    }
    @Test fun malformedUnknownOrClaimedMutationDoesNotBecomeTrustedStorageEvidence(){
        for(raw in listOf("not-json","{\"status\":\"ok\",\"status\":\"ok\"}",
            source(buildJsonObject{put("scope","other")}).toString(),source(buildJsonObject{put("filesChanged",true)}).toString())){
            val wrapped=Json.parseToJsonElement(StorageModelDelivery.modelResult(raw)).jsonObject
            assertEquals(JsonNull,wrapped.getValue("data"));assertTrue(wrapped.getValue("truncated").jsonPrimitive.boolean)
        }
    }
    @Test fun oversizedInputIsNotEchoedAndOutputBudgetIsValidated(){
        val wrapped=Json.parseToJsonElement(StorageModelDelivery.modelResult("x".repeat(131_073))).jsonObject
        assertEquals(JsonNull,wrapped.getValue("data"))
        try{StorageModelDelivery.modelResult(source().toString(),8192);fail("No unbounded output budget")}
        catch(_:IllegalArgumentException){}
    }
    @Test fun inspectSpecificSizeAndExtensionMetadataStayIntactWhenSmall(){
        val original=source(buildJsonObject{put("observedKnownBytes",80);put("unknownSizeFiles",0);put("sizeTotalCapped",false)
            put("largestFiles",buildJsonArray{add(buildJsonObject{put("path","demo.bin");put("bytes",20)})})
            put("extensionGroups",buildJsonArray{add(buildJsonObject{put("extension","bin");put("files",6)})});put("extensionGroupsOmitted",0)})
        assertEquals(original,result(original).getValue("data"))
    }
}
