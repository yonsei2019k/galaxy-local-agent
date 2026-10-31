package com.user.localagent

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.data.LocalDatabase
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID

/** Failure injection after a real synthetic Room enqueue, without constructing the app or a model. */
@RunWith(AndroidJUnit4::class)
class ChatSubmissionRecoveryValidation {
    @Test fun failedPostCommitAttachmentStageCancelsDurableRunWithoutLosingHistoryOrDraft()=runBlocking(Dispatchers.IO){fixture{repo,reopen->
        val thread=repo.chat.createThread();val other=repo.chat.createThread()
        val draft=repo.chat.saveDraft(thread.id,"SYNTHETIC_NEWER_DRAFT","qa-changed-model",true)
        val otherDraft=repo.chat.saveDraft(other.id,"SYNTHETIC_OTHER_DRAFT","qa-other",false)
        val committed=repo.chat.enqueueUserRun(thread.id,thread.activeBranchId,"Synthetic committed request","qa-model",false){user,run->
            QueuedChatRequest(run.id,thread.id,thread.activeBranchId,null,user.text,"qa-model",false,emptyList(),user.id).toJson()
        }
        val reserved=repo.chat.assistantForRun(committed.id)!!
        val recovery=try {
            throw IOException("Synthetic after-commit attachment persistence failure")
        }catch(_:IOException){recoverCommittedSubmission(repo.chat,committed.id)}
        assertEquals(SubmissionRecoveryKind.CANCELLED,recovery.kind)
        assertTrue(submissionSourceStillLive(repo,recovery.run))
        assertEquals("cancelled",repo.chat.run(committed.id)?.status)
        assertTrue(repo.chat.queuedRuns().isEmpty());assertNull(repo.getMessage(reserved.id))
        assertEquals("Synthetic committed request",repo.getMessage(committed.userMessageId!!)?.text)
        assertEquals(draft,repo.chat.readDraft(thread.id));assertEquals(otherDraft,repo.chat.readDraft(other.id))
        val restored=reopen()
        assertEquals("cancelled",restored.chat.run(committed.id)?.status)
        assertTrue(restored.chat.queuedRuns().isEmpty())
        assertTrue(runCatching{restored.chat.startRun(committed.id)}.isFailure)
        assertEquals(draft,restored.chat.readDraft(thread.id))
    }}

    @Test fun cancellationFailureReportsActualQueuedOrStartedStateAndNeverCreatesReplacementRun()=runBlocking(Dispatchers.IO){fixture{repo,_->
        val thread=repo.chat.createThread()
        val user=repo.chat.addMessage(thread.id,thread.activeBranchId,"user","Synthetic queued request")
        val run=repo.chat.createRun(thread.id,thread.activeBranchId,user.id)
        val recovery=recoverCommittedSubmission(repo.chat,run.id){throw IOException("Synthetic cancellation failure")}
        assertEquals(SubmissionRecoveryKind.QUEUED,recovery.kind);assertEquals(run.id,recovery.run?.id)
        assertEquals(listOf(run.id),repo.chat.queuedRuns().map{it.id})
        assertEquals(1,repo.chat.messages(thread.id).first().count{it.role=="user"})
        repo.chat.startRun(run.id)
        val started=recoverCommittedSubmission(repo.chat,run.id)
        assertEquals(SubmissionRecoveryKind.OTHER_STATE,started.kind);assertEquals("running",started.run?.status)
        assertEquals(listOf(run.id),repo.chat.runsForThread(thread.id).map{it.id})
    }}

    @Test fun uncommittedRequestIsQuotedWithoutChangingDraftPreferencesAndDeletedRoomStaysDeleted()=runBlocking(Dispatchers.IO){fixture{repo,_->
        val thread=repo.chat.createThread()
        val draft=repo.chat.saveDraft(thread.id,"SYNTHETIC_CURRENT_DRAFT","qa-current-model",true)
        val request="Synthetic uncommitted request 한글🙂\nsecond line"
        assertTrue(preserveUnsubmittedRequest(repo,thread.id,request))
        assertEquals(draft,repo.chat.readDraft(thread.id))
        val saved=repo.chat.messages(thread.id).first().single()
        assertTrue(saved.text.startsWith("[전송하지 못한 요청]"));assertEquals("assistant",saved.role)
        val quoted=Json.parseToJsonElement(saved.text.substringAfter("\n\n")).jsonObject
        assertEquals(request,quoted.getValue("quoted_owner_request").jsonPrimitive.content)
        assertFalse(quoted.getValue("submitted").jsonPrimitive.boolean)
        assertFalse(quoted.getValue("applied").jsonPrimitive.boolean)
        repo.chat.deleteThread(thread.id)
        assertFalse(preserveUnsubmittedRequest(repo,thread.id,request))
        assertNull(repo.chat.thread(thread.id))
    }}

    @Test fun cancelledSubmissionCannotRestoreAttachmentsAfterClearMoveOrDeletedSource()=runBlocking(Dispatchers.IO){fixture{repo,_->
        val a=repo.chat.createProject("Synthetic submission A");val b=repo.chat.createProject("Synthetic submission B")
        suspend fun request():com.user.localagent.data.ChatRunRecord {
            val thread=repo.chat.createThread(a.id)
            val user=repo.chat.addMessage(thread.id,thread.activeBranchId,"user","Synthetic original submission")
            return repo.chat.createRun(thread.id,thread.activeBranchId,user.id)
        }
        val cleared=request();repo.chat.clearThread(cleared.threadId)
        val clearRecovery=recoverCommittedSubmission(repo.chat,cleared.id)
        assertEquals(SubmissionRecoveryKind.CANCELLED,clearRecovery.kind)
        assertFalse(submissionSourceStillLive(repo,clearRecovery.run))
        val moved=request();repo.chat.moveThread(moved.threadId,b.id)
        assertFalse(submissionSourceStillLive(repo,recoverCommittedSubmission(repo.chat,moved.id).run))
        val erased=request();repo.deleteMessage(erased.userMessageId!!)
        assertFalse(submissionSourceStillLive(repo,recoverCommittedSubmission(repo.chat,erased.id).run))
        assertNull(repo.chat.readDraft(cleared.threadId));assertNull(repo.chat.readDraft(moved.threadId))
    }}

    private suspend fun fixture(block:suspend(LocalRepository,suspend()->LocalRepository)->Unit){
        assumeTrue("chatDataValidationApproved=true required",InstrumentationRegistry.getArguments().getString("chatDataValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="chat-submission-recovery-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also{SecureRandom().nextBytes(it)};System.loadLibrary("sqlcipher")
        fun open()=Room.databaseBuilder(context,LocalDatabase::class.java,name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone())).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        var database=open()
        try {
            block(LocalRepository(database)) {database.close();database=open();LocalRepository(database)}
        } finally {database.close();key.fill(0);context.deleteDatabase(name)}
    }
}
