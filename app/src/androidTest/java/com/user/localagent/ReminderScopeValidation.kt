package com.user.localagent

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.data.*
import com.user.localagent.scheduling.ReminderScheduler
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated encrypted databases and unique far-future work only. Never reads or deletes owner reminders. */
@RunWith(AndroidJUnit4::class)
class ReminderScopeValidation {
    private val future get()=System.currentTimeMillis()+7L*24*60*60*1000
    private data class Fixture(val root:LocalRepository,val db:LocalDatabase,val projectA:ProjectRecord,val projectB:ProjectRecord,val roomA:ChatThreadRecord,val roomB:ChatThreadRecord,val a:LocalRepository,val b:LocalRepository)
    private suspend fun fixture(block:suspend(Fixture)->Unit){
        assumeTrue(InstrumentationRegistry.getArguments().getString("reminderScopeValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="reminder-scope-qa-${UUID.randomUUID()}.db";val key=ByteArray(32).also {SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .addMigrations(KnowledgeMigrations.MIGRATION_1_2,ChatMigrations.MIGRATION_2_3).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            db.openHelper.writableDatabase
            val root=LocalRepository(db);val p=root.chat.createProject("QA reminder A");val q=root.chat.createProject("QA reminder B")
            val a=root.chat.createThread(p.id,"QA A");val b=root.chat.createThread(q.id,"QA B")
            block(Fixture(root,db,p,q,a,b,root.forThread(a.id),root.forThread(b.id)))
        }finally {db.close();key.fill(0);check(name.startsWith("reminder-scope-qa-"));context.deleteDatabase(name)}
    }

    @Test fun sameGroupProjectsCannotReadUpdateOrDeleteEachOthersReminders()=runBlocking(Dispatchers.IO){fixture {f->
        val legacy=ReminderRecord(title="QA legacy owner",atEpochMillis=future,group="shared")
        f.db.dao().reminder(legacy) // Historical unbound rows retain owner scope.
        val owner=f.root.saveReminder("QA explicit owner",future,"shared")
        val a=f.a.saveReminder("QA A only",future,"shared");val b=f.b.saveReminder("QA B only",future,"shared")
        assertEquals(listOf(a.id),f.a.listReminders("shared").map {it.id})
        assertEquals(listOf(b.id),f.b.listReminders("shared").map {it.id})
        assertEquals(setOf(legacy.id,owner.id),f.root.listReminders("shared").map {it.id}.toSet())
        assertEquals(setOf(a.id),f.a.reminders.first().map {it.id}.toSet())
        assertEquals(setOf(legacy.id,owner.id),f.root.reminders.first().map {it.id}.toSet())
        assertEquals(setOf(a.id,b.id,legacy.id,owner.id),f.root.remindersForOwnerManagement.first().map {it.id}.toSet())
        assertEquals("project:${f.projectA.id}",f.db.chatDao().sourceScope("reminder",a.id)?.scopeKey)
        assertEquals(f.roomA.id,f.db.chatDao().sourceScope("reminder",a.id)?.threadId)
        assertTrue(runCatching {f.a.getReminder(b.id)}.isFailure)
        assertTrue(runCatching {f.a.getReminder(legacy.id)}.isFailure)
        assertTrue(runCatching {f.a.saveReminder("wrong",future,"shared",b.id,expectedUpdatedAt=b.updatedAt)}.isFailure)
        assertTrue(runCatching {f.a.deleteReminder(b.id,expectedUpdatedAt=b.updatedAt)}.isFailure)
        assertTrue(runCatching {f.a.setReminderStatus(b.id,"delivered")}.isFailure)
        assertEquals(b,f.b.getReminder(b.id));assertEquals(legacy,f.root.getReminder(legacy.id))
        assertEquals(a,f.root.getReminderForDelivery(a.id));assertTrue(runCatching {f.a.getReminderForDelivery(a.id)}.isFailure)
        f.a.setReminderStatus(a.id,"delivered")
        assertTrue(f.a.listReminders("shared").isEmpty());assertEquals(a.id,f.a.listReminders("shared",includeCompleted=true).single().id)
        val completed=checkNotNull(f.a.getReminder(a.id))
        f.a.deleteReminder(a.id,completed.updatedAt)
        assertNull(f.a.getReminder(a.id));assertNull(f.db.chatDao().sourceScope("reminder",a.id));assertNull(f.root.getReminderForDelivery(a.id))
        assertEquals(b,f.b.getReminder(b.id))
    }}

    @Test fun staleVersionsCannotOverwriteOrDeleteNotesAndReminders()=runBlocking(Dispatchers.IO){fixture {f->
        val first=f.a.saveNote("QA note","Original full note","qa")
        val updated=f.a.saveNote("QA note","Independent edit","qa",first.id,first.updatedAt)
        assertTrue(updated.updatedAt>first.updatedAt)
        assertTrue(runCatching {f.a.saveNote("QA note","stale overwrite","qa",first.id,first.updatedAt)}.isFailure)
        assertTrue(runCatching {f.a.deleteNote(first.id,first.updatedAt)}.isFailure)
        assertEquals(updated,f.a.getNote(first.id))
        val competing=coroutineScope {listOf("QA revision one","QA revision two").map {text->async {runCatching {f.a.saveNote("QA note",text,"qa",updated.id,updated.updatedAt)}.isSuccess}}.awaitAll()}
        assertEquals(1,competing.count {it})
        val finalNote=checkNotNull(f.a.getNote(first.id));assertTrue(finalNote.updatedAt>updated.updatedAt)
        f.a.deleteNote(finalNote.id,finalNote.updatedAt);assertNull(f.a.getNote(finalNote.id))
        assertTrue(runCatching {f.a.saveNote("QA note","resurrect","qa",finalNote.id,finalNote.updatedAt)}.isFailure)
        assertTrue(runCatching {f.a.deleteNote(finalNote.id,finalNote.updatedAt)}.isFailure)

        val reminder=f.a.saveReminder("QA initial",future,"shared")
        val changed=f.a.saveReminder("QA changed",future+1000,"shared",reminder.id,reminder.updatedAt)
        assertTrue(changed.updatedAt>reminder.updatedAt)
        assertTrue(runCatching {f.a.saveReminder("stale",future,"shared",reminder.id,reminder.updatedAt)}.isFailure)
        assertTrue(runCatching {f.a.deleteReminder(reminder.id,reminder.updatedAt)}.isFailure)
        assertEquals(changed,f.a.getReminder(reminder.id))
        f.a.deleteReminder(changed.id,changed.updatedAt);assertNull(f.a.getReminder(changed.id))
        assertTrue(runCatching {f.a.saveReminder("resurrect",future,"shared",changed.id,changed.updatedAt)}.isFailure)
        assertTrue(runCatching {f.a.deleteReminder(changed.id,changed.updatedAt)}.isFailure)
    }}

    @Test fun deletedProjectAndThreadScopesCannotBeDeliveredButLiveProjectSchedulesSurviveChatDeletion()=runBlocking(Dispatchers.IO){fixture {f->
        val a=f.a.saveReminder("QA project A",future,"same");val b=f.b.saveReminder("QA project B",future,"same")
        val legacy=ReminderRecord(title="QA legacy",atEpochMillis=future);f.db.dao().reminder(legacy)
        val threadProject=f.root.chat.createProject("QA thread scope",ProjectSettings(memoryMode="thread"))
        val room=f.root.chat.createThread(threadProject.id,"QA thread");var scoped=f.root.forThread(room.id)
        val own=scoped.saveReminder("QA thread action",future)
        assertEquals(listOf(a.id),f.root.reminderIdsForProjectRemoval(f.projectA.id))
        assertTrue(f.root.reminderIdsForThreadRemoval(f.roomA.id).isEmpty())
        assertEquals(listOf(own.id),f.root.reminderIdsForThreadRemoval(room.id))
        f.root.chat.clearThread(room.id);scoped=f.root.forThread(room.id)
        assertEquals(own,scoped.getReminder(own.id));assertEquals(own,f.root.getReminderForDelivery(own.id))
        f.root.chat.deleteThread(room.id)
        assertNull(f.root.getReminderForDelivery(own.id));assertNull(f.db.dao().reminderById(own.id));assertNull(f.db.chatDao().sourceScope("reminder",own.id))
        f.root.chat.deleteThread(f.roomA.id)
        assertEquals(a,f.root.getReminderForDelivery(a.id)) // The project still owns this independent action.
        f.root.chat.deleteProject(f.projectA.id)
        assertNull(f.root.getReminderForDelivery(a.id));assertNull(f.db.dao().reminderById(a.id));assertNull(f.db.chatDao().sourceScope("reminder",a.id))
        assertEquals(setOf(b.id,legacy.id),f.root.pendingRemindersForDelivery().map {it.id}.toSet())
        assertEquals(b,f.root.getReminderForDelivery(b.id));assertEquals(legacy,f.root.getReminder(legacy.id))
        // A stale/orphan binding is never reclassified as owner or delivered.
        f.db.chatDao().deleteProjectRow(f.projectB.id)
        assertNull(f.root.getReminderForDelivery(b.id));assertFalse(f.root.claimReminderForDelivery(b))
        assertFalse(f.root.pendingRemindersForDelivery().any {it.id==b.id})
        assertFalse(f.root.remindersForOwnerManagement.first().any {it.id==b.id})
        assertEquals("pending",f.db.dao().reminderById(b.id)?.status)
    }}

    @Test fun deletedOwnNotesAndMemoriesReadBackAsAbsentWhileExistingForeignRecordsRemainDenied()=runBlocking(Dispatchers.IO){fixture {f->
        val a=f.a.saveNote("QA own","QA exact body","qa");val b=f.b.saveNote("QA foreign","QA foreign body","qa")
        assertTrue(runCatching {f.a.getNote(b.id)}.isFailure)
        f.a.deleteNote(a.id,a.updatedAt);assertNull(f.a.getNote(a.id));assertNull(f.b.getNote(a.id))
        assertEquals(b,f.b.getNote(b.id))
        val memory=f.a.saveMemory("QA scoped memory","fact")
        val other=f.b.saveMemory("QA foreign memory","fact")
        assertTrue(runCatching {f.a.getMemory(other.id)}.isFailure)
        f.a.forgetMemory(memory.id);assertNull(f.a.getMemory(memory.id));assertEquals(other,f.b.getMemory(other.id))
    }}

    @Test fun farFutureProjectScheduleIsCancelledAndRemovedWithoutDelivery()=runBlocking(Dispatchers.IO){fixture {f->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler=ReminderScheduler(context)
        val record=f.a.saveReminder("QA future only",future,"qa")
        try {scheduler.withMutation {
            scheduler.schedule(record);assertTrue(scheduler.verifyScheduled(record))
            val ids=f.root.reminderIdsForProjectRemoval(f.projectA.id);assertEquals(listOf(record.id),ids)
            ids.forEach {scheduler.cancel(it)}
            f.root.chat.deleteProject(f.projectA.id)
            ids.forEach {scheduler.cancel(it)}
            assertNull(f.root.getReminderForDelivery(record.id))
            withTimeout(15_000){while(scheduler.verifyScheduled(record))delay(25)}
        }}finally {withContext(NonCancellable){
            scheduler.cancel(record.id)
            // This isolated database and id were created by this fixture, even if the project was deleted.
            f.db.withTransaction {f.db.dao().deleteReminder(record.id);f.db.chatDao().deleteSourceScope("reminder",record.id)}
        }}
    }}
}
