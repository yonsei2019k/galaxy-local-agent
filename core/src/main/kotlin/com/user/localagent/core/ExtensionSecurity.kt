package com.user.localagent.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class DeclarativeExtensionManifest(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val name: String,
    val sourceRepository: String,
    val pinnedCommit: String,
    val artifactSha256: String,
    val capabilities: List<String>,
)

/** Read-only inspection result. The host must separately approve this exact bundle hash. */
data class ExtensionInspection(
    val manifest: DeclarativeExtensionManifest,
    val bundleSha256: String,
    val unpackedBytes: Long,
    val declarativeFiles: Map<String, String>,
) {
    val approvalRequired: Boolean get() = true
    val executableInstructionsTrusted: Boolean get() = false
}

/**
 * V1 accepts declarative Markdown and UTF-8 templates only, never executable code.
 * The manifest is detached, outside the ZIP: artifactSha256 hashes the complete ZIP.
 * expectedBundleSha256 and expectedPinnedCommit must come from a separately trusted,
 * pinned catalog, not from the downloaded manifest. This scanner performs no network,
 * filesystem writes, approval, installation, or execution. Markdown is untrusted data.
 */
object ExtensionSecurity {
    const val MAX_COMPRESSED_BYTES = 5 * 1024 * 1024
    const val MAX_UNPACKED_BYTES = 10 * 1024 * 1024
    const val MAX_FILES = 100
    private val HASH = Regex("[a-fA-F0-9]{64}")
    private val COMMIT = Regex("[a-fA-F0-9]{40}")
    private val ALLOWED_CAPABILITIES = setOf("read_notes", "search_memories", "draft_document", "draft_email")
    private data class Entry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val offset: Int,
        val directory: Boolean,
    )

    fun inspect(
        bundle: ByteArray,
        manifestJson: String,
        expectedBundleSha256: String,
        expectedPinnedCommit: String,
    ): ExtensionInspection {
        require(bundle.size in 22..MAX_COMPRESSED_BYTES) { "Bundle must be at most 5 MiB" }
        require(HASH.matches(expectedBundleSha256)) { "Trusted bundle SHA-256 is required" }
        require(COMMIT.matches(expectedPinnedCommit)) { "Trusted 40-character commit is required" }
        // Hash before parsing even ZIP metadata. An immutable snapshot avoids caller mutation.
        val snapshot = bundle.copyOf()
        val hash = MessageDigest.getInstance("SHA-256").digest(snapshot).joinToString("") { "%02x".format(it) }
        require(hash.equals(expectedBundleSha256, true)) { "Bundle SHA-256 mismatch" }
        require(manifestJson.length <= 16 * 1024 && manifestJson.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "Manifest exceeds 16 KiB" }
        rejectDuplicateKeys(manifestJson)
        // The typed decoder accepts quoted numerics, so enforce JSON token types first.
        val root = Json.parseToJsonElement(manifestJson) as? JsonObject
            ?: error("Manifest must be a JSON object")
        val stringFields = setOf("id", "version", "name", "sourceRepository", "pinnedCommit", "artifactSha256")
        require(root.keys == stringFields + setOf("schemaVersion", "capabilities")) { "Manifest fields must match schema version 1 exactly" }
        val schema = root["schemaVersion"] as? JsonPrimitive
        require(schema != null && !schema.isString && schema.content == "1") { "schemaVersion must be the JSON integer 1, not a quoted/coerced value" }
        require(stringFields.all { field -> (root[field] as? JsonPrimitive)?.isString == true }) { "Manifest identity, source, commit and hash fields must be JSON strings" }
        val capabilities = root["capabilities"] as? JsonArray
            ?: error("capabilities must be a JSON array")
        require(capabilities.all { value -> value is JsonPrimitive && value.isString }) { "Every capability must be a JSON string" }
        val decoded = Json.decodeFromString<DeclarativeExtensionManifest>(manifestJson)
        require(decoded.schemaVersion == 1) { "Only declarative schema version 1 is supported" }
        require(Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*").matches(decoded.id) && decoded.id.length <= 80) { "Invalid extension id" }
        require(Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(decoded.version)) { "Version must be major.minor.patch" }
        require(decoded.name.isNotBlank() && decoded.name.length <= 100 && decoded.name.none { it.code < 32 }) { "Invalid extension name" }
        require(Regex("https://github\\.com/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}").matches(decoded.sourceRepository)) { "Source must be a canonical HTTPS GitHub repository URL" }
        require(COMMIT.matches(decoded.pinnedCommit) && decoded.pinnedCommit.equals(expectedPinnedCommit, true)) { "Pinned commit mismatch" }
        require(HASH.matches(decoded.artifactSha256) && decoded.artifactSha256.equals(hash, true)) { "Detached manifest artifact hash mismatch" }
        require(decoded.capabilities.size <= ALLOWED_CAPABILITIES.size && decoded.capabilities.distinct().size == decoded.capabilities.size) { "Duplicate or excessive capabilities" }
        require(decoded.capabilities.all { it in ALLOWED_CAPABILITIES }) { "Unsupported capability; network/send/exec/install/other-app reads are forbidden" }
        val manifest = decoded.copy(capabilities = Collections.unmodifiableList(decoded.capabilities.toList()))
        val entries = centralDirectory(snapshot)
        val files = linkedMapOf<String, String>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(snapshot), Charsets.UTF_8).use { zip ->
            for (expected in entries) {
                val actual = zip.nextEntry ?: error("Missing local ZIP entry")
                require(actual.name == expected.name && actual.method == expected.method && actual.isDirectory == expected.directory) { "Local/central ZIP metadata mismatch" }
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count == -1) break
                    total += count
                    require(total <= MAX_UNPACKED_BYTES && bytes.size().toLong() + count <= expected.size) { "Unpacked bundle exceeds limits or declared size" }
                    bytes.write(buffer, 0, count)
                }
                require(bytes.size().toLong() == expected.size) { "ZIP entry size mismatch" }
                zip.closeEntry() // Forces CRC validation, including data descriptors.
                if (!expected.directory) {
                    val text = strictUtf8(bytes.toByteArray())
                    require(text.none { it.code < 32 && it != '\t' && it != '\n' && it != '\r' }) { "Only plain UTF-8 templates are allowed" }
                    files[expected.name] = text
                }
            }
            require(zip.nextEntry == null) { "Unexpected hidden local ZIP entry" }
        }
        require(!files["SKILL.md"].isNullOrBlank()) { "Nonempty SKILL.md is required" }
        return ExtensionInspection(manifest, hash, total, Collections.unmodifiableMap(files.toMap()))
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun centralDirectory(data: ByteArray): List<Entry> {
        fun u16(at: Int): Int {
            require(at >= 0 && at + 2 <= data.size) { "Truncated ZIP metadata" }
            return (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)
        }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        fun boundedInt(value: Long): Int {
            require(value in 0..data.size.toLong()) { "ZIP offset out of bounds" }
            return value.toInt()
        }
        // V1 deliberately rejects ZIP64, split ZIPs, comments, extra fields and trailers.
        val end = data.size - 22
        require(u32(end) == 0x06054b50L && u16(end + 20) == 0) { "ZIP must have an ordinary end record without comment/trailer" }
        require(u16(end + 4) == 0 && u16(end + 6) == 0) { "Split ZIP is unsupported" }
        val count = u16(end + 10)
        require(count in 1..MAX_FILES && u16(end + 8) == count) { "ZIP must contain 1–100 entries" }
        val centralSize = boundedInt(u32(end + 12))
        val centralStart = boundedInt(u32(end + 16))
        require(centralStart.toLong() + centralSize == end.toLong()) { "Invalid central ZIP bounds" }
        var position = centralStart
        var unpacked = 0L
        val names = mutableSetOf<String>()
        val entries = ArrayList<Entry>()
        repeat(count) {
            require(position + 46 <= end && u32(position) == 0x02014b50L) { "Invalid central ZIP entry" }
            val flags = u16(position + 8)
            val method = u16(position + 10)
            require(u16(position + 6) <= 20 && flags and 0xF7F7 == 0 && method in listOf(0, 8)) { "Unsupported/encrypted ZIP entry" }
            val nameLength = u16(position + 28)
            val extraLength = u16(position + 30)
            val commentLength = u16(position + 32)
            require(nameLength in 1..180 && extraLength == 0 && commentLength == 0 && u16(position + 34) == 0) { "Unsupported ZIP name/extras/comment/disk" }
            require(position + 46 + nameLength <= end) { "Truncated ZIP filename" }
            val name = strictUtf8(data.copyOfRange(position + 46, position + 46 + nameLength))
            val directory = name.endsWith('/')
            validatePath(name, directory)
            require(names.add(name.lowercase(Locale.ROOT).removeSuffix("/"))) { "Duplicate/case-colliding ZIP path" }
            val mode = ((u32(position + 38) ushr 16) and 0xF000).toInt()
            require(mode == 0 || (directory && mode == 0x4000) || (!directory && mode == 0x8000)) { "ZIP symlinks/special files are forbidden" }
            val size = u32(position + 24)
            val compressed = u32(position + 20)
            unpacked += size
            require(size <= MAX_UNPACKED_BYTES && unpacked <= MAX_UNPACKED_BYTES && compressed <= MAX_COMPRESSED_BYTES && (!directory || size == 0L)) { "ZIP exceeds unpacked limits" }
            entries += Entry(name, flags, method, u32(position + 16), compressed, size, boundedInt(u32(position + 42)), directory)
            position += 46 + nameLength
        }
        require(position == end) { "Unexpected central ZIP data" }
        val ordered = entries.sortedBy { it.offset }
        var nextOffset = 0L
        for (entry in ordered) {
            val offset = entry.offset
            require(offset.toLong() == nextOffset && offset + 30 <= centralStart && u32(offset) == 0x04034b50L) { "Hidden/prefixed/overlapping ZIP entries" }
            require(u16(offset + 6) == entry.flags && u16(offset + 8) == entry.method) { "Local/central ZIP flags mismatch" }
            val length = u16(offset + 26)
            require(length in 1..180 && u16(offset + 28) == 0 && offset + 30 + length <= centralStart) { "Invalid local ZIP filename/extras" }
            require(strictUtf8(data.copyOfRange(offset + 30, offset + 30 + length)) == entry.name) { "Local/central ZIP path mismatch" }
            nextOffset = offset.toLong() + 30 + length + entry.compressedSize
            require(nextOffset <= centralStart) { "ZIP data overlaps central directory" }
            if (entry.flags and 8 != 0) {
                var descriptor = boundedInt(nextOffset)
                if (u32(descriptor) == 0x08074b50L) descriptor += 4
                require(descriptor + 12 <= centralStart && u32(descriptor) == entry.crc && u32(descriptor + 4) == entry.compressedSize && u32(descriptor + 8) == entry.size) { "Invalid ZIP data descriptor" }
                nextOffset = descriptor.toLong() + 12
            } else {
                require(u32(offset + 14) == entry.crc && u32(offset + 18) == entry.compressedSize && u32(offset + 22) == entry.size) { "Local/central ZIP size mismatch" }
            }
        }
        require(nextOffset == centralStart.toLong()) { "Hidden ZIP payload" }
        return ordered
    }

    private fun validatePath(name: String, directory: Boolean) {
        require(Regex("[A-Za-z0-9._/-]+").matches(name) && !name.startsWith('/') && '\\' !in name) { "Unsafe ZIP path" }
        val path = name.removeSuffix("/")
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') }) { "ZIP path traversal/ambiguous path" }
        if (directory) {
            require(path == "templates" || path.startsWith("templates/")) { "Only template directories are allowed" }
        } else {
            require(name == "SKILL.md" || (name.startsWith("templates/") && (name.endsWith(".txt") || name.endsWith(".md")))) { "Only SKILL.md and plain .txt/.md templates are allowed; manifest must be detached" }
        }
    }
}

