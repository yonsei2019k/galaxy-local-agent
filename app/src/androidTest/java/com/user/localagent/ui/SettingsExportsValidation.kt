package com.user.localagent.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual Android output/readback; only uniquely owned synthetic files are touched. */
@RunWith(AndroidJUnit4::class)
class SettingsExportsValidation {
    private fun approved() = assumeTrue("settingsExportsApproved=true required",
        InstrumentationRegistry.getArguments().getString("settingsExportsApproved") == "true")

    private suspend fun fixture(block: suspend (File, PendingFileExportStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "settings-export-test-${UUID.randomUUID()}")
        check(root.mkdir())
        val snapshots = File(root, "snapshots").apply { check(mkdir()) }
        try { block(root, PendingFileExportStore(snapshots)) }
        finally {
            for (directory in listOf(snapshots, root)) {
                directory.listFiles()?.filter { it.isFile && it.canonicalFile.parentFile == directory.canonicalFile }
                    ?.forEach { check(it.delete()) }
                check(directory.delete())
            }
        }
    }

    @Test fun wholeUnicodeSnapshotIsWrittenVerifiedAndCannotBeReplayed() = runBlocking(Dispatchers.IO) {
        approved()
        fixture { root, store ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val original = "한글과 이모지 🙂\n".repeat(4000)
            val request = store.prepare("synthetic-owner", "txt") { it.writeText(original, Charsets.UTF_8) }
            val target = File(root, "destination.txt").apply { writeText("existing") }
            val uri = Uri.fromFile(target)
            var verified = 0
            assertTrue(savePendingExport(context, store, request.token, request.owner, request.format, uri) {
                assertEquals(original, context.contentResolver.openInputStream(it)!!.bufferedReader().use { reader -> reader.readText() })
                verified++
            })
            assertEquals(1, verified)
            assertEquals(original, target.readText())
            assertFalse(request.file.exists())
            target.writeText("later owner edit")
            assertFalse(savePendingExport(context, store, request.token, request.owner, request.format, uri) { fail("A duplicate must not verify or write") })
            assertEquals("later owner edit", target.readText())
        }
    }

    @Test fun changedSourceIsRejectedBeforeDestinationIsOpened() = runBlocking(Dispatchers.IO) {
        approved()
        fixture { root, store ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val request = store.prepare("synthetic-owner", "txt") { it.writeText("original") }
            request.file.writeText("tampered")
            val target = File(root, "destination.txt").apply { writeText("keep destination") }
            val result = runCatching {
                savePendingExport(context, store, request.token, request.owner, request.format, Uri.fromFile(target)) { fail("A changed source cannot verify") }
            }
            assertTrue(result.isFailure)
            assertEquals("keep destination", target.readText())
            assertFalse(request.file.exists())
        }
    }

    @Test fun lostProcessRequestAndWrongOwnerCannotTruncateDestination() = runBlocking(Dispatchers.IO) {
        approved()
        fixture { root, store ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val request = store.prepare("synthetic-owner", "txt") { it.writeText("snapshot") }
            val target = File(root, "destination.txt").apply { writeText("keep destination") }
            val uri = Uri.fromFile(target)
            assertTrue(runCatching {
                savePendingExport(context, store, request.token, "another-owner", "txt", uri) { fail("Wrong owner") }
            }.isFailure)
            assertEquals("keep destination", target.readText())
            // A replacement process has no authority to consume the old opaque token.
            val replacement = PendingFileExportStore(request.file.parentFile!!)
            assertTrue(runCatching {
                savePendingExport(context, replacement, request.token, request.owner, request.format, uri) { fail("Lost process request") }
            }.isFailure)
            assertEquals("keep destination", target.readText())
            assertFalse(request.file.exists())
            store.cancel(request.token)
        }
    }
}
