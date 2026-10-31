package com.user.localagent.audio

import java.net.URI
import java.util.UUID

data class RecordingListingItem(val displayName: String, val contentUri: String, val durationMillis: Long?)
data class ListedRecording(val recordingId: String, val displayName: String, val durationMillis: Long?)
class RecordingSelection internal constructor(
    val recordingId: String,
    val displayName: String,
    val durationMillis: Long?,
    val threadId: String,
    val requestRunId: String,
    internal val contentUri: String,
    internal val generation: Long,
)

/** One ToolExecutor owns one store. Nothing is persisted or resolved from model-provided URI/path text. */
class ListedRecordingStore(private val ownerThreadId: String, private val activeRunId: () -> String?) {
    private val lock = Any()
    private var generation = 0L
    private val selected = linkedMapOf<String, RecordingSelection>()

    init { requireKey(ownerThreadId) }

    fun register(items: List<RecordingListingItem>): List<ListedRecording> = synchronized(lock) {
        require(items.size <= 20)
        val run = currentRun()
        // Validate the entire provider listing before replacing the old selection set.
        items.forEach { item ->
            requireMediaStoreRecording(item.contentUri)
            require(item.displayName.length <= 200 && item.displayName.none { it == '\u0000' })
            require(item.durationMillis == null || item.durationMillis >= 0)
        }
        check(generation < Long.MAX_VALUE)
        generation++
        selected.clear()
        items.map { item ->
            val id = UUID.randomUUID().toString()
            val entry = RecordingSelection(id, item.displayName, item.durationMillis, ownerThreadId, run, item.contentUri, generation)
            selected[id] = entry
            ListedRecording(id, item.displayName, item.durationMillis)
        }
    }

    fun resolve(recordingId: String): RecordingSelection = synchronized(lock) {
        val entry = selected[recordingId] ?: error("이 요청의 최신 목록에 없는 녹음입니다. 녹음 목록을 다시 조회해주세요.")
        requireCurrentLocked(entry)
        entry
    }

    fun requireCurrent(selection: RecordingSelection) = synchronized(lock) { requireCurrentLocked(selection) }

    fun invalidate() = synchronized(lock) { selected.clear(); if (generation < Long.MAX_VALUE) generation++ }

    private fun requireCurrentLocked(selection: RecordingSelection) {
        check(selection.threadId == ownerThreadId && selection.requestRunId == currentRun() &&
            selection.generation == generation && selected[selection.recordingId] === selection) {
            "녹음 목록의 실행 범위가 끝났거나 교체되었습니다. 현재 요청에서 다시 조회해주세요."
        }
        requireMediaStoreRecording(selection.contentUri)
    }

    private fun currentRun() = checkNotNull(activeRunId()) { "진행 중인 요청에서 녹음 목록을 조회해주세요." }.also(::requireKey)

    companion object {
        private fun requireKey(value: String) {
            require(value.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "올바른 대화·실행 범위가 필요합니다." }
        }
        private fun requireMediaStoreRecording(value: String) {
            require(value.length <= 250)
            val uri = URI(value)
            require(uri.scheme == "content" && uri.rawAuthority == "media" && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.matches(Regex("/external(?:_primary)?/audio/media/[0-9]{1,20}"))) {
                "실제 MediaStore 녹음 목록에서 받은 항목만 사용할 수 있습니다."
            }
        }
    }
}
