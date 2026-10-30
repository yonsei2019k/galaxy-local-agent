package com.user.localagent.audio

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/** Exercises decoder bounds with synthetic audio only; it never opens a user's recording. */
@RunWith(AndroidJUnit4::class)
class AudioDecoderValidation {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureDir get() = File(context.cacheDir, "shared/audio-decoder-tests").apply { mkdirs() }
    private val decoder get() = AudioChunkDecoder(context)

    @Test fun wavResamplesToMono16kAndSplitsAtThirtySeconds() = runBlocking {
        val fixture = makePcmWav("split.wav", rate = 16_000, seconds = 31)
        val chunks = mutableListOf<AudioChunk>()
        try {
            decoder.decode(uri(fixture)) { chunk ->
                assertTrue(chunk.file.isFile)
                assertEquals(16_000, readU32(chunk.file, 24))
                assertEquals(1, readU16(chunk.file, 22))
                assertEquals(16, readU16(chunk.file, 34))
                chunks += chunk
            }
            assertEquals(listOf(0, 1), chunks.map { it.index })
            assertEquals(0L, chunks[0].startMillis)
            assertEquals(30_000L, chunks[0].durationMillis)
            assertEquals(30_000L, chunks[1].startMillis)
            assertEquals(1_000L, chunks[1].durationMillis)
            assertTrue(chunks.all { !it.file.exists() })
        } finally { fixture.delete() }
    }

    @Test fun callbackCancellationDeletesCurrentChunkAndTemporaryFiles() = runBlocking {
        val fixture = makePcmWav("cancel.wav", rate = 16_000, seconds = 2)
        val seen = mutableListOf<File>()
        try {
            val failure = runCatching {
                decoder.decode(uri(fixture)) { chunk -> seen += chunk.file; throw CancellationException("test cancellation") }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, seen.size)
            assertTrue(seen.none { it.exists() })
            assertTrue(File(context.cacheDir, "audio-chunks").listFiles().orEmpty().isEmpty())
        } finally { fixture.delete() }
    }

    @Test fun oversizedKnownLengthIsRejectedBeforeReading() = runBlocking {
        val fixture = File(fixtureDir, "oversized-${System.nanoTime()}.wav")
        try {
            RandomAccessFile(fixture, "rw").use { it.setLength(AudioChunkDecoder.MAX_INPUT_BYTES + 1) }
            val failure = runCatching { decoder.decode(uri(fixture)) { fail("oversized input must not produce chunks") } }.exceptionOrNull()
            assertTrue("expected a size-limit error, got $failure", failure is java.io.IOException)
            assertTrue(failure!!.message.orEmpty().contains("100 MB"))
        } finally { fixture.delete() }
    }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun makePcmWav(name: String, rate: Int, seconds: Int): File {
        val file = File(fixtureDir, "$name-${System.nanoTime()}")
        val frames = rate * seconds
        val dataBytes = frames * 2
        RandomAccessFile(file, "rw").use { out ->
            out.writeBytes("RIFF"); writeLe32(out, 36 + dataBytes)
            out.writeBytes("WAVEfmt "); writeLe32(out, 16)
            writeLe16(out, 1); writeLe16(out, 1); writeLe32(out, rate)
            writeLe32(out, rate * 2); writeLe16(out, 2); writeLe16(out, 16)
            out.writeBytes("data"); writeLe32(out, dataBytes)
            repeat(frames) { i ->
                val sample = (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / rate) * 12_000).toInt()
                writeLe16(out, sample)
            }
        }
        return file
    }

    private fun readU16(file: File, offset: Long): Int = RandomAccessFile(file, "r").use {
        it.seek(offset); it.readUnsignedByte() or (it.readUnsignedByte() shl 8)
    }
    private fun readU32(file: File, offset: Long): Int = RandomAccessFile(file, "r").use {
        it.seek(offset); it.readUnsignedByte() or (it.readUnsignedByte() shl 8) or
            (it.readUnsignedByte() shl 16) or (it.readUnsignedByte() shl 24)
    }
    private fun writeLe16(out: RandomAccessFile, n: Int) { out.write(n and 255); out.write((n ushr 8) and 255) }
    private fun writeLe32(out: RandomAccessFile, n: Int) { repeat(4) { out.write((n ushr (8 * it)) and 255) } }
}
