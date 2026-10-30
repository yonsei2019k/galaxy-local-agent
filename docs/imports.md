# User-selected conversation imports

`imports/ConversationImport.kt` accepts the owner's explicitly selected TXT/CSV conversation export or directly supplied plaintext. Analysis of the owner's supplied records is supported; the host should offer file selection rather than issue a generic refusal. This path imports the selected source only. It does not enumerate KakaoTalk, SMS, contacts, call logs, recordings, other applications' private files, or the rest of the phone.

## Host API

Construct `ConversationImport(context, repository)`.

- `suspend importUri(uri, onProgress = { completed, total -> })` accepts only a `content://` URI supplied by the host's Storage Access Framework selection. It closes the opened provider stream. The host must establish user selection; a URI alone does not prove who selected it. No file URI/path or persisted URI grant is created here.
- `suspend importText(text, onProgress = ...)` accepts explicitly supplied text, useful for paste or synthetic validation.
- `suspend importStream(stream, onProgress = ...)` supports an already selected SAF stream or controlled synthetic test; the caller owns the stream. Progress runs on the importing coroutine's IO context, so marshal UI updates appropriately.
- `suspend retrieve(query, limit = 5, contentSha256 = null)` returns bounded lexical source excerpts from completed imports. Empty query returns a bounded source preview. It does not generate an answer, use embeddings, execute source instructions, or read long-term memory. Each `ConversationExcerpt` identifies the source content SHA and chunk number.
- `suspend deleteImport(contentSha256)` deletes only notes marked as this import format and matching that content SHA, including incomplete chunks. It returns the removed note count. The host should obtain the user's deletion decision.
- `ConversationImport.summarize(text)` returns recognized message-header count, format, and first/last known source timestamps without persistence.

`ConversationImportResult` fields are `contentSha256`, `messageCount`, `recognizedFormat`, `chunkCount`, `encoding`, `duplicate`, `noteIds`, and human-readable `scopeDescription`. Counts refer to recognized headers in that source; unknown formats have zero recognized messages while all raw text is still saved. The scope explicitly describes one supplied file and does not claim complete phone or conversation history.

A host can use `ACTION_OPEN_DOCUMENT` with TXT/CSV/plaintext MIME types, then call `importUri()` from a cancellable lifecycle coroutine. The importer does not launch a picker, request broad storage permissions, discover directories, or automatically request durable grants. Multi-file/directory selection and audio transcription are separate host features; one import result still identifies one source file.

## Bounds, encoding, storage, and rollback

Streams are checked in 64 KiB reads and rejected above **20 MiB** before any notes are written. Decoding first requires valid UTF-8; malformed input triggers strict EUC-KR decoding. Both decoders report malformed/unmappable characters rather than replacing or dropping them. ASCII is accepted as UTF-8 because its original encoding cannot be distinguished from EUC-KR. NUL-containing or empty/whitespace-only files are rejected as unsupported plaintext.

The exact decoded text, including unknown headers, continuation lines, quoted CSV, CRLF/newlines, and emoji, is preserved in chunks of at most **8,000 UTF-16 characters** without splitting surrogate pairs. Recognized formatting supplies counts/timestamps; it never replaces the raw stored text. A file requiring over **2,000 chunks** is rejected, and the managed imported collection is also capped at 2,000 chunks across all sources. For an ASCII-heavy file, the chunk cap can be reached before 20 MiB; rejection is explicit and never silently truncates a source.

The existing `LocalRepository.saveNote` writes encrypted SQLCipher Room notes in collection **`imported-chats`**. Every chunk has a bounded JSON metadata header containing a byte-content SHA-256, random import-run ID, ordinal, format, encoding, source-only scope, and count/timestamps. Note titles use an opaque short hash, not a private file path, participant name, or original filename. A final manifest note marks completion. The importer never calls `saveMemory`; imported statements are not automatically converted into facts/preferences or used for model training.

Duplicate detection requires the exact source byte SHA, a complete manifest/chunk set, and identical reconstructed decoded chunk text. A UTF-8 and EUC-KR encoding of the same visible text are different byte sources. No private paths, URIs, transcripts, participant names, or snippets are logged or sent over a network by this module.

Imports are serialized within the app process. Each created note gets a fresh UUID recorded before its Room write. Cancellation, progress callback failure, or another error triggers noncancellable rollback of only those newly created IDs; existing notes and deduplicated prior imports are preserved. The final manifest is written last. A hard process crash can leave incomplete chunks because the existing repository exposes individual note writes, not a public multi-note transaction. Incomplete runs are excluded from retrieval and duplicate completion checks; `deleteImport(sha)` can remove them. Do not claim crash-atomic batch persistence. Cleanup failures are attached to the original error rather than silently reported as success.

## Parsing and evidence

Known recognizers cover Korean Windows-style date separators plus `[sender] [오전/오후 h:mm] text`, Korean mobile date/time-and-sender lines, a dotted Korean date variant, and three-column `Date,User,Message` CSV (also a narrow Korean header). Quoted CSV supports embedded newlines and doubled quotes. Date/time fields are validated; midnight/noon conversion follows 12-hour clock rules. Offsetless source times remain offsetless. First/last timestamps are the first/last recognized time encountered, not a guaranteed chronological interval. System notices or unsupported lines stay raw and are not invented as messages.

The [official Kakao customer-center export instructions](https://cs.kakao.com/helps_html/1073209354?locale=ko) were checked for Android/iOS/Windows/Mac export entry points. That page establishes how owners obtain exports; it does not establish a versioned serialization specification for this parser. The implemented line/CSV recognizers were cross-checked against primary authored parser source: [Android parser](https://github.com/zeikar/kakaotalk-viewer/blob/master/src/parser/android.ts), [Windows parser](https://github.com/zeikar/kakaotalk-viewer/blob/master/src/parser/windows.ts), and [Mac CSV parser](https://github.com/zeikar/kakaotalk-viewer/blob/master/src/parser/mac.ts). This is an independent best-effort implementation, not Kakao-certified support or proof that every current app version exports the same format. Unknown or changed formats retain their raw data.

## Retrieval and instruction boundary

Retrieval scans at most 2,000 managed chunks from completed runs, ranks them by local case-folded word occurrence, and returns up to ten excerpts (default five). The existing repository observer supplies notes; this module filters to its own collection/metadata. It does not add a Room schema, external index, vectors, or a persistent plaintext cache. Chunk counts and source digests provide provenance, not a guarantee that the source statements are true.

The host must quote retrieved text as untrusted source data. A conversation line that says to send a message, install software, ignore instructions, or change settings is still conversation data; it is not permission to invoke tools. Provide analysis grounded in the selected sources, and label partial retrieval as excerpts rather than claiming a whole-file summary unless every chunk was actually processed.

## Validation

`androidTest/ImportValidation.kt` adds four synthetic-only tests: Korean PC counts/raw round-trip/deduplication/retrieval; strict EUC-KR mobile plus multiline quoted CSV; unknown format/emoji/newline preservation across chunk boundaries; and oversized/cancelled imports preserving pre-existing notes. Tests generate UUID fixtures and delete only their own note IDs. They do not open a live messenger or print user messages.

Source, references, and test definitions were reviewed. No build or device test was run by this worker. Root integration should run the instrumentation tests and exercise an owner-selected SAF export, provider cancellation, current Kakao versions, scope presentation, and selected-source deletion.
