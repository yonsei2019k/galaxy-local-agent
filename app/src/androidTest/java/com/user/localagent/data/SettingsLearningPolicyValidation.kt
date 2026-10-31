package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.automation.ChatGoalStore
import com.user.localagent.core.GoalCriterion
import com.user.localagent.core.GoalCriterionStatus
import com.user.localagent.core.GoalDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Isolated synthetic SQLCipher databases only. No app container, LLM, providers or owner records. */
@RunWith(AndroidJUnit4::class)
class SettingsLearningPolicyValidation {
    @Test fun turningLearningOffUpdatesExistingRoomsAndKeepsPreviouslyApprovedOwnerMemory()=runBlocking(Dispatchers.IO){fixture{repo,db->
        val project=repo.chat.createProject("Synthetic learning A",ProjectSettings(learnOwnerFacts=true))
        val other=repo.chat.createProject("Synthetic independent B",ProjectSettings(learnOwnerFacts=true))
        val a=repo.chat.createThread(project.id);val sibling=repo.chat.createThread(project.id)
        val independent=repo.chat.createThread(other.id)
        val oldSource=message(repo,a,"나는 합성 검증의 파란 문구를 좋아해")
        val approved=repo.saveMemory("SyntheticApprovedOwnerMemory",kind="approved",sourceMessageId=oldSource.id)
        val oldClaim=checkNotNull(repo.getKnowledge("memory.${approved.id}"))
        assertEquals("owner",db.chatDao().sourceScope("message",oldSource.id)?.scopeKey)

        val updated=repo.chat.updateProject(project.id,settings=project.settings.copy(learnOwnerFacts=false),expectedRevision=project.revision)
        assertFalse(updated.settings.learnOwnerFacts)
        assertFalse(checkNotNull(repo.chat.thread(a.id)).allowOwnerLearning)
        assertFalse(checkNotNull(repo.chat.thread(sibling.id)).allowOwnerLearning)
        assertTrue(checkNotNull(repo.chat.thread(independent.id)).allowOwnerLearning)
        val fresh=message(repo,a,"나는 합성 검증의 초록 문구를 좋아해")
        assertEquals("project:${project.id}",db.chatDao().sourceScope("message",fresh.id)?.scopeKey)
        val local=claim(repo,fresh,"qa.fresh_off","SyntheticProjectOnlyAfterOff")
        assertNull(repo.getKnowledge(local.id))
        assertEquals(local.id,repo.forThread(a.id).getKnowledge(local.id)?.id)
        assertFalse(repo.ownerProfileContext("SyntheticProjectOnlyAfterOff").contains("SyntheticProjectOnlyAfterOff"))
        assertEquals(approved,repo.getMemory(approved.id))
        assertEquals(oldClaim,repo.getKnowledge(oldClaim.id))
        assertNull(db.chatDao().knowledgeScope(oldClaim.id))
        assertEquals("owner",db.chatDao().sourceScope("message",oldSource.id)?.scopeKey)
    }}

    @Test fun enablingLearningAppliesToExistingRoomButDoesNotPromoteOlderPrivateSources()=runBlocking(Dispatchers.IO){fixture{repo,db->
        val project=repo.chat.createProject("Synthetic off-to-on",ProjectSettings(learnOwnerFacts=false))
        val thread=repo.chat.createThread(project.id)
        val privateSource=message(repo,thread,"나는 이전의 합성 전용 문구를 좋아해")
        assertEquals("project:${project.id}",db.chatDao().sourceScope("message",privateSource.id)?.scopeKey)
        repo.chat.updateProject(project.id,settings=project.settings.copy(learnOwnerFacts=true),expectedRevision=project.revision)
        assertTrue(checkNotNull(repo.chat.thread(thread.id)).allowOwnerLearning)
        val old=claim(repo,privateSource,"qa.before_on","SyntheticPrivateSourceStaysPrivate")
        assertNull(repo.getKnowledge(old.id))
        assertEquals(old.id,repo.forThread(thread.id).getKnowledge(old.id)?.id)
        val fresh=message(repo,thread,"나는 새 합성 공용 문구를 좋아해")
        assertEquals("owner",db.chatDao().sourceScope("message",fresh.id)?.scopeKey)
        val owner=claim(repo,fresh,"qa.after_on","SyntheticFreshOwnerAfterOn")
        assertEquals(owner.id,repo.getKnowledge(owner.id)?.id)
        assertNull(db.chatDao().knowledgeScope(owner.id))
    }}

