package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.util.UUID

/** Synthetic owned records only; no user messages, calendar writes or outgoing communications. */
@RunWith(AndroidJUnit4::class)
class ToolEffectValidation {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=(context.applicationContext as LocalAgentApplication).container
    private fun call(name:String,body:JsonObject)=ToolCall(UUID.randomUUID().toString(),name,body)

    @Test fun notesAndMemoryAreVerifiedByReadbackAndErasure()=runBlocking {
        val token="effect-qa-${UUID.randomUUID()}"
        var noteId:String?=null;var memoryId:String?=null
        try {
            val saved=app.tools.execute(call("save_note",buildJsonObject {put("content",token);put("scope",token)}))
            assertTrue(saved.content,saved.success);assertEquals(VerificationState.VERIFIED,saved.verification)
            val note=app.repository.searchUserNotes(token,token).single();noteId=note.id
            assertEquals(token,app.repository.getNote(note.id)?.body)
            val updated=app.tools.execute(call("update_note",buildJsonObject {put("id",note.id);put("scope",token);put("content","$token changed")}))
            assertEquals(VerificationState.VERIFIED,updated.verification)
            assertEquals("$token changed",app.repository.getNote(note.id)?.body)
            val erased=app.tools.execute(call("delete_note",buildJsonObject {put("id",note.id)}))
            assertEquals(VerificationState.VERIFIED,erased.verification);assertNull(app.repository.getNote(note.id))
            val memory=app.tools.execute(call("save_memory",buildJsonObject {put("content",token);put("kind","fact")}))
            assertEquals(VerificationState.VERIFIED,memory.verification)
            memoryId=app.repository.searchMemories(token).single {it.text==token}.id
            val forgotten=app.tools.execute(call("delete_memory",buildJsonObject {put("id",memoryId)}))
            assertEquals(VerificationState.VERIFIED,forgotten.verification);assertNull(app.repository.getMemory(memoryId!!))
        }finally {withContext(NonCancellable){noteId?.let {app.repository.deleteNote(it)};memoryId?.let {app.repository.forgetMemory(it)}}}
    }

    @Test fun reminderCreationRequiresPersistedBackupQueue()=runBlocking {
        val token="reminder-effect-qa-${UUID.randomUUID()}"
        var id:String?=null
        try {
            val at=ZonedDateTime.now(ToolRegistry.defaultZone).plusDays(7).toOffsetDateTime().toString()
            val result=app.tools.execute(call("create_reminder",buildJsonObject {put("title",token);put("datetime",at);put("scope",token)}))
            val stored=app.repository.pendingReminders().singleOrNull {it.title==token}
            id=stored?.id
            assertTrue(result.content,result.success);assertEquals(VerificationState.VERIFIED,result.verification)
            assertNotNull(stored);assertTrue(app.scheduler.verifyScheduled(stored!!))
            val erased=app.tools.execute(call("delete_reminder",buildJsonObject {put("id",stored.id)}))
            assertTrue(erased.success);assertNull(app.repository.getReminder(stored.id))
        }finally {withContext(NonCancellable){
            // Find our own record even if creation committed before the result failed.
            app.repository.pendingReminders().filter {it.title==token}.forEach {app.scheduler.cancel(it.id);app.repository.deleteReminder(it.id)}
            id?.let {app.scheduler.cancel(it);app.repository.deleteReminder(it)}
        }}
    }

    @Test fun invalidToolNeverClaimsVerifiedSuccess()=runBlocking {
        val result=app.tools.execute(call("send_sms",buildJsonObject {put("message","synthetic")}))
        assertFalse(result.success);assertEquals(VerificationState.FAILED,result.verification)
    }
}
