package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.data.DraftStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Redirects BOTH applicationContext and filesDir; never opens or deletes a real user's draft. */
@RunWith(AndroidJUnit4::class)
class DraftStoreValidation {
    private lateinit var directory: File
    private lateinit var isolated: Context
    private lateinit var store: DraftStore

    @Before fun isolatedDraftDirectory() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "draft-store-qa-${UUID.randomUUID()}")
        assertEquals(target.cacheDir.canonicalFile, directory.canonicalFile.parentFile)
        assertTrue(directory.mkdirs())
        isolated = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
        assertEquals(directory.canonicalFile, isolated.applicationContext.filesDir.canonicalFile)
        assertNotEquals(target.filesDir.canonicalFile, isolated.applicationContext.filesDir.canonicalFile)
        store = DraftStore(isolated)
    }

    @After fun removeOnlySyntheticDraftFiles() {
        if (!::directory.isInitialized || !directory.exists()) return
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        assertEquals(cache, directory.canonicalFile.parentFile)
        assertTrue(directory.name.startsWith("draft-store-qa-"))
        directory.listFiles().orEmpty().forEach { file ->
            assertEquals(directory.canonicalFile, file.canonicalFile.parentFile)
            assertTrue(file.isFile && file.name.startsWith("chat-draft.enc"))
            assertTrue(file.delete())
        }
        assertTrue(directory.delete())
        // The shared Android Keystore alias is deliberately never deleted or modified by cleanup.
    }

    @Test fun aesGcmRoundTripHasNoPlaintextMarkerAndUsesFreshCiphertext() {
        val marker = "draft-qa-${UUID.randomUUID()}"
        val text = "$marker 한글 초안"
        store.write(text)
        val file = File(directory, "chat-draft.enc")
        val first = file.readBytes()
        assertTrue(first.size >= text.toByteArray(Charsets.UTF_8).size + 28)
        assertFalse(first.toString(Charsets.ISO_8859_1).contains(marker))
        assertEquals(text, DraftStore(isolated).read())
        store.write(text)
        val second = file.readBytes()
        assertFalse(first.contentEquals(second))
        assertFalse(second.toString(Charsets.ISO_8859_1).contains(marker))
        assertEquals(text, DraftStore(isolated).read())
    }

    @Test fun queuedOldWriteCannotRestoreSubmittedAndClearedDraft() {
        store.write("synthetic existing draft")
        val oldRevision = store.reserveWrite()
        store.write("", store.reserveWrite())
        store.write("synthetic stale queued draft", oldRevision)
        assertEquals("", store.read())
        assertFalse(File(directory, "chat-draft.enc").exists())
    }

    @Test fun oversizedInputDoesNotReplaceExistingEncryptedDraft() {
        val text = "synthetic retained draft"
        store.write(text)
        val before = File(directory, "chat-draft.enc").readBytes()
        assertTrue(runCatching { store.write("x".repeat(12_001)) }.exceptionOrNull() is IllegalArgumentException)
        assertArrayEquals(before, File(directory, "chat-draft.enc").readBytes())
        assertEquals(text, DraftStore(isolated).read())
    }
}
