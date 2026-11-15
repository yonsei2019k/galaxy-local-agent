package com.user.localagent.core

import kotlinx.serialization.json.*

/**
 * Bounded prompt retention, never read authority. Only the caller's current request evidence is
 * considered; no earlier generation/run cache is kept. Issued-token/current-query verification
 * remains exclusively in NoteReadPatchSession. Retained objects are neither copied nor clipped.
 */
object NotePageEvidenceRetention {
    const val MAX_PAGE_EVIDENCE_CHARS = 6_000 // Serialized UTF-16, including the full host wrapper.

    data class Result(val retainedPages: Int, val retainedChars: Int, val evictedPages: Int) {
        fun notice(): String = if (evictedPages == 0) "" else
            "메모 원문 일부는 현재 입력 한도 때문에 제외됐다. 수정할 원문 페이지가 현재 입력에 없으면 read_note로 다시 조회해야 한다. 작업 노트의 요약은 수정 근거가 아니다.\n"
    }

    private data class Page(
        val id: String, val scope: String, val version: String, val hash: String,
        val total: Int, val offset: Int, val length: Int
    ) {
        fun sameSnapshot(other: Page): Boolean = id == other.id && scope == other.scope &&
            version == other.version && hash == other.hash && total == other.total
    }
    private data class Entry(val index: Int, val item: JsonObject, val page: Page, val chars: Int)

    /** Ordinary evidence keeps the existing WorkNote policy, including segment boundaries. */
    fun compact(evidence: MutableList<JsonObject>, workNote: WorkNote, segmentBoundary: Boolean = false): Result {
        val pages = mutableListOf<Entry>()
        val ordinary = mutableListOf<Pair<Int, JsonObject>>()
        var evicted = 0
        evidence.forEachIndexed { index, item ->
            val tool = item.string("tool")
            val page = if (tool == "read_note") parsePage(item) else null
            if (page != null) {
                // Latest observed snapshot wins; a reread keeps only the newest exact range/token.
                pages.removeAll { old -> old.page.id == page.id &&
                    (!old.page.sameSnapshot(page) || old.page.offset == page.offset && old.page.length == page.length) }
                pages += Entry(index, item, page, item.toString().length)
                while (pages.size > NoteReadPatchSession.MAX_PENDING ||
                    pages.sumOf { it.chars.toLong() } > MAX_PAGE_EVIDENCE_CHARS) {
                    pages.removeAt(0)
                    evicted++
                }
            } else {
                // A failed/malformed read clears the host read session. Do not retain earlier pages.
                // A verified write also makes the previous read snapshot obsolete.
                if (tool == "read_note" || tool == "patch_note" && item.flag("success") == true &&
                    item.string("verification") == "VERIFIED") {
                    pages.clear()
                }
                ordinary += index to item
            }
        }
        val liveOrdinary = ordinary.map { it.second }.toMutableList()
        if (segmentBoundary) workNote.absorbAll(liveOrdinary) else workNote.absorb(liveOrdinary)
        // Both existing WorkNote methods remove only a prefix. Preserve the remaining original order.
        val keep = ordinary.takeLast(liveOrdinary.size) + pages.map { it.index to it.item }
        evidence.clear()
        evidence.addAll(keep.sortedBy { it.first }.map { it.second })
        return Result(pages.size, pages.sumOf { it.chars }, evicted)
    }

    /** Shape checks are retention hints only; they cannot authenticate a token or grant permission. */
    private fun parsePage(item: JsonObject): Page? = runCatching {
        require(item.flag("success") == true && item.string("verification") == "VERIFIED")
        val wrapper = item["result"] as? JsonObject ?: return@runCatching null
        require(wrapper.flag("notePageIntact") == true && wrapper.flag("truncated") == false &&
            wrapper.flag("dataOnly") == true)
        val data = wrapper["data"] as? JsonObject ?: return@runCatching null
        fun string(key: String): String = requireNotNull(data.string(key))
        fun int(key: String): Int = requireNotNull((data[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
        fun flag(key: String): Boolean = requireNotNull(data.flag(key))
        val id = string("id")
        val scope = string("scope")
        val version = string("expectedUpdatedAt")
        val hash = string("bodySha256")
        val token = string("readToken")
        val offset = int("offset")
        val text = string("text")
        val total = int("totalLength")
        require(string("status") == "ok" && id.isNotBlank() && id.length <= 100 &&
            scope.isNotBlank() && scope.length <= 80 && scope !in InternalNoteCollections.protected &&
            version.matches(Regex("[0-9]+")) && version.toLongOrNull()?.let { it >= 0 } == true &&
            hash.matches(Regex("[a-f0-9]{64}")) && token.matches(Regex("[a-f0-9-]{36}")) &&
            string("offsetUnit") == "utf16_code_units" && flag("untrustedData") && !flag("grantsPermission"))
        require(total in 0..NoteReadPatchSession.MAX_LEGACY_READ && offset in 0..total &&
            text.length <= NoteReadPatchSession.MAX_PAGE && int("returnedLength") == text.length &&
            offset + text.length <= total && (text.isNotEmpty() || offset == total) &&
            data.toString().length <= NoteReadPatchSession.MAX_PAGE_JSON)
        val more = offset + text.length < total
        val complete = offset == 0 && !more
        require(flag("hasMore") == more && flag("completeBody") == complete && flag("partial") == !complete &&
            wrapper.flag("sourceScopePartial") == !complete)
        require(if (more) int("nextOffset") == offset + text.length else data["nextOffset"] == JsonNull)
        Page(id, scope, version, hash, total, offset, text.length)
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
