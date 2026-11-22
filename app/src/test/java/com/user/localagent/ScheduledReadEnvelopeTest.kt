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

    @Test fun restartPayloadCannotAcquireAnotherToolOrCloudAndDeviceGrants() {
        assertThrows(IllegalArgumentException::class.java) { request.copy(scheduledReadTool="save_note") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(scheduledReadTool="read_webpage") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(modelId="auto") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(pcDataBinding="b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(pcImageRecoveryAllowed=true) }
    }
}
