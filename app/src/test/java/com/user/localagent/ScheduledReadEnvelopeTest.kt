package com.user.localagent

import com.user.localagent.core.ConversationPrivacy
import com.user.localagent.core.ScheduledReadPolicy
import org.junit.Assert.*
import org.junit.Test

class ScheduledReadEnvelopeTest {
    private val request = QueuedChatRequest(
        ScheduledReadPolicy.ID_PREFIX + "a".repeat(64), "room-a", "branch-a", "project-a",
        "Read the original request\nwithout changing it.", ConversationPrivacy.LOCAL_SELECTION,
        false, emptyList(), "message-a", scheduledReadTool = "search_notes",
    )

    @Test fun roundTripRetainsOriginalTextAndOneLocalRead() {
        assertEquals(request, QueuedChatRequest.fromJson(request.toJson()))
        assertNull(QueuedChatRequest.fromJson(request.copy(scheduledReadTool=null).toJson()).scheduledReadTool)
    }

    @Test fun fastClockMarkerSurvivesPromotionPayloadAndRejectsAnotherTool() {
        val clock = request.copy(scheduledReadTool = ScheduledReadPolicy.CLOCK_TOOL, scheduledClockReceiptOnly = true)
        assertEquals(clock, QueuedChatRequest.fromJson(clock.toJson()))
        assertFalse(QueuedChatRequest.fromJson(request.toJson()).scheduledClockReceiptOnly)
        assertThrows(IllegalArgumentException::class.java) { request.copy(scheduledClockReceiptOnly = true) }
        val malformed = clock.toJson().replace("\"scheduledClockReceiptOnly\":true", "\"scheduledClockReceiptOnly\":\"true\"")
        assertThrows(IllegalArgumentException::class.java) { QueuedChatRequest.fromJson(malformed) }
    }

    @Test fun restartPayloadCannotAcquireAnotherToolOrCloudAndDeviceGrants() {
        assertThrows(IllegalArgumentException::class.java) { request.copy(scheduledReadTool="save_note") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(scheduledReadTool="read_webpage") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(modelId="auto") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(pcDataBinding="b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(pcImageRecoveryAllowed=true) }
    }
}
