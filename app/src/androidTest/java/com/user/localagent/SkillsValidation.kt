package com.user.localagent

import android.content.Intent
import android.content.IntentFilter
import android.net.MailTo
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.skills.BuiltinSkills
import java.io.File
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ContentResolver/PdfRenderer checks using synthetic, same-UID FileProvider fixtures. */
@RunWith(AndroidJUnit4::class)
class SkillsValidation {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val skills get() = BuiltinSkills(context)

    @Test fun utf8DocumentRoundTripAndOverwrite() = runBlocking {
        withFixtures { fixtures ->
            val file = fixtures.create("document.md")
            val uri = fixtures.uri(file)
            val text = "# ZipHap 문서 테스트\n\n한국어와 UTF-8 기호: 가 나 다, café, ✓\n**원문 보존** — 수식 =1+1도 텍스트입니다.\n"
            skills.writeDocument(uri, text + "이전 내용은 남으면 안 됩니다.\n".repeat(40))
            skills.writeDocument(uri, text)
            assertEquals("UTF-8 Markdown must remain literal source text", text, skills.readDocument(uri))
            assertArrayEquals("Writing a shorter document must truncate old content", text.toByteArray(Charsets.UTF_8), file.readBytes())
        }
    }

    @Test fun csvKoreanQuotedMultilineRoundTrip() = runBlocking {
        withFixtures { fixtures ->
            val input = fixtures.create("input.csv")
            val output = fixtures.create("output.csv")
            input.writeText("항목,내용\r\n테스트행,\"쉼표, 인용 \"\"확인\"\"\"\r\n여러줄,\"첫 줄\n둘째 줄\"\r\n빈칸,\r\n", Charsets.UTF_8)
            val expected = listOf(
                listOf("항목", "내용"),
                listOf("테스트행", "쉼표, 인용 \"확인\""),
                listOf("여러줄", "첫 줄\n둘째 줄"),
                listOf("빈칸", ""),
            )
            val imported = skills.readCsv(fixtures.uri(input))
            assertEquals("CSV import must preserve columns, quotes, newlines and Korean", expected, imported)
            skills.writeCsv(fixtures.uri(output), imported)
            assertEquals("Exported CSV must preserve ordinary values", expected, skills.readCsv(fixtures.uri(output)))
            val exported = output.readText(Charsets.UTF_8)
            assertTrue("Export must escape embedded double quotes", exported.contains("\"\"확인\"\""))
            assertTrue("Export must preserve quoted multiline cells", exported.contains("첫 줄\n둘째 줄"))
        }
    }

    @Test fun csvFormulaLikeCellsAreLiteralAndNeutralizedOnExport() = runBlocking {
        withFixtures { fixtures ->
            val input = fixtures.create("formula-input.csv")
            val output = fixtures.create("formula-output.csv")
            input.writeText("\"=1+1\"\r\n", Charsets.UTF_8)
            assertEquals("Formula input is a value, never an evaluated result", listOf(listOf("=1+1")), skills.readCsv(fixtures.uri(input)))
            val values = listOf("=1+1", "+SUM(1,2)", "-42", "@SUM(A1:A2)", "  =1+1", "\t=1+1")
            skills.writeCsv(fixtures.uri(output), values.map { listOf(it) } + listOf(listOf("한국어 원문")))
            val returned = skills.readCsv(fixtures.uri(output))
            assertEquals(values.size + 1, returned.size)
            values.forEachIndexed { index, original ->
                assertEquals("Formula-like export must become an apostrophe-prefixed literal", "'$original", returned[index].single())
            }
            assertEquals("Ordinary text must stay unchanged", "한국어 원문", returned.last().single())
        }
    }

    @Test fun pdfCreationInspectionAndKoreanEmbeddedText() = runBlocking {
        withFixtures { fixtures ->
            val file = fixtures.create("document.pdf")
            val uri = fixtures.uri(file)
            val text = "ZipHap 문서 테스트\n" + (1..90).joinToString("\n") { number -> "테스트 줄 $number — 기기 안에서 만든 PDF 원문입니다." }
            val writtenPages = skills.writePdf(uri, text, "ZipHap 문서 테스트")
            assertTrue("Native PDF writer must produce at least one page", writtenPages >= 1)
            assertTrue("Synthetic long document must exercise pagination", writtenPages >= 2)
            assertTrue("Output must have a PDF signature", file.inputStream().use { input ->
                val signature = ByteArray(5)
                input.read(signature) == 5 && signature.toString(Charsets.US_ASCII) == "%PDF-"
            })
            val inspection = skills.inspectPdf(uri, includeText = true)
            assertEquals("Rendered PDF page count must match writer result", writtenPages, inspection.pageCount)
            assertEquals(writtenPages, inspection.pages.size)
            assertEquals((1..writtenPages).toList(), inspection.pages.map { it.number })
            assertTrue("Pages must have valid dimensions", inspection.pages.all { it.width > 0 && it.height > 0 })
            assertFalse("Small fixture text must not be truncated", inspection.textTruncated)
            if (Build.VERSION.SDK_INT >= 35) {
                assertTrue("Android 15+ must expose embedded-text extraction", inspection.textSupported)
                val normalized = Normalizer.normalize(inspection.pages.joinToString("\n") { it.text.orEmpty() }, Normalizer.Form.NFKC)
                    .filterNot { it.isWhitespace() }
                listOf("ZipHap", "문서", "테스트").forEach { token ->
                    assertTrue("Embedded PDF text is missing expected synthetic token: $token", normalized.contains(token))
                }
            } else {
                assertFalse(inspection.textSupported)
                assertTrue(inspection.pages.all { it.text == null })
            }
            val metadataOnly = skills.inspectPdf(uri, includeText = false)
            assertEquals(writtenPages, metadataOnly.pageCount)
            assertTrue("Metadata-only inspection must not return text", metadataOnly.pages.all { it.text == null })
        }
    }

