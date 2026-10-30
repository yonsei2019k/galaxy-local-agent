package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.imports.ConversationImport
import com.user.localagent.imports.ConversationImportResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.UUID

/** Synthetic data only. Each test cleans only its own generated note IDs. No live messages are read. */
@RunWith(AndroidJUnit4::class)
class ImportValidation {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = (context.applicationContext as LocalAgentApplication).container.repository
    private val importer get() = ConversationImport(context, repository)

    @Test fun koreanPcExportIsCountedPreservedAndDeduplicated() = runBlocking {
        val token = "qa-import-" + UUID.randomUUID()
        val raw = "$token\r\n--------------- 2026년 10월 30일 금요일 ---------------\r\n[합성A] [오전 9:01] 회의 자료 확인 $token\r\n이어지는 줄\r\n[합성B] [오후 1:02] 확인했습니다\r\n"
        val own = mutableSetOf<String>()
        try {
            val result = importer.importText(raw)
            own += result.noteIds
            assertEquals(2, result.messageCount)
            assertEquals("kakao-pc-ko", result.recognizedFormat)
            assertEquals("UTF-8", result.encoding)
            assertEquals(raw, reconstruct(result))
            assertTrue(result.scopeDescription.contains("직접 선택한 파일 1개"))
            val again = importer.importText(raw)
            assertTrue(again.duplicate)
            assertEquals(result.contentSha256, again.contentSha256)
            assertEquals(result.noteIds.toSet(), again.noteIds.toSet())
            val snippets = importer.retrieve("회의 자료", contentSha256 = result.contentSha256)
            assertTrue(snippets.isNotEmpty())
            assertTrue(snippets.all { it.contentSha256 == result.contentSha256 && it.text.length <= 8000 })
        } finally { withContext(NonCancellable) { own.forEach { repository.deleteNote(it) } } }
    }

    @Test fun mobileEucKrAndQuotedCsvUseOnlySyntheticData() = runBlocking {
        val token = "qa-import-" + UUID.randomUUID()
        val mobile = "2026년 10월 30일 오전 12:05, 합성A : 자료 확인 $token\n2026년 10월 30일 오후 12:10, 합성B : 회신\n"
        val csv = "Date,User,Message\r\n2026-10-30 09:00:12,\"합성A\",\"줄 하나 $token\r\n줄 둘, \"\"인용\"\"\"\r\n2026-10-30 09:01:00,\"합성B\",\"회신\"\r\n"
        val own = mutableSetOf<String>()
        try {
            val first = ByteArrayInputStream(mobile.toByteArray(Charset.forName("EUC-KR"))).use { importer.importStream(it) }
            own += first.noteIds
            assertEquals("EUC-KR", first.encoding)
            assertEquals(2, first.messageCount)
            assertEquals("kakao-mobile-ko", first.recognizedFormat)
            assertEquals(mobile, reconstruct(first))
            val parsed = ConversationImport.summarize(mobile)
            assertEquals("2026-10-30T00:05", parsed.firstTimestamp)
            assertEquals("2026-10-30T12:10", parsed.lastTimestamp)
            val second = importer.importText(csv)
            own += second.noteIds
            assertEquals("conversation-csv", second.recognizedFormat)
            assertEquals(2, second.messageCount)
            assertEquals(csv, reconstruct(second))
        } finally { withContext(NonCancellable) { own.forEach { repository.deleteNote(it) } } }
    }

    @Test fun unknownFormatPreservesEveryCharacterAcrossChunkBoundaries() = runBlocking {
        val token = "qa-import-" + UUID.randomUUID()
        val raw = "$token\r\n알 수 없는 형식\r\n" + "가".repeat(7_930) + "😀\r\n이 문장도 원문 데이터입니다.\n" + "나".repeat(8_050)
        val own = mutableSetOf<String>()
        try {
            val result = importer.importText(raw)
            own += result.noteIds
            assertEquals("plain-text", result.recognizedFormat)
            assertEquals(0, result.messageCount)
            assertTrue(result.chunkCount >= 3)
            assertEquals(raw, reconstruct(result))
        } finally { withContext(NonCancellable) { own.forEach { repository.deleteNote(it) } } }
    }

    @Test fun oversizedAndCancelledImportsPreserveExistingNotes() = runBlocking {
        val token = "qa-import-" + UUID.randomUUID()
        val keep = repository.saveNote("QA import preservation", token, "__qa__")
        try {
            val before = repository.notes.first().map { it.id }.toSet()
            val oversized = object : InputStream() {
                var remaining = ConversationImport.MAX_BYTES + 1
                override fun read(): Int = if (remaining-- > 0) 65 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(length, remaining)
                    buffer.fill(65.toByte(), offset, offset + count)
                    remaining -= count
                    return count
                }
            }
            val failure = runCatching { oversized.use { importer.importStream(it) } }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(before, repository.notes.first().map { it.id }.toSet())
            val child = launch {
                importer.importText("$token\n" + "합성 자료\n".repeat(5_000)) { completed, _ ->
                    if (completed == 1) coroutineContext.cancel()
                }
            }
            child.join()
            assertTrue(child.isCancelled)
            assertEquals(before, repository.notes.first().map { it.id }.toSet())
            assertEquals(token, repository.getNote(keep.id)?.body)
        } finally { withContext(NonCancellable) { repository.deleteNote(keep.id) } }
    }

    private suspend fun reconstruct(result: ConversationImportResult): String = repository.notes.first().asSequence()
        .filter { it.id in result.noteIds }.map { it to Json.parseToJsonElement(it.body.substringBefore('\n')).jsonObject }
        .filter { it.second["kind"]?.jsonPrimitive?.content == "chunk" }
        .sortedBy { it.second.getValue("chunk").jsonPrimitive.int }
        .joinToString("") { (note, _) -> note.body.substringAfter('\n') }
}