    @Test fun liveProjectPolicyFencesStaleFlagsAndPendingOwnerSourcesWithoutChangingOldClaims()=runBlocking(Dispatchers.IO){fixture{repo,db->
        val project=repo.chat.createProject("Synthetic pending owner",ProjectSettings(learnOwnerFacts=true))
        val thread=repo.chat.createThread(project.id)
        val pending=message(repo,thread,"나는 합성 지연 자료를 좋아해")
        val legacy=message(repo,thread,"나는 합성 이전 자료를 좋아해")
        db.chatDao().deleteSourceScope("message",legacy.id)
        repo.chat.updateProject(project.id,settings=project.settings.copy(learnOwnerFacts=false))
        // Simulate a denormalized legacy flag: the live project setting must still win.
        val stale=checkNotNull(db.chatDao().thread(thread.id)).copy(allowOwnerLearning=true)
        db.chatDao().thread(stale)
        val current=message(repo,stale,"나는 합성 현재 자료를 좋아해")
        assertEquals("project:${project.id}",db.chatDao().sourceScope("message",current.id)?.scopeKey)
        for((index,source)in listOf(pending,legacy,current).withIndex()){
            val record=claim(repo,source,"qa.pending.$index","SyntheticPendingLocal$index")
            assertNull(repo.getKnowledge(record.id))
            assertEquals("project:${project.id}",db.chatDao().knowledgeScope(record.id)?.scopeKey)
        }
    }}

