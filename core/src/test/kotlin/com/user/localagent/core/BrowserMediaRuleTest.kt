package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserMediaRuleTest {
    private val initial=BrowserMediaSample("${"a".repeat(32)}:1",0,1000,60000,
        paused=false,ended=false,seeking=false,readyState=4,rate=2.0)

    @Test fun actualTimelineAndPlaybackRateProduceAnEstimate() {
        val progress=BrowserMediaProgress()
        assertEquals(BrowserMediaStatus.CHECKING,progress.observe(initial,1000).status)
        val advancing=progress.observe(initial.copy(positionMs=5000),3000)
        assertEquals(BrowserMediaStatus.PLAYING,advancing.status)
        assertEquals(28,advancing.remainingSeconds)
        assertEquals(BrowserMediaStatus.PAUSED,progress.observe(initial.copy(positionMs=5000,paused=true),5000).status)
    }

    @Test fun bufferingAndSeekingDoNotSupplyTerminalPlaybackEvidence() {
        val progress=BrowserMediaProgress()
        progress.observe(initial,1000)
        val waiting=progress.observe(initial.copy(readyState=2),3000)
        assertEquals(BrowserMediaStatus.BUFFERING,waiting.status)
        assertNull(waiting.remainingSeconds)
        val seek=initial.copy(positionMs=59900,epoch=1,seeking=true)
        assertEquals(BrowserMediaStatus.SEEKING,progress.observe(seek,5000).status)
        assertEquals(BrowserMediaStatus.CHECKING,progress.observe(seek.copy(seeking=false,ended=true,positionMs=60000),6000).status)
    }

    @Test fun elapsedEndRequiresContinuityAndUnknownDurationCanStillPlay() {
        val progress=BrowserMediaProgress()
        val nearEnd=initial.copy(positionMs=56000)
        progress.observe(nearEnd,1000)
        assertEquals(BrowserMediaStatus.ENDED,
            progress.observe(nearEnd.copy(positionMs=60000,paused=true,ended=true),3000).status)
        val unbounded=initial.copy(durationMs=null)
        progress.observe(unbounded,5000)
        val playing=progress.observe(unbounded.copy(positionMs=5000),7000)
        assertEquals(BrowserMediaStatus.PLAYING,playing.status)
        assertNull(playing.remainingSeconds)
    }

    @Test fun observerArgumentsAreReadOnlyAndBounded() {
        val call=ToolCall("test",AppBrowserToolCatalog.READ,buildJsonObject {
            put("playbackRule","playing && advancing");put("waitSeconds",9)
        })
        for(browser in listOf(AppBrowserToolCatalog,PcBrowserToolCatalog)){
            val observation=call.copy(name=browser.read)
            ToolRegistry.validate(observation)
            browser.validate(observation)
            assertEquals(9,BrowserMediaRule.from(observation)?.waitSeconds)
            assertThrows(IllegalArgumentException::class.java) {browser.validate(observation.copy(
                arguments=buildJsonObject {put("waitSeconds",9)}
            ))}
            assertThrows(IllegalArgumentException::class.java) {ToolRegistry.validate(observation.copy(
                arguments=JsonObject(observation.arguments+("script" to JsonPrimitive("bad")))
            ))}
        }
    }

    @Test fun theSameObservationCanWaitAcrossBoundedReads() {
        val tool=AppBrowserToolCatalog.READ
        val args=buildJsonObject {put("playbackRule","ended")}
        val goal=TaskGoal("Watch the media",listOf("Observe end"),requiredTools=setOf(tool))
        val call=ToolCall("wait",tool,args)
        val step=HarnessStep(call,StepEvidence(tool,VerificationState.VERIFIED,"playing"))
        assertTrue(TaskHarness.beforeStep(goal,listOf(step,step),call) is HarnessDecision.Advance)
        assertTrue(TaskHarness.beforeStep(goal,listOf(step,step,step),call) is HarnessDecision.Stop)
    }
}
