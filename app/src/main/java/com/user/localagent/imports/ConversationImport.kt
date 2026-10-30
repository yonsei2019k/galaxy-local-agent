package com.user.localagent.imports

import android.content.Context
import android.net.Uri
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.NoteRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

/** Counts describe recognized messages in one explicitly supplied file, never all phone history. */
data class ConversationImportResult(val contentSha256: String, val messageCount: Int, val recognizedFormat: String,
    val chunkCount: Int, val encoding: String, val duplicate: Boolean, val noteIds: List<String>, val scopeDescription: String)
data class ConversationImportCatalogItem(val contentSha256: String, val messageCount: Int, val chunkCount: Int,
    val recognizedFormat: String, val firstTimestamp: String?, val lastTimestamp: String?)
data class ConversationExcerpt(val contentSha256: String, val chunk: Int, val text: String, val score: Double,
    val scopeDescription: String)
data class ConversationParseSummary(val messageCount: Int, val recognizedFormat: String,
    val firstTimestamp: String?, val lastTimestamp: String?)

/** No network, message APIs, contact reads, automatic memory creation, or filesystem-path discovery. */
class ConversationImport(context: Context, private val repository: LocalRepository) {
    private val context = context.applicationContext

    suspend fun importUri(uri: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): ConversationImportResult {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "사용자가 선택한 문서 URI가 필요합니다." }
        return importOpened({ context.contentResolver.openInputStream(uri) ?: error("선택한 파일을 열 수 없습니다.") }, true, onProgress)
    }
    suspend fun importText(text: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): ConversationImportResult {
        require(text.length <= MAX_BYTES) { "대화 파일은 20 MiB 이하여야 합니다." }
        return importOpened({ ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }, true, onProgress)
    }
    /** For a host-provided SAF stream; the caller retains stream ownership. */
    suspend fun importStream(input: InputStream, onProgress: (Int, Int) -> Unit = { _, _ -> }): ConversationImportResult =
        importOpened({ input }, false, onProgress)

    private suspend fun importOpened(open: () -> InputStream, close: Boolean, onProgress: (Int, Int) -> Unit): ConversationImportResult = IMPORT_LOCK.withLock {
        val created = mutableListOf<String>()
        try {
            // The lock and rollback registry live outside withContext, including prompt cancellation
            // while the completed IO result is dispatched back to the caller.
            withContext(Dispatchers.IO) {
                val input = open()
                val bytes = try { readBounded(input) } finally { if (close) input.close() }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                val decoded = decode(bytes)
                val text = decoded.first
                require(text.isNotBlank() && '\u0000' !in text) { "지원하는 일반 텍스트 파일이 아닙니다." }
                val parsed = summarize(text)
                val chunks = chunk(text)
                currentCoroutineContext().ensureActive()
                val existing = ownedNotes()
                val complete = existing.firstOrNull { (_, meta) ->
                    if (meta.string("kind") != "manifest" || meta.string("sha256") != sha) false else {
                        val found = completeChunks(existing, meta).sortedBy { it.second.integer("chunk") }
                        found.size == chunks.size && found.zip(chunks).all { (saved, raw) -> saved.first.body.substringAfter('\n', "") == raw }
                    }
                }
                if (complete != null) {
                    val records = completeChunks(existing, complete.second)
                    return@withContext result(sha, parsed, chunks.size, decoded.second, true, records.map { it.first.id } + complete.first.id)
                }
                val occupied = existing.count { it.second.string("kind") == "chunk" }
                require(occupied + chunks.size <= MAX_CHUNKS) { "저장할 대화 조각이 2,000개를 넘습니다. 기존 가져오기 자료를 정리한 뒤 다시 선택해주세요." }
                val run = UUID.randomUUID().toString()
                chunks.forEachIndexed { index, raw ->
                    currentCoroutineContext().ensureActive()
                    val id = UUID.randomUUID().toString()
                    created += id // Know the ID even if cancellation arrives after SQLite commits.
                    val metadata = metadata(sha, run, "chunk", chunks.size, parsed, decoded.second, index + 1)
                    repository.saveNote("대화 자료 ${sha.take(12)} · ${index + 1}/${chunks.size}", metadata.toString() + "\n" + raw, COLLECTION, id)
                    onProgress(index + 1, chunks.size)
                    currentCoroutineContext().ensureActive()
                }
                val manifestId = UUID.randomUUID().toString()
                created += manifestId
                repository.saveNote("대화 자료 ${sha.take(12)} · 범위", metadata(sha, run, "manifest", chunks.size, parsed, decoded.second).toString(), COLLECTION, manifestId)
                currentCoroutineContext().ensureActive()
                result(sha, parsed, chunks.size, decoded.second, false, created.toList())
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                created.forEach { id ->
                    try { repository.deleteNote(id) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                }
            }
            throw error
        }
    }
    /** Completed-source metadata only; no transcript excerpts, paths, or participant identifiers. */
    suspend fun catalog(limit: Int = 20): List<ConversationImportCatalogItem> = withContext(Dispatchers.IO) {
        val notes = ownedNotes()
        notes.asSequence().filter { (_, meta) -> meta.string("kind") == "manifest" && completeChunks(notes, meta).size == meta.integer("chunks") }
            .distinctBy { it.second.string("sha256") }.take(limit.coerceIn(1, 100)).map { (_, meta) ->
                ConversationImportCatalogItem(meta.string("sha256")!!, (meta.integer("recognized_messages") ?: 0).coerceAtLeast(0),
                    meta.integer("chunks")!!, meta.string("format") ?: "plain-text", meta.string("first_timestamp"), meta.string("last_timestamp"))
            }.toList()
    }
    /** Lexical source snippets only. They are untrusted quoted data, not instructions or stored memories. */
    suspend fun retrieve(query: String, limit: Int = 5, contentSha256: String? = null): List<ConversationExcerpt> = withContext(Dispatchers.Default) {
        if (contentSha256 != null) require(SHA.matches(contentSha256))
        val coroutine = currentCoroutineContext()
        val all = ownedNotes()
        val completedRuns = all.filter { it.second.string("kind") == "manifest" && completeChunks(all, it.second).size == it.second.integer("chunks") }
            .mapNotNull { it.second.string("run") }.toSet()
        val terms = query.lowercase(Locale.ROOT).take(500).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }.take(32).toSet()
        all.asSequence().filter { (_, meta) -> meta.string("kind") == "chunk" && meta.string("run") in completedRuns &&
            (contentSha256 == null || meta.string("sha256") == contentSha256) }.take(MAX_CHUNKS).mapNotNull { (note, meta) ->
            coroutine.ensureActive()
            val raw = note.body.substringAfter('\n', "")
            val lower = raw.lowercase(Locale.ROOT)
            val hits = terms.count { it in lower }
            if (terms.isNotEmpty() && hits == 0) null
            else ConversationExcerpt(meta.string("sha256")!!, meta.integer("chunk")!!, raw,
                if (terms.isEmpty()) 0.0 else hits.toDouble() / terms.size, "직접 가져온 파일의 ${meta.integer("chunk")}/${meta.integer("chunks")}번째 조각 · 원문 인용")
        }.sortedWith(compareByDescending<ConversationExcerpt> { it.score }.thenBy { it.contentSha256 }.thenBy { it.chunk })
            .take(limit.coerceIn(1, 10)).toList()
    }
    suspend fun deleteImport(contentSha256: String): Int = IMPORT_LOCK.withLock {
        require(SHA.matches(contentSha256))
        val records = ownedNotes().filter { it.second.string("sha256") == contentSha256 }
        records.forEach { currentCoroutineContext().ensureActive(); repository.deleteNote(it.first.id) }
        records.size
    }

    private suspend fun ownedNotes(): List<Pair<NoteRecord, JsonObject>> = repository.notes.first().asSequence()
        .filter { it.collection == COLLECTION }.mapNotNull { note ->
            val header = note.body.substringBefore('\n')
            if (header.length > 4000) return@mapNotNull null
            val meta = runCatching { Json.parseToJsonElement(header) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            if (meta.string("type") != TYPE || !SHA.matches(meta.string("sha256").orEmpty()) ||
                meta.string("run").isNullOrBlank() || (meta.integer("chunks") ?: 0) !in 1..MAX_CHUNKS) null else note to meta
        }.toList()
    private fun completeChunks(notes: List<Pair<NoteRecord, JsonObject>>, manifest: JsonObject): List<Pair<NoteRecord, JsonObject>> {
        val count = manifest.integer("chunks") ?: return emptyList()
        val found = notes.filter { (_, meta) -> meta.string("kind") == "chunk" && meta.string("run") == manifest.string("run") && meta.string("sha256") == manifest.string("sha256") }
        return if (found.size == count && found.mapNotNull { it.second.integer("chunk") }.toSet() == (1..count).toSet()) found else emptyList()
    }
    private fun metadata(sha: String, run: String, kind: String, count: Int, parsed: ConversationParseSummary, encoding: String, index: Int? = null) = buildJsonObject {
        put("type", TYPE); put("kind", kind); put("sha256", sha); put("run", run); put("chunks", count)
        if (index != null) put("chunk", index)
        put("format", parsed.recognizedFormat); put("encoding", encoding); put("recognized_messages", parsed.messageCount)
        put("first_timestamp", parsed.firstTimestamp); put("last_timestamp", parsed.lastTimestamp)
        put("scope", "user_supplied_single_file_only")
    }
    private fun result(sha: String, parsed: ConversationParseSummary, count: Int, encoding: String, duplicate: Boolean, ids: List<String>) =
        ConversationImportResult(sha, parsed.messageCount, parsed.recognizedFormat, count, encoding, duplicate, ids,
            "직접 선택한 파일 1개 · 인식한 메시지 ${parsed.messageCount}개 · ${count}개 조각 · 파일 밖의 대화·전체 휴대전화 기록은 포함하지 않음")
    private suspend fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - total + 1))
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= MAX_BYTES) { "대화 파일은 20 MiB 이하여야 합니다." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun decode(bytes: ByteArray): Pair<String, String> {
        fun strict(name: String) = Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return try { strict("UTF-8") to "UTF-8" }
        catch (_: CharacterCodingException) {
            try { strict("EUC-KR") to "EUC-KR" }
            catch (_: CharacterCodingException) { throw IllegalArgumentException("UTF-8 또는 EUC-KR 텍스트로 읽을 수 없습니다. 원본 인코딩을 확인해주세요.") }
        }
    }
    private suspend fun chunk(text: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            currentCoroutineContext().ensureActive()
            require(parts.size < MAX_CHUNKS) { "한 파일의 대화 조각이 2,000개를 넘습니다." }
            var end = minOf(start + CHUNK_CHARS, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            parts += text.substring(start, end)
            start = end
        }
        return parts
    }

    companion object {
        const val COLLECTION = "imported-chats"
        const val MAX_BYTES = 20 * 1024 * 1024
        const val MAX_CHUNKS = 2000
        const val CHUNK_CHARS = 8000
        private const val TYPE = "conversation-import-v1"
        private val SHA = Regex("[a-f0-9]{64}")
        private val IMPORT_LOCK = Mutex()
        private val DATE = Regex("^(?:-{3,}\\s*)?(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일(?:\\s+[가-힣]+요일)?(?:\\s*-{3,})?\\s*$")
        private val PC = Regex("^\\[([^\\]]{1,300})]\\s+\\[(?:(오전|오후)\\s*)?(\\d{1,2}):(\\d{1,2})] (.*)$")
        private val MOBILE = Regex("^(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일\\s+(오전|오후)\\s+(\\d{1,2}):(\\d{1,2}),\\s*(.{1,300}?)\\s+: (.*)$")
        private val DOTTED = Regex("^(\\d{4})\\.\\s*(\\d{1,2})\\.\\s*(\\d{1,2})\\.\\s*(오전|오후)\\s+(\\d{1,2}):(\\d{1,2}),\\s*(.{1,300}?)\\s+: (.*)$")
        private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.integer(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

        /** Best-effort known headers; all source characters are preserved separately in encrypted chunks. */
        suspend fun summarize(text: String): ConversationParseSummary {
            csvSummary(text)?.let { return it }
            var date: LocalDate? = null
            var count = 0
            var first: String? = null
            var last: String? = null
            val formats = mutableSetOf<String>()
            fun record(timestamp: String?, format: String) { count++; formats += format; if (first == null) first = timestamp; if (timestamp != null) last = timestamp }
            for ((index, source) in text.lineSequence().withIndex()) {
                if (index % 128 == 0) currentCoroutineContext().ensureActive()
                val line = if (index == 0) source.removePrefix("\uFEFF") else source
                val mobile = MOBILE.matchEntire(line) ?: DOTTED.matchEntire(line)
                if (mobile != null) {
                    val values = mobile.groupValues
                    val timestamp = runCatching { LocalDateTime.of(LocalDate.of(values[1].toInt(), values[2].toInt(), values[3].toInt()), time(values[4], values[5], values[6])).toString() }.getOrNull()
                    if (timestamp != null && values[7].isNotBlank()) record(timestamp, if (line.contains("년")) "kakao-mobile-ko" else "kakao-dotted-ko")
                    continue
                }
                val pc = PC.matchEntire(line)
                if (pc != null && pc.groupValues[1].isNotBlank()) {
                    val clock = runCatching { time(pc.groupValues[2], pc.groupValues[3], pc.groupValues[4]) }.getOrNull()
                    if (clock != null) record(date?.let { LocalDateTime.of(it, clock).toString() }, "kakao-pc-ko")
                    continue
                }
                if (!line.contains(',')) DATE.matchEntire(line)?.let { match ->
                    date = runCatching { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) }.getOrNull()
                }
            }
            return ConversationParseSummary(count, if (formats.isEmpty()) "plain-text" else if (formats.size == 1) formats.single() else "mixed-known-text", first, last)
        }
        private fun time(ampm: String, hour: String, minute: String): LocalTime {
            val rawHour = hour.toInt()
            if (ampm.isNotEmpty()) require(rawHour in 1..12)
            val h = if (ampm.isEmpty()) rawHour else rawHour % 12 + if (ampm == "오후") 12 else 0
            return LocalTime.of(h, minute.toInt())
        }
        private suspend fun csvSummary(text: String): ConversationParseSummary? {
            val header = text.substringBefore('\n').trim().removePrefix("\uFEFF").lowercase(Locale.ROOT)
            if (header !in setOf("date,user,message", "\"date\",\"user\",\"message\"", "날짜,사용자,메시지")) return null
            var position = text.indexOf('\n') + 1
            if (position == 0) return ConversationParseSummary(0, "conversation-csv", null, null)
            var count = 0
            var first: String? = null
            var last: String? = null
            while (position < text.length) {
                currentCoroutineContext().ensureActive()
                if (text[position] == '\n' || text[position] == '\r') { position++; continue }
                val fields = mutableListOf<String>()
                while (fields.size < 4) {
                    val field = StringBuilder()
                    if (text.getOrNull(position) == '"') {
                        position++
                        var closed = false
                        while (position < text.length) {
                            if (position % 4096 == 0) currentCoroutineContext().ensureActive()
                            val char = text[position++]
                            if (char == '"') {
                                if (text.getOrNull(position) == '"') { field.append('"'); position++ } else { closed = true; break }
                            } else field.append(char)
                        }
                        if (!closed) return null
                    } else {
                        while (position < text.length && text[position] !in ",\r\n") { field.append(text[position++]); if (position % 4096 == 0) currentCoroutineContext().ensureActive() }
                    }
                    fields += field.toString()
                    if (text.getOrNull(position) == ',') { position++; continue }
                    if (position < text.length && text[position] !in "\r\n") return null
                    break
                }
                if (fields.size != 3) return null
                val timestamp = runCatching { LocalDateTime.parse(fields[0].replaceFirst(' ', 'T')).toString() }.getOrNull()
                    ?: runCatching { OffsetDateTime.parse(fields[0]).toString() }.getOrNull()
                if (timestamp != null && fields[1].isNotBlank() && fields[2].isNotBlank()) {
                    count++; if (first == null) first = timestamp; last = timestamp
                }
            }
            return ConversationParseSummary(count, "conversation-csv", first, last)
        }
    }
}




