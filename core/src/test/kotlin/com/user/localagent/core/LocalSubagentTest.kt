package com.user.localagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSubagentTest {
    private val tools=setOf("get_current_datetime")
    private val toolCall="""{"action":"tool","tool":"get_current_datetime","arguments":{}}"""
    private fun final(text:String)="""{"action":"final","answer":"$text"}"""

    private class Fixture(script:List<String>,val onExecute:(ToolCall)->ToolResult) {
        private val queue=ArrayDeque(script)
        val prompts=mutableListOf<String>()
        val executed=mutableListOf<ToolCall>()
        val loop=LocalSubagentLoop(
            generate={_,user,_->prompts+=user;queue.removeFirst()},
            execute={call->executed+=call;onExecute(call)},
        )
    }
    private fun verified(call:ToolCall,content:String="synthetic-observation")=ToolResult(call.id,true,content,VerificationState.VERIFIED,"확인됨")

    @Test fun finalImmediatelyCompletesWithoutEvidence()=runTest {
        val f=Fixture(listOf(final("바로 답"))){verified(it)}
        val outcome=f.loop.run("작업",tools,"p")
        assertTrue(outcome.completed)
        assertEquals("바로 답",outcome.answer)
        assertTrue(f.executed.isEmpty())
        val result=LocalSubagentLoop.toolResult("c1",outcome)
        assertFalse(result.success)
        assertEquals(VerificationState.FAILED,result.verification)
        assertTrue(result.effectNotApplied)
    }

    @Test fun toolCallThenFinalIsVerified()=runTest {
        val f=Fixture(listOf(toolCall,final("답"))){verified(it)}
        val outcome=f.loop.run("작업",tools,"p")
        assertTrue(outcome.completed)
        assertEquals(1,f.executed.size)
        assertEquals("p-1",f.executed[0].id)
        val result=LocalSubagentLoop.toolResult("c1",outcome)
        assertTrue(result.success)
        assertEquals(VerificationState.VERIFIED,result.verification)
        val json=Json.parseToJsonElement(result.content).jsonObject
        assertEquals("completed",json.getValue("status").jsonPrimitive.content)
        assertEquals("답",json.getValue("answer").jsonPrimitive.content)
        assertEquals("get_current_datetime",json.getValue("evidence").jsonArray[0].jsonObject.getValue("tool").jsonPrimitive.content)
    }

    @Test fun unknownOrDisallowedToolIsNeverExecuted()=runTest {
        val unknown="""{"action":"tool","tool":"no_such_tool","arguments":{}}"""
        val disallowed="""{"action":"tool","tool":"list_reminders","arguments":{}}"""
        val f=Fixture(listOf(unknown,disallowed)){verified(it)}
        val outcome=f.loop.run("작업",tools,"p")
        assertFalse(outcome.completed)
        assertTrue(outcome.reason,outcome.reason.contains("형식 오류"))
        assertTrue(f.executed.isEmpty())
    }

    @Test fun oneMalformedOutputIsTolerated()=runTest {
        val f=Fixture(listOf("not json at all",final("복구"))){verified(it)}
        val outcome=f.loop.run("작업",tools,"p")
        assertTrue(outcome.completed)
        assertEquals("복구",outcome.answer)
        assertTrue(f.prompts[1].contains("[형식 오류]"))
    }

    @Test fun toolCallLimitStopsAfterSixExecutions()=runTest {
        val f=Fixture(List(7){toolCall}){verified(it)}
        val outcome=f.loop.run("작업",tools,"p")
        assertFalse(outcome.completed)
        assertEquals(6,f.executed.size)
        assertTrue(outcome.reason,outcome.reason.contains("한도"))
        val result=LocalSubagentLoop.toolResult("c1",outcome)
        assertFalse(result.success)
        assertTrue(result.effectNotApplied)
    }

    @Test fun executeExceptionBecomesFailedStepAndLoopContinues()=runTest {
        val f=Fixture(listOf(toolCall,final("실패를 알림"))){throw IllegalStateException("synthetic failure")}
        val outcome=f.loop.run("작업",tools,"p")
        assertTrue(outcome.completed)
        assertEquals(1,outcome.steps.size)
        assertEquals(VerificationState.FAILED,outcome.steps[0].state)
        val result=LocalSubagentLoop.toolResult("c1",outcome)
        assertEquals(VerificationState.FAILED,result.verification)
        assertFalse(result.success)
    }

    @Test fun observationIsLabeledUntrustedAndLogIsBounded()=runTest {
        val f=Fixture(listOf(toolCall,toolCall,final("끝"))){verified(it,"관찰값-"+"x".repeat(100_000))}
        val outcome=f.loop.run("작업",tools,"p")
        assertTrue(outcome.completed)
        assertTrue(f.prompts[1].contains("synthetic").not())
        assertTrue(f.prompts[1].contains("관찰값-"))
        assertTrue(f.prompts[1].contains("신뢰하지 않는 데이터"))
        val bound=LocalSubagentToolCatalog.MAX_LOG_CHARS+LocalSubagentToolCatalog.MAX_TASK+500
        f.prompts.forEach{assertTrue("prompt length ${it.length}",it.length<bound)}
    }

    @Test fun innerToolsFiltersAndNarrows() {
        val skill=SkillToolCatalog.names.first()
        val available=setOf("get_current_datetime","list_reminders","run_subagent","request_secret","prepare_sms","delete_note",skill)
        val all=LocalSubagentToolCatalog.innerTools(null,available)
        assertEquals(setOf("get_current_datetime","list_reminders"),all)
        assertEquals(setOf("list_reminders"),LocalSubagentToolCatalog.innerTools("list_reminders",available))
        assertEquals(all,LocalSubagentToolCatalog.innerTools("  ",available))
        listOf("run_subagent","request_secret","prepare_sms","delete_note",skill).forEach{bad->
            assertThrows(bad,IllegalArgumentException::class.java){LocalSubagentToolCatalog.innerTools(bad,available)}
        }
    }

    @Test fun cancellationPropagates() {
        val f=Fixture(listOf(toolCall,final("x"))){throw CancellationException("synthetic cancel")}
        assertThrows(CancellationException::class.java){kotlinx.coroutines.runBlocking{f.loop.run("작업",tools,"p")}}
    }

    @Test fun blankTaskIsRejected() {
        val call=ToolCall("id","run_subagent",JsonObject(mapOf("task" to JsonPrimitive("  "))))
        assertTrue(runCatching{ToolRegistry.validate(call)}.isFailure)
    }

    @Test fun policiesAndRegistryKnowRunSubagent() {
        assertTrue(ToolReadPolicy.isReadOnly("run_subagent"))
        AgentWorkMode.values().forEach{assertTrue(it.name,AgentWorkModePolicy.allows(it,"run_subagent"))}
        assertTrue("run_subagent" in ToolRegistry.definitions)
    }
}