    @Test fun emailComposeOnlyPreservesRecipientsAndEncodedDraft() {
        val filter = IntentFilter(Intent.ACTION_SENDTO).apply { addDataScheme("mailto") }
        // Block any accidental SENDTO activity start during this check; the expected count is zero.
        val monitor = instrumentation.addMonitor(filter, null, true)
        try {
            val recipients = listOf("qa+compose@example.test", "second@example.test")
            val subject = "ZipHap 메일 초안 & 확인"
            val body = "보내기 전에 사용자가 검토합니다.\n예약=없음 & 자동발송 없음"
            val intent = skills.composeEmail(recipients, subject, body)
            assertEquals(Intent.ACTION_SENDTO, intent.action)
            val uri = requireNotNull(intent.data)
            assertEquals("mailto", uri.scheme)
            assertArrayEquals(recipients.toTypedArray(), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
            assertEquals(subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(body, intent.getStringExtra(Intent.EXTRA_TEXT))
            // AOSP MailTo.parse calls getQuery() before splitting '&', decoding escaped
            // delimiters twice. Verify the actual RFC-encoded query plus Intent extras.
            val raw=uri.toString()
            assertEquals(recipients.joinToString(","), Uri.decode(raw.substringAfter("mailto:").substringBefore('?')))
            val query=Uri.parse("https://example.test/?"+raw.substringAfter('?'))
            assertEquals("Subject must survive URI escaping", subject, query.getQueryParameter("subject"))
            assertEquals("Body must survive URI escaping", body, query.getQueryParameter("body"))
            assertEquals("Compose must return an Intent without opening or sending email", 0, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun malformedUtf8AndMalformedCsvAreRejected() = runBlocking {
        withFixtures { fixtures ->
            val invalidText = fixtures.create("invalid.txt")
            invalidText.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            assertTrue("Malformed UTF-8 must not be silently repaired", runCatching { skills.readDocument(fixtures.uri(invalidText)) }.isFailure)
            val invalidCsv = fixtures.create("invalid.csv")
            invalidCsv.writeText("label,value\r\nrow,\"unclosed field", Charsets.UTF_8)
            assertTrue("An unclosed CSV quote must fail explicitly", runCatching { skills.readCsv(fixtures.uri(invalidCsv)) }.isFailure)
        }
    }

    private inner class Fixtures(val directory: File) {
        val files = mutableListOf<File>()
        fun create(name: String): File {
            require(Regex("[a-z0-9-]+\\.(?:txt|md|csv|pdf)").matches(name))
            val file = File(directory, name)
            check(file.canonicalFile.parentFile == directory.canonicalFile)
            check(file.createNewFile()) { "Synthetic fixture already exists" }
            files += file
            return file
        }
        fun uri(file: File): Uri {
            check(file in files && file.canonicalFile.parentFile == directory.canonicalFile)
            return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
    }

    private suspend fun withFixtures(block: suspend (Fixtures) -> Unit) = withContext(Dispatchers.IO) {
        val filesRoot = context.filesDir.canonicalFile
        val exportsRoot = File(filesRoot, "exports").canonicalFile
        check(exportsRoot.parentFile == filesRoot)
        check(exportsRoot.isDirectory || exportsRoot.mkdir())
        val directory = File(exportsRoot, "qa-skills-${UUID.randomUUID()}").canonicalFile
        check(directory.parentFile == exportsRoot)
        check(directory.mkdir())
        val fixtures = Fixtures(directory)
        var failure: Throwable? = null
        try {
            block(fixtures)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                val cleanupErrors = mutableListOf<Throwable>()
                fixtures.files.asReversed().forEach { file ->
                    runCatching {
                        // Delete individually recorded files only; never recursively delete a directory.
                        check(file.canonicalFile.parentFile == directory && file.canonicalFile.name == file.name)
                        check(!file.exists() || (file.isFile && file.delete()))
                    }.exceptionOrNull()?.let(cleanupErrors::add)
                }
                runCatching {
                    check(directory.canonicalFile.parentFile == exportsRoot && directory.name.startsWith("qa-skills-"))
                    check(directory.listFiles()?.isEmpty() == true) { "Unexpected files left in owned fixture directory" }
                    check(directory.delete())
                }.exceptionOrNull()?.let(cleanupErrors::add)
                if (cleanupErrors.isNotEmpty()) {
                    if (failure != null) cleanupErrors.forEach { failure!!.addSuppressed(it) }
                    else {
                        cleanupErrors.drop(1).forEach { cleanupErrors.first().addSuppressed(it) }
                        throw cleanupErrors.first()
                    }
                }
            }
        }
    }
}
