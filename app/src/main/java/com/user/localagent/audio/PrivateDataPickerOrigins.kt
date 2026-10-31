package com.user.localagent.audio

import java.util.UUID

/** Only these two existing system pickers may consume their own origin token. */
enum class PrivateDataPickerPurpose { AUDIO_TRANSCRIPTION, CONVERSATION_IMPORT }

/** Primitive snapshot of the host's real ChatAccessScope and originating active branch. No URI/body. */
data class PrivateDataPickerScope(
    val threadId: String,
    val branchId: String,
    val projectId: String?,
    val scopeKey: String,
    val useOwnerMemory: Boolean,
    val threadRevision: Long,
    val memoryEnabled: Boolean,
) {
    init {
        requireKey(threadId); requireKey(branchId); projectId?.let(::requireKey)
        require(threadRevision >= 0 && scopeKey.length in 1..200 && scopeKey.none { it.code < 32 || it.code == 127 })
    }
    companion object {
        private fun requireKey(value: String) = require(value.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
            "자료를 선택할 원래 대화·분기 범위를 확인해주세요."
        }
    }
}

/** A host-owned reference. Generated data-class copy must not forge a consumable origin token. */
class PrivateDataPickerOrigin internal constructor(
    val selectionId: String,
    val purpose: PrivateDataPickerPurpose,
    val scope: PrivateDataPickerScope,
)

/** App-owned in-memory registry; SavedState keeps only selectionId. Process loss rejects old UUIDs. */
class PrivateDataPickerOrigins(private val maximumPending: Int = 16) {
    private val lock = Any()
    private val pending = linkedMapOf<String, PrivateDataPickerOrigin>()

    init { require(maximumPending in 1..32) }

    fun begin(scope: PrivateDataPickerScope, purpose: PrivateDataPickerPurpose): String = synchronized(lock) {
        check(pending.size < maximumPending) { "기존 자료 선택을 마치거나 취소한 뒤 다시 선택해주세요." }
        val token = UUID.randomUUID().toString()
        pending[token] = PrivateDataPickerOrigin(token, purpose, scope)
        token
    }

    /** Removes before purpose validation: misrouted/duplicate callbacks cannot reuse the token. */
    fun consume(selectionId: String, expectedPurpose: PrivateDataPickerPurpose): PrivateDataPickerOrigin = synchronized(lock) {
        require(selectionId.matches(UUID_PATTERN)) { "원래 자료 선택 요청을 확인할 수 없습니다. 파일을 다시 선택해주세요." }
        val origin = pending.remove(selectionId) ?: error("원래 자료 선택 요청을 확인할 수 없습니다. 파일을 다시 선택해주세요.")
        check(origin.purpose == expectedPurpose) { "자료 선택 요청의 종류가 일치하지 않습니다. 파일을 다시 선택해주세요." }
        origin
    }

    fun cancel(selectionId: String): Boolean = synchronized(lock) { pending.remove(selectionId) != null }
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    companion object { private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") }
}
