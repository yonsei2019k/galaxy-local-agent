package com.user.localagent.workspace

import com.user.localagent.core.ToolCall
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectStorageAuditTest {
    private fun call(name:String="inspect_project_storage",path:String?=null)=ToolCall("qa",name,buildJsonObject {path?.let {put("path",it)}})
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun parse(text:String)=Json.parseToJsonElement(text).jsonObject
    @Test fun metadataReportsObservedSizesWithoutReadingOrChangingFileContents()=runBlocking {
        val files=mapOf("photo.png" to byteArrayOf(1,2,3),"report.txt" to byteArrayOf(1))
        var hashes=0
        val audit=ProjectStorageAudit({path->assertEquals("",path);files.map {(name,bytes)->ProjectFile(name,false,bytes.size.toLong())}}, {_,_->hashes++;error("metadata must not hash")})
        val result=parse(audit.execute(call()))
        assertEquals(2,result.getValue("observedFiles").jsonPrimitive.int);assertEquals(4L,result.getValue("observedKnownBytes").jsonPrimitive.long)
        assertFalse(result.getValue("partial").jsonPrimitive.boolean);assertTrue(result.getValue("readOnly").jsonPrimitive.boolean)
        assertEquals(0,hashes);assertEquals("photo.png",result.getValue("largestFiles").jsonArray.first().jsonObject.getValue("path").jsonPrimitive.content)
        assertFalse(result.getValue("wholePhoneInspected").jsonPrimitive.boolean);assertEquals(0,result.getValue("deletedFiles").jsonPrimitive.int)
    }
    @Test fun onlyActualSameSizeAndHashBecomeDuplicateCandidates()=runBlocking {
        val files=mapOf("a.png" to byteArrayOf(0,1,2),"b.jpg" to byteArrayOf(0,1,2),"c.bin" to byteArrayOf(3,4,5),"solo.txt" to byteArrayOf(6))
        val audit=ProjectStorageAudit({files.map {(name,bytes)->ProjectFile(name,false,bytes.size.toLong())}}, {path,cap->val bytes=files.getValue(path);assertTrue(bytes.size<=cap);ProjectFileDigest(path,bytes.size.toLong(),sha(bytes))})
        val result=parse(audit.execute(call("find_project_duplicates")))
        val groups=result.getValue("duplicateGroups").jsonArray
        assertEquals(1,groups.size);assertEquals(listOf("a.png","b.jpg"),groups.single().jsonObject.getValue("paths").jsonArray.map {it.jsonPrimitive.content})
        assertEquals(3L,result.getValue("potentialDuplicateBytes").jsonPrimitive.long);assertEquals(3,result.getValue("hashedFiles").jsonPrimitive.int)
        assertTrue(result.getValue("doesNotAuthorizeDeletion").jsonPrimitive.boolean);assertFalse(result.getValue("visualSimilarityAnalyzed").jsonPrimitive.boolean)
    }
    @Test fun foreignPathsAndNonImmediateChildrenNeverReachTheHashCallback()=runBlocking {
        var hashes=0
        val audit=ProjectStorageAudit({listOf(ProjectFile("../outside.bin",false,1),ProjectFile("foreign/file.bin",false,1),ProjectFile("root",true,null))},{_,_->hashes++;error("unexpected hash")})
        val result=parse(audit.execute(call("find_project_duplicates", "root")))
        assertTrue(result.getValue("partial").jsonPrimitive.boolean);assertEquals(0,result.getValue("observedFiles").jsonPrimitive.int);assertEquals(0,hashes)
        assertTrue(result.getValue("issues").jsonArray.any {it.jsonPrimitive.content=="invalid_child_path"})
    }
    @Test fun directoryDepthAndTotalLimitsCannotMasqueradeAsWholeProjectCoverage()=runBlocking {
        val depthAudit=ProjectStorageAudit({path->listOf(ProjectFile(if(path.isBlank())"d"else"$path/d",true,null))},{_,_->error("not a file")})
        val depth=parse(depthAudit.execute(call()))
        assertTrue(depth.getValue("partial").jsonPrimitive.boolean);assertEquals(4,depth.getValue("observedDirectories").jsonPrimitive.int)
        val many=ProjectStorageAudit({path->if(path.isBlank())(1..6).map {ProjectFile("d$it",true,null)}else(1..100).map {ProjectFile("$path/f$it",false,1)}},{_,_->error("metadata only")})
        val result=parse(many.execute(call()))
        assertTrue(result.getValue("partial").jsonPrimitive.boolean);assertFalse(result.getValue("enumerationComplete").jsonPrimitive.boolean)
        assertEquals(500,result.getValue("observedFiles").jsonPrimitive.int+result.getValue("observedDirectories").jsonPrimitive.int)
    }
    @Test fun hashBudgetIncludesFailuresAndChangedOrMalformedDigestsAreNotEvidence()=runBlocking {
        val bytes=ProjectStorageAudit.MAX_FILE_BYTES
        var calls=0
        val budget=ProjectStorageAudit({(1..6).map {ProjectFile("f$it.bin",false,bytes)}},{_,_->calls++;throw IllegalStateException("synthetic provider read failure")})
        val bounded=parse(budget.execute(call("find_project_duplicates")))
        assertEquals(3,calls);assertTrue(bounded.getValue("hashBudgetChargedBytes").jsonPrimitive.long<=ProjectStorageAudit.MAX_HASH_BYTES)
        assertTrue(bounded.getValue("partial").jsonPrimitive.boolean);assertTrue(bounded.getValue("duplicateGroups").jsonArray.isEmpty())
        val changed=ProjectStorageAudit({listOf(ProjectFile("a",false,1),ProjectFile("b",false,1))},{path,_->ProjectFileDigest(path,2,"0".repeat(64))})
        val bad=parse(changed.execute(call("find_project_duplicates")))
        assertEquals(0,bad.getValue("hashedFiles").jsonPrimitive.int);assertTrue(bad.getValue("partial").jsonPrimitive.boolean)
        val malformed=ProjectStorageAudit({listOf(ProjectFile("a",false,1),ProjectFile("b",false,1))},{_,_->ProjectFileDigest("other",1,"not-a-sha")})
        assertEquals(0,parse(malformed.execute(call("find_project_duplicates"))).getValue("hashedFiles").jsonPrimitive.int)
    }
    @Test fun cancellationIsPropagatedAndNestedReadFailureIsExplicitlyPartial()=runBlocking {
        val cancelled=ProjectStorageAudit({throw CancellationException("synthetic cancellation")},{_,_->error("no hash")})
        assertTrue(runCatching {cancelled.execute(call())}.exceptionOrNull() is CancellationException)
        val partial=ProjectStorageAudit({path->if(path.isBlank())listOf(ProjectFile("d",true,null))else error("synthetic read failure")},{_,_->error("no hash")})
        val result=parse(partial.execute(call()))
        assertTrue(result.getValue("partial").jsonPrimitive.boolean);assertTrue(result.getValue("issues").jsonArray.any {it.jsonPrimitive.content=="directory_read_failed"})
    }
}
