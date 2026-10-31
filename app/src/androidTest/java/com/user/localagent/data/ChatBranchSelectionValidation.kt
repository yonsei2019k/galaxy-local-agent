package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Preserved versions and room preferences in an isolated synthetic DB; no model/UI/device data. */
@RunWith(AndroidJUnit4::class)
class ChatBranchSelectionValidation {
    @Test fun returningToOriginalVersionPreservesDraftModelAndBothAnswerHistories() = runBlocking(Dispatchers.IO) {
        assumeTrue("chatBranchValidationApproved=true required", InstrumentationRegistry.getArguments().getString("chatBranchValidationApproved") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "chat-branch-validation-${UUID.randomUUID()}.db"
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        System.loadLibrary("sqlcipher")
        fun open() = Room.databaseBuilder(context, LocalDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        var database: LocalDatabase? = null
        try {
            database = open()
            var repository = LocalRepository(checkNotNull(database))
            val project = repository.chat.createProject("Synthetic branch test", ProjectSettings(modelId = "qa-project-model", thinking = true))
            val thread = repository.chat.createThread(project.id)
            val originalBranch = thread.activeBranchId
            val user = repository.chat.addMessage(thread.id, originalBranch, "user", "Synthetic original request")
            val originalText = "Synthetic original answer with preserved full text.\n".repeat(800)
            val originalAnswer = repository.chat.addMessage(thread.id, originalBranch, "assistant", originalText)
            repository.chat.saveDraft(thread.id, "SYNTHETIC_UNSENT_DRAFT", "qa-room-model", true)

            val editedBranch = repository.chat.forkBranch(thread.id, user.id, "Synthetic edited version", includeMessage = false)
            repository.chat.addMessage(thread.id, editedBranch.id, "user", "Synthetic edited request")
            repository.chat.addMessage(thread.id, editedBranch.id, "assistant", "Synthetic new answer")
            repository.chat.selectBranch(thread.id, originalBranch)
            assertEquals(originalBranch, repository.chat.thread(thread.id)?.activeBranchId)
            assertEquals(listOf(user.id, originalAnswer.id), repository.chat.messages(thread.id).first().map { it.id })
            assertEquals(originalText, repository.chat.fullMessageText(originalAnswer.id))
            assertEquals("qa-room-model", repository.chat.readDraft(thread.id)?.modelId)

            database?.close()
            database = open()
            repository = LocalRepository(checkNotNull(database))
            assertEquals(setOf(originalBranch, editedBranch.id), repository.chat.branches(thread.id).map { it.id }.toSet())
            assertEquals(originalBranch, repository.chat.thread(thread.id)?.activeBranchId)
            val restored = repository.chat.readDraft(thread.id)
            assertEquals("SYNTHETIC_UNSENT_DRAFT", restored?.text)
            assertEquals("qa-room-model", restored?.modelId)
            assertEquals(true, restored?.thinking)
            repository.chat.selectBranch(thread.id, editedBranch.id)
            assertEquals("Synthetic new answer", repository.chat.messages(thread.id).first().last().text)
            assertEquals(originalText, repository.chat.messages(thread.id, originalBranch).first().last().text)

            val unrelated = repository.chat.createThread()
            assertTrue(runCatching { repository.chat.selectBranch(thread.id, unrelated.activeBranchId) }.isFailure)
            assertEquals(editedBranch.id, repository.chat.thread(thread.id)?.activeBranchId)
            assertEquals("SYNTHETIC_UNSENT_DRAFT", repository.chat.readDraft(thread.id)?.text)
        } finally { database?.close(); context.deleteDatabase(name); key.fill(0) }
    }
}
