package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure oracle regressions, not evidence of Android/provider/model execution. */
class QaStorageGoalGradeV2RegressionTest {
    private val body="파일 수=3\n중복 그룹 수=1\n중복 경로=a.png, b.png\n추가 사본 바이트=73"
    private val hashes=mapOf("a.png" to "a".repeat(64),"b.png" to "a".repeat(64),"c.png" to "c".repeat(64))
    private fun step(name:String,state:VerificationState=VerificationState.VERIFIED,path:JsonElement?=null,effectNotApplied:Boolean=false)=
        HarnessStep(name,buildJsonObject{path?.let{put("path",it)}},StepEvidence(name,state,"synthetic oracle fixture",effectNotApplied=effectNotApplied))
    private fun good()=QaStorageGoalGradeV2.Snapshot("completed",true,true,
        listOf(step("find_project_duplicates"),step("save_note")),1,
        listOf(QaStorageGoalGradeV2.Note("report",body)),true,"report",body,
        hashes.keys.toList(),hashes,hashes,"완료\n"+body)
    private fun assess(s:QaStorageGoalGradeV2.Snapshot)=QaStorageGoalGradeV2.assess(s)
    private fun fails(s:QaStorageGoalGradeV2.Snapshot,key:String){val a=assess(s);assertFalse(key,a.checks.getValue(key));assertFalse(a.goalMetBeforeCleanup)}

    @Test fun actualFindThenSaveCanMeetGoalWithoutLegacyInspectRecipe() {
        val a=assess(good());assertTrue(a.goalMetBeforeCleanup);assertFalse(a.legacyApiRecipeMet);assertEquals(0,a.inspectCalls)
    }
    @Test fun legacyInspectFirstIsRecordedIndependentlyAndDoesNotChangeGoalVerdict() {
        val s=good();val a=assess(s.copy(steps=listOf(step("inspect_project_storage"))+s.steps))
        assertTrue(a.goalMetBeforeCleanup);assertTrue(a.legacyApiRecipeMet);assertEquals(1,a.inspectCalls)
        val late=assess(s.copy(steps=s.steps+step("inspect_project_storage")))
        assertTrue(late.goalMetBeforeCleanup);assertFalse(late.legacyApiRecipeMet);assertEquals(1,late.inspectCalls)
    }
    @Test fun saveBeforeFindOrNoVerifiedFindCannotMeetGoal() {
        fails(good().copy(steps=listOf(step("save_note"),step("find_project_duplicates"))),"POST_STORAGE_V2_FIND_THEN_SAVE")
        fails(good().copy(steps=listOf(step("find_project_duplicates",VerificationState.FAILED),step("save_note"))),"POST_STORAGE_V2_FIND_THEN_SAVE")
    }
    @Test fun pausedOrMissingActualPcGenerationCannotMeetGoal() {
        fails(good().copy(status="paused"),"POST_STORAGE_V2_COMPLETED")
        fails(good().copy(pcSeen=false),"POST_STORAGE_V2_PC_ROUTE")
        fails(good().copy(allGenerationsPc=false),"POST_STORAGE_V2_PC_ROUTE")
    }
    @Test fun unverifiedPreparedOrOutOfScopeCallsCannotMeetGoal() {
        for(state in listOf(VerificationState.UNVERIFIED,VerificationState.PREPARED))
            fails(good().copy(steps=good().steps+step("inspect_project_storage",state)),"POST_STORAGE_V2_NO_UNVERIFIED")
        fails(good().copy(steps=good().steps+step("delete_note")),"POST_STORAGE_V2_ALLOWED_TOOLS")
    }
    @Test fun nonRootAndMalformedStoragePathsCannotMeetGoal() {
        for(path in listOf(JsonPrimitive("other"),JsonPrimitive(7),JsonNull))
            fails(good().copy(steps=listOf(step("find_project_duplicates",path=path),step("save_note"))),"POST_STORAGE_V2_PROJECT_ROOT")
        assertTrue(assess(good().copy(steps=listOf(step("find_project_duplicates",path=JsonPrimitive("")),step("save_note")))).goalMetBeforeCleanup)
    }
    @Test fun duplicateSavesOrApprovalsAndEffectNotAppliedCannotMeetGoal() {
        fails(good().copy(steps=good().steps+step("save_note")),"POST_STORAGE_V2_SINGLE_SAVE")
        fails(good().copy(saveApprovals=2),"POST_STORAGE_V2_SINGLE_SAVE")
        fails(good().copy(saveApprovals=0),"POST_STORAGE_V2_SINGLE_SAVE")
        fails(good().copy(steps=listOf(step("find_project_duplicates"),step("save_note",effectNotApplied=true))),"POST_STORAGE_V2_SINGLE_SAVE")
    }
    @Test fun extraNoteWrongTitleOrMissingProviderReadbackCannotMeetGoal() {
        fails(good().copy(notes=good().notes+QaStorageGoalGradeV2.Note("extra",body)),"POST_STORAGE_V2_NOTE_COUNT")
        fails(good().copy(notes=listOf(QaStorageGoalGradeV2.Note("other",body))),"POST_STORAGE_V2_NOTE_TITLE")
        fails(good().copy(noteReadbackMatches=false),"POST_STORAGE_V2_NOTE_READBACK")
    }
    @Test fun incorrectValuesSpacesCrLfAndExtraLineCannotMeetExactFourLines() {
        for(text in listOf(body.replace("수=3","수=4"),body.replace(", ",","),body.replace("\n","\r\n"),body+"\n"))
            fails(good().copy(notes=listOf(QaStorageGoalGradeV2.Note("report",text))),"POST_STORAGE_V2_EXACT_FOUR_LINES")
    }
    @Test fun extraRenamedMissingOrRepeatedFilePathsCannotMeetGoal() {
        for(paths in listOf(hashes.keys.toList()+"extra.png",listOf("a.png","b.png","renamed.png"),listOf("a.png","b.png"),listOf("a.png","a.png","c.png")))
            fails(good().copy(filePaths=paths),"POST_STORAGE_V2_EXACT_FILE_PATHS")
    }
    @Test fun changedOrMissingOriginalFileBytesCannotMeetGoal() {
        fails(good().copy(hashes=hashes+mapOf("c.png" to "d".repeat(64))),"POST_STORAGE_V2_FIXTURE_HASHES")
        fails(good().copy(hashes=hashes-"b.png"),"POST_STORAGE_V2_FIXTURE_HASHES")
    }
    @Test fun FinalAnswerWithoutActualReportCannotMeetGoal() {
        fails(good().copy(finalBody="저장했어요"),"POST_STORAGE_V2_FINAL_BODY")
    }
}
