package com.user.localagent

import com.user.localagent.core.HarnessStep
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.JsonPrimitive

/** Test-only oracle over actual post-run reads. Never edits a proposal or grants tool authority. */
object QaStorageGoalGradeV2 {
    private val allowed=setOf("inspect_project_storage","find_project_duplicates","save_note")
    data class Note(val title:String,val body:String)
    data class Snapshot(
        val status:String?,val pcSeen:Boolean,val allGenerationsPc:Boolean,
        val steps:List<HarnessStep>,val saveApprovals:Int,
        val notes:List<Note>,val noteReadbackMatches:Boolean,
        val expectedTitle:String,val expectedBody:String,
        val filePaths:List<String>,val hashes:Map<String,String>,val expectedHashes:Map<String,String>,
        val finalBody:String,
    )
    data class Assessment(val checks:Map<String,Boolean>,val legacyApiRecipeMet:Boolean,val inspectCalls:Int) {
        val goalMetBeforeCleanup:Boolean get()=checks.values.all{it}
    }
    fun assess(s:Snapshot):Assessment {
        val verified=s.steps.filter{it.evidence.state==VerificationState.VERIFIED}
        val effective=verified.filterNot{it.evidence.effectNotApplied}
        val note=s.notes.singleOrNull()
        return Assessment(linkedMapOf(
            "POST_STORAGE_V2_COMPLETED" to (s.status=="completed"),
            "POST_STORAGE_V2_PC_ROUTE" to (s.pcSeen&&s.allGenerationsPc),
            "POST_STORAGE_V2_ALLOWED_TOOLS" to s.steps.all{it.tool in allowed},
            "POST_STORAGE_V2_NO_UNVERIFIED" to s.steps.none{it.evidence.state in setOf(VerificationState.PREPARED,VerificationState.UNVERIFIED)},
            "POST_STORAGE_V2_FIND_THEN_SAVE" to sequence(effective.map{it.tool},listOf("find_project_duplicates","save_note")),
            "POST_STORAGE_V2_PROJECT_ROOT" to s.steps.filter{it.tool in setOf("inspect_project_storage","find_project_duplicates")}.all{
                val path=it.arguments["path"]
                path==null||(path is JsonPrimitive&&path.isString&&path.content.isBlank())
            },
            "POST_STORAGE_V2_SINGLE_SAVE" to (verified.count{it.tool=="save_note"}==1&&effective.count{it.tool=="save_note"}==1&&s.saveApprovals==1),
            "POST_STORAGE_V2_NOTE_COUNT" to (s.notes.size==1),
            "POST_STORAGE_V2_NOTE_TITLE" to (note?.title==s.expectedTitle),
            "POST_STORAGE_V2_EXACT_FOUR_LINES" to (note!=null&&s.expectedBody.split('\n').size==4&&note.body==s.expectedBody&&note.body.split('\n').size==4),
            "POST_STORAGE_V2_NOTE_READBACK" to (note!=null&&s.noteReadbackMatches),
            "POST_STORAGE_V2_EXACT_FILE_PATHS" to (s.expectedHashes.size==3&&s.filePaths.size==3&&s.filePaths.toSet()==s.expectedHashes.keys),
            "POST_STORAGE_V2_FIXTURE_HASHES" to (s.hashes==s.expectedHashes),
            "POST_STORAGE_V2_FINAL_BODY" to s.finalBody.contains(s.expectedBody),
        ),legacyApiRecipeMet(s.steps),
            s.steps.count{it.tool=="inspect_project_storage"})
    }
    fun legacyApiRecipeMet(steps:List<HarnessStep>):Boolean=sequence(
        steps.filter{it.evidence.state==VerificationState.VERIFIED}.map{it.tool},
        listOf("inspect_project_storage","find_project_duplicates","save_note"))
    private fun sequence(actual:List<String>,expected:List<String>):Boolean {
        var cursor=0
        for(tool in actual)if(cursor<expected.size&&tool==expected[cursor])cursor++
        return cursor==expected.size
    }
}
