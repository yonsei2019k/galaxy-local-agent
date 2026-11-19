package com.user.localagent.data

import org.junit.Assert.*
import org.junit.Test

class RemoteQaLessonTest {
    @Test fun extractsOnlyExactTerminalHostCodes(){
        val timeout="PC_BRIDGE_TIMEOUT_GET_SESSION_STATUS"
        assertEquals(RemoteQaLesson.STATUS_TIMEOUT,RemoteQaLesson.observed("failed",timeout))
        assertNull(RemoteQaLesson.observed("completed",timeout))
        assertNull(RemoteQaLesson.observed("paused",timeout))
        assertNull(RemoteQaLesson.observed("failed","Model says $timeout"))
    }
}
