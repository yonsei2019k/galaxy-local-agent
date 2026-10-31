package com.user.localagent

import org.junit.Assert.*
import org.junit.Test

class ChatWorkerAdmissionTest {
    @Test fun anotherRoomCanStartWhileTheFirstRoomRemainsActive() {
        assertEquals(1, ChatWorkerAdmission.nextRunnableIndex(listOf("room-a", "room-b"), listOf("room-a")))
    }
    @Test fun twoActiveRequestsBoundAdmissionRegardlessOfWhichResourceTheyAreWaitingFor() {
        assertNull(ChatWorkerAdmission.nextRunnableIndex(listOf("room-c"), listOf("room-a", "room-b")))
    }
    @Test fun aRoomCannotOvertakeItsOwnEarlierRequest() {
        assertNull(ChatWorkerAdmission.nextRunnableIndex(listOf("room-a", "room-a"), listOf("room-a")))
        assertEquals(0, ChatWorkerAdmission.nextRunnableIndex(listOf("room-a", "room-a"), emptyList()))
    }
    @Test fun completionReleasesOnlyItsRoomAndPreservesQueueOrderAcrossTheRemainingRoom() {
        val queued = listOf("room-a", "room-b", "room-c")
        assertNull(ChatWorkerAdmission.nextRunnableIndex(queued, listOf("room-a", "room-b")))
        assertEquals(0, ChatWorkerAdmission.nextRunnableIndex(queued, listOf("room-b")))
        assertEquals(1, ChatWorkerAdmission.nextRunnableIndex(queued, listOf("room-a")))
    }
}
