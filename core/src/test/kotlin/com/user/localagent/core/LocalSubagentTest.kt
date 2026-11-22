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
        // Distinct arguments per call: identical verified repeats are now blocked before they count.
        val f=Fixture(List(7){"""{"action":"tool","tool":"search_contacts","arguments":{"query":"q$it"}}"""}){verified(it)}
        val outcome=f.loop.run("작업",setOf("search_contacts"),"p")
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

    @Test fun mixedObservationsRemainPartialAfterFinal()=runTest {
        for(state in listOf(VerificationState.FAILED,VerificationState.PREPARED,VerificationState.UNVERIFIED)) {
            val next="""{"action":"tool","tool":"list_reminders","arguments":{}}"""
            val f=Fixture(listOf(toolCall,next,final("둘 다 확인했습니다"))){call->
                if(call.name=="get_current_datetime")verified(call)
                else ToolResult(call.id,false,"합성 미확인",state)
            }
            val outcome=f.loop.run("두 자료 확인",tools+"list_reminders","p")
            val result=LocalSubagentLoop.toolResult("c1",outcome)
            val body=Json.parseToJsonElement(result.content).jsonObject
            assertFalse(result.success)
            assertEquals(VerificationState.UNVERIFIED,result.verification)
            assertEquals("partial",body.getValue("status").jsonPrimitive.content)
            assertEquals(2,body.getValue("evidence").jsonArray.size)
        }
    }

    @Test fun nonStringFinalFieldsAreRejected()=runTest {
        for(value in listOf("42","true","null")) {
            val invalid="""{"action":"final","answer":$value}"""
            val f=Fixture(listOf(invalid,invalid)){verified(it)}
            assertFalse(f.loop.run("작업",tools,"p").completed)
            assertTrue(f.executed.isEmpty())
        }
    }

    @Test fun mixedActionFieldsAreRejectedBeforeHostCall()=runTest {
        val invalid="""{"action":"tool","tool":"get_current_datetime","answer":"완료"}"""
        val f=Fixture(listOf(invalid,invalid,final("검정용"))){verified(it)}
        assertFalse(f.loop.run("작업",tools,"p").completed)
        assertTrue(f.executed.isEmpty())
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

    // ---- budget / repeat guard / classification / resolution / observed ----
    private val twoTools=setOf("search_contacts","search_memories")
    private fun callJson(tool:String,args:String)="""{"action":"tool","tool":"$tool","arguments":$args}"""
    private val a1=callJson("search_contacts","""{"query":"alpha","limit":2}""")
    private val a2=callJson("search_contacts","""{"limit":2,"query":"alpha"}""")
    private val b1=callJson("search_memories","""{"query":"beta"}""")
    private fun obj(vararg pairs:Pair<String,String>)=JsonObject(pairs.associate{it.first to JsonPrimitive(it.second)})
    private fun step(tool:String,state:VerificationState,args:JsonObject)=LocalSubagentStep(tool,state,"s",args)

    @Test fun innerBudgetReservesParentReceiptSlot() {
        assertEquals(6,LocalSubagentToolCatalog.innerBudget(0,18,null))
        assertEquals(1,LocalSubagentToolCatalog.innerBudget(16,18,null))
        assertEquals(0,LocalSubagentToolCatalog.innerBudget(17,18,null))
        assertEquals(1,LocalSubagentToolCatalog.innerBudget(0,18,2))
        assertEquals(0,LocalSubagentToolCatalog.innerBudget(0,18,1))
    }

    @Test fun maxToolCallsLimitsExecutionsAndShowsRemaining()=runTest {
        val f=Fixture(listOf(a1,b1,final("x"))){verified(it)}
        val outcome=f.loop.run("작업",twoTools,"p",maxToolCalls=1)
        assertFalse(outcome.completed)
        assertEquals(1,f.executed.size)
        assertTrue(outcome.reason,outcome.reason.contains("1회"))
        assertTrue(f.prompts[0].contains("남은 도구 호출: 1회"))
        assertTrue(f.prompts[1].contains("남은 도구 호출: 0회"))
    }

    @Test fun invalidMaxToolCallsRejected() {
        for(n in listOf(0,7)) {
            val f=Fixture(listOf(final("x"))){verified(it)}
            assertThrows(IllegalArgumentException::class.java){kotlinx.coroutines.runBlocking{f.loop.run("작업",twoTools,"p",maxToolCalls=n)}}
        }
    }

    @Test fun reorderedArgumentRepeatIsBlockedWithEarlierObservation()=runTest {
        val f=Fixture(listOf(a1,a2,final("끝"))){verified(it,"obs-alpha-text")}
        val outcome=f.loop.run("작업",twoTools,"p")
        assertTrue(outcome.completed)
        assertEquals(1,f.executed.size)
        assertEquals(1,outcome.steps.size)
        assertTrue(f.prompts[2].contains("반복 차단"))
        assertTrue(f.prompts[2].contains("obs-alpha-text"))
    }

    @Test fun aThenBThenAIsBlockedOnThird()=runTest {
        val f=Fixture(listOf(a1,b1,a2,final("끝"))){verified(it,"obs-${it.name}")}
        val outcome=f.loop.run("작업",twoTools,"p")
        assertTrue(outcome.completed)
        assertEquals(listOf("search_contacts","search_memories"),f.executed.map{it.name})
        assertTrue(f.prompts[3].contains("반복 차단"))
        assertTrue(f.prompts[3].contains("obs-search_contacts"))
    }

    @Test fun twoBlockedRepeatsEndIncomplete()=runTest {
        val f=Fixture(listOf(a1,a2,a1,final("x"))){verified(it)}
        val outcome=f.loop.run("작업",twoTools,"p")
        assertFalse(outcome.completed)
        assertEquals(1,f.executed.size)
        assertTrue(outcome.reason,outcome.reason.contains("반복"))
    }

    @Test fun sameToolDifferentArgsExecutes()=runTest {
        val other=callJson("search_contacts","""{"query":"gamma","limit":2}""")
        val f=Fixture(listOf(a1,other,final("끝"))){verified(it)}
        assertTrue(f.loop.run("작업",twoTools,"p").completed)
        assertEquals(2,f.executed.size)
    }

    @Test fun repeatOfFailedCallIsExecutedAgain()=runTest {
        var n=0
        val f=Fixture(listOf(a1,a2,final("끝"))){call->
            n++
            if(n==1)ToolResult(call.id,false,"fail",VerificationState.FAILED,"실패")else verified(call)
        }
        val outcome=f.loop.run("작업",twoTools,"p")
        assertTrue(outcome.completed)
        assertEquals(2,f.executed.size)
        assertTrue(LocalSubagentLoop.unresolved(outcome.steps).isEmpty())
    }

    @Test fun invalidArgumentsAreRefusedBeforeExecution()=runTest {
        val missing=callJson("search_contacts","{}")
        val f=Fixture(listOf(missing,final("끝"))){verified(it)}
        val outcome=f.loop.run("작업",twoTools,"p")
        assertTrue(f.executed.isEmpty())
        val st=outcome.steps.single()
        assertEquals(false,st.started)
        assertTrue(st.effectNotApplied)
        assertEquals(VerificationState.FAILED,st.state)
        assertTrue(st.summary,st.summary.startsWith("실행 전 검증 거부"))
    }

    @Test fun escapedExceptionHasUnknownStart()=runTest {
        val f=Fixture(listOf(a1,final("끝"))){throw IllegalStateException("synthetic")}
        val outcome=f.loop.run("작업",twoTools,"p")
        val st=outcome.steps.single()
        assertEquals(null,st.started)
        assertFalse(st.effectNotApplied)
        assertEquals("실행 중 예외 · 시작 여부 미확인",st.summary)
        val evidence=Json.parseToJsonElement(LocalSubagentLoop.toolResult("c",outcome).content).jsonObject.getValue("evidence").jsonArray[0].jsonObject
        assertEquals("unknown",evidence.getValue("started").jsonPrimitive.content)
        assertFalse("effectNotApplied" in evidence)
    }

    @Test fun structuredHostFailureIsPreserved()=runTest {
        val f=Fixture(listOf(a1,final("끝"))){ToolResult(it.id,false,"host-fail",VerificationState.FAILED,"구조 실패",effectNotApplied=false)}
        val outcome=f.loop.run("작업",twoTools,"p")
        val st=outcome.steps.single()
        assertEquals(true,st.started)
        assertFalse(st.effectNotApplied)
        assertEquals("구조 실패",st.summary)
        val evidence=Json.parseToJsonElement(LocalSubagentLoop.toolResult("c",outcome).content).jsonObject.getValue("evidence").jsonArray[0].jsonObject
        assertEquals("true",evidence.getValue("started").jsonPrimitive.content)
    }

    @Test fun evidenceCarriesStartedFalseAndEffectNotApplied()=runTest {
        val f=Fixture(listOf(callJson("search_contacts","{}"),final("끝"))){verified(it)}
        val outcome=f.loop.run("작업",twoTools,"p")
        val evidence=Json.parseToJsonElement(LocalSubagentLoop.toolResult("c",outcome).content).jsonObject.getValue("evidence").jsonArray[0].jsonObject
        assertEquals("false",evidence.getValue("started").jsonPrimitive.content)
        assertEquals("true",evidence.getValue("effectNotApplied").jsonPrimitive.content)
    }

    @Test fun failedThenVerifiedSameCallIsResolved() {
        val steps=listOf(step("t",VerificationState.FAILED,obj("a" to "1","b" to "2")),step("t",VerificationState.VERIFIED,obj("b" to "2","a" to "1")))
        assertTrue(LocalSubagentLoop.unresolved(steps).isEmpty())
        val r=LocalSubagentLoop.toolResult("c",LocalSubagentOutcome(true,"답","",steps))
        assertEquals(VerificationState.VERIFIED,r.verification)
        assertEquals("completed",Json.parseToJsonElement(r.content).jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test fun failedThenVerifiedDifferentTargetStaysPartial() {
        val steps=listOf(step("t",VerificationState.FAILED,obj("a" to "1")),step("t",VerificationState.VERIFIED,obj("a" to "2")))
        assertEquals(listOf(0),LocalSubagentLoop.unresolved(steps))
        val r=LocalSubagentLoop.toolResult("c",LocalSubagentOutcome(true,"답","",steps))
        assertEquals(VerificationState.UNVERIFIED,r.verification)
        assertEquals("partial",Json.parseToJsonElement(r.content).jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test fun verifiedThenLaterFailedStaysUnresolved() {
        val steps=listOf(step("t",VerificationState.VERIFIED,obj("a" to "1")),step("t",VerificationState.FAILED,obj("a" to "1")))
        assertEquals(listOf(1),LocalSubagentLoop.unresolved(steps))
        val r=LocalSubagentLoop.toolResult("c",LocalSubagentOutcome(true,"답","",steps))
        assertEquals(VerificationState.UNVERIFIED,r.verification)
    }

    @Test fun observedSurvivesInterruption()=runTest {
        var n=0
        val f=Fixture(listOf(a1,b1,final("x"))){call->
            n++
            if(n==2)throw CancellationException("synthetic cancel")
            verified(call)
        }
        assertThrows(CancellationException::class.java){kotlinx.coroutines.runBlocking{f.loop.run("작업",twoTools,"p")}}
        assertEquals(1,f.loop.observed.size)
        assertEquals(VerificationState.VERIFIED,f.loop.observed[0].state)
        val r=LocalSubagentLoop.toolResult("c",LocalSubagentOutcome(false,"","취소됨",f.loop.observed))
        assertEquals(VerificationState.FAILED,r.verification)
        assertTrue(r.verificationSummary,r.verificationSummary.contains("보존"))
        assertEquals("partial",Json.parseToJsonElement(r.content).jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test fun loopCannotRunTwice()=runTest {
        val f=Fixture(listOf(final("a"),final("b"))){verified(it)}
        f.loop.run("작업",twoTools,"p")
        assertThrows(IllegalStateException::class.java){kotlinx.coroutines.runBlocking{f.loop.run("작업",twoTools,"p")}}
    }

    @Test fun formatErrorsAndUnlistedToolsNeverExecute()=runTest {
        val badArgs="""{"action":"tool","tool":"search_contacts","arguments":"x"}"""
        val unlisted=callJson("list_reminders","{}")
        for(script in listOf(listOf("garbage",badArgs),listOf(unlisted,unlisted),listOf(badArgs,unlisted))) {
            val f=Fixture(script){verified(it)}
            val outcome=f.loop.run("작업",twoTools,"p")
            assertFalse(outcome.completed)
            assertEquals(0,f.executed.size)
        }
    }
}
