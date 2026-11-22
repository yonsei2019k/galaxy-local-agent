package com.user.localagent

import com.user.localagent.core.ConversationPrivacy
import com.user.localagent.core.office.OfficeSeat
import com.user.localagent.core.office.ZipHapRoom
import com.user.localagent.inference.InferenceOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class OfficeCollaborationTest {
    private val parent = "b3180cb3-068f-49fb-a7f0-a038b7a85e3d"
    private val room = ZipHapRoom.DIRECTOR

    private fun child(stage: OfficeStage = OfficeStage.PROPOSAL) = QueuedChatRequest(
        officeChildId(parent, room, stage), room.threadId, "branch", null, "요청",
        ConversationPrivacy.LOCAL_SELECTION, false, emptyList(), "user", mode = "review",
        officeStage = stage, officeParentId = parent,
    )

    @Test fun verifiesSavedRecipientStageAndReadOnlyMode() {
        val request = child()
        assertEquals(request, QueuedChatRequest.fromJson(request.toJson()))
        assertThrows(IllegalArgumentException::class.java) { request.copy(threadId = ZipHapRoom.ASSOCIATE_GYUMIN.threadId) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(mode = "act") }
        assertThrows(IllegalArgumentException::class.java) { child(OfficeStage.SUMMARY) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(officeParentId = null) }
        assertNotEquals(request.runId, child(OfficeStage.REVIEW).runId)
    }

    @Test fun colleaguesAreBoundedDataAndDisplayRoleIsHostAssigned() {
        val text = Json.parseToJsonElement(
            officeTaskText(OfficeStage.REVIEW, OfficeSeat.DIRECTOR, "원래 요청", mapOf(OfficeSeat.CHAIR to "x".repeat(4000)))
        ).jsonObject
        assertEquals("원래 요청", text.getValue("ownerRequest").jsonPrimitive.content)
        assertEquals(1800, text.getValue("colleagueReplies").jsonObject.getValue(OfficeSeat.CHAIR.label).jsonPrimitive.content.length)
        assertEquals(OfficeSeat.DIRECTOR, officeAuthor("office.DIRECTOR"))
        assertNull(officeAuthor("회장 이수빈"))
    }

    @Test fun writerDoesNotCreateAResumeCheckpointForAnOfficeChild() {
        val request = child()
        val snapshot = ContinuationSnapshot(
            "assistant", request.modelId, request.text, "합성 지시", emptyList(), InferenceOptions(), 2,
        )

        assertNull(writerCheckpoint(request, snapshot, null))
        assertThrows(IllegalArgumentException::class.java) { request.copy(continuation = snapshot) }
        val ordinary = request.copy(officeStage = null, officeParentId = null)
        val stored = QueuedChatRequest.fromJson(checkNotNull(writerCheckpoint(ordinary, snapshot, null)))
        assertEquals(snapshot.messageId, stored.continuation?.messageId)
    }
}
