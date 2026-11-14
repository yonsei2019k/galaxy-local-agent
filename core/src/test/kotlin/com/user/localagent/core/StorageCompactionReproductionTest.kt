package com.user.localagent

import com.user.localagent.core.compactJsonData
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Diagnostic reproduction, not a desired-behavior regression: mirrors current host field ORDER. */
class StorageCompactionReproductionTest {
    @Test fun currentGenericPreviewDropsDuplicateGroupsAndPotentialBytesButMarksPartial(){
        val source=buildJsonObject{
            put("status","ok");put("scope","selected_project_only");put("path","");put("readOnly",true)
            put("untrustedSourceData",true);put("partial",false);put("enumerationComplete",true)
            put("issues",JsonArray(emptyList()));put("observedFiles",3);put("observedDirectories",0);put("visitedDirectories",1)
            put("limits",buildJsonObject{put("depth",4);put("entries",500);put("entriesPerDirectory",100);put("hashBytesPerFile",16_777_216);put("hashBytesTotal",67_108_864)})
            put("filesChanged",false);put("deletedFiles",0);put("movedFiles",0)
            put("comparison","exact_bytes_at_observation");put("hashedFiles",3);put("verifiedHashBytes",12);put("hashBudgetChargedBytes",15);put("skippedHashCandidates",0)
            put("duplicateGroups",buildJsonArray{add(buildJsonObject{
                put("sha256","0".repeat(64));put("bytesEach",4);put("observedCopies",2)
                put("paths",buildJsonArray{add("demo-a.bin");add("demo-b.bin")});put("pathsOmitted",0)
            })})
            put("observedDuplicateGroups",1);put("duplicateGroupsOmitted",0);put("duplicatePathsOmitted",0)
            put("potentialDuplicateBytes",4);put("visualSimilarityAnalyzed",false);put("doesNotAuthorizeDeletion",true);put("concurrentChangesNotExcluded",true)
        }
        assertTrue(source.containsKey("potentialDuplicateBytes"))
        val wrapped=Json.parseToJsonElement(compactJsonData(source.toString(),1100)).jsonObject
        assertTrue(wrapped.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(wrapped.getValue("sourceScopePartial").jsonPrimitive.boolean)
        val data=wrapped.getValue("data").jsonObject
        assertFalse(data.containsKey("duplicateGroups"))
        assertFalse(data.containsKey("observedDuplicateGroups"))
        assertFalse(data.containsKey("potentialDuplicateBytes"))
        assertTrue(wrapped.toString().length<=1100)
    }
}
