package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScreenObservationWaitTest {
    @Test fun playbackCanBindTheCurrentChromeLessonWithoutUserLabels() {
        val wait=checkNotNull(ScreenObservationWait.from(buildJsonObject {put("requirePlaybackEnd",true)}))
        assertTrue(wait.automaticLesson)
        assertEquals(90,wait.seconds)
        assertEquals(ScreenObservationWait.Status.PENDING,wait.status("com.android.chrome",true,emptyList(),emptyList()))
        assertEquals(ScreenObservationWait.Status.CONTEXT_CHANGED,wait.status("com.example.other",true,emptyList(),emptyList()))
        assertEquals(ScreenObservationWait.Status.CONTEXT_CHANGED,wait.status("com.android.chrome",false,emptyList(),emptyList()))
    }

    @Test fun adaptivePlaybackCanRecheckThreeTimesWithoutUnboundedRepeats() {
        val tool=ScreenControlToolCatalog.READ
        val goal=TaskGoal("Wait for the current lesson",listOf("Observe end"),requiredTools=setOf(tool))
        val args=buildJsonObject {put("requirePlaybackEnd",true)}
        val step=HarnessStep(tool,args,StepEvidence(tool,VerificationState.VERIFIED,ScreenControlToolCatalog.PLAYBACK_WAIT_PENDING))
        val call=ToolCall("wait",tool,args)
        assertTrue(TaskHarness.beforeStep(goal,listOf(step,step),call) is HarnessDecision.Advance)
        assertTrue(TaskHarness.beforeStep(goal,listOf(step,step,step),call) is HarnessDecision.Stop)
    }

    @Test fun aDifferentAutomaticLessonDoesNotSettleAnOlderWaitWithIdenticalArguments() {
        val tool=ScreenControlToolCatalog.READ
        val goal=TaskGoal("Wait for this lesson",listOf("Observe end"),requiredTools=setOf(tool))
        val args=buildJsonObject {put("requirePlaybackEnd",true)}
        fun entry(summary:String,scope:String):HarnessStep {
            val call=ToolCall(scope,tool,args)
            val result=ToolResult(scope,true,"{}",VerificationState.VERIFIED,summary,
                screenReadEvidence=ScreenReadEvidence("id","browser",1,emptyMap(),scope))
            return HarnessStep(call,AnalysisCompletionEvidence.fromResult(call,result))
        }
        val first="a1234567-1234-1234-1234-123456789abc"
        val second="b1234567-1234-1234-1234-123456789abc"
        val pending=entry(ScreenControlToolCatalog.PLAYBACK_WAIT_PENDING,first)
        val wrong=entry(ScreenControlToolCatalog.PLAYBACK_WAIT_READY,second)
        val right=entry(ScreenControlToolCatalog.PLAYBACK_WAIT_READY,first)
        assertTrue(TaskHarness.decide(goal,listOf(pending,wrong),requestedFinish=true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal,listOf(pending,right),requestedFinish=true) is HarnessDecision.Finish)
    }
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
