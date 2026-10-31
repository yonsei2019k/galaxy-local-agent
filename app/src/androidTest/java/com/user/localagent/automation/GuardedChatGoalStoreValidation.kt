package com.user.localagent.automation

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.data.LocalDatabase
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Isolated encrypted synthetic DB only; no owner DB, app container, model, queue execution or provider. */
@RunWith(AndroidJUnit4::class)
class GuardedChatGoalStoreValidation {
    @Test fun guardedVersionCasRejectsStaleSaveDeleteAndResurrection()=runBlocking(Dispatchers.IO){fixture{repo,store,_->
        val project=repo.chat.createProject("Synthetic guarded goal");val thread=repo.chat.createThread(project.id)
        val scope=repo.chat.accessScope(thread.id)
        val saved=store.saveGuarded(scope,draft(thread.id,project.id))
        assertEquals(saved,store.loadGuarded(scope))
        val revised=store.saveGuarded(scope,saved.copy(outcome="SYNTHETIC_REVISED_OUTCOME"))
        assertTrue(runCatching {store.saveGuarded(scope,saved.copy(outcome="SYNTHETIC_STALE"))}.isFailure)
        assertTrue(runCatching {store.deleteGuarded(scope,saved.id,saved.updatedAt)}.isFailure)
        assertEquals(revised,store.loadGuarded(scope))
        assertTrue(store.deleteGuarded(scope,revised.id,revised.updatedAt));assertNull(store.loadGuarded(scope))
        assertTrue(runCatching {store.saveGuarded(scope,revised)}.isFailure)
        assertTrue(runCatching {store.deleteGuarded(scope,revised.id,revised.updatedAt)}.isFailure)
    }}

    @Test fun fullAccessPolicyChangeWithoutThreadRevisionRejectsOldReadsWritesAndDeletes()=runBlocking(Dispatchers.IO){fixture{repo,store,db->
        val project=repo.chat.createProject("Synthetic goal policy");val thread=repo.chat.createThread(project.id)
        val captured=repo.chat.accessScope(thread.id)
        val saved=store.saveGuarded(captured,draft(thread.id,project.id))
        val before=checkNotNull(db.dao().noteById(ChatGoalStore.noteId(thread.id)))
        repo.chat.updateProject(project.id,settings=project.settings.copy(useOwnerMemory=!captured.useOwnerMemory))
        val current=repo.chat.accessScope(thread.id)
        assertEquals(captured.threadRevision,current.threadRevision);assertNotEquals(captured,current)
        assertTrue(runCatching {store.loadGuarded(captured)}.isFailure)
        assertTrue(runCatching {store.saveGuarded(captured,saved.copy(outcome="SYNTHETIC_NOT_SAVED"))}.isFailure)
        assertTrue(runCatching {store.deleteGuarded(captured,saved.id,saved.updatedAt)}.isFailure)
        assertEquals(before,db.dao().noteById(before.id))
        assertEquals(saved,store.loadGuarded(current))
    }}

    @Test fun managementToolAuditCannotVerifyBusinessGoalCriterion()=runBlocking(Dispatchers.IO){fixture{repo,store,_->
        assertTrue(ToolRegistry.definitions.keys.containsAll(GoalToolCatalog.names))
        val project=repo.chat.createProject("Synthetic goal evidence");val thread=repo.chat.createThread(project.id)
        val scope=repo.chat.accessScope(thread.id)
        val saved=store.saveGuarded(scope,draft(thread.id,project.id))
        val run=repo.chat.createRun(thread.id,thread.activeBranchId)
        for(name in GoalToolCatalog.names) {
            val audit=repo.recordToolRunEvidence(name,true,"VERIFIED · synthetic goal management receipt",run.id,thread.id)
            val candidate=saved.copy(linkedRunIds=listOf(run.id),criteria=saved.criteria.map {
                it.copy(status=GoalCriterionStatus.VERIFIED,evidence=listOf("tool-run:${audit.id}"))
            })
            assertTrue(runCatching {store.saveGuarded(scope,candidate)}.isFailure)
            assertEquals(saved,store.loadGuarded(scope))
        }
    }}

    private fun draft(thread:String,project:String?)=GoalDefinition(threadId=thread,projectId=project,
        outcome="SYNTHETIC_GOAL_OUTCOME",criteria=listOf(GoalCriterion(text="SYNTHETIC_OBSERVABLE_RESULT")))
    private suspend fun fixture(block:suspend(LocalRepository,ChatGoalStore,LocalDatabase)->Unit) {
        assumeTrue("conversationGoalStoreApproved=true required",InstrumentationRegistry.getArguments().getString("conversationGoalStoreApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="conversation-goal-qa-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)};System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {val repo=LocalRepository(db);block(repo,ChatGoalStore(repo),db)}finally {db.close();key.fill(0);context.deleteDatabase(name)}
    }
}
