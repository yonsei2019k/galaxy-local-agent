package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.preserveUnappliedInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Synthetic isolated DB only; never opens the owner's installed database or runs an action. */
@RunWith(AndroidJUnit4::class)
class ChatInstructionRecoveryValidation {
    @Test fun unappliedInstructionPreservesDraftPreferencesAndNeverResurrectsDeletedRoom()=runBlocking(Dispatchers.IO){
        assumeTrue("chatDataValidationApproved=true required",InstrumentationRegistry.getArguments().getString("chatDataValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="chat-instruction-recovery-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val database=Room.databaseBuilder(context,LocalDatabase::class.java,name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try{
            val repository=LocalRepository(database)
            val thread=repository.chat.createThread(title="Synthetic instruction recovery")
            val original=repository.chat.addMessage(thread.id,thread.activeBranchId,"user","Synthetic original request")
            val selected=repository.chat.forkBranch(thread.id,original.id,"Synthetic current branch")
            val draft=repository.chat.saveDraft(thread.id,"SYNTHETIC_EXISTING_UNSENT_DRAFT","qa-model-choice",true,original.id,12)
            val other=repository.chat.createThread(title="Synthetic unrelated room")
            val otherDraft=repository.chat.saveDraft(other.id,"SYNTHETIC_OTHER_DRAFT","qa-other-model",false)
            val instruction="Synthetic unapplied instruction\nKeep the selected model and draft. 한글🙂"

            assertTrue(preserveUnappliedInstruction(repository,thread.id,instruction,selected.id))
            assertEquals("Existing draft text/model/thinking/scroll/revision remain unchanged",draft,repository.chat.readDraft(thread.id))
            assertEquals(otherDraft,repository.chat.readDraft(other.id))
            val recovery=repository.chat.messages(thread.id,selected.id).first().last()
            assertEquals("assistant",recovery.role)
            assertTrue(recovery.text.startsWith("[아직 적용하지 않은 추가 지시]"))
            val quoted=Json.parseToJsonElement(recovery.text.substringAfter("\n\n")).jsonObject
            assertEquals(instruction,quoted.getValue("quoted_owner_instruction").jsonPrimitive.content)
            assertFalse(quoted.getValue("applied").jsonPrimitive.boolean)
            assertEquals(listOf(original.id),repository.chat.messages(thread.id,thread.activeBranchId).first().map{it.id})
            assertEquals(1,repository.chat.messages(thread.id,selected.id).first().count{it.role=="assistant"})

            assertTrue(preserveUnappliedInstruction(repository,thread.id,"Reply from the original goal card",thread.activeBranchId))
            val originalMessages=repository.chat.messages(thread.id,thread.activeBranchId).first()
            assertEquals(2,originalMessages.size)
            assertTrue(originalMessages.last().text.contains("Reply from the original goal card"))
            assertEquals(1,repository.chat.messages(thread.id,selected.id).first().count{it.role=="assistant"})

            repository.chat.deleteThread(thread.id,true)
            val before=repository.chat.threads.first().map{it.id}.toSet()
            assertFalse(preserveUnappliedInstruction(repository,thread.id,"Synthetic late instruction"))
            assertEquals(before,repository.chat.threads.first().map{it.id}.toSet())
            assertEquals(otherDraft,repository.chat.readDraft(other.id))
        }finally{database.close();context.deleteDatabase(name);key.fill(0)}
    }
}
