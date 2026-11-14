package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskHarnessPreconditionRecoveryTest {
    private val oldHash="a".repeat(64)
    private val newHash="b".repeat(64)
    private fun goal(vararg tools:String,budget:Int=12)=TaskGoal("관찰한 대상만 수정하고 결과를 확인한다.",listOf("원래 대상과 범위를 보존한다."),budget,tools.toSet())
    private fun fileArgs(path:String="handoff.txt",hash:String?=oldHash,content:String="담당: 운영팀")=buildJsonObject{
        put("path",path);put("content",content);hash?.let{put("expectedSha256",it)}
    }
    private fun step(tool:String,state:VerificationState,args:JsonObject,notApplied:Boolean=false)=
        HarnessStep(tool,args,StepEvidence(tool,state,"호스트 실행 근거",effectNotApplied=notApplied))
    private fun read(path:String="handoff.txt",state:VerificationState=VerificationState.VERIFIED)=
        step("read_project_file",state,buildJsonObject{put("path",path)})
    private fun call(tool:String,args:JsonObject)=ToolCall("next",tool,args)

    @Test fun staleHashCanBeRepairedByFreshReadAndVerifiedWriteToTheSameFile(){
        val task=goal("read_project_file","write_project_file")
        val rejected=step("write_project_file",VerificationState.FAILED,fileArgs(),true)
        val before=listOf(read(),rejected)
        assertTrue(TaskHarness.beforeStep(task,before,call("read_project_file",buildJsonObject{put("path","handoff.txt")})) is HarnessDecision.Advance)
        val refreshed=before+read()
        assertTrue(TaskHarness.beforeStep(task,refreshed,call("write_project_file",fileArgs(hash=newHash))) is HarnessDecision.Advance)
        val repaired=refreshed+step("write_project_file",VerificationState.VERIFIED,fileArgs(hash=newHash))
        assertTrue(TaskHarness.decide(task,repaired,true) is HarnessDecision.Finish)
        assertEquals(VerificationState.FAILED,repaired[1].evidence.state)
    }

    @Test fun verifiedWriteToAnotherPathDoesNotResolveTheRejectedFile(){
        val rejected=step("write_project_file",VerificationState.FAILED,fileArgs(),true)
        val other=step("write_project_file",VerificationState.VERIFIED,fileArgs("other.txt",newHash))
        assertTrue(TaskHarness.decide(goal("write_project_file"),listOf(rejected,other),true) is HarnessDecision.Stop)
    }

    @Test fun onlyLaterVerifiedEvidenceForTheSameRecordAndToolResolvesAnUpdate(){
        val args=buildJsonObject{put("id","note-1");put("content","최신 내용")}
        val rejected=step("update_note",VerificationState.FAILED,args,true)
        val same=step("update_note",VerificationState.VERIFIED,buildJsonObject{put("id","note-1");put("content","관찰 후 수정")})
        val other=step("update_note",VerificationState.VERIFIED,buildJsonObject{put("id","note-2");put("content","관찰 후 수정")})
        val otherTool=step("update_reminder",VerificationState.VERIFIED,buildJsonObject{put("id","note-1");put("title","관찰 후 수정")})
        assertTrue(TaskHarness.decide(goal("update_note"),listOf(rejected,same),true) is HarnessDecision.Finish)
        assertTrue(TaskHarness.decide(goal("update_note"),listOf(rejected,other),true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal("update_note","update_reminder"),listOf(rejected,otherTool),true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal("update_note"),listOf(same,rejected),true) is HarnessDecision.Stop)
    }

    @Test fun reminderUpdateRecoveryMatchesItsActualId(){
        val rejected=step("update_reminder",VerificationState.FAILED,buildJsonObject{put("id","reminder-1");put("title","예전 수정")},true)
        val repaired=step("update_reminder",VerificationState.VERIFIED,buildJsonObject{put("id","reminder-1");put("title","관찰 후 수정")})
        assertTrue(TaskHarness.beforeStep(goal("update_reminder"),listOf(rejected),call("update_reminder",repaired.arguments)) is HarnessDecision.Advance)
        assertTrue(TaskHarness.decide(goal("update_reminder"),listOf(rejected,repaired),true) is HarnessDecision.Finish)
    }

    @Test fun ordinaryFailedOrUnverifiedWritesStillBlockMutationsAndCompletion(){
        listOf(VerificationState.FAILED,VerificationState.UNVERIFIED).forEach{state->
            val failure=step("write_project_file",state,fileArgs(),notApplied=state==VerificationState.UNVERIFIED)
            val repaired=step("write_project_file",VerificationState.VERIFIED,fileArgs(hash=newHash))
            val task=goal("write_project_file")
            assertTrue(TaskHarness.beforeStep(task,listOf(failure),call("write_project_file",repaired.arguments)) is HarnessDecision.Stop)
            assertTrue(TaskHarness.decide(task,listOf(failure,repaired),true) is HarnessDecision.Stop)
        }
    }

    @Test fun preparedOrUnverifiedFollowupDoesNotResolveARejectedWrite(){
        val rejected=step("write_project_file",VerificationState.FAILED,fileArgs(),true)
        listOf(VerificationState.PREPARED,VerificationState.UNVERIFIED).forEach{state->
            val followup=step("write_project_file",state,fileArgs(hash=newHash))
            assertTrue(TaskHarness.decide(goal("write_project_file"),listOf(rejected,followup),true) is HarnessDecision.Stop)
        }
    }

    @Test fun creationDeletionAndExternalFailuresCannotUseTheUpdateRecoveryFlag(){
        listOf("save_note","create_reminder","delete_note","delete_reminder","prepare_sms","send_gmail_message").forEach{tool->
            val failure=step(tool,VerificationState.FAILED,buildJsonObject{put("id","original");put("path","handoff.txt")},true)
            val success=step(tool,VerificationState.VERIFIED,buildJsonObject{put("id","other");put("path","other.txt")})
            assertTrue(TaskHarness.beforeStep(goal(tool),listOf(failure),call(tool,success.arguments)) is HarnessDecision.Stop)
            assertTrue(TaskHarness.decide(goal(tool),listOf(failure,success),true) is HarnessDecision.Stop)
        }
        val creation=step("write_project_file",VerificationState.FAILED,fileArgs(hash=null),true)
        assertTrue(TaskHarness.beforeStep(goal("write_project_file"),listOf(creation),call("write_project_file",fileArgs(hash=newHash))) is HarnessDecision.Stop)
    }

    @Test fun missingMalformedOrOutsideProjectTargetsDoNotEnableRecovery(){
        listOf(buildJsonObject{},buildJsonObject{put("id",1)}).forEach{args->
            val failure=step("update_note",VerificationState.FAILED,args,true)
            assertTrue(TaskHarness.beforeStep(goal("update_note"),listOf(failure),call("update_note",buildJsonObject{put("id","real");put("content","내용")})) is HarnessDecision.Stop)
        }
        listOf(fileArgs("../outside.txt"),fileArgs(hash="not-a-hash")).forEach{args->
            val failure=step("write_project_file",VerificationState.FAILED,args,true)
            assertTrue(TaskHarness.beforeStep(goal("write_project_file"),listOf(failure),call("write_project_file",fileArgs(hash=newHash))) is HarnessDecision.Stop)
        }
    }

    @Test fun recoveryDoesNotGrantAnotherToolOrBypassAnEmptyGoalOrBudget(){
        val rejected=step("write_project_file",VerificationState.FAILED,fileArgs(),true)
        assertTrue(TaskHarness.beforeStep(goal("write_project_file"),listOf(rejected),call("save_note",buildJsonObject{put("content","다른 작업")})) is HarnessDecision.Stop)
        assertTrue(TaskHarness.beforeStep(goal(),listOf(rejected),call("write_project_file",fileArgs(hash=newHash))) is HarnessDecision.Stop)
        assertTrue(TaskHarness.beforeStep(goal("write_project_file",budget=1),listOf(rejected),call("write_project_file",fileArgs(hash=newHash))) is HarnessDecision.Stop)
    }

    @Test fun recoveryKeepsTheEquivalentCallRepeatGuard(){
        val rejected=step("write_project_file",VerificationState.FAILED,fileArgs(),true)
        val decision=TaskHarness.beforeStep(goal("write_project_file"),listOf(rejected),call("write_project_file",fileArgs()))
        assertTrue(decision is HarnessDecision.Stop&&decision.repeated)
    }

    @Test fun readFailureRecoveryRetainsTheExistingRule(){
        val failed=read("missing.txt",VerificationState.FAILED)
        val found=read("report-final.txt")
        assertTrue(TaskHarness.decide(goal("read_project_file"),listOf(failed,found),true) is HarnessDecision.Finish)
        assertTrue(TaskHarness.decide(goal("read_project_file"),listOf(failed),true) is HarnessDecision.Stop)
    }

    @Test fun fullReadRestoredAfterRejectionCanRetryTheUnchangedIntendedEditOnce(){
        val rejected=step("write_project_file",VerificationState.FAILED,fileArgs(),true)
        val refreshed=listOf(rejected,read())
        assertTrue(TaskHarness.beforeStep(goal("read_project_file","write_project_file"),refreshed,call("write_project_file",fileArgs())) is HarnessDecision.Advance)
        assertTrue(TaskHarness.decide(goal("read_project_file","write_project_file"),refreshed+step("write_project_file",VerificationState.VERIFIED,fileArgs()),true) is HarnessDecision.Finish)
        assertTrue(TaskHarness.beforeStep(goal("write_project_file"),listOf(rejected,read("other.txt")),call("write_project_file",fileArgs())) is HarnessDecision.Stop)
    }
    @Test fun preparedExternalActionRemainsPendingAfterARepairedUpdate(){
        val rejected=step("write_project_file",VerificationState.FAILED,fileArgs(),true)
        val repaired=step("write_project_file",VerificationState.VERIFIED,fileArgs(hash=newHash))
        val prepared=step("prepare_sms",VerificationState.PREPARED,buildJsonObject{put("phoneNumber","00000000002");put("message","확인 요청")})
        val decision=TaskHarness.decide(goal("write_project_file","prepare_sms"),listOf(rejected,repaired,prepared),true)
        assertTrue(decision is HarnessDecision.Stop&&decision.pendingExternalCompletion)
    }
}
