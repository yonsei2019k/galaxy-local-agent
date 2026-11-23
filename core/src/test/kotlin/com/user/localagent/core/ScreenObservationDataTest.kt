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
