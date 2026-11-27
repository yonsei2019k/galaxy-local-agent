package com.user.localagent.automation

import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalModelContextTest {
    @Test fun longRunHistoryStaysInTheJournalWhileTheModelKeepsTheGoalAndProofTarget() {
        val proof = com.user.localagent.core.GoalProofTarget(com.user.localagent.core.GoalProofKind.PROJECT_FILE, "result.txt")
        val criterion = com.user.localagent.core.GoalCriterion(
            text = "결과 저장", proofTarget = proof, evidence = List(90) { "audit-$it" },
        )
        val goal = com.user.localagent.core.GoalDefinition(
            threadId = "thread", outcome = "자료 정리", criteria = listOf(criterion),
            constraints = listOf("초안만 작성"), linkedRunIds = List(1000) { "segment-$it" },
        )
        val snapshot = GoalModelContext.snapshot(goal)
        assertEquals(1000, snapshot.getValue("linkedRunCount").jsonPrimitive.int)
        assertFalse("linkedRunIds" in snapshot)
        assertEquals(goal.toJson()["constraints"], snapshot["constraints"])
        val row = snapshot.getValue("criteria").jsonArray.single().jsonObject
        assertEquals(criterion.id, row.getValue("id").jsonPrimitive.content)
        assertEquals(proof.target, row.getValue("proofTarget").jsonObject.getValue("target").jsonPrimitive.content)
        assertEquals(90, row.getValue("evidenceCount").jsonPrimitive.int)
        assertFalse("evidence" in row)
    }

    private val id="goal-"+"a".repeat(95)
    private val revision="1900000000000"
    private fun payload()=buildJsonObject {
        put("status","goal_read");put("goalPresent",true);put("scopeBound",true)
        put("threadId","thread");put("projectId","project")
        put("goalId",id);put("expectedUpdatedAt",revision);put("goalStatus","active")
        put("outcome","복잡한 원문🙂 ".repeat(500));put("constraints",buildJsonArray {repeat(8){add("사용자 조건 ".repeat(100))}});put("totalConstraints",8)
        put("totalCriteria",16);put("criteriaOffset",0);put("criteriaPartial",false)
        put("criteria",buildJsonArray {repeat(16){index->add(buildJsonObject {
            put("id",("criterion-$index-").padEnd(100,'x'));put("text","삭제하지 않은 기준🙂 ".repeat(40));put("status","pending");put("evidenceCount",0)
        })}})
        put("deferredCallerStop",false);put("backgroundScheduled",false)
    }
    private fun result(value:JsonObject=payload(),state:VerificationState=VerificationState.VERIFIED)=ToolResult("synthetic",true,value.toString(),state)

    @Test fun boundedContextKeepsGoalVersionAndEveryVisibleCriterionIdWhole() {
        val source=payload();val text=GoalModelContext.build(result(source))
        assertTrue(text.length<=2200)
        val actual=Json.parseToJsonElement(text).jsonObject
        assertEquals(id,actual.getValue("goalId").jsonPrimitive.content)
        assertEquals(revision,actual.getValue("expectedUpdatedAt").jsonPrimitive.content)
        val rows=actual.getValue("criteria").jsonArray
        assertTrue(rows.isNotEmpty())
        rows.forEachIndexed {index,row->assertEquals(source.getValue("criteria").jsonArray[index].jsonObject.getValue("id"),row.jsonObject.getValue("id"))}
        assertEquals(rows.size,actual.getValue("nextCriteriaOffset").jsonPrimitive.int)
        assertTrue(actual.getValue("criteriaPartial").jsonPrimitive.boolean);assertTrue(actual.getValue("contextPartial").jsonPrimitive.boolean)
    }
    @Test fun deferredCallerStopAndPreparedNeverDisappearBehindLongGoalText() {
        val source=JsonObject(payload()+mapOf("deferredCallerStop" to JsonPrimitive(true),"quiesced" to JsonPrimitive(true)))
        val text=GoalModelContext.build(result(source,VerificationState.PREPARED),1600)
        val actual=Json.parseToJsonElement(text).jsonObject
        assertTrue(text.length<=1600);assertEquals("PREPARED",actual.getValue("verification").jsonPrimitive.content)
        assertTrue(actual.getValue("deferredCallerStop").jsonPrimitive.boolean)
        assertEquals(revision,actual.getValue("expectedUpdatedAt").jsonPrimitive.content)
        assertFalse(actual.getValue("goalCompletionEvidence").jsonPrimitive.boolean)
    }
    @Test fun pageOffsetsRemainActualAndNoCapabilityOrBusinessCompletionCanBeClaimed() {
        val source=JsonObject(payload()+mapOf("criteriaOffset" to JsonPrimitive(16),"totalCriteria" to JsonPrimitive(40),
            "goalCompletionEvidence" to JsonPrimitive(true),"grantsCapabilities" to JsonPrimitive(true)))
        val actual=Json.parseToJsonElement(GoalModelContext.build(result(source))).jsonObject
        assertEquals(16,actual.getValue("criteriaOffset").jsonPrimitive.int)
        assertEquals(16+actual.getValue("criteria").jsonArray.size,actual.getValue("nextCriteriaOffset").jsonPrimitive.int)
        assertFalse(actual.getValue("goalCompletionEvidence").jsonPrimitive.boolean);assertFalse(actual.getValue("grantsCapabilities").jsonPrimitive.boolean)
    }
    @Test fun absentGoalClearAndMalformedResultCannotInventRevision() {
        for(status in listOf("no_goal","goal_cleared")) {
            val value=buildJsonObject {put("status",status);put("goalPresent",false);if(status=="goal_cleared")put("goalId",id)}
            val actual=Json.parseToJsonElement(GoalModelContext.build(result(value))).jsonObject
            assertFalse(actual.containsKey("expectedUpdatedAt"));assertFalse(actual.getValue("goalPresent").jsonPrimitive.boolean)
        }
        val actual=Json.parseToJsonElement(GoalModelContext.build(ToolResult("x",false,"not JSON",VerificationState.FAILED))).jsonObject
        assertEquals("goal_result_unavailable",actual.getValue("status").jsonPrimitive.content)
        assertFalse(actual.containsKey("goalId"))
    }
    private fun fragment(text:String,offset:Int=0,total:Int=text.length)=buildJsonObject {
        put("status","goal_text_read");put("goalPresent",true);put("scopeBound",true);put("goalId",id);put("expectedUpdatedAt",revision)
        put("threadId","thread");put("projectId",JsonNull)
        put("textRead",buildJsonObject {
            put("field","outcome");put("offset",offset);put("totalChars",total);put("text",text)
            put("nextOffset",if(offset+text.length<total)JsonPrimitive(offset+text.length)else JsonNull)
            put("textComplete",offset==0&&text.length==total)
        })
    }
    @Test fun fragmentRawTextScopeAndWholeVersusSuffixArePreservedWithoutClipping() {
        val source=fragment("정확한 원문🙂 ".repeat(70),offset=100,total=2000)
        val text=GoalModelContext.build(result(source));val actual=Json.parseToJsonElement(text).jsonObject
        assertTrue(text.length<=2200);assertEquals(source.getValue("textRead"),actual.getValue("textRead"))
        assertTrue(actual.getValue("goalPresent").jsonPrimitive.boolean);assertTrue(actual.getValue("scopeBound").jsonPrimitive.boolean)
        assertEquals("thread",actual.getValue("threadId").jsonPrimitive.content);assertTrue(actual.getValue("projectId") is JsonNull)
        assertEquals(revision,actual.getValue("expectedUpdatedAt").jsonPrimitive.content)
        val suffix=fragment("TAIL",offset=7,total=11)
        val tail=Json.parseToJsonElement(GoalModelContext.build(result(suffix))).jsonObject.getValue("textRead").jsonObject
        assertFalse(tail.getValue("textComplete").jsonPrimitive.boolean);assertTrue(tail.getValue("nextOffset") is JsonNull)
    }
    @Test fun oversizedExactFragmentFailsExplicitlyRatherThanSilentlyAuthorizingClippedText() {
        val source=fragment("\\\"".repeat(450))
        assertTrue(runCatching {GoalModelContext.build(result(source),1200)}.isFailure)
    }
    @Test fun totalConstraintCountAndIncompleteMetadataCannotAuthorizeWholeConstraints() {
        val actual=Json.parseToJsonElement(GoalModelContext.build(result())).jsonObject
        assertEquals(8,actual.getValue("totalConstraints").jsonPrimitive.int)
        assertTrue(actual.getValue("constraintsPartial").jsonPrimitive.boolean)
        assertFalse(actual.getValue("constraintsComplete").jsonPrimitive.boolean)
    }
}
