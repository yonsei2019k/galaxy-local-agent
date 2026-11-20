package com.user.localagent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.user.localagent.AppContainer
import com.user.localagent.connections.ConnectionStore
import com.user.localagent.connections.PcDataSelection
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** The renderer receives thumbnails only. Model text never supplies a file or content URI. */
internal object ChatResultPreviews {
    private val uuid = "[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"
    private val imageName = Regex("image-$uuid\\.png")
    private val chartName = Regex("$uuid\\.png")

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    suspend fun load(context: Context, app: AppContainer, thread: String, branch: String, visible: Set<String>): JsonArray =
        withContext(Dispatchers.IO) {
            if (thread.isBlank() || branch.isBlank() || visible.isEmpty()) return@withContext JsonArray(emptyList())
            val room = app.repository.chat.thread(thread) ?: return@withContext JsonArray(emptyList())
            suspend fun bound(runId: String?): String? {
                val run = runId?.let { app.repository.chat.run(it) } ?: return null
                if (run.threadId != thread || run.branchId != branch || run.projectId != room.projectId || run.status != "completed") return null
                return app.repository.chat.assistantForRun(run.id)?.id?.takeIf(visible::contains)
            }

            suspend fun preview(kind: String, metadata: JsonObject?, directory: String, filename: Regex): JsonObject? {
                if (metadata == null) return null
                val messageId = bound(metadata.string("runId")) ?: return null
                val name = metadata.string("fileName")?.takeIf(filename::matches) ?: return null
                return buildJsonObject {
                    put("messageId", messageId)
                    put("kind", kind)
                    put("src", thumbnail(context, directory, name, metadata.string("fileSha256")))
                }
            }

            suspend fun <T> optional(block: suspend () -> T?): T? = try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }

            val image = optional {
                val note = app.repository.forThread(thread).latestNote("generated-images") ?: return@optional null
                val metadata = Json.parseToJsonElement(note.body).jsonObject
                if (metadata.string("type") != "generated_image_v2") return@optional null
                preview("image", metadata, "generated-images", imageName)
            }
            val chart = optional {
                val store = ConnectionStore(context)
                val metadata = store.read("pc.chat.chart." + PcDataSelection.storageKey(thread))
                    ?.let { Json.parseToJsonElement(it).jsonObject }
                if (metadata?.string("fileSha256")?.matches(Regex("[a-f0-9]{64}")) != true) return@optional null
                preview("chart", metadata, "shared/data-analysis", chartName)
            }

            val current = app.repository.chat.thread(thread)
            if (current == null || current.activeBranchId != branch || current.deletedAt != null || current.projectId != room.projectId ||
                app.runs.selectedThreadId.value != thread) JsonArray(emptyList())
            else JsonArray(listOfNotNull(image, chart))
        }

    private fun thumbnail(context: Context, directory: String, filename: String, expectedHash: String?): String? {
        val root = File(context.filesDir, directory).canonicalFile
        val file = File(root, filename).canonicalFile
        require(file.parentFile == root && file.name == filename)
        if (!file.isFile || file.length() !in 1..8_388_608) return null
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                require(output.size() + count <= 8_388_608)
                output.write(block, 0, count)
            }
            output.toByteArray()
        }
        if (expectedHash != null && MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) } != expectedHash) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/png" || bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 960) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output) || output.size() > 180_000) null
            else "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
}
