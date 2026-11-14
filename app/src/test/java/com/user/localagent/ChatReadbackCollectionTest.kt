package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatReadbackCollectionTest {
    private val b=RequiredReadbackPolicy.Binding("qa","qa-scope","qa-workspace")
    private val tools=setOf("save_note","search_notes")
    private fun out(body:JsonObject,args:JsonObject=buildJsonObject{put("query","QA")})=ObservedToolOutcome(ToolCall("qa-call","search_notes",args),ToolResult("qa-call",true,body.toString(),VerificationState.VERIFIED))
    private fun body(id:String="qa-a",truncated:Boolean=false,status:String="ok")=buildJsonObject{
        put("status",status);put("details",buildJsonObject{put("notes",buildJsonArray{add(buildJsonObject{
            put("id",id);put("title","QA");put("collection","qa");put("text",if(truncated)"clip"else"QA body");put("updatedAt",1);put("truncated",truncated)
        })})})
    }
    private fun collect(vararg observed:ObservedToolOutcome)=ChatReadbackEvidence.collect(b,tools,observed.map{HarnessStep(it.call,StepEvidence(it.call.name,VerificationState.VERIFIED,"QA"))},observed.toList())
    @Test fun clippedIdentityHasNoInventedFullStateSnapshot(){
        val read=collect(out(body(truncated=true))).reads.single()
        assertTrue(read.returned.isEmpty());assertEquals(setOf(RequiredReadbackPolicy.Resource("note","qa-a")),read.incompleteTargets)
    }
    @Test fun exactTypedQueryRetainsOnlyObservedIdentityCoverage(){
        val full=out(body());val empty=out(buildJsonObject{put("status","ok");put("details",buildJsonObject{put("notes",buildJsonArray{})})})
        val reads=collect(full,empty).reads
        assertTrue(reads.last().returned.isEmpty());assertEquals(reads.first().returned.map{it.resource}.toSet(),reads.last().queriedTargets)
        val changed=empty.copy(call=empty.call.copy(arguments=buildJsonObject{put("query","Other")}))
        assertTrue(collect(full,changed).reads.last().queriedTargets.isEmpty())
    }
    @Test fun malformedEnvelopeStillRecordsActualIdentityAsIncomplete(){
        val read=collect(out(body(status="bad"))).reads.single()
        assertFalse(read.completeForTarget);assertTrue(RequiredReadbackPolicy.Resource("note","qa-a") in read.incompleteTargets)
    }
    @Test fun misalignedStepAndResultIdCannotCreateHostProof(){
        val result=out(body());val step=HarnessStep(result.call,StepEvidence("search_notes",VerificationState.VERIFIED,"QA"))
        assertFalse(ChatReadbackEvidence.collect(b,tools,listOf(step),listOf(result.copy(result=result.result.copy(id="different")))).aligned)
        assertFalse(ChatReadbackEvidence.collect(b,tools,emptyList(),listOf(result)).aligned)
    }
    @Test fun malformedVerifiedTypedArgumentsFailClosedRatherThanThrow(){
        val bad=out(body(),buildJsonObject{put("query","QA");put("limit","bad")})
        val result=collect(bad)
        assertFalse(result.aligned);assertTrue(result.mutations.isEmpty());assertTrue(result.reads.isEmpty())
    }
    @Test fun durableQuerySignatureUsesHostDefaultsWithoutInterpretingQueryMeaning(){
        fun sig(args:JsonObject)=ChatReadbackEvidence.querySignature("search_notes",args)
        val original=sig(buildJsonObject{put("query","QA text")})
        assertNotNull(original);assertTrue(original!!.matches(Regex("[a-f0-9]{64}")))
        assertEquals(original,sig(buildJsonObject{put("limit",5);put("scope","");put("query","QA text")}))
        assertEquals(original,sig(buildJsonObject{put("scope","   ");put("query","QA text");put("limit",9)}))
        assertNotEquals(original,sig(buildJsonObject{put("query","qa text")}))
        assertNotEquals(original,sig(buildJsonObject{put("query","QA  text")}))
        assertNotEquals(original,sig(buildJsonObject{put("query","QA text");put("scope","actual-group")}))
        assertNull(sig(buildJsonObject{put("query","QA text");put("limit","bad")}))
        assertNull(ChatReadbackEvidence.querySignature("save_note",buildJsonObject{}))
        assertEquals(ChatReadbackEvidence.querySignature("list_reminders",buildJsonObject{}),ChatReadbackEvidence.querySignature("list_reminders",buildJsonObject{put("limit",20);put("scope","");put("includeCompleted",false)}))
        assertNotEquals(ChatReadbackEvidence.querySignature("list_reminders",buildJsonObject{}),ChatReadbackEvidence.querySignature("list_reminders",buildJsonObject{put("includeCompleted",true)}))
        assertNull(ChatReadbackEvidence.querySignature("read_project_file",buildJsonObject{put("path","../qa.txt")}))
    }
}
