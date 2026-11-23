package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScreenObservationDataTest {
    @Test fun chromeObservationRetainsActionRefsBeyondThreeRows() {
        val snapshot = "a".repeat(64)
        val raw = buildJsonObject {
            put("snapshotId", snapshot)
            put("screenChanged", true)
            put("lastAction", "tap")
            put("texts", JsonArray(listOf(JsonPrimitive("온라인 교육"))))
            put("elements", buildJsonArray {
                for (i in 1..90) add(buildJsonObject {
                    put("ref", "e"+i)
                    put("role", "tap")
                    put("label", if (i == 12) "다음 강의" else "메뉴 "+i)
                })
            })
        }
        val result = ScreenObservationData.forModel(raw.toString())
        assertTrue(result.length <= 4200)
        val output = Json.parseToJsonElement(result).jsonObject
        val data = output.getValue("data").jsonObject
        assertEquals(JsonPrimitive(true), output["dataOnly"])
        assertEquals(JsonPrimitive(true), output["sourceScopePartial"])
        assertEquals(JsonPrimitive(snapshot), data["snapshotId"])
        assertEquals(JsonPrimitive(true), data["screenChanged"])
        assertTrue(data.getValue("elements").jsonArray.any { it.jsonObject["ref"] == JsonPrimitive("e12") })
        assertEquals(JsonPrimitive("온라인 교육"), data.getValue("texts").jsonArray.single())
    }

    @Test fun playbackAndImageEvidenceSurviveAFullNativeButtonList() {
        val raw=buildJsonObject {
            put("snapshotId","a".repeat(64));put("imageStatus","candidates_found")
            put("playbackStatus","unverified");put("effectStatus","check_current_screen")
            put("expectedChange","재생 시간이 늘어남");put("mediaElapsedSeconds",20);put("mediaDurationSeconds",600)
            put("elements",buildJsonArray {
                repeat(100) {index->add(buildJsonObject {put("ref","e$index");put("label","메뉴 $index")})}
                add(buildJsonObject {put("ref","v123");put("source","image");put("label","▶ 동영상 재생")})
            })
        }
        val compact=ScreenObservationData.forModel(raw.toString(),refreshedRef="e88")
        val data=Json.parseToJsonElement(compact).jsonObject.getValue("data").jsonObject
        assertTrue(compact.length<=4200)
        assertEquals(listOf("e88","v123"),data.getValue("elements").jsonArray.take(2).map {it.jsonObject.getValue("ref").jsonPrimitive.content})
        for (key in listOf("imageStatus","playbackStatus","effectStatus","expectedChange","mediaElapsedSeconds","mediaDurationSeconds"))
            assertEquals(raw[key],data[key])
    }

    @Test fun aPendingPlaybackWaitCannotFinishAndAnUnrelatedLessonCannotResolveIt() {
        val read=ScreenControlToolCatalog.READ
        val action=ScreenControlToolCatalog.ACTION
        val goal=TaskGoal("Complete the lesson",listOf("Confirm player stopped"),requiredTools=setOf(read,action))
        fun wait(page:String,seconds:Int,summary:String):HarnessStep {
            val args=buildJsonObject {
                put("forApp","com.android.chrome");put("pageLabel",page);put("untilEnabledLabel","다음")
                put("waitSeconds",seconds);put("requirePlaybackEnd",true)
            }
            return HarnessStep(read,args,StepEvidence(read,VerificationState.VERIFIED,summary))
        }
        val played=HarnessStep(action,buildJsonObject {put("ref","v001")},StepEvidence(action,VerificationState.VERIFIED,"tapped"))
        val pending=wait("7강",30,ScreenControlToolCatalog.PLAYBACK_WAIT_PENDING)
        val unrelated=wait("8강",30,ScreenControlToolCatalog.PLAYBACK_WAIT_READY)
        val ready=wait("7강",20,ScreenControlToolCatalog.PLAYBACK_WAIT_READY)
        assertTrue(TaskHarness.decide(goal,listOf(played,pending),requestedFinish=true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal,listOf(played,pending,unrelated),requestedFinish=true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal,listOf(played,pending,ready),requestedFinish=true) is HarnessDecision.Finish)
    }

    @Test fun screenReadsAfterNavigationStayWithinTheNormalStepBudget() {
        val read = ScreenControlToolCatalog.READ
        val action = ScreenControlToolCatalog.ACTION
        val goal = TaskGoal("Navigate the course", listOf("See the lesson"), requiredTools = setOf(read, action))
        val steps = mutableListOf<HarnessStep>()
        fun attempt(tool: String, args: JsonObject = JsonObject(emptyMap())) =
            TaskHarness.beforeStep(goal, steps, ToolCall("next", tool, args))
        fun completed(tool: String, args: JsonObject = JsonObject(emptyMap())) =
            HarnessStep(tool, args, StepEvidence(tool, VerificationState.VERIFIED, "observed"))
        repeat(4) { page ->
            assertTrue(attempt(read) is HarnessDecision.Advance)
            steps.add(completed(read))
            val args = buildJsonObject { put("action", "tap"); put("snapshotId", "screen"+page); put("ref", "e12") }
            assertTrue(attempt(action, args) is HarnessDecision.Advance)
            steps.add(completed(action, args))
        }
        steps.add(completed(read))
        assertTrue(attempt(read) is HarnessDecision.Advance)
        steps.add(completed(read))
        assertTrue(attempt(read) is HarnessDecision.Stop)
        val firstAction = steps[1]
        assertTrue(attempt(action, firstAction.arguments) is HarnessDecision.Stop)
    }
}
