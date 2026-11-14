package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NextActionQuestionTest {
    @Test fun questionIsNotAnExecutableToolOrCompletion(){
        val raw="""{"type":"clarification","question":"어느 연락처인가요?"}"""
        assertEquals("어느 연락처인가요?",NextActionQuestion.parse(raw))
        assertTrue(runCatching{ToolRegistry.parseAndValidate(raw)}.isFailure)
        assertFalse(ToolRegistry.definitions.containsKey("clarification"))
    }
    @Test fun invalidQuestionsCannotSmuggleAnAction(){
        listOf(
            """{"type":"clarification","question":"누구?","tool":"prepare_sms","arguments":{}}""",
            """{"type":"clarification","question":" "}""",
            """{"type":"clarification","question":123}""",
            """{"type":"clarification","question":"처음","question":"바뀜"}""",
            """{"type":"clarification","question":"<think>추론</think>누구?"}""",
            buildJsonObject{put("type","clarification");put("question","가".repeat(301))}.toString()
        ).forEach{assertTrue(it,runCatching{NextActionQuestion.parse(it)}.isFailure)}
    }
    @Test fun ordinaryCallsKeepTheirValidationPathAndScopedSchema(){
        val owner="메모에 '원문' 저장"
        val raw="""{"type":"tool_call","tool":"save_note","arguments":{"content":"내용"}}"""
        assertNull(NextActionQuestion.parse(raw))
        assertEquals("save_note",ToolRegistry.parseAndValidate(raw).name)
        val branches=Json.parseToJsonElement(NextActionQuestion.schema(setOf("save_note"),owner)).jsonObject.getValue("oneOf").jsonArray
        assertEquals(2,branches.size)
        assertEquals(Json.parseToJsonElement(ToolCallSchema.forTools(setOf("save_note"),owner)).jsonObject.getValue("oneOf").jsonArray.single(),branches.first())
        assertFalse(NextActionQuestion.schema(setOf("save_note"),owner).contains("prepare_sms"))
        assertTrue(runCatching{NextActionQuestion.schema(emptySet())}.isFailure)
    }
}
