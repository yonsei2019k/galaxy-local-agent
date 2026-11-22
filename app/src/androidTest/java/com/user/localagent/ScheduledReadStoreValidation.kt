package com.user.localagent

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.ScheduledReadPolicy
import com.user.localagent.data.ChatStore
import com.user.localagent.data.LocalDatabase
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.ProjectSettings
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledReadStoreValidation {
    @Test fun sameOccurrenceStartsOnceAndOnlyItsRoomCanStopIt() = runBlocking(Dispatchers.IO) {
        isolated { chat ->
            val owner = chat.createThread(title="Scheduled fixture A")
            val other = chat.createThread(title="Scheduled fixture B")
            val original = chat.startRun(chat.createRun(owner.id,owner.activeBranchId).id)
            val outside = chat.startRun(chat.createRun(other.id,other.activeBranchId).id)
            val due = System.currentTimeMillis()+60_000
            suspend fun register()=chat.scheduleRead(original.id,"Fixture","Original request",due,"search_notes")
            val scheduled = register()
            assertEquals(scheduled.id,register().id)
            assertEquals(ScheduledReadPolicy.SCHEDULED,chat.promoteScheduledRead(scheduled.id,due-1)?.status)
            assertTrue(runCatching { chat.cancelScheduledRead(outside.id,scheduled.id) }.isFailure)
            val queued = checkNotNull(chat.promoteScheduledRead(scheduled.id,due))
            assertEquals("queued",queued.status)
            assertEquals(queued.userMessageId,chat.promoteScheduledRead(scheduled.id,due)?.userMessageId)
            assertEquals("Original request",QueuedChatRequest.fromJson(queued.contextJson).text)
            chat.startRun(queued.id)
            assertEquals(ScheduledReadPolicy.STOP_REQUESTED,chat.cancelScheduledRead(original.id,queued.id).errorCode)
            assertTrue(runCatching { chat.verifyScheduledRead(queued.id,owner.id) }.isFailure)
            chat.recoverInterruptedRuns()
            assertEquals("cancelled",chat.promoteScheduledRead(queued.id,due)?.status)
        }
    }

    @Test fun cancelledAndRevokedOccurrencesNeverEnterTheQueue() = runBlocking(Dispatchers.IO) {
        isolated { chat ->
            val project=chat.createProject("Scheduled fixture project")
            val owner=chat.createThread(project.id,"Scheduled fixture")
            val original=chat.startRun(chat.createRun(owner.id,owner.activeBranchId).id)
            val due=System.currentTimeMillis()+60_000
            val revoked=chat.scheduleRead(original.id,"Revoked","Read notes",due,"search_notes")
            val cancelled=chat.scheduleRead(original.id,"Cancelled","Read time",due,"get_current_datetime")
            assertEquals("cancelled",chat.cancelScheduledRead(original.id,cancelled.id).status)
            chat.updateProject(project.id,settings=ProjectSettings(disabledTools=setOf("search_notes")))
            assertEquals("scheduled_scope_changed",chat.promoteScheduledRead(revoked.id,due)?.errorCode)
            assertEquals("cancelled",chat.promoteScheduledRead(cancelled.id,due)?.status)
            assertTrue(chat.queuedRuns().isEmpty())
        }
    }

    private suspend fun isolated(block:suspend (ChatStore)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="scheduled-read-qa-"+UUID.randomUUID()+".db"
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone())).build()
        try {block(LocalRepository(db).chat)} finally {
            db.close();key.fill(0);check(name.startsWith("scheduled-read-qa-"));context.deleteDatabase(name)
        }
    }
}
