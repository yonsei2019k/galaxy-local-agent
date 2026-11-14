package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ChatReadbackEvidenceTest {
    private val binding=RequiredReadbackPolicy.Binding("run","project","workspace")
    private val noteTools=setOf("save_note","search_notes")
    private fun outcome(tool:String,args:JsonObject,body:JsonObject)=ObservedToolOutcome(
        ToolCall("call-$tool-${body.hashCode()}",tool,args),ToolResult("call-$tool-${body.hashCode()}",true,body.toString(),VerificationState.VERIFIED))
    private fun assess(tools:Set<String>,outcomes:List<ObservedToolOutcome>,prior:List<HarnessStep> = emptyList())=
        ChatReadbackEvidence.assess(binding,tools,tools,prior+outcomes.map{HarnessStep(it.call,StepEvidence(it.call.name,VerificationState.VERIFIED,"host"))},outcomes,8)
    private fun note(id:String="saved-id",version:Long=1)=buildJsonObject{
        put("id",id);put("title","발주");put("collection","team");put("text","40500원");put("updatedAt",version);put("truncated",false)
    }
    private fun save()=outcome("save_note",buildJsonObject{put("title","발주");put("content","40500원");put("scope","team")},buildJsonObject{
        put("status","ok");put("id","saved-id");put("storedAndReadBack",true);put("note",note())
    })
    private fun search(vararg notes:JsonObject)=outcome("search_notes",buildJsonObject{put("query","발주")},buildJsonObject{
        put("status","ok");put("details",buildJsonObject{put("notes",JsonArray(notes.toList()))})
    })
    @Test fun emptyOrWrongTargetSearchCannotCompleteTheActualSavedTarget(){
        assertFalse(assess(noteTools,listOf(save(),search())).readbackSatisfied)
        assertFalse(assess(noteTools,listOf(save(),search(note("different-id")))).readbackSatisfied)
        assertTrue(assess(noteTools,listOf(save(),search(note()))).readbackSatisfied)
    }
    @Test fun readBeforeMutationAndAStaleVersionCannotSatisfyPostWriteReadback(){
        assertFalse(assess(noteTools,listOf(search(note()),save())).readbackSatisfied)
        assertFalse(assess(noteTools,listOf(save(),search(note(version=2)))).readbackSatisfied)
    }
    @Test fun clippedTargetCannotBecomeFullReadProof(){
        val clipped=JsonObject(note()+mapOf("truncated" to JsonPrimitive(true)))
        assertFalse(assess(noteTools,listOf(save(),search(clipped))).readbackSatisfied)
    }
    @Test fun priorMutationSummaryWithoutItsOriginalHostReceiptStaysUnproved(){
        val prior=HarnessStep(save().call,StepEvidence("save_note",VerificationState.VERIFIED,"legacy summary"))
        val result=assess(noteTools,listOf(search(note())),listOf(prior))
        assertFalse(result.readbackSatisfied)
        assertTrue(RequiredReadbackPolicy.Blocker.MISSING_MUTATION_PROOF in result.blockers)
    }
    private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun fileRead(text:String="운영팀",hash:String=sha(text))=outcome("read_project_file",buildJsonObject{put("path","handoff.txt")},buildJsonObject{
        put("path","handoff.txt");put("text",text);put("sha256",hash);put("truncated",false)
    })
    private fun fileWrite()=outcome("write_project_file",buildJsonObject{put("path","handoff.txt");put("content","운영팀")},buildJsonObject{
        put("path","handoff.txt");put("sha256",sha("운영팀"));put("verified",true);put("executed",false)
    })
    @Test fun fileRequiresALaterReadWithTheSamePathAndActualTextHash(){
        val tools=setOf("read_project_file","write_project_file")
        assertFalse(assess(tools,listOf(fileRead("운엉팀"),fileWrite())).readbackSatisfied)
        assertTrue(assess(tools,listOf(fileRead("운엉팀"),fileWrite(),fileRead())).readbackSatisfied)
        assertFalse(assess(tools,listOf(fileWrite(),fileRead("다른 내용",sha("운영팀")))).readbackSatisfied)
    }
    @Test fun reminderGroupAndScheduleAreComparedAcrossActualReceiptShapes(){
        fun record(listing:Boolean,at:Long)=buildJsonObject{
            put("id","reminder-id");put("title","장비 반납");put(if(listing)"scope"else"group","team");put("atEpochMillis",at);put("updatedAt",2)
        }
        val write=outcome("create_reminder",buildJsonObject{put("title","장비 반납")},buildJsonObject{
            put("status","ok");put("id","reminder-id");put("storedAndReadBack",true);put("queueVerified",true);put("reminder",record(false,100))
        })
        fun read(at:Long)=outcome("list_reminders",buildJsonObject{},buildJsonObject{
            put("status","ok");put("truncated",false);put("reminders",buildJsonArray{add(record(true,at))})
        })
        val tools=setOf("create_reminder","list_reminders")
        assertTrue(assess(tools,listOf(write,read(100))).readbackSatisfied)
        assertFalse(assess(tools,listOf(write,read(101))).readbackSatisfied)
    }
}
