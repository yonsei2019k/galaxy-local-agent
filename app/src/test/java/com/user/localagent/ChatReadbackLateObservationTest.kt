package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Synthetic receipts only. Also compiles against the unchanged1062 API to reproduce the defect. */
class ChatReadbackLateObservationTest {
    private val binding=RequiredReadbackPolicy.Binding("qa-run","qa-scope","qa-workspace")
    private fun out(tool:String,args:JsonObject,body:String)=ObservedToolOutcome(ToolCall("qa-${tool}-${body.hashCode()}",tool,args),
        ToolResult("qa-${tool}-${body.hashCode()}",true,body,VerificationState.VERIFIED))
    private fun assess(tools:Set<String>,vararg results:ObservedToolOutcome)=ChatReadbackEvidence.assess(binding,tools,tools,
        results.map{HarnessStep(it.call,StepEvidence(it.call.name,VerificationState.VERIFIED,"QA host"))},results.toList(),8)
    private fun note(version:Long=1,clipped:Boolean=false)=buildJsonObject{
        put("id","qa-note");put("title","QA title");put("collection","qa-team");put("text",if(clipped)"partial"else"QA body");put("updatedAt",version);put("truncated",clipped)
    }
    private fun save()=out("save_note",buildJsonObject{put("title","QA title");put("content","QA body");put("scope","qa-team")},
        buildJsonObject{put("status","ok");put("id","qa-note");put("storedAndReadBack",true);put("note",note())}.toString())
    private fun search(vararg rows:JsonObject,query:String="QA title")=out("search_notes",buildJsonObject{put("query",query);put("scope","qa-team")},
        buildJsonObject{put("status","ok");put("details",buildJsonObject{put("notes",JsonArray(rows.toList()))})}.toString())
    private val notes=setOf("save_note","search_notes")
    @Test fun goodThenSameTargetClippedNewVersionCannotKeepOldProof(){
        assertFalse(assess(notes,save(),search(note()),search(note(2,true))).readbackSatisfied)
    }
    @Test fun goodThenSameTargetMalformedRowCannotKeepOldProof(){
        val malformed=JsonObject(note(2).filterKeys{it!="text"})
        assertFalse(assess(notes,save(),search(note()),search(malformed)).readbackSatisfied)
    }
    @Test fun emptySameTypedQueryInvalidatesEarlierTargetButDifferentQueryDoesNot(){
        assertFalse(assess(notes,save(),search(note()),search()).readbackSatisfied)
        assertTrue(assess(notes,save(),search(note()),search(query="unrelated exact query")).readbackSatisfied)
    }
    @Test fun unrelatedLaterClippedTargetDoesNotEraseActualTargetProof(){
        val other=JsonObject(note(2,true)+mapOf("id" to JsonPrimitive("qa-other")))
        assertTrue(assess(notes,save(),search(note()),search(other,query="unrelated exact query")).readbackSatisfied)
    }
    @Test fun laterFullSameVersionRestoresProofAndLaterChangedVersionStillBlocks(){
        assertTrue(assess(notes,save(),search(note()),search(note(1,true)),search(note())).readbackSatisfied)
        assertFalse(assess(notes,save(),search(note()),search(note(2))).readbackSatisfied)
    }
    private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun fileWrite()=out("write_project_file",buildJsonObject{put("path","qa.txt");put("content","QA body")},
        buildJsonObject{put("path","qa.txt");put("sha256",sha("QA body"));put("verified",true);put("executed",false)}.toString())
    private fun fileRead(clipped:Boolean=false,path:String="qa.txt")=out("read_project_file",buildJsonObject{put("path","qa.txt")},
        buildJsonObject{put("path",path);put("text",if(clipped)"part"else"QA body");put("sha256",sha(if(clipped)"different actual full body"else"QA body"));put("truncated",clipped)}.toString())
    private val files=setOf("write_project_file","read_project_file")
    @Test fun goodThenTruncatedSameFileCannotKeepOldProof(){
        assertFalse(assess(files,fileWrite(),fileRead(),fileRead(true)).readbackSatisfied)
    }
    @Test fun goodThenMalformedSameFileReceiptCannotKeepOldProof(){
        val malformed=fileRead().let{out(it.call.name,it.call.arguments,"{malformed host receipt")}
        assertFalse(assess(files,fileWrite(),fileRead(),malformed).readbackSatisfied)
    }
    @Test fun hostFilePathMismatchDoesNotBecomeForeignOrOldProof(){
        assertFalse(assess(files,fileWrite(),fileRead(),fileRead(path="foreign.txt")).readbackSatisfied)
    }
    private fun reminder(version:Long=1,listing:Boolean=true)=buildJsonObject{
        put("id","qa-reminder");put("title","QA reminder");put(if(listing)"scope"else"group","qa-team");put("atEpochMillis",100);put("updatedAt",version)
    }
    private fun reminderWrite()=out("create_reminder",buildJsonObject{put("title","QA reminder")},
        buildJsonObject{put("status","ok");put("id","qa-reminder");put("storedAndReadBack",true);put("queueVerified",true);put("reminder",reminder(listing=false))}.toString())
    private fun list(row:JsonObject=reminder(),clipped:Boolean=false)=out("list_reminders",buildJsonObject{put("scope","qa-team")},
        buildJsonObject{put("status","ok");put("truncated",clipped);put("reminders",buildJsonArray{add(row)})}.toString())
    private val reminders=setOf("create_reminder","list_reminders")
    @Test fun goodThenTruncatedReminderListInvalidatesItsObservedTarget(){
        assertFalse(assess(reminders,reminderWrite(),list(),list(reminder(2),true)).readbackSatisfied)
    }
    @Test fun goodThenMalformedReminderRowCannotKeepOldProof(){
        val malformed=JsonObject(reminder(2).filterKeys{it!="atEpochMillis"})
        assertFalse(assess(reminders,reminderWrite(),list(),list(malformed)).readbackSatisfied)
    }
    @Test fun missingOriginalMutationReceiptStaysUnproved(){
        val old=HarnessStep(save().call,StepEvidence("save_note",VerificationState.VERIFIED,"legacy summary"))
        val read=search(note())
        val assessment=ChatReadbackEvidence.assess(binding,notes,notes,listOf(old,HarnessStep(read.call,StepEvidence("search_notes",VerificationState.VERIFIED,"host"))),listOf(read),8)
        assertFalse(assessment.readbackSatisfied)
    }
    @Test fun equivalentNoteDefaultsAndRuntimeLimitCannotEvadeKnownCoverage(){
        fun args(scope:String="",limit:Int=5)=buildJsonObject{put("limit",limit);put("scope",scope);put("query","QA title")}
        val initial=search(note()).let{it.copy(call=it.call.copy(arguments=buildJsonObject{put("query","QA title")}))}
        for(args in listOf(args(),args("   "),args(limit=9))){
            val empty=search().let{it.copy(call=it.call.copy(arguments=args))}
            assertFalse(assess(notes,save(),initial,empty).readbackSatisfied)
        }
    }
    @Test fun equivalentReminderDefaultsCannotEvadeKnownCoverage(){
        val initial=list().let{it.copy(call=it.call.copy(arguments=buildJsonObject{}))}
        val empty=out("list_reminders",buildJsonObject{put("includeCompleted",false);put("limit",20);put("scope","")},buildJsonObject{
            put("status","ok");put("truncated",false);put("reminders",buildJsonArray{})
        }.toString())
        assertFalse(assess(reminders,reminderWrite(),initial,empty).readbackSatisfied)
    }
    @Test fun differentEffectiveFiltersOrQuerySpellingRemainUnrelated(){
        val good=search(note())
        for(args in listOf(buildJsonObject{put("query","QA title");put("scope","other-group")},buildJsonObject{put("query","qa title");put("scope","qa-team")},buildJsonObject{put("query","QA  title");put("scope","qa-team")})){
            val empty=search().let{it.copy(call=it.call.copy(arguments=args))}
            assertTrue(assess(notes,save(),good,empty).readbackSatisfied)
        }
        val otherGroup=out("list_reminders",buildJsonObject{put("scope","other-group")},buildJsonObject{put("status","ok");put("truncated",false);put("reminders",buildJsonArray{})}.toString())
        assertTrue(assess(reminders,reminderWrite(),list(),otherGroup).readbackSatisfied)
    }
    @Test fun malformedEnvelopeWithSameActualTargetCannotKeepOldProofAcrossDifferentQueries(){
        val bad=out("search_notes",buildJsonObject{put("query","different literal")},buildJsonObject{put("details",buildJsonObject{put("notes",buildJsonArray{add(note(2))})})}.toString())
        assertFalse(assess(notes,save(),search(note()),bad).readbackSatisfied)
    }
}
