# Built-in local skills

`BuiltinSkills(context)` is a bounded native Android baseline in `com.user.localagent.skills`. It does not register automatic model tools, obtain URI permissions, launch activities, send email, or grant capabilities. `catalog` and `catalogJson()` expose seven capability descriptions for an orchestration layer; they are descriptive metadata, not authorization or a strict JSON argument validator.

The Compose host must obtain input through `ACTION_OPEN_DOCUMENT` and output through `ACTION_CREATE_DOCUMENT`, retain grants only when requested, validate proposed calls against its own registry, obtain confirmation before writes or external UI, and call these suspend methods from its lifecycle coroutine. Only `content://` URIs are accepted. The methods cannot establish that an arbitrary URI was actually selected by the user; the host must enforce that association. Provider errors and cancellation propagate to the caller. A failed provider write can leave partial output; use a newly created document and report failure.

## Public API

```kotlin
val skills = BuiltinSkills(context)
val capabilities = skills.catalog
val manifestJson: String = skills.catalogJson()

val text: String = skills.readDocument(inputUri)
skills.writeDocument(outputUri, "# Plain Markdown\n")
val table: List<List<String>> = skills.readCsv(inputUri)
skills.writeCsv(outputUri, table)
val pdf: BuiltinSkills.PdfInspection = skills.inspectPdf(inputUri, includeText = true)
val writtenPageCount: Int = skills.writePdf(outputUri, text, title = "Local document")
val intent: Intent = skills.composeEmail(listOf("name@example.com"), "Subject", "Body")
// Activity decides when to launch intent; no email is sent by this class.

val parsed = BuiltinSkills.parseCsv("name,value\r\nexample,42\r\n")
val encoded = BuiltinSkills.encodeCsv(parsed)
```

Capability names: `document.read`, `document.write`, `spreadsheet.read_csv`, `spreadsheet.write_csv`, `pdf.inspect`, `pdf.create`, `email.compose`.

## Supported formats and limits

- Documents: strict UTF-8 text and Markdown, maximum 4 MiB; malformed UTF-8 and NUL are rejected. Markdown is stored as source text. DOCX, rich Word editing, HTML rendering, OCR, and automatic summaries are unsupported.
- CSV: maximum 1 MiB, 10,000 rows, 128 columns per row, and 16,384 characters per cell. Commas, doubled quotes, CRLF/LF records and quoted multiline values are supported. Unclosed or misplaced quotes are rejected. No formulas, scripts, macros, external links, or XLSX are executed or interpreted. Export quotes every cell, doubles embedded quotes, and uses CRLF. Cells whose first non-whitespace character is `=`, `+`, `-`, or `@`, or which start with tab/CR/LF, are prefixed with an apostrophe. This deliberately changes those exported values, including negative numbers, to reduce formula execution when opened in another app. Import leaves original strings unchanged. An empty input is an empty table; empty column-less rows cannot be exported.
- PDF inspection: maximum 16 MiB and 100 pages. A private temporary copy supplies a seekable descriptor for SAF providers, and is removed afterward. Page count and dimensions are available on the app's Android 12+ baseline. Embedded text uses `PdfRenderer.Page.getTextContents()` only on Android 15/API 35+, up to 200,000 returned characters across pages. `PdfInspection` reports `textSupported` and `textTruncated`; each `PdfPageInfo` has its 1-based page number, dimensions, and nullable text. Text is null when extraction is disabled or unavailable. Scanned PDFs may have no embedded text. Password-protected, damaged, or unsupported PDFs report platform errors. No OCR, semantic reflow, or preservation of PDF reading order is promised.
- PDF creation: plain text with a title, line wrapping, and pagination on 595 × 842 point pages; maximum 200,000 input characters, 100 pages and 16 MiB output. Uses installed Android system fonts; typography, emoji, and complex scripts depend on platform font fallback. Markdown syntax remains literal. Tables, rich styling, PDF form editing and existing PDF modifications are unsupported.
- Email: 1–20 bounded ASCII addresses, subject up to 500 characters without line breaks, body up to 10,000 characters. Returns an `ACTION_SENDTO` mailto Intent with encoded subject/body and corresponding extras. It does not access the mailbox, attach private data, authenticate, or send. The host handles missing compatible email apps and obtains confirmation before opening external UI.

No network, additional dependencies, or heavyweight office runtime is used. Work is dispatched onto `Dispatchers.IO`; suspend I/O loops check coroutine cancellation. Native PDF calls themselves can block until the platform returns. File limits bound input bytes, output pages and returned text, but are not a sandbox for platform PDF parser memory usage.

## API verification

Inspected the installed Android 37 SDK's `android.jar` with JDK `javap`: `PdfRenderer`, `PdfRenderer.Page.getTextContents()`, `PdfPageTextContent.getText()`, `PdfDocument`, and `PdfDocument.PageInfo.Builder`. The text extraction call is guarded by API level 35. This task did not run Gradle or an Android device test; the root integration owns compilation and runtime verification.
