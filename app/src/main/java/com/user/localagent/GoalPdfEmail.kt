package com.user.localagent

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Local draft. Only an owner tap on its originating message can open an external composer. */
internal object GoalPdfEmail {
    const val KEY = "preparedPdfEmail"
    const val MARKER = "[[prepared-pdf-email]]"
    const val DIGESTS = "verifiedChatPdfDigests"
    private const val MAX_FILE_BYTES = 10L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 25L * 1024 * 1024

    fun matchesScope(current: QueuedChatRequest, source: ChatRunRecord): Boolean {
        if (source.threadId != current.threadId || source.branchId != current.branchId || source.projectId != current.projectId) return false
        if (source.id != current.runId && source.id !in current.goal?.linkedRunIds.orEmpty()) return false
        val saved = runCatching { QueuedChatRequest.fromJson(source.contextJson) }.getOrNull() ?: return false
        return saved.runId == source.id && saved.threadId == current.threadId && saved.branchId == current.branchId &&
            saved.projectId == current.projectId && saved.goal?.id == current.goal?.id &&
            saved.goalSegment?.workDigest == current.goalSegment?.workDigest
    }

    /** Older original PDFs use a digest-derived filename. Other legacy PDFs without byte evidence cannot be attached. */
    fun verifyFile(directory: File, name: String, expectedHash: String?): File {
        require(com.user.localagent.ui.chatFileNames("[[file:$name]]").singleOrNull() == name && name.endsWith(".pdf"))
        val root = directory.canonicalFile
        val candidate = File(root, name)
        require(!Files.isSymbolicLink(candidate.toPath()) && candidate.canonicalFile.parentFile == root &&
            candidate.isFile && candidate.length() in 5..MAX_FILE_BYTES) { "저장한 PDF를 찾지 못했어요." }
        val digest = candidate.inputStream().buffered().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(5)
            java.io.DataInputStream(input).readFully(header)
            require(header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)))
            md.update(header)
            val buffer = ByteArray(16 * 1024)
            var total = 5L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_FILE_BYTES)
                md.update(buffer, 0, read)
            }
            md.digest()
        }
        val matches = if (expectedHash == null) name == "artifact-" + UUID.nameUUIDFromBytes(digest) + ".pdf"
            else expectedHash.matches(Regex("[0-9a-f]{64}")) && digest.joinToString("") { "%02x".format(it) } == expectedHash
        require(matches) { "저장할 때 확인한 PDF와 현재 파일이 달라요. 원본을 다시 확인해 주세요." }
        return candidate
    }

    suspend fun resolve(app: AppContainer, directory: File, request: QueuedChatRequest, names: List<String>): List<File> =
        withContext(Dispatchers.IO) {
            val remaining = names.toMutableSet()
            val verified = mutableMapOf<String, File>()
            val ids = (listOf(request.runId) + request.goal?.linkedRunIds.orEmpty().asReversed()).distinct()
            for (sourceId in ids) {
                val row = app.repository.chat.run(sourceId) ?: continue
                if (!matchesScope(request, row)) continue
                val metadata = Json.parseToJsonElement(row.contextJson).jsonObject
                val saved = (metadata["verifiedChatFiles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { field -> field.isString }?.content }
                val digests = metadata[DIGESTS] as? JsonObject
                for (name in saved.intersect(remaining)) {
                    verified[name] = verifyFile(directory, name, digests?.get(name)?.jsonPrimitive?.content)
                    remaining.remove(name)
                }
                if (remaining.isEmpty()) break
            }
            require(remaining.isEmpty()) { "이 작업에서 저장한 PDF 목록과 일치하지 않아요. 원하는 파일을 다시 확인해 주세요." }
            names.map(verified::getValue).also { files ->
                require(files.sumOf { it.length() } <= MAX_TOTAL_BYTES) { "첨부할 PDF의 합계가 25MB를 넘어요. 나누어 준비해 주세요." }
            }
        }

    suspend fun prepare(app: AppContainer, context: Context, request: QueuedChatRequest, call: ToolCall): ToolResult {
        val names = requireNotNull(PdfMailAttachments.requested(call))
        resolve(app, File(context.filesDir, "exports"), request, names)
        val row = requireNotNull(app.repository.chat.run(request.runId))
        require(row.status == "running" && matchesScope(request, row))
        val saved = Json.parseToJsonElement(row.contextJson).jsonObject
        require(saved[KEY] == null || saved[KEY] == call.arguments) { "이 단계에 이미 다른 첨부 초안이 있어요." }
        val written = app.repository.chat.updateRunContext(row.id, JsonObject(saved + (KEY to call.arguments)).toString())
        check(Json.parseToJsonElement(written.contextJson).jsonObject[KEY] == call.arguments)
        return ToolResult(call.id, true, "PDF " + names.size + "개를 첨부한 메일을 대화에 준비했어요. 아래 카드에서 Gmail을 열고 내용을 확인한 뒤 보내 주세요.",
            VerificationState.PREPARED, "대화에 PDF 첨부 초안 저장")
    }

    suspend fun lookup(app: AppContainer, thread: String, branch: String, messageId: String): Pair<QueuedChatRequest,ToolCall>? {
        val room = app.repository.chat.thread(thread) ?: return null
        if (room.deletedAt != null || room.activeBranchId != branch || app.runs.selectedThreadId.value != thread) return null
        val msg = app.repository.getMessage(messageId) ?: return null
        if (msg.threadId != thread || msg.branchId != branch || msg.role != "assistant") return null
        val run = msg.runId?.let { app.repository.chat.run(it) } ?: return null
        if (run.threadId != thread || run.branchId != branch || run.projectId != room.projectId ||
            app.repository.chat.assistantForRun(run.id)?.id != messageId) return null
        val args = Json.parseToJsonElement(run.contextJson).jsonObject[KEY] as? JsonObject ?: return null
        val request = QueuedChatRequest.fromJson(run.contextJson)
        if (!matchesScope(request,run)) return null
        val call = ToolCall("pdf-mail", "prepare_email", args)
        ToolRegistry.validate(call)
        requireNotNull(PdfMailAttachments.requested(call))
        return request to call
    }

    fun preview(call:ToolCall) = buildJsonObject {
        put("to", call.arguments.getValue("to")); put("subject", call.arguments.getValue("subject"))
        put("body", call.arguments.getValue("body"))
        put("files", JsonArray(requireNotNull(PdfMailAttachments.requested(call)).map(::JsonPrimitive)))
    }

    suspend fun composer(context: Context, app: AppContainer, thread: String, branch: String, messageId: String, expected:JsonObject): Intent {
        val (request, call) = requireNotNull(lookup(app,thread,branch,messageId))
        require(preview(call) == expected)
        val files = resolve(app, File(context.filesDir, "exports"), request, requireNotNull(PdfMailAttachments.requested(call)))
        require(lookup(app,thread,branch,messageId)?.second == call) { "초안이 바뀌었어요. 다시 열어 주세요." }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) })
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            setPackage("com.google.android.gm")
            type = "application/pdf"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(call.arguments.getValue("to").jsonPrimitive.content))
            putExtra(Intent.EXTRA_SUBJECT, call.arguments.getValue("subject").jsonPrimitive.content)
            putExtra(Intent.EXTRA_TEXT, call.arguments.getValue("body").jsonPrimitive.content)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("PDF", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
