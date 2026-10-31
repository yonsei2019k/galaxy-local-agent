package com.user.localagent.automation

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.VerificationState
import com.user.localagent.data.LocalDatabase
import com.user.localagent.data.LocalRepository
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQLCipher fixture only. Never opens or exports the owner's real database. */
@RunWith(AndroidJUnit4::class)
class PlanningFeedbackValidation {
    @Test fun encryptedReopenPreparedCountsAndForgottenGeneration()=runBlocking(Dispatchers.IO){
        assumeTrue("Explicit planningFeedbackApproved=true is required",InstrumentationRegistry.getArguments().getString("planningFeedbackApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName="qa-planning-feedback-${UUID.randomUUID()}.db"
        val passphrase=ByteArray(32).also {SecureRandom().nextBytes(it)}
        var database:LocalDatabase?=null
        fun open():LocalDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context,LocalDatabase::class.java,databaseName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        }
        try {
            database=open()
            val first=PlanningFeedbackStore(LocalRepository(database!!))
            val generation=first.generation()
            assertTrue(first.record("calendar","prepare_calendar_event",VerificationState.PREPARED,true,expectedGeneration=generation))
            assertTrue(first.record("notes","save_note",VerificationState.VERIFIED,true,expectedGeneration=generation))
            assertTrue(first.proposeLesson("notes","save_note","verify_result",generation))
            database!!.close();database=null
            val databaseFile=context.getDatabasePath(databaseName)
            assertTrue(databaseFile.isFile&&databaseFile.length()>16)
            val signature=databaseFile.inputStream().use {input->ByteArray(16).also {assertEquals(16,input.read(it))}}
            assertNotEquals("Synthetic feedback database must not have a plaintext SQLite header","SQLite format 3\u0000",signature.toString(Charsets.ISO_8859_1))
            database=open()
            val reopened=PlanningFeedbackStore(LocalRepository(database!!))
            val stored=reopened.snapshot()
            assertEquals(generation,stored.generation);assertEquals(2,stored.stats.size)
            val prepared=stored.stats.single {it.tool=="prepare_calendar_event"}
            assertEquals(1L,prepared.prepared);assertEquals(0L,prepared.verifiedSuccess)
            assertTrue(reopened.contextFor("calendar").contains("prepared_is_not_external_completion"))
            assertEquals(1L,stored.stats.single {it.tool=="save_note"}.proposedLessons.getValue("verify_result"))
            assertTrue(reopened.delete("calendar","prepare_calendar_event"))
            assertFalse(reopened.record("calendar","prepare_calendar_event",VerificationState.VERIFIED,true,expectedGeneration=generation))
            assertEquals("save_note",reopened.snapshot().stats.single().tool)
            val beforeReset=reopened.generation();val afterReset=reopened.reset()
            assertTrue(afterReset>beforeReset);assertTrue(reopened.snapshot().stats.isEmpty())
            assertFalse(reopened.record("notes","save_note",VerificationState.VERIFIED,true,expectedGeneration=beforeReset))
            database!!.close();database=null
            database=open()
            val forgotten=PlanningFeedbackStore(LocalRepository(database!!))
            assertEquals(afterReset,forgotten.generation());assertTrue(forgotten.snapshot().stats.isEmpty())
            assertEquals("",forgotten.contextFor("notes"))
        }finally {
            database?.close();passphrase.fill(0)
            check(databaseName.startsWith("qa-planning-feedback-"))
            context.deleteDatabase(databaseName)
            check(!context.getDatabasePath(databaseName).exists())
        }
    }
}
