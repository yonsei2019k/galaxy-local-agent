package com.user.localagent.audio

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID

/** Isolated synthetic WAV/cache only. No recording, recognizer, model, account, or provider mutation. */
@RunWith(AndroidJUnit4::class)
class AudioDecoderOrphanValidation {
    @Test fun restartCleanupPreservesLiveDecodeAndUnrelatedFilesButRemovesOwnedOrphans() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("audioDecoderOrphanValidationApproved") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(base.cacheDir, "shared/decoder-orphan-qa-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = fixture
        }
        val release = CompletableDeferred<Unit>()
        var decode: Job? = null
        try {
            val root = File(fixture, "audio-decoder-sessions").apply { check(mkdir()) }
            val orphan = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
            File(orphan, "source.bin").writeBytes(byteArrayOf(1, 2))
            File(orphan, "audio-chunk-1234.wav").writeBytes(byteArrayOf(3))
            val foreign = File(root, "unrelated-directory").apply { check(mkdir()) }
            val foreignFile = File(foreign, "source.bin").apply { writeText("SYNTHETIC_UNRELATED") }
            val outside = File(fixture, "keep.txt").apply { writeText("SYNTHETIC_OUTSIDE") }
            val linked = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
            val link = File(linked, "source.bin")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            val legacy = File(fixture, "audio-source-123456789.bin").apply { writeBytes(byteArrayOf(4)) }
            val legacyDir = File(fixture, "audio-chunks").apply { check(mkdir()) }
            val legacyWav = File(legacyDir, "audio-chunk-987654321.wav").apply { writeBytes(byteArrayOf(5)) }
            val input = File(fixture, "input.wav").apply { writeBytes(silentWav()) }
            val uri = FileProvider.getUriForFile(base, "${base.packageName}.files", input)
            val callback = CompletableDeferred<AudioChunk>()
            decode = launch {
                AudioChunkDecoder(isolated).decode(uri, chunkSeconds = 5) { chunk ->
                    check(callback.complete(chunk))
                    release.await()
                }
            }
            val live = withTimeout(15_000) { callback.await() }
            val liveSession = checkNotNull(live.file.parentFile)
            assertEquals(root.canonicalFile, liveSession.parentFile.canonicalFile)
            assertTrue(File(liveSession, "source.bin").isFile)
            AudioChunkDecoder.cleanupOrphanedSessions(isolated)
            assertFalse(orphan.exists())
            assertTrue(live.file.isFile)
            assertTrue(File(liveSession, "source.bin").isFile)
            assertTrue(legacy.isFile)
            assertTrue(legacyWav.isFile)
            assertTrue(foreignFile.isFile)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("SYNTHETIC_OUTSIDE", outside.readText())

            release.complete(Unit)
            withTimeout(15_000) { checkNotNull(decode).join() }
            assertFalse(liveSession.exists())
            AudioChunkDecoder.cleanupOrphanedSessions(isolated)
            assertFalse(legacy.exists())
            assertFalse(legacyWav.exists())
            assertFalse(legacyDir.exists())
            assertTrue(input.isFile)
            assertTrue(foreignFile.isFile)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("SYNTHETIC_OUTSIDE", outside.readText())
        } finally {
            withContext(NonCancellable) {
                release.complete(Unit)
                decode?.cancelAndJoin()
                check(fixture.canonicalFile.toPath().startsWith(File(base.cacheDir, "shared").canonicalFile.toPath()))
                check(fixture.deleteRecursively()) { "Synthetic decoder fixture cleanup failed" }
            }
        }
    }

    private fun silentWav(): ByteArray = ByteBuffer.allocate(44 + 3200).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + 3200); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1.toShort()); putShort(1.toShort()); putInt(16_000); putInt(32_000)
        putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray()); putInt(3200)
        put(ByteArray(3200))
    }.array()
}
