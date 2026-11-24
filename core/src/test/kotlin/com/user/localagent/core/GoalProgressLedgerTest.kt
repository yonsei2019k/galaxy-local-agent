package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GoalProgressLedgerTest {
    private fun item(id:String,course:String="KAIRB",status:LedgerStatus=LedgerStatus.TODO,evidence:List<String> = emptyList())=
        LedgerItem(id,course,"unit-$id",LedgerKind.LECTURE,status,"",evidence)
    private fun ledger(vararg items:LedgerItem)=GoalProgressLedger("g1",items=items.toList())

    @Test fun doneWithoutObservedEvidenceIsRejected() {
        val base=ledger(item("a"))
        assertThrows(IllegalArgumentException::class.java) {
            base.apply(listOf(LedgerOp.Set("a",LedgerStatus.DONE,null,listOf("audit-x"))),emptySet(),10)
        }
    }

    @Test fun doneWithObservedEvidenceIsAccepted() {
        val base=ledger(item("a"))
        val result=base.apply(listOf(LedgerOp.Set("a",LedgerStatus.DONE,null,listOf("audit-1"))),setOf("audit-1"),10)
        assertEquals(LedgerStatus.DONE,result.items.single().status)
        assertEquals(listOf("audit-1"),result.items.single().evidence)
        assertEquals(10L,result.updatedAt)
    }

    @Test fun limitsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) {ledger(*Array(301) {item("i$it")}).validate()}
        assertThrows(IllegalArgumentException::class.java) {ledger(item("a").copy(note="n".repeat(201))).validate()}
        assertThrows(IllegalArgumentException::class.java) {GoalProgressLedger("g1",requirements="r".repeat(2001)).validate()}
        val big=Array(300) {LedgerItem("i$it".padEnd(100,'x'),"c".repeat(100),"u".repeat(200),LedgerKind.OTHER,LedgerStatus.TODO,"n".repeat(200),List(10) {"e".repeat(100)})}
        assertThrows(IllegalArgumentException::class.java) {ledger(*big).validate()}
        ledger(item("a").copy(note="n".repeat(200))).validate()
    }

    @Test fun duplicateIdAndUnknownIdAreRejected() {
        val base=ledger(item("a"))
        assertThrows(IllegalArgumentException::class.java) {GoalProgressLedger("g1").apply(listOf(LedgerOp.Add(item("a")),LedgerOp.Add(item("a"))),emptySet(),1)}
        assertThrows(IllegalArgumentException::class.java) {base.apply(listOf(LedgerOp.Add(item("a"))),emptySet(),1)}
        assertThrows(IllegalArgumentException::class.java) {base.apply(listOf(LedgerOp.Set("zzz",LedgerStatus.ACTIVE,null,emptyList())),emptySet(),1)}
    }

    @Test fun summaryCountsDonePerCourse() {
        val l=ledger(item("a",status=LedgerStatus.DONE,evidence=listOf("x")),item("b"),item("c"),item("d","OTHER"))
        assertEquals("KAIRB 1/3 · OTHER 0/1",l.summary())
    }

    @Test fun setMetaLeavesNullFieldsUnchanged() {
        val base=GoalProgressLedger("g1",requirements="req",dailyLimit=3,deadline="2026-12-31")
        val r=base.apply(listOf(LedgerOp.SetMeta(null,5,null)),emptySet(),2)
        assertEquals("req",r.requirements);assertEquals(5,r.dailyLimit);assertEquals("2026-12-31",r.deadline)
    }

    private fun call(vararg ops:JsonObject)=ToolCall("c",GoalProgressToolCatalog.UPDATE,buildJsonObject {
        put("goalId","g1");put("expectedUpdatedAt","1");put("ops",JsonArray(ops.toList()))
    })

    @Test fun parseOpsAcceptsKnownOps() {
        val ops=GoalProgressToolCatalog.parseOps(call(
            buildJsonObject {put("op","add");put("id","a");put("course","KAIRB");put("unit","1강");put("kind","lecture")},
            buildJsonObject {put("op","set");put("id","a");put("status","done");put("evidence",JsonArray(listOf(JsonPrimitive("audit-1"))))},
            buildJsonObject {put("op","setMeta");put("dailyLimit",4)},
        ))
        assertEquals(3,ops.size)
        assertEquals(LedgerOp.Set("a",LedgerStatus.DONE,null,listOf("audit-1")),ops[1])
    }

    @Test fun parseOpsRejectsUnknownKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            GoalProgressToolCatalog.parseOps(call(buildJsonObject {put("op","setMeta");put("dailyLimit",4);put("extra",1)}))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoalProgressToolCatalog.parseOps(call(buildJsonObject {put("op","delete");put("id","a")}))
        }
    }
}
