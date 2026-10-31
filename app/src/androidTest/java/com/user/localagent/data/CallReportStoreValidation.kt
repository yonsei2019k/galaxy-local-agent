package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Isolated encrypted database: no owner recording, conversation, provider, account or send. */
@RunWith(AndroidJUnit4::class)
class CallReportStoreValidation {
    @Test fun reportIsAtomicIdempotentAndKeepsMoreThanTwentyThousandCharacters()=runBlocking(Dispatchers.IO){fixture {repo,_->
        val project=UUID.randomUUID().toString();val thread=UUID.randomUUID().toString()
        val route=repo.chat.ensurePrivateCallFollowUpRoute(project,thread)
        assertEquals(route,repo.chat.ensurePrivateCallFollowUpRoute(project,thread))
        val access=repo.chat.accessScope(thread);assertFalse(access.useOwnerMemory)
        assertFalse(checkNotNull(repo.chat.project(project)).settings.learnOwnerFacts)
        val text="원음 근거 확인 필요 🩺\n".repeat(2000)+"SYNTHETIC_END"
        val publication=UUID.randomUUID().toString()
        val first=repo.chat.publishPrivateCallFollowUpReport(thread,publication,text)
        assertEquals(text,repo.chat.fullMessageText(first.id))
        assertEquals(first,repo.chat.publishPrivateCallFollowUpReport(thread,publication,text))
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,publication,text+"CHANGED")}.isFailure)
        repo.deleteMessage(publication)
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,publication,text)}.isFailure)
    }}
    @Test fun deletedOrClearedReportCannotReturnAndForeignIdsNeverReused()=runBlocking(Dispatchers.IO){fixture {repo,_->
        val foreign=repo.chat.createProject("SYNTHETIC_OTHER_PROJECT")
        assertTrue(runCatching {repo.chat.ensurePrivateCallFollowUpRoute(foreign.id,UUID.randomUUID().toString())}.isFailure)
        val project=UUID.randomUUID().toString();val thread=UUID.randomUUID().toString()
        repo.chat.ensurePrivateCallFollowUpRoute(project,thread)
        val id=UUID.randomUUID().toString()
        repo.chat.publishPrivateCallFollowUpReport(thread,id,"SYNTHETIC_REPORT")
        repo.chat.clearThread(thread)
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,id,"SYNTHETIC_REPORT")}.isFailure)
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,UUID.randomUUID().toString(),"SYNTHETIC_LATE_NEW_PUBLICATION")}.isFailure)
        repo.chat.deleteThread(thread)
        assertTrue(runCatching {repo.chat.ensurePrivateCallFollowUpRoute(project,thread)}.isFailure)
    }}
    @Test fun projectPolicyChangeBlocksLatePrivateReportPublication()=runBlocking(Dispatchers.IO){fixture {repo,_->
        val project=UUID.randomUUID().toString();val thread=UUID.randomUUID().toString()
        repo.chat.ensurePrivateCallFollowUpRoute(project,thread)
        val old=checkNotNull(repo.chat.project(project))
        repo.chat.updateProject(project,settings=old.settings.copy(useOwnerMemory=true))
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,UUID.randomUUID().toString(),"SYNTHETIC_LATE")}.isFailure)
        assertTrue(runCatching {repo.chat.ensurePrivateCallFollowUpRoute(project,thread)}.isFailure)
    }}
    @Test fun atomicCleanupPreservesEditedReportAndRejectsWrongScope()=runBlocking(Dispatchers.IO){fixture {repo,db->
        val thread=UUID.randomUUID().toString()
        repo.chat.ensurePrivateCallFollowUpRoute(UUID.randomUUID().toString(),thread)
        val id=UUID.randomUUID().toString();val body="SYNTHETIC_UNREVIEWED"
        val saved=repo.chat.publishPrivateCallFollowUpReport(thread,id,body)
        fun sha(text:String)=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
        assertFalse(repo.deletePrivateCallFollowUpReportIfUnchanged(UUID.randomUUID().toString(),id,sha(body)))
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET text=? WHERE id=?",arrayOf("SYNTHETIC_OWNER_EDIT",saved.id))
        assertFalse(repo.deletePrivateCallFollowUpReportIfUnchanged(thread,id,sha(body)))
        assertEquals("SYNTHETIC_OWNER_EDIT",repo.chat.fullMessageText(id))
        assertTrue(repo.deletePrivateCallFollowUpReportIfUnchanged(thread,id,sha("SYNTHETIC_OWNER_EDIT")))
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,id,body)}.isFailure)
    }}
    @Test fun hostRollbackResumesExactPublicationButStaleOwnerDeletePermanentlyBlocksIt()=runBlocking(Dispatchers.IO){fixture {repo,_->
        val thread=UUID.randomUUID().toString();repo.chat.ensurePrivateCallFollowUpRoute(UUID.randomUUID().toString(),thread)
        val id=UUID.randomUUID().toString();val body="SYNTHETIC_PAUSED_PUBLICATION"
        fun sha(text:String)=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
        repo.chat.publishPrivateCallFollowUpReport(thread,id,body)
        assertTrue(repo.rollbackPrivateCallFollowUpReportIfUnchanged(thread,id,sha(body)))
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,id,body+"CHANGED")}.isFailure)
        assertEquals(body,repo.chat.publishPrivateCallFollowUpReport(thread,id,body).text)
        assertTrue(repo.rollbackPrivateCallFollowUpReportIfUnchanged(thread,id,sha(body)))
        repo.forThread(thread).deleteMessage(id) // The owner can delete from a stale screen after host rollback.
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,id,body)}.isFailure)
        assertFalse(repo.rollbackPrivateCallFollowUpReportIfUnchanged(thread,id,sha(body)))
    }}
    @Test fun hostRollbackCannotEraseOwnerEditOrBypassAThreadClear()=runBlocking(Dispatchers.IO){fixture {repo,db->
        val thread=UUID.randomUUID().toString();repo.chat.ensurePrivateCallFollowUpRoute(UUID.randomUUID().toString(),thread)
        val id=UUID.randomUUID().toString();val body="SYNTHETIC_ORIGINAL_REPORT"
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString(""){"%02x".format(it)}
        repo.chat.publishPrivateCallFollowUpReport(thread,id,body)
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET text=? WHERE id=?",arrayOf("SYNTHETIC_OWNER_EDIT",id))
        assertFalse(repo.rollbackPrivateCallFollowUpReportIfUnchanged(thread,id,sha))
        assertEquals("SYNTHETIC_OWNER_EDIT",repo.chat.fullMessageText(id))
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET text=? WHERE id=?",arrayOf(body,id))
        assertTrue(repo.rollbackPrivateCallFollowUpReportIfUnchanged(thread,id,sha))
        repo.chat.clearThread(thread)
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,id,body)}.isFailure)
        assertTrue(runCatching {repo.chat.publishPrivateCallFollowUpReport(thread,UUID.randomUUID().toString(),body)}.isFailure)
    }}
    private suspend fun fixture(block:suspend(LocalRepository,LocalDatabase)->Unit){
        assumeTrue(InstrumentationRegistry.getArguments().getString("callReportStoreApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="call-report-qa-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)};System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone())).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {block(LocalRepository(db),db)}finally {db.close();key.fill(0);context.deleteDatabase(name)}
    }
}
