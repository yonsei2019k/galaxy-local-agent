package com.user.localagent.data

import org.junit.Assert.*
import org.junit.Test

class FirstChatTitleTest {
    @Test fun offlineTitleUsesOnlyTheFirstNonblankLine() {
        assertEquals("제주 여행 준비", firstChatTitle("\n  제주\t 여행 준비 \n 긴 상세 조건"))
        assertNull(firstChatTitle("/goal 여행 준비"))
        assertNull(firstChatTitle(" \n "))
    }

    @Test fun truncationPreservesCompleteUnicodeCodePoints() {
        val title=checkNotNull(firstChatTitle("🌴".repeat(52)))
        assertEquals("🌴".repeat(47)+"…",title)
    }
}
