package com.user.localagent.automation

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.data.LocalDatabase
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Isolated schema3 SQLCipher DBs. No app container, model, provider, account or user data. */
@RunWith(AndroidJUnit4::class)
class ChatGoalStoreValidation {
    @Test fun queuedGoalCannotBecomeNewTextWithAnOldGoalSnapshot()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val project=repo.chat.createProject("Synthetic queue goal")
        val thread=repo.chat.createThread(project.id)
        var request:com.user.localagent.QueuedChatRequest?=null
        val run=repo.chat.enqueueUserRun(thread.id,thread.activeBranchId,"Synthetic original goal","auto",false){user,record->
            com.user.localagent.QueuedChatRequest(record.id,thread.id,thread.activeBranchId,project.id,
                "Synthetic original goal","auto",false,emptyList(),user.id,goal=goal(thread.id,project.id))
                .also{request=it}.toJson()
        }
        val before=repo.chat.run(run.id)
        assertTrue(runCatching{repo.chat.editQueuedRun(run.id,"Conflicting replacement",request!!.copy(text="Conflicting replacement").toJson())}.isFailure)
        assertEquals(before,repo.chat.run(run.id))
        assertEquals("Synthetic original goal",repo.chat.messages(thread.id,thread.activeBranchId).first().first{it.role=="user"}.text)
    }}

    @Test fun goalsKeepExactScopeRejectStaleEditsAndCannotResurrectDeletedPayload()=runBlocking(Dispatchers.IO){fixture{repo,store,_->
        val a=repo.chat.createProject("Synthetic goal project A");val b=repo.chat.createProject("Synthetic goal project B")
        val ta=repo.chat.createThread(a.id);val tb=repo.chat.createThread(b.id)
        val original=store.save(ta.id,goal(ta.id,a.id))
        assertEquals(original,store.load(ta.id));assertNull(store.load(tb.id))
        assertTrue(runCatching{store.save(ta.id,original.copy(projectId=b.id))}.isFailure)
        val updated=store.save(ta.id,original.copy(outcome="Synthetic revised outcome"))
        assertTrue(updated.updatedAt>original.updatedAt)
        assertTrue(runCatching{store.save(ta.id,original.copy(outcome="Synthetic stale overwrite"))}.isFailure)
        assertEquals(updated,store.load(ta.id))
        assertEquals(updated,ChatGoalStore(repo).load(ta.id))
        store.delete(ta.id);assertNull(store.load(ta.id))
        assertTrue(runCatching{store.save(ta.id,updated)}.isFailure)
        assertNull(store.load(ta.id))
        val fresh=store.save(ta.id,goal(ta.id,a.id))
        assertNotEquals(original.id,fresh.id)
        assertTrue(fresh.updatedAt>0)
        repo.chat.deleteThread(ta.id)
        assertTrue(runCatching{store.save(ta.id,fresh)}.isFailure)
        assertNull(store.load(tb.id))
    }}

    @Test fun verifiedCriteriaRequireActualLinkedAuditAndPreparedOrOtherScopeIsRejected()=runBlocking(Dispatchers.IO){fixture{repo,store,_->
        val a=repo.chat.createProject("Synthetic audit project A");val b=repo.chat.createProject("Synthetic audit project B")
        val ta=repo.chat.createThread(a.id);val tb=repo.chat.createThread(b.id)
        val saved=store.save(ta.id,goal(ta.id,a.id))
        assertTrue(runCatching{store.save(ta.id,saved.copy(criteria=listOf(saved.criteria.single().copy(status=GoalCriterionStatus.VERIFIED,evidence=listOf("tool-run:missing")))))}.isFailure)
        val run=repo.chat.createRun(ta.id,ta.activeBranchId)
        repo.recordToolRun("get_current_datetime",true,"PREPARED · synthetic unverified result",run.id,ta.id)
        val prepared=repo.chat.toolRunsForThread(ta.id).first().single()
        val linked=saved.copy(linkedRunIds=listOf(run.id))
        assertTrue(runCatching{store.save(ta.id,linked.copy(criteria=listOf(linked.criteria.single().copy(status=GoalCriterionStatus.VERIFIED,evidence=listOf(prepared.id)))))}.isFailure)
        repo.recordToolRun("get_current_datetime",true,"VERIFIED · synthetic readback",run.id,ta.id)
        val audit=repo.chat.toolRunsForThread(ta.id).first().first{it.summary.startsWith("VERIFIED")}
        val verified=store.save(ta.id,linked.copy(criteria=listOf(linked.criteria.single().copy(status=GoalCriterionStatus.VERIFIED,evidence=listOf("tool-run:${audit.id}")))))
        assertEquals(GoalCriterionStatus.VERIFIED,store.load(ta.id)?.criteria?.single()?.status)
        assertEquals(GoalStatus.DRAFT,verified.status)
        val other=repo.chat.createRun(tb.id,tb.activeBranchId)
        repo.recordToolRun("get_current_datetime",true,"VERIFIED · synthetic other scope",other.id,tb.id)
        val otherAudit=repo.chat.toolRunsForThread(tb.id).first().single()
        assertTrue(runCatching{store.save(ta.id,verified.copy(linkedRunIds=listOf(other.id),criteria=listOf(verified.criteria.single().copy(evidence=listOf(otherAudit.id)))))}.isFailure)
        assertEquals(verified,store.load(ta.id))
    }}

    @Test fun readOnlyWorkflowMaySaveGoalButManualConfirmationAndCompletionStayExplicit()=runBlocking(Dispatchers.IO){fixture{repo,store,_->
        val project=repo.chat.createProject("Synthetic manual goal project");val thread=repo.chat.createThread(project.id)
        assertFalse(AgentWorkModePolicy.allows(AgentWorkMode.GOAL,"write_project_file"))
        val pending=store.save(thread.id,goal(thread.id,project.id))
        assertTrue(runCatching{store.save(thread.id,pending.copy(status=GoalStatus.COMPLETE))}.isFailure)
        val manual=store.save(thread.id,pending.copy(criteria=pending.criteria.map{it.copy(status=GoalCriterionStatus.USER_CONFIRMED)}))
        assertTrue(manual.completionReady);assertEquals(GoalStatus.DRAFT,manual.status)
        assertEquals(GoalCriterionStatus.USER_CONFIRMED,manual.criteria.single().status)
        assertTrue(manual.criteria.single().evidence.isEmpty())
        val complete=store.save(thread.id,manual.copy(status=GoalStatus.COMPLETE))
        assertEquals(GoalStatus.COMPLETE,complete.status)
        assertTrue(runCatching{store.save(thread.id,complete.copy(outcome="x".repeat(12000)))}.isFailure)
        assertEquals(complete,store.load(thread.id))
    }}

    @Test fun projectMoveRebindsOnlyGoalAndDatabaseAbortRollsBackWithoutLosingOriginal()=runBlocking(Dispatchers.IO){fixture{repo,store,db->
        val a=repo.chat.createProject("Synthetic move source");val b=repo.chat.createProject("Synthetic move target")
        val thread=repo.chat.createThread(a.id);val witness=repo.chat.createThread(a.id)
        val originalScope=repo.forThread(thread.id)
        val note=originalScope.saveNote("Synthetic source-only note","Synthetic project A material")
        val run=repo.chat.createRun(thread.id,thread.activeBranchId)
        val original=store.save(thread.id,goal(thread.id,a.id).copy(
            criteria=listOf(GoalCriterion(text="Synthetic manually checked result",status=GoalCriterionStatus.USER_CONFIRMED)),
            constraints=listOf("Keep the exact synthetic source condition"),linkedRunIds=listOf(run.id),status=GoalStatus.COMPLETE))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER goal_move_qa_abort BEFORE INSERT ON notes WHEN NEW.collection='chat-goals' BEGIN SELECT RAISE(ABORT,'synthetic goal move rollback'); END")
        try{
            assertTrue(runCatching{store.moveThread(thread.id,b.id)}.isFailure)
            assertEquals(a.id,repo.chat.thread(thread.id)?.projectId)
            assertEquals(original,store.load(thread.id))
        }finally{db.openHelper.writableDatabase.execSQL("DROP TRIGGER goal_move_qa_abort")}
        assertEquals(b.id,store.moveThread(thread.id,b.id).projectId)
        val moved=checkNotNull(store.load(thread.id))
        assertEquals(original.id,moved.id);assertEquals(original.outcome,moved.outcome);assertEquals(original.constraints,moved.constraints)
        assertEquals(original.criteria.map{it.text},moved.criteria.map{it.text})
        assertEquals(GoalStatus.DRAFT,moved.status)
        assertEquals(GoalCriterionStatus.PENDING,moved.criteria.single().status)
        assertTrue(moved.linkedRunIds.isEmpty()&&moved.criteria.single().evidence.isEmpty())
        assertEquals(a.id,repo.chat.run(run.id)?.projectId)
        assertTrue(repo.forThread(witness.id).notes.first().any{it.id==note.id&&it.body==note.body})
        assertFalse(repo.forThread(thread.id).notes.first().any{it.id==note.id})
    }}

    private fun goal(threadId:String,projectId:String?)=GoalDefinition(threadId=threadId,projectId=projectId,outcome="Synthetic concrete outcome",criteria=listOf(GoalCriterion(text="Synthetic observable acceptance criterion")))
    private suspend fun fixture(block:suspend(LocalRepository,ChatGoalStore,LocalDatabase)->Unit){
        assumeTrue("goalStoreValidationApproved=true required",InstrumentationRegistry.getArguments().getString("goalStoreValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="chat-goals-qa-${UUID.randomUUID()}.db";val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try{val repo=LocalRepository(db);block(repo,ChatGoalStore(repo),db)}finally{db.close();context.deleteDatabase(name);key.fill(0)}
    }
}
