package com.user.localagent.automation

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalReadAuthorityTest {
    private fun goal()=GoalDefinition("goal-a","thread-a","project-a","Original outcome and its final suffix",
        listOf(GoalCriterion("c-a","First complete criterion"),GoalCriterion("c-b","Second complete criterion")),
        constraints=listOf("First condition","Last condition"),updatedAt=123L)
    private fun preview(goal:GoalDefinition=goal(),fields:Map<String,JsonElement> = emptyMap()):JsonObject = JsonObject(buildJsonObject {
        put("goalId",goal.id);put("expectedUpdatedAt",goal.updatedAt.toString());put("threadId",goal.threadId)
        put("projectId",goal.projectId?.let(::JsonPrimitive)?:JsonNull)
        put("toolSuccess",true);put("verification","VERIFIED");put("scopeBound",true);put("goalPresent",true)
        put("outcome",goal.outcome);put("totalCriteria",goal.criteria.size);put("totalConstraints",goal.constraints.size)
        put("criteria",buildJsonArray {goal.criteria.forEach {criterion->add(buildJsonObject {put("id",criterion.id);put("text",criterion.text)})}})
        put("constraints",JsonArray(goal.constraints.map(::JsonPrimitive)))
    }+fields)
    private fun item(preview:JsonObject,tool:String=GoalToolCatalog.GET)=buildJsonObject {
        put("tool",tool);put("success",true);put("verification","VERIFIED");put("result",preview)
    }
    private fun authority(preview:JsonObject):GoalReadAuthority=GoalReadAuthority().also {guard->
        val evidence=listOf(item(preview));guard.accept(evidence,"Actual model query\n${JsonArray(evidence)}")
    }
    private fun update(goal:GoalDefinition=goal(),patch:JsonObject)=ToolCall("edit",GoalToolCatalog.UPDATE,buildJsonObject {
        put("goalId",goal.id);put("expectedUpdatedAt",goal.updatedAt.toString());patch.forEach {key,value->put(key,value)}
    })
    private fun edit(field:String="outcome",old:String,new:String="replacement",criterionId:String?=null,index:Int?=null)=buildJsonObject {
        put("field",field);put("oldText",old);put("newText",new)
        criterionId?.let {put("criterionId",it)};index?.let {put("constraintIndex",it)}
    }
    private fun textEdit(goal:GoalDefinition=goal(),edit:JsonObject)=update(goal,buildJsonObject {put("textEdits",JsonArray(listOf(edit)))})
    private fun textRead(goal:GoalDefinition=goal(),field:String="outcome",offset:Int=0,size:Int=900,id:String?=null,index:Int?=null):JsonObject {
        val actual=when(field){"outcome"->goal.outcome;"criterion"->goal.criteria.first {it.id==id}.text;else->goal.constraints[index!!]}
        val end=minOf(actual.length,offset+size)
        return buildJsonObject {
            put("field",field);id?.let {put("criterionId",it)};index?.let {put("constraintIndex",it)}
            put("offset",offset);put("totalChars",actual.length);put("text",actual.substring(offset,end))
            put("nextOffset",if(end==actual.length)JsonNull else JsonPrimitive(end))
            put("textComplete",offset==0&&end==actual.length)
        }
    }
    private fun onlyRead(goal:GoalDefinition=goal(),read:JsonObject)=preview(goal,mapOf("outcome" to JsonPrimitive("clipped…"),
        "criteria" to JsonArray(emptyList()),"constraints" to JsonArray(emptyList()),"textRead" to read))
    private fun reject(guard:GoalReadAuthority,call:ToolCall,goal:GoalDefinition=goal())=assertTrue(runCatching {guard.requireEdit(call,goal)}.isFailure)

    @Test fun exactWholeOutcomeAllowsReplacementAndClippedPreviewDoesNot() {
        val call=update(patch=buildJsonObject {put("outcome","Entirely new outcome")})
        authority(preview()).requireEdit(call,goal())
        reject(authority(preview(fields=mapOf("outcome" to JsonPrimitive("Original outcome…")))),call)
    }

    @Test fun explicitFullFieldReadAlsoAllowsWholeOutcomeAndCriterionSetters() {
        authority(onlyRead(read=textRead())).requireEdit(update(patch=buildJsonObject {put("outcome","New outcome")}),goal())
        val read=textRead(field="criterion",id="c-b")
        val call=update(patch=buildJsonObject {put("criteria",buildJsonArray {add(buildJsonObject {put("id","c-b");put("text","New second criterion")})})})
        authority(onlyRead(read=read)).requireEdit(call,goal())
    }

    @Test fun criterionPatchAndDeleteRequireOnlyTheExactTargetCriterionWholeText() {
        val partial=preview(fields=mapOf("criteria" to buildJsonArray {add(buildJsonObject {put("id","c-a");put("text",goal().criteria[0].text)})}))
        val guard=authority(partial)
        guard.requireEdit(update(patch=buildJsonObject {put("criteria",buildJsonArray {add(buildJsonObject {put("id","c-a");put("text","Edited first")})})}),goal())
        guard.requireEdit(update(patch=buildJsonObject {put("removeCriterionIds",JsonArray(listOf(JsonPrimitive("c-a"))))}),goal())
        reject(guard,update(patch=buildJsonObject {put("removeCriterionIds",JsonArray(listOf(JsonPrimitive("c-b"))))}))
    }

    @Test fun clippedCriterionTextCannotAuthorizeItsReplacementOrDeletion() {
        val clipped=preview(fields=mapOf("criteria" to buildJsonArray {add(buildJsonObject {put("id","c-a");put("text","First…")})}))
        reject(authority(clipped),update(patch=buildJsonObject {put("criteria",buildJsonArray {add(buildJsonObject {put("id","c-a");put("text","New")})})}))
        reject(authority(clipped),update(patch=buildJsonObject {put("removeCriterionIds",JsonArray(listOf(JsonPrimitive("c-a"))))}))
    }

    @Test fun newCriterionAndEmptyOldConditionAdditionsNeedNoOldBodyRead() {
        GoalReadAuthority().requireEdit(update(patch=buildJsonObject {put("criteria",buildJsonArray {add(buildJsonObject {put("text","Added criterion")})})}),goal())
        val empty=goal().copy(constraints=emptyList())
        GoalReadAuthority().requireEdit(update(empty,buildJsonObject {put("constraints",JsonArray(listOf(JsonPrimitive("New condition"))))}),empty)
    }

    @Test fun unchangedExistingFieldsDoNotNeedReplacementProof() {
        val current=goal()
        GoalReadAuthority().requireEdit(update(current,buildJsonObject {
            put("outcome",current.outcome);put("constraints",JsonArray(current.constraints.map(::JsonPrimitive)))
            put("criteria",buildJsonArray {add(buildJsonObject {put("id","c-a");put("text",current.criteria[0].text)})})
        }),current)
    }

    @Test fun evenLosslessAdditionsNeedTheActualGoalAndVersion() {
        val patch=buildJsonObject {put("criteria",buildJsonArray {add(buildJsonObject {put("text","Add")})})}
        reject(GoalReadAuthority(),update(goal().copy(id="other-goal"),patch))
        reject(GoalReadAuthority(),update(goal().copy(updatedAt=124L),patch))
    }

    @Test fun replacingConditionsNeedsAllOldStringsInTheirOriginalOrder() {
        val call=update(patch=buildJsonObject {put("constraints",JsonArray(listOf(JsonPrimitive("Replacement condition"))))})
        authority(preview()).requireEdit(call,goal())
        for(strings in listOf(listOf("First condition"),listOf("Last condition","First condition"),listOf("First…","Last condition"))) {
            reject(authority(preview(fields=mapOf("constraints" to JsonArray(strings.map(::JsonPrimitive))))),call)
        }
    }

    @Test fun finalSuffixFragmentCanBeEditedWithoutReplacingTheUnreadPrefix() {
        val long=goal().copy(outcome="가".repeat(3000)+"Unique final suffix")
        val read=textRead(long,offset=long.outcome.length-24,size=24)
        val call=textEdit(long,edit(old="final suffix"))
        authority(onlyRead(long,read)).requireEdit(call,long)
        reject(authority(onlyRead(long,read)),update(long,buildJsonObject {put("outcome","Suffix only, prefix lost")} ),long)
    }

    @Test fun textEditNeedsTheWholeUniqueOldPhraseWithinTheDeliveredFragment() {
        val current=goal().copy(outcome="UNIQUE-before-middle-UNIQUE-after")
        val read=textRead(current,offset=10,size=12)
        authority(onlyRead(current,read)).requireEdit(textEdit(current,edit(old="middle")),current)
        reject(authority(onlyRead(current,read)),textEdit(current,edit(old="before-middle")),current)
        reject(authority(onlyRead(current,read)),textEdit(current,edit(old="UNIQUE-after")),current)
    }

    @Test fun ambiguousOldTextIsRejectedEvenWhenTheWholeFieldWasDelivered() {
        val current=goal().copy(outcome="repeat and repeat")
        reject(authority(preview(current)),textEdit(current,edit(old="repeat")),current)
    }

    @Test fun clippedNormalPreviewIsNotAValidatedOffsetFragment() {
        reject(authority(preview(fields=mapOf("outcome" to JsonPrimitive("Original outcome…")))),textEdit(edit=edit(old="Original")))
    }

    @Test fun legacyConditionBeyondSixteenCanBeReadAndPatchedByItsActualIndex() {
        val current=goal().copy(constraints=(0..19).map {"Condition $it unique"})
        val read=textRead(current,field="constraint",index=19)
        authority(onlyRead(current,read)).requireEdit(textEdit(current,edit(field="constraint",old="19",index=19)),current)
        reject(authority(onlyRead(current,read)),textEdit(current,edit(field="constraint",old="18",index=18)),current)
    }

    @Test fun fragmentOffsetTotalTextContinuationAndCompleteFlagsMustMatchCurrentSource() {
        val read=textRead(offset=9,size=12)
        val call=textEdit(edit=edit(old="outcome"))
        for(change in listOf(
            mapOf("offset" to JsonPrimitive(8)),mapOf("totalChars" to JsonPrimitive(999)),
            mapOf("text" to JsonPrimitive("x"+read.getValue("text").jsonPrimitive.content.drop(1))),
            mapOf("nextOffset" to JsonPrimitive(99)),mapOf("textComplete" to JsonPrimitive(true)),
            mapOf("offset" to JsonPrimitive("9")),mapOf("criterionId" to JsonPrimitive("c-a"))
        ))reject(authority(onlyRead(read=JsonObject(read+change))),call)
    }

    @Test fun suffixEofIsNotAnEntireFieldAndWrongFieldIdentityCannotLendProof() {
        val read=textRead(offset=9,size=900)
        reject(authority(onlyRead(read=JsonObject(read+mapOf("textComplete" to JsonPrimitive(true))))),textEdit(edit=edit(old="outcome")))
        val criterion=textRead(field="criterion",id="c-a")
        reject(authority(onlyRead(read=criterion)),textEdit(edit=edit(field="criterion",old="Second",criterionId="c-b")))
    }

    @Test fun onlyAnExactCurrentQueryItemOrWholeEvidenceArrayCanGrantProof() {
        val a=item(preview(fields=mapOf("criteria" to buildJsonArray {add(buildJsonObject {put("id","c-a");put("text",goal().criteria[0].text)})})))
        val b=item(preview(fields=mapOf("criteria" to buildJsonArray {add(buildJsonObject {put("id","c-b");put("text",goal().criteria[1].text)})})))
        val guard=GoalReadAuthority()
        val removeB=update(patch=buildJsonObject {put("removeCriterionIds",JsonArray(listOf(JsonPrimitive("c-b"))))})
        guard.accept(listOf(a,b),"Delivered individually: $a")
        reject(guard,removeB)
        guard.accept(listOf(a,b),"Whole: ${JsonArray(listOf(a,b))}")
        guard.requireEdit(removeB,goal())
        guard.accept(listOf(a,b),b.toString().dropLast(1))
        reject(guard,removeB)
    }

    @Test fun aNewGenerationReplacesOldReadAuthorityInsteadOfAccumulatingPages() {
        val guard=authority(preview())
        val call=update(patch=buildJsonObject {put("outcome","New")})
        guard.requireEdit(call,goal())
        val clipped=item(preview(fields=mapOf("outcome" to JsonPrimitive("clipped…"))))
        guard.accept(listOf(clipped),clipped.toString())
        reject(guard,call)
        guard.accept(emptyList(),"A summary without evidence")
        reject(guard,call)
    }

    @Test fun anotherGoalScopeVersionOrMissingScopeMetadataCannotAuthorizeOverwrite() {
        val call=update(patch=buildJsonObject {put("outcome","New")})
        for(change in listOf(mapOf("goalId" to JsonPrimitive("other-goal")),mapOf("threadId" to JsonPrimitive("other-thread")),
            mapOf("projectId" to JsonPrimitive("other-project")),mapOf("projectId" to JsonNull),mapOf("expectedUpdatedAt" to JsonPrimitive("124")),
            mapOf("expectedUpdatedAt" to JsonPrimitive(123)),mapOf("totalCriteria" to JsonPrimitive(999))))reject(authority(preview(fields=change)),call)
        reject(authority(JsonObject(preview().filterKeys {it!="projectId"})),call)
        reject(authority(JsonObject(preview().filterKeys {it!="threadId"})),call)
        val noProject=goal().copy(projectId=null)
        authority(preview(noProject)).requireEdit(update(noProject,buildJsonObject {put("outcome","New")}),noProject)
    }

    @Test fun currentGoalRevisionChangeRevokesPreviouslyDeliveredUnchangedText() {
        val guard=authority(preview())
        val revised=goal().copy(updatedAt=124L)
        reject(guard,update(revised,buildJsonObject {put("outcome","New")}),revised)
    }

    @Test fun sourceQuotedPreviewAndUnverifiedWrappersCannotBecomeGoalReadAuthority() {
        val call=update(patch=buildJsonObject {put("outcome","New")})
        val source=item(preview(),tool="search_notes")
        val guard=GoalReadAuthority();guard.accept(listOf(source),source.toString());reject(guard,call)
        for(changed in listOf(JsonObject(item(preview())+mapOf("success" to JsonPrimitive("true"))),
            item(preview(fields=mapOf("toolSuccess" to JsonPrimitive(false)))),item(preview(fields=mapOf("scopeBound" to JsonPrimitive(false)))),
            item(preview(fields=mapOf("verification" to JsonPrimitive("PREPARED")))))) {
            guard.accept(listOf(changed),changed.toString());reject(guard,call)
        }
    }

    @Test fun acceptedProofIsADeepCopyAndCannotBeExpandedAfterDelivery() {
        val mutable=preview(fields=mapOf("outcome" to JsonPrimitive("clipped…"))).toMutableMap()
        val delivered=item(JsonObject(mutable))
        val guard=GoalReadAuthority();guard.accept(listOf(delivered),delivered.toString())
        mutable["outcome"]=JsonPrimitive(goal().outcome)
        reject(guard,update(patch=buildJsonObject {put("outcome","New")}))
    }

    @Test fun onlyUpdateUsesThisGuardAndOversizedNewContextCannotKeepOldProof() {
        val guard=authority(preview())
        guard.requireEdit(ToolCall("read",GoalToolCatalog.GET,buildJsonObject {}),goal())
        val item=item(preview())
        guard.accept(listOf(item),"x".repeat(1_000_001)+item)
        reject(guard,update(patch=buildJsonObject {put("outcome","New")}))
        assertFalse(guard.toString().contains(goal().outcome))
    }

    @Test fun actualGoalFormatterPreservesExactReadFragmentAndScopeForAuthority() {
        val current=goal().copy(outcome="가".repeat(2500)+"Unique final suffix")
        val source=onlyRead(current,textRead(current,offset=current.outcome.length-24,size=24))
        val rendered=GoalModelContext.build(ToolResult("read",true,source.toString(),VerificationState.VERIFIED,"Actual field read"))
        val evidence=listOf(item(Json.parseToJsonElement(rendered).jsonObject))
        val guard=GoalReadAuthority();guard.accept(evidence,"Delivered source: ${JsonArray(evidence)}")
        guard.requireEdit(textEdit(current,edit(old="final suffix")),current)
        reject(guard,update(current,buildJsonObject {put("outcome","Dropped unread prefix")}),current)
    }
}
