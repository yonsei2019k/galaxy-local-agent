package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScreenObservationWaitTest {
    private val app = "com.android.chrome"
    private val wait = ScreenObservationWait(app,"과정 1강","다음",60)

    @Test fun disabledOrAmbiguousNextStaysPending() {
        val labels = listOf("과정 1강","다음")
        assertEquals(ScreenObservationWait.Status.PENDING,wait.status(app,true,labels,emptyList()))
        assertEquals(ScreenObservationWait.Status.PENDING,wait.status(app,true,labels,listOf("다음","다음")))
        assertEquals(ScreenObservationWait.Status.READY,wait.status(app,true,labels,listOf("다음")))
    }

    @Test fun differentLessonAppOrWindowCannotSatisfyWait() {
        val labels = listOf("과정 1강","다음")
        assertEquals(ScreenObservationWait.Status.CONTEXT_CHANGED,wait.status("other",true,labels,listOf("다음")))
        assertEquals(ScreenObservationWait.Status.CONTEXT_CHANGED,wait.status(app,false,labels,listOf("다음")))
        assertEquals(ScreenObservationWait.Status.CONTEXT_CHANGED,wait.status(app,true,listOf("과정 2강"),listOf("다음")))
    }

    @Test fun waitNeedsABoundedDurationAndAllBindingFields() {
        assertNull(ScreenObservationWait.from(buildJsonObject {}))
        assertNull(ScreenObservationWait.from(buildJsonObject {put(ScreenControlToolCatalog.IMAGE,true)}))
        val good=buildJsonObject {
            put("forApp",app);put("pageLabel","과정 1강");put("untilEnabledLabel","다음");put("waitSeconds",90)
        }
        assertEquals(90,ScreenObservationWait.from(good)?.seconds)
        assertThrows(IllegalArgumentException::class.java){
            ScreenObservationWait.from(JsonObject(good+("waitSeconds" to JsonPrimitive(91))))
        }
        assertThrows(IllegalArgumentException::class.java){
            ScreenObservationWait.from(JsonObject(good-"pageLabel"))
        }
    }
}
