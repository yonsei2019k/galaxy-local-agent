package com.user.localagent.integrations

internal enum class OwnerLoginCardAction(val label: String) {
    SUBMIT_CREDENTIALS("로그인하고 계속"),
    CHECK_RESULT("로그인 결과 다시 확인");

    companion object {
        fun forCard(pairedPc: Boolean, resultPending: Boolean, editingCredentials: Boolean) =
            if (pairedPc && resultPending && !editingCredentials) CHECK_RESULT else SUBMIT_CREDENTIALS
    }
}
