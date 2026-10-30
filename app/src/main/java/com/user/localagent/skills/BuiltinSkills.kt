package com.user.localagent.skills

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native bounded operations. The Activity obtains SAF grants and launches returned intents. */
class BuiltinSkills(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    data class BuiltinSkillCapability(
        val name: String,
        val description: String,
        val arguments: String,
        val effect: String,
    )

    data class PdfPageInfo(val number: Int, val width: Int, val height: Int, val text: String?)
    data class PdfInspection(
        val pageCount: Int,
        val pages: List<PdfPageInfo>,
        val textSupported: Boolean,
        val textTruncated: Boolean,
    )

    val catalog = listOf(
        BuiltinSkillCapability("document.read", "Read UTF-8 plain text or Markdown; no DOCX", "uri: content URI selected by user", "read"),
        BuiltinSkillCapability("document.write", "Write UTF-8 plain text or Markdown to user-selected destination", "uri: content URI, text: string", "write; confirmation required"),
        BuiltinSkillCapability("spreadsheet.read_csv", "Read CSV values only; no formula or macro execution; no XLSX", "uri: content URI selected by user", "read"),
        BuiltinSkillCapability("spreadsheet.write_csv", "Write escaped CSV; prefix formula-like cells with apostrophe", "uri: content URI, rows: array of string arrays", "write; confirmation required"),
        BuiltinSkillCapability("pdf.inspect", "Read PDF page dimensions and embedded text on Android 15+; no OCR", "uri: content URI, includeText: boolean = true", "read"),
        BuiltinSkillCapability("pdf.create", "Create a paginated plain text PDF", "uri: content URI, text: string, title: string", "write; confirmation required"),
        BuiltinSkillCapability("email.compose", "Prepare mailto intent for external email app; user sends it", "to: string array, subject: string, body: string", "external UI; confirmation required"),
    )

    /** Model-visible catalog, not a grant or automatic tool-execution registry. */
    fun catalogJson(): String = buildJsonArray {
        catalog.forEach { capability ->
            add(buildJsonObject {
                put("name", capability.name)
                put("description", capability.description)
                put("arguments", capability.arguments)
                put("effect", capability.effect)
            })
        }
    }.toString()

    suspend fun readDocument(uri: Uri): String = withContext(Dispatchers.IO) {
        decodeUtf8(readUri(uri, MAX_TEXT_BYTES)).also { validateText(it) }
    }

    suspend fun writeDocument(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        validateText(text)
        writeBytes(uri, text.toByteArray(Charsets.UTF_8))
    }

    suspend fun readCsv(uri: Uri): List<List<String>> = withContext(Dispatchers.IO) {
        parseCsv(decodeUtf8(readUri(uri, MAX_CSV_BYTES)))
    }

    suspend fun writeCsv(uri: Uri, rows: List<List<String>>) = withContext(Dispatchers.IO) {
        writeBytes(uri, encodeCsv(rows).toByteArray(Charsets.UTF_8))
    }

    /** A seekable private copy supports SAF providers that return pipes, with a hard byte limit. */
    suspend fun inspectPdf(uri: Uri, includeText: Boolean = true): PdfInspection = withContext(Dispatchers.IO) {
        checkUri(uri)
        val temporary = File.createTempFile("skill-pdf-", ".pdf", app.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        count += read
                        require(count <= MAX_PDF_BYTES) { "PDF exceeds 16 MiB" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Unable to open PDF")
            val descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            // Renderer takes ownership of its descriptor. Close it ourselves if construction fails.
            val renderer = try { PdfRenderer(descriptor) } catch (failure: Throwable) {
                descriptor.close()
                throw failure
            }
            renderer.use {
                require(renderer.pageCount in 1..MAX_PDF_PAGES) { "PDF must have 1–100 pages" }
                val supportsText = Build.VERSION.SDK_INT >= 35
                var remaining = MAX_PDF_TEXT_CHARS
                var truncated = false
                val pages = (0 until renderer.pageCount).map { index ->
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(index).use { page ->
                        val text = if (includeText && Build.VERSION.SDK_INT >= 35) {
                            val result = StringBuilder()
                            for (content in page.textContents) {
                                currentCoroutineContext().ensureActive()
                                val value = content.text
                                val added = value.take(remaining)
                                result.append(added)
                                remaining -= added.length
                                if (added.length < value.length) truncated = true
                                if (remaining > 0 && result.isNotEmpty()) {
                                    result.append('\n')
                                    remaining--
                                }
                            }
                            result.toString()
                        } else null
                        PdfPageInfo(index + 1, page.width, page.height, text)
                    }
                }
                PdfInspection(renderer.pageCount, pages, supportsText, truncated)
            }
        } finally {
            temporary.delete()
        }
    }

    /** Returns actual pages written. Font fallback depends on installed Android system fonts. */
    suspend fun writePdf(uri: Uri, text: String, title: String = "Local document"): Int = withContext(Dispatchers.IO) {
        checkUri(uri)
        validateText(text)
        require(text.length <= MAX_PDF_TEXT_CHARS) { "PDF text exceeds 200000 characters" }
        require(title.length <= 200 && !title.contains('\u0000')) { "Title exceeds 200 characters or contains NUL" }
        val document = PdfDocument()
        val temporary = File.createTempFile("skill-output-", ".pdf", app.cacheDir)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        var page: PdfDocument.Page? = null
        var pages = 0
        var y = 0f
        try {
            fun nextPage() {
                page?.let { document.finishPage(it) }
                page = null
                require(pages < MAX_PDF_PAGES) { "PDF output exceeds 100 pages" }
                pages++
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pages).create())
                y = 48f
            }
            nextPage()
            for (line in (title + "\n\n" + text).replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                currentCoroutineContext().ensureActive()
                var offset = 0
                do {
                    if (y > 794f) nextPage()
                    val remaining = line.substring(offset)
                    var length = if (remaining.isEmpty()) 0 else paint.breakText(remaining, true, 499f, null).coerceAtLeast(1)
                    if (length < remaining.length && length > 0 && Character.isHighSurrogate(remaining[length - 1])) length--
                    if (length == 0 && remaining.isNotEmpty()) length = Character.charCount(remaining.codePointAt(0))
                    page!!.canvas.drawText(remaining, 0, length, 48f, y, paint)
                    y += 18f
                    offset += length
                } while (offset < line.length)
            }
            page?.let { document.finishPage(it) }
            page = null
            temporary.outputStream().use { document.writeTo(it) }
            require(temporary.length() <= MAX_PDF_BYTES) { "Generated PDF exceeds 16 MiB" }
            resolver.openOutputStream(uri, "wt")?.use { output ->
                temporary.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Unable to open output destination")
            pages
        } finally {
            page?.let { document.finishPage(it) }
            document.close()
            temporary.delete()
        }
    }

    /** Never launches an Activity or sends email. User reviews and sends in their email app. */
    fun composeEmail(to: List<String>, subject: String, body: String): Intent {
        require(to.size in 1..20) { "Choose 1–20 recipients" }
        require(to.all { it.length in 3..254 && EMAIL.matches(it) }) { "Invalid recipient address" }
        require(subject.length <= 500 && '\r' !in subject && '\n' !in subject && '\u0000' !in subject) { "Invalid subject" }
        require(body.length <= 10000 && '\u0000' !in body) { "Email body exceeds 10000 characters or contains NUL" }
        val mailto = "mailto:" + to.joinToString(",") { Uri.encode(it, "@") } +
            "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body)
        return Intent(Intent.ACTION_SENDTO, Uri.parse(mailto))
            .putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
    }

    private fun checkUri(uri: Uri) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "Use a content URI selected with Android's file picker" }
    }

    private suspend fun readUri(uri: Uri, limit: Int): ByteArray {
        checkUri(uri)
        return resolver.openInputStream(uri)?.use { readBounded(it, limit) } ?: error("Unable to open input")
    }

    private suspend fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read == -1) break
            require(output.size().toLong() + read <= limit) { "Input exceeds $limit bytes" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private suspend fun writeBytes(uri: Uri, bytes: ByteArray) {
        checkUri(uri)
        require(bytes.size <= MAX_TEXT_BYTES) { "Output exceeds 4 MiB" }
        currentCoroutineContext().ensureActive()
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Unable to open output destination")
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

    private fun validateText(text: String) {
        require(text.length <= MAX_TEXT_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "Text exceeds 4 MiB" }
        require('\u0000' !in text) { "Binary input is unsupported; use UTF-8 text or Markdown" }
    }

    companion object {
        const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        const val MAX_CSV_BYTES = 1024 * 1024
        const val MAX_CSV_ROWS = 10000
        const val MAX_CSV_COLUMNS = 128
        const val MAX_CSV_CELL_CHARS = 16384
        const val MAX_PDF_BYTES = 16L * 1024 * 1024
        const val MAX_PDF_PAGES = 100
        const val MAX_PDF_TEXT_CHARS = 200000
        private val EMAIL = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}")

        /** Strict comma-separated records. Empty input is an empty table; quoted multiline fields supported. */
        fun parseCsv(text: String): List<List<String>> {
            require(text.length <= MAX_CSV_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_CSV_BYTES) { "CSV exceeds 1 MiB" }
            require('\u0000' !in text) { "CSV contains NUL" }
            if (text.isEmpty()) return emptyList()
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var afterQuote = false
            var index = if (text.startsWith('\uFEFF')) 1 else 0
            fun endField() {
                require(row.size < MAX_CSV_COLUMNS) { "CSV exceeds 128 columns" }
                row.add(field.toString())
                field.setLength(0)
                afterQuote = false
            }
            fun endRow() {
                endField()
                require(rows.size < MAX_CSV_ROWS) { "CSV exceeds 10000 rows" }
                rows.add(row)
                row = mutableListOf()
            }
            while (index < text.length) {
                val char = text[index++]
                if (quoted) {
                    if (char == '"') {
                        if (index < text.length && text[index] == '"') {
                            field.append('"')
                            index++
                        } else {
                            quoted = false
                            afterQuote = true
                        }
                    } else field.append(char)
                } else {
                    when (char) {
                        ',' -> endField()
                        '\r', '\n' -> {
                            if (char == '\r' && index < text.length && text[index] == '\n') index++
                            endRow()
                        }
                        '"' -> {
                            require(field.isEmpty() && !afterQuote) { "Quote must begin an empty CSV field" }
                            quoted = true
                        }
                        else -> {
                            require(!afterQuote) { "Unexpected character after closing CSV quote" }
                            field.append(char)
                        }
                    }
                }
                require(field.length <= MAX_CSV_CELL_CHARS) { "CSV cell exceeds 16384 characters" }
            }
            require(!quoted) { "Unclosed CSV quote" }
            if (row.isNotEmpty() || field.isNotEmpty() || afterQuote || text.last() == ',') endRow()
            return rows
        }

        /** Formula-like prefixes are exported as literal strings for safer opening in spreadsheet apps. */
        fun encodeCsv(rows: List<List<String>>): String {
            require(rows.size <= MAX_CSV_ROWS) { "CSV exceeds 10000 rows" }
            val output = StringBuilder()
            for (row in rows) {
                require(row.size in 1..MAX_CSV_COLUMNS) { "Each row must have 1–128 columns" }
                row.forEachIndexed { index, cell ->
                    require(cell.length <= MAX_CSV_CELL_CHARS && '\u0000' !in cell) { "CSV cell exceeds limit or contains NUL" }
                    val first = cell.dropWhile { it.isWhitespace() }.firstOrNull()
                    val literal = if (first in listOf('=', '+', '-', '@') || cell.startsWith('\t') || cell.startsWith('\r') || cell.startsWith('\n')) "'$cell" else cell
                    if (index > 0) output.append(',')
                    output.append('"').append(literal.replace("\"", "\"\"")).append('"')
                    require(output.length <= MAX_CSV_BYTES) { "CSV output exceeds 1 MiB" }
                }
                output.append("\r\n")
            }
            require(output.toString().toByteArray(Charsets.UTF_8).size <= MAX_CSV_BYTES) { "CSV output exceeds 1 MiB" }
            return output.toString()
        }
    }
}