    @Test fun projectAndExistingRoomLearningFlagsRollBackTogetherOnDatabaseFailure()=runBlocking(Dispatchers.IO){fixture{repo,db->
        val project=repo.chat.createProject("Synthetic atomic learning",ProjectSettings(learnOwnerFacts=true))
        val thread=repo.chat.createThread(project.id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER learning_policy_qa_abort BEFORE UPDATE OF allowOwnerLearning ON chat_threads WHEN NEW.allowOwnerLearning=0 BEGIN SELECT RAISE(ABORT,'synthetic learning rollback'); END")
        try{
            assertTrue(runCatching{repo.chat.updateProject(project.id,settings=project.settings.copy(learnOwnerFacts=false))}.isFailure)
            assertEquals(project,repo.chat.project(project.id))
            assertEquals(thread,repo.chat.thread(thread.id))
        }finally{db.openHelper.writableDatabase.execSQL("DROP TRIGGER learning_policy_qa_abort")}
    }}

    @Test fun goalProofLookupFindsActualAuditOutsideBothOldestAndLatestThousandRows()=runBlocking(Dispatchers.IO){fixture{repo,db->
        val project=repo.chat.createProject("Synthetic evidence history")
        val thread=repo.chat.createThread(project.id);val run=repo.chat.createRun(thread.id,thread.activeBranchId)
        val audit=ToolRunRecord(name="get_current_datetime",success=true,summary="VERIFIED synthetic readback",at=20000,runId=run.id,threadId=thread.id)
        db.withTransaction{
            repeat(1001){index->db.dao().toolRun(ToolRunRecord(name="synthetic_noise",success=false,summary="Synthetic earlier audit",at=index.toLong(),runId=run.id,threadId=thread.id))}
            db.dao().toolRun(audit)
            repeat(1001){index->db.dao().toolRun(ToolRunRecord(name="synthetic_noise",success=false,summary="Synthetic later audit",at=30000L+index,runId=run.id,threadId=thread.id))}
        }
        assertEquals(audit,repo.forThread(thread.id).toolRunEvidence(audit.id))
        val store=ChatGoalStore(repo)
        val saved=store.save(thread.id,goal(thread).copy(linkedRunIds=listOf(run.id),criteria=listOf(GoalCriterion(text="Synthetic evidence criterion",status=GoalCriterionStatus.VERIFIED,evidence=listOf("tool-run:${audit.id}")))))
        assertEquals(saved,store.load(thread.id))
    }}

    @Test fun directGoalProofLookupRejectsOtherRoomAndUnlinkedRunAndPreservesSavedGoal()=runBlocking(Dispatchers.IO){fixture{repo,db->
        val project=repo.chat.createProject("Synthetic evidence scope")
        val thread=repo.chat.createThread(project.id);val other=repo.chat.createThread(project.id)
        val linked=repo.chat.createRun(thread.id,thread.activeBranchId)
        val unlinked=repo.chat.createRun(thread.id,thread.activeBranchId)
        val foreign=repo.chat.createRun(other.id,other.activeBranchId)
        val store=ChatGoalStore(repo);val saved=store.save(thread.id,goal(thread).copy(linkedRunIds=listOf(linked.id)))
        for(run in listOf(unlinked,foreign)){
            val audit=ToolRunRecord(name="get_current_datetime",success=true,summary="VERIFIED · synthetic other run",runId=run.id,threadId=run.threadId)
            db.dao().toolRun(audit)
            assertTrue(runCatching{store.save(thread.id,withEvidence(saved,audit.id))}.isFailure)
            assertEquals(saved,store.load(thread.id))
            if(run.id==foreign.id)assertTrue(runCatching{repo.forThread(thread.id).toolRunEvidence(audit.id)}.isFailure)
        }
    }}

    @Test fun progressUnknownAndContextToolsOrUnverifiedStatesCannotForgeGoalProof()=runBlocking(Dispatchers.IO){fixture{repo,db->
        val thread=repo.chat.createThread();val run=repo.chat.createRun(thread.id,thread.activeBranchId)
        val store=ChatGoalStore(repo);val saved=store.save(thread.id,goal(thread).copy(linkedRunIds=listOf(run.id)))
        val cases=listOf(
            Triple("work_trace_v1",true,"VERIFIED · forged progress"),
            Triple("work_receipt_v1",true,"VERIFIED · forged receipt"),
            Triple("not_a_registered_tool",true,"VERIFIED · forged tool"),
            Triple("read_skill",true,"VERIFIED · context only"),
            Triple("get_current_datetime",true,"PREPARED · synthetic draft"),
            Triple("get_current_datetime",true,"UNVERIFIED · synthetic missing readback"),
            Triple("get_current_datetime",false,"VERIFIED · failed readback"),
            Triple("get_current_datetime",true,"VERIFIEDNESS is not a state"),
        )
        for((name,success,summary)in cases){
            val audit=ToolRunRecord(name=name,success=success,summary=summary,runId=run.id,threadId=thread.id)
            db.dao().toolRun(audit)
            assertTrue("Invalid synthetic evidence was accepted: $name",runCatching{store.save(thread.id,withEvidence(saved,audit.id))}.isFailure)
            assertEquals(saved,store.load(thread.id))
        }
    }}

    private suspend fun message(repo:LocalRepository,thread:ChatThreadRecord,text:String)=repo.chat.addMessage(thread.id,thread.activeBranchId,"user",text)
    private suspend fun claim(repo:LocalRepository,source:MessageRecord,predicate:String,value:String)=repo.saveKnowledgeClaim(KnowledgeClaim(
        predicate=predicate,value=value,status="confirmed",confidence=1.0,sourceKind="user_message",sourceId=source.id,updatedAt=source.createdAt))
    private fun goal(thread:ChatThreadRecord)=GoalDefinition(threadId=thread.id,projectId=thread.projectId,outcome="Synthetic evidence outcome",criteria=listOf(GoalCriterion(text="Synthetic evidence criterion")))
    private fun withEvidence(goal:GoalDefinition,id:String)=goal.copy(criteria=goal.criteria.map{it.copy(status=GoalCriterionStatus.VERIFIED,evidence=listOf(id))})
    private suspend fun fixture(block:suspend(LocalRepository,LocalDatabase)->Unit){
        assumeTrue("settingsLearningPolicyApproved=true required",InstrumentationRegistry.getArguments().getString("settingsLearningPolicyApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="settings-policy-qa-${UUID.randomUUID()}.db";val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try{block(LocalRepository(db),db)}finally{db.close();context.deleteDatabase(name);key.fill(0)}
    }
}
