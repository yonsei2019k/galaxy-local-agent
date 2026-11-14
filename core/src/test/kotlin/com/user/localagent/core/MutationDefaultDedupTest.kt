package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MutationDefaultDedupTest {
    private fun args(value:String)=Json.parseToJsonElement(value).jsonObject
    private fun step(tool:String,arguments:JsonObject)=HarnessStep(tool,arguments,StepEvidence(tool,VerificationState.VERIFIED,"host readback"))
    private fun decision(tool:String,previous:List<HarnessStep>,next:JsonObject)=TaskHarness.beforeStep(
        TaskGoal("Owner mutation",listOf("Actual readback"),requiredTools=setOf(tool)),previous,ToolCall("next",tool,next))
    private fun stopped(value:HarnessDecision)=assertTrue(value is HarnessDecision.Stop&&value.repeated)

    @Test fun omittedAndExplicitDefaultCollectionCannotCreateTheSameNoteTwice(){
        val omitted=args("""{"content":"report","title":"summary"}""")
        val explicit=args("""{"content":"report","title":"summary","scope":"default"}""")
        stopped(decision("save_note",listOf(step("save_note",omitted)),explicit))
        stopped(decision("save_note",listOf(step("save_note",explicit)),omitted))
    }
    @Test fun aReadBetweenEquivalentWritesDoesNotPermitAnotherCreation(){
        val saved=step("save_note",args("""{"content":"report","title":"summary"}"""))
        val read=step("search_notes",args("""{"query":"summary"}"""))
        stopped(decision("save_note",listOf(saved,read),args("""{"scope":"default","title":"summary","content":"report"}""")))
    }
    @Test fun omittedAndExplicitHostDefaultTitleAreTheSameCreation(){
        stopped(decision("save_note",listOf(step("save_note",args("""{"content":"report"}"""))),
            args("""{"content":"report","title":"메모","scope":"default"}""")))
    }
    @Test fun actualDifferentBodyTitleOrCollectionRemainsSelectable(){
        val first=step("save_note",args("""{"content":"report","title":"summary"}"""))
        listOf(
            """{"content":"different","title":"summary"}""",
            """{"content":"report","title":"different"}""",
            """{"content":"report","title":"summary","scope":"team"}""",
            """{"content":"report","title":"summary","scope":""}"""
        ).forEach{assertTrue(decision("save_note",listOf(first),args(it)) is HarnessDecision.Advance)}
    }
    @Test fun omittedAndExplicitReminderGroupCannotScheduleTwice(){
        val saved=step("create_reminder",args("""{"title":"review","datetime":"2030-01-02T13:00:00+09:00"}"""))
        stopped(decision("create_reminder",listOf(saved),args("""{"title":"review","datetime":"2030-01-02T13:00:00+09:00","scope":"default"}""")))
        assertTrue(decision("create_reminder",listOf(saved),args("""{"title":"review","datetime":"2030-01-02T13:00:00+09:00","scope":"team"}""")) is HarnessDecision.Advance)
    }
    @Test fun rawDurableReadbackFingerprintsDoNotChangeOrCollapseDefaults(){
        val omitted=step("save_note",args("""{"content":"report","title":"summary"}"""))
        val explicit=step("save_note",args("""{"content":"report","title":"summary","scope":"default"}"""))
        assertEquals("""save_note:{"content":"report","title":"summary"}""",omitted.fingerprint)
        assertNotEquals(omitted.fingerprint,explicit.fingerprint)
    }
    @Test fun callerArgumentsStayUnmodifiedAndEmptyTitleIsNotTheDefault(){
        val original=args("""{"content":"report"}""")
        val saved=step("save_note",original)
        assertEquals(original,saved.arguments)
        assertEquals(setOf("content"),saved.arguments.keys)
        assertTrue(decision("save_note",listOf(saved),args("""{"content":"report","title":""}""")) is HarnessDecision.Advance)
    }
    @Test fun otherToolArgumentsAndReadDefaultsRetainOriginalRepeatRules(){
        val read=step("search_notes",args("""{"query":"report"}"""))
        assertTrue(decision("search_notes",listOf(read),args("""{"query":"report","scope":"default"}""")) is HarnessDecision.Advance)
        val write=step("update_note",args("""{"id":"note1","content":"report","scope":"default"}"""))
        assertTrue(decision("update_note",listOf(write),args("""{"id":"note2","content":"report","scope":"default"}""")) is HarnessDecision.Advance)
    }
}
