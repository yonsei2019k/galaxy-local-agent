package com.user.localagent.ui

import com.user.localagent.audio.PrivateDataPickerScope
import com.user.localagent.data.LocalRepository

/** Two matching metadata observations before opening a picker; no source body is read here. */
internal object PrivateDataUiScopeCapture {
    suspend fun capture(threadId: String, read: suspend (String) -> PrivateDataPickerScope): PrivateDataPickerScope {
        require(threadId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "자료를 연결할 원래 대화를 먼저 선택해주세요." }
        val first = read(threadId)
        val second = read(threadId)
        check(first.threadId == threadId && second.threadId == threadId && first == second) {
            "자료를 선택하는 동안 원래 대화 범위가 변경되었습니다. 다시 선택해주세요."
        }
        return first
    }
}

internal suspend fun capturePrivateDataUiScope(repository: LocalRepository, threadId: String): PrivateDataPickerScope =
    PrivateDataUiScopeCapture.capture(threadId) { target ->
        val before = checkNotNull(repository.chat.thread(target)) { "원래 대화를 확인하지 못했습니다." }
        val access = repository.chat.accessScope(target)
        val after = checkNotNull(repository.chat.thread(target)) { "원래 대화를 확인하지 못했습니다." }
        check(before.revision == after.revision && before.activeBranchId == after.activeBranchId &&
            access.threadId == after.id && access.projectId == after.projectId && access.threadRevision == after.revision) {
            "원래 대화·분기가 변경되었습니다. 다시 선택해주세요."
        }
        PrivateDataPickerScope(access.threadId, after.activeBranchId, access.projectId, access.scopeKey,
            access.useOwnerMemory, access.threadRevision, access.memoryEnabled)
    }

internal suspend fun requirePrivateDataUiScope(repository: LocalRepository, expected: PrivateDataPickerScope) {
    check(capturePrivateDataUiScope(repository, expected.threadId) == expected) {
        "원래 대화·프로젝트 범위가 변경되었습니다. 자료를 다시 확인해주세요."
    }
}
