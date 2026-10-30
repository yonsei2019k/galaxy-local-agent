package com.user.localagent.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioChunk(val file: File, val index: Int, val startMillis: Long, val durationMillis: Long)

/** Decodes user-selected audio into short private WAV files without uploading source audio. */
class AudioChunkDecoder(private val context: Context) {
    suspend fun decode(uri: Uri, onChunk: suspend (AudioChunk) -> Unit) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Select an audio file using the system file picker." }
        val dir = File(context.cacheDir, "audio-chunks").apply { mkdirs() }
        val source = File.createTempFile("audio-source-", ".bin", context.cacheDir)
        val writer = ChunkWriter(dir, onChunk)
        try {
            copyBounded(uri, source)
            val header = ByteArray(12)
            source.inputStream().use { it.read(header) }
            if (header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
                decodeWav(source, writer)
            } else {
                decodeCompressed(source, writer)
            }
            writer.finish()
        } finally {
            writer.abort()
            source.delete()
            dir.listFiles()?.takeIf { it.isEmpty() }?.let { dir.delete() }
        }
    }

    private suspend fun copyBounded(uri: Uri, target: File) {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.length > MAX_INPUT_BYTES) throw IOException("Audio file exceeds the 100 MB limit.")
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_INPUT_BYTES) throw IOException("Audio file exceeds the 100 MB limit.")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException("The selected audio file is empty.")
            }
        } ?: throw IOException("Could not open the selected audio file.")
    }

    private suspend fun decodeWav(file: File, writer: ChunkWriter) {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44L) throw IOException("Malformed WAV file.")
            raf.seek(12)
            var format = -1
            var channels = 0
            var rate = 0
            var bits = 0
            var dataStart = -1L
            var dataSize = -1L
            while (raf.filePointer + 8 <= raf.length()) {
                currentCoroutineContext().ensureActive()
                val id = ByteArray(4).also(raf::readFully).toString(Charsets.US_ASCII)
                val size = readU32(raf)
                val body = raf.filePointer
                if (size > raf.length() - body) throw IOException("Malformed WAV chunk bounds.")
                when (id) {
                    "fmt " -> {
                        if (size < 16) throw IOException("Malformed WAV format header.")
                        format = readU16(raf)
                        channels = readU16(raf)
                        rate = readU32(raf).toInt()
                        raf.skipBytes(6)
                        bits = readU16(raf)
                    }
                    "data" -> { dataStart = body; dataSize = size }
                }
                raf.seek(body + size + (size and 1L))
                if (dataStart >= 0 && format >= 0) break
            }
            if (format !in listOf(1, 3) || channels !in 1..2 || rate !in MIN_RATE..MAX_RATE ||
                !((format == 1 && bits == 16) || (format == 3 && bits == 32)) || dataStart < 0 || dataSize < 0) {
                throw IOException("Unsupported or malformed WAV. Use PCM 16-bit or float WAV audio.")
            }
            val bytesPerSample = bits / 8
            val frameBytes = channels * bytesPerSample
            if (dataSize % frameBytes != 0L) throw IOException("Malformed WAV sample data.")
            val frames = dataSize / frameBytes
            if (frames * 1000L / rate > MAX_DURATION_MS) throw IOException("Audio duration exceeds two hours.")
            raf.seek(dataStart)
            val bytes = ByteArray(frameBytes * 4096)
            val resampler = LinearResampler(rate, writer)
            var remaining = dataSize
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val count = minOf(bytes.size.toLong(), remaining).toInt().let { it - (it % frameBytes) }
                raf.readFully(bytes, 0, count)
                val bb = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN)
                while (bb.remaining() >= frameBytes) {
                    val left = readSample(bb, format, bits)
                    val mono = if (channels == 2) (left + readSample(bb, format, bits)) * 0.5f else left
                    resampler.push(mono)
                }
                remaining -= count
            }
            resampler.finish()
        }
    }

    private suspend fun decodeCompressed(file: File, writer: ChunkWriter) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var sourceFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; sourceFormat = format; break
                }
            }
            if (track < 0) throw IOException("This file does not contain a supported audio track.")
            val selectedFormat = sourceFormat ?: throw IOException("Audio track format is unavailable.")
            val durationUs = try { selectedFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
            if (durationUs <= 0 || durationUs / 1000 > MAX_DURATION_MS) throw IOException("Audio duration is unavailable or exceeds two hours.")
            extractor.selectTrack(track)
            val mime = selectedFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException("Unknown audio format.")
            codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                throw IOException("No local decoder is available for this audio format.", e)
            }
            codec.configure(selectedFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var idleLoops = 0
            var outputRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = LinearResampler(outputRate, writer)
            var pcmStarted = false
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                var progressed = false
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw IOException("Decoder input buffer unavailable.")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (pcmStarted) throw IOException("Audio output format changed during decoding.")
                        val fmt = codec.outputFormat
                        outputRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = try { fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) } catch (_: Exception) { AudioFormat.ENCODING_PCM_16BIT }
                        if (outputRate !in MIN_RATE..MAX_RATE || outputChannels !in 1..2 ||
                            encoding !in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) {
                            throw IOException("The decoded audio format is unsupported.")
                        }
                        resampler = LinearResampler(outputRate, writer)
                        progressed = true
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex) ?: throw IOException("Decoder output buffer unavailable.")
                        if (info.size > 0) {
                            pcmStarted = true
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frameBytes = outputChannels * bytesPerSample
                            if (info.size % frameBytes != 0) throw IOException("Decoder returned partial PCM frame.")
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            while (buffer.remaining() >= frameBytes) {
                                val left = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float.coerceIn(-1f, 1f) else buffer.short / 32768f
                                val mono = if (outputChannels == 2) {
                                    val right = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float.coerceIn(-1f, 1f) else buffer.short / 32768f
                                    (left + right) * 0.5f
                                } else left
                                resampler.push(mono)
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        progressed = true
                    }
                }
                if (progressed) idleLoops = 0 else if (++idleLoops > MAX_IDLE_LOOPS) throw IOException("Audio decoder stopped making progress.")
            }
            resampler.finish()
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            codec?.release()
            extractor.release()
        }
    }

    private fun readSample(buffer: ByteBuffer, format: Int, bits: Int): Float =
        if (format == 3 && bits == 32) buffer.float.coerceIn(-1f, 1f) else (buffer.short / 32768f)

    private fun readU16(raf: RandomAccessFile): Int = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
    private fun readU32(raf: RandomAccessFile): Long = readU16(raf).toLong() or (readU16(raf).toLong() shl 16)

    private class LinearResampler(private val inputRate: Int, private val writer: ChunkWriter) {
        private val step = inputRate.toDouble() / OUTPUT_RATE
        private var inputIndex = 0L
        private var nextOutputPosition = 0.0
        private var previous = 0f
        private var hasPrevious = false
        suspend fun push(sample: Float) {
            if (!hasPrevious) {
                writer.write(sample); hasPrevious = true; previous = sample; inputIndex = 1; nextOutputPosition = step
                return
            }
            val currentIndex = inputIndex.toDouble()
            while (nextOutputPosition <= currentIndex) {
                val fraction = (nextOutputPosition - (currentIndex - 1.0)).coerceIn(0.0, 1.0).toFloat()
                writer.write(previous + (sample - previous) * fraction)
                nextOutputPosition += step
            }
            previous = sample
            inputIndex++
        }
        suspend fun finish() = Unit
    }

    private class ChunkWriter(private val directory: File, private val callback: suspend (AudioChunk) -> Unit) {
        private var output: RandomAccessFile? = null
        private var file: File? = null
        private var framesInChunk = 0
        private var totalFrames = 0L
        private var index = 0

        suspend fun write(sample: Float) {
            currentCoroutineContext().ensureActive()
            if (totalFrames >= MAX_OUTPUT_FRAMES) throw IOException("Decoded audio exceeds two hours.")
            if (output == null) openChunk()
            val pcm = (sample.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
            output!!.write(pcm and 0xff); output!!.write((pcm shr 8) and 0xff)
            framesInChunk++; totalFrames++
            if (framesInChunk == FRAMES_PER_CHUNK) emit()
        }

        private fun openChunk() {
            file = File.createTempFile("audio-chunk-", ".wav", directory)
            output = RandomAccessFile(file, "rw").also { it.write(ByteArray(44)) }
            framesInChunk = 0
        }

        suspend fun finish() { if (framesInChunk > 0) emit() }

        private suspend fun emit() {
            val raf = output ?: return
            val chunkFile = file ?: return
            val dataBytes = framesInChunk * 2
            raf.seek(0)
            raf.write("RIFF".toByteArray(Charsets.US_ASCII)); writeLe32(raf, 36 + dataBytes)
            raf.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); writeLe32(raf, 16)
            writeLe16(raf, 1); writeLe16(raf, 1); writeLe32(raf, OUTPUT_RATE)
            writeLe32(raf, OUTPUT_RATE * 2); writeLe16(raf, 2); writeLe16(raf, 16)
            raf.write("data".toByteArray(Charsets.US_ASCII)); writeLe32(raf, dataBytes)
            raf.fd.sync(); raf.close(); output = null; file = null
            val start = (totalFrames - framesInChunk) * 1000 / OUTPUT_RATE
            val duration = framesInChunk * 1000L / OUTPUT_RATE
            val chunk = AudioChunk(chunkFile, index++, start, duration)
            try { callback(chunk) } finally { chunkFile.delete() }
            framesInChunk = 0
        }

        fun abort() { try { output?.close() } catch (_: Exception) { }; output = null; file?.delete(); file = null }
        private fun writeLe16(raf: RandomAccessFile, n: Int) { raf.write(n and 255); raf.write((n shr 8) and 255) }
        private fun writeLe32(raf: RandomAccessFile, n: Int) { repeat(4) { raf.write((n ushr (8 * it)) and 255) } }
    }

    companion object {
        const val MAX_INPUT_BYTES = 100L * 1024 * 1024
        const val MAX_DURATION_MS = 2L * 60 * 60 * 1000
        const val OUTPUT_RATE = 16_000
        private const val MIN_RATE = 8_000
        private const val MAX_RATE = 192_000
        private const val FRAMES_PER_CHUNK = 30 * OUTPUT_RATE
        private const val MAX_OUTPUT_FRAMES = 2L * 60 * 60 * OUTPUT_RATE
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_IDLE_LOOPS = 3_000
    }
}
