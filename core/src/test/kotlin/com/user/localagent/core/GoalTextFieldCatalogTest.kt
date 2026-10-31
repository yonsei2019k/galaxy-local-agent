package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Field identity/shape checks through the actual registry and native schema, no text rewriting. */
class GoalTextFieldCatalogTest {
    private fun objectOf(vararg values:Pair<String,JsonElement>)=JsonObject(mapOf(*values))
    private fun version()=objectOf("goalId" to JsonPrimitive("actual-goal"),"expectedUpdatedAt" to JsonPrimitive("1900000000000"))
    private fun read(field:String,selector:Pair<String,JsonElement>?=null)=JsonObject(version()+mapOf("textField" to JsonPrimitive(field))+listOfNotNull(selector))
    private fun edit(field:String,selector:Pair<String,JsonElement>?=null,old:String="원문",new:String="수정")=
        JsonObject(mapOf("field" to JsonPrimitive(field),"oldText" to JsonPrimitive(old),"newText" to JsonPrimitive(new))+listOfNotNull(selector))
    private fun patch(vararg edits:JsonElement)=JsonObject(version()+mapOf("textEdits" to JsonArray(edits.toList())))
    private fun valid(name:String,arguments:JsonObject)=ToolRegistry.parseAndValidate(buildJsonObject{
        put("type","tool_call");put("tool",name);put("arguments",arguments)
    }.toString())
    private fun invalid(name:String,arguments:JsonObject){assertTrue(arguments.keys.toString(),runCatching{valid(name,arguments)}.isFailure)}
    private val criterion="criterionId" to JsonPrimitive("criterion-actual")
    private val constraint="constraintIndex" to JsonPrimitive(0)

    @Test fun allThreeFieldReadsUseActualVersionAndTypedSelectors(){
        valid(GoalToolCatalog.GET,read("outcome"))
        valid(GoalToolCatalog.GET,read("criterion",criterion))
        valid(GoalToolCatalog.GET,read("constraint",constraint))
        assertEquals(setOf("outcome","criterion","constraint"),GoalToolCatalog.textFields)
    }

    @Test fun fragmentReadsAlwaysRequireBothGoalAndVersion(){
        for(key in listOf("goalId","expectedUpdatedAt"))invalid(GoalToolCatalog.GET,JsonObject(read("outcome").filterKeys{it!=key}))
        invalid(GoalToolCatalog.GET,objectOf("textField" to JsonPrimitive("outcome")))
        invalid(GoalToolCatalog.GET,JsonObject(read("outcome")+mapOf("textField" to JsonPrimitive("unknown"))))
    }

    @Test fun fieldIdentityCannotBeOmittedCrossedOrForged(){
        invalid(GoalToolCatalog.GET,read("criterion"))
        invalid(GoalToolCatalog.GET,read("constraint"))
        invalid(GoalToolCatalog.GET,read("outcome",criterion))
        invalid(GoalToolCatalog.GET,read("outcome",constraint))
        invalid(GoalToolCatalog.GET,read("criterion",constraint))
        invalid(GoalToolCatalog.GET,read("constraint",criterion))
        invalid(GoalToolCatalog.GET,JsonObject(read("criterion",criterion)+constraint))
        invalid(GoalToolCatalog.GET,read("criterion","criterionId" to JsonPrimitive("x".repeat(101))))
    }

    @Test fun textFragmentAndCriteriaPageArgumentsCannotBeMixedEvenAtDefaults(){
        for(key in listOf("criteriaOffset","criteriaLimit"))invalid(GoalToolCatalog.GET,JsonObject(read("outcome")+mapOf(key to JsonPrimitive(if(key=="criteriaOffset")0 else 8))))
        valid(GoalToolCatalog.GET,version())
        valid(GoalToolCatalog.GET,JsonObject(version()+mapOf("criteriaOffset" to JsonPrimitive(8))))
    }

    @Test fun textArgumentsCannotSilentlyChangeTheNormalCriteriaRead(){
        for(extra in listOf(criterion,constraint,"textOffset" to JsonPrimitive(0),"textLimit" to JsonPrimitive(900)))
            invalid(GoalToolCatalog.GET,JsonObject(version()+extra))
    }

    @Test fun textOffsetAndLimitKeepExactIntegerBounds(){
        for(offset in listOf(0,12000))for(limit in listOf(1,900))valid(GoalToolCatalog.GET,JsonObject(read("outcome")+mapOf("textOffset" to JsonPrimitive(offset),"textLimit" to JsonPrimitive(limit))))
        for(offset in listOf<JsonElement>(JsonPrimitive(-1),JsonPrimitive(12001),JsonPrimitive("0"),JsonPrimitive(1.5)))
            invalid(GoalToolCatalog.GET,JsonObject(read("outcome")+mapOf("textOffset" to offset)))
        for(limit in listOf<JsonElement>(JsonPrimitive(0),JsonPrimitive(901),JsonPrimitive("900"),JsonNull))
            invalid(GoalToolCatalog.GET,JsonObject(read("outcome")+mapOf("textLimit" to limit)))
        for(index in listOf<JsonElement>(JsonPrimitive(-1),JsonPrimitive(12001),JsonPrimitive("0"),JsonPrimitive(0.5)))
            invalid(GoalToolCatalog.GET,read("constraint","constraintIndex" to index))
        valid(GoalToolCatalog.GET,read("constraint","constraintIndex" to JsonPrimitive(15)))
        valid(GoalToolCatalog.GET,read("constraint","constraintIndex" to JsonPrimitive(16)))
        valid(GoalToolCatalog.GET,read("constraint","constraintIndex" to JsonPrimitive(12000)))
    }

    @Test fun exactPartialEditsAllowDeletionAndDistinctActualFieldIdentities(){
        valid(GoalToolCatalog.UPDATE,patch(edit("outcome",old="원문",new=""),edit("criterion",criterion),
            edit("criterion","criterionId" to JsonPrimitive("criterion-another")),edit("constraint",constraint),
            edit("constraint","constraintIndex" to JsonPrimitive(15))))
        valid(GoalToolCatalog.UPDATE,patch(edit("outcome",old=" ",new="\n")))
        assertTrue(GoalToolCatalog.isArrayArgument(GoalToolCatalog.UPDATE,"textEdits"))
        assertFalse(GoalToolCatalog.isArrayArgument(GoalToolCatalog.CREATE,"textEdits"))
    }

    @Test fun textEditsCannotSupplyEvidenceUnknownFieldsOrMissingText(){
        val base=edit("outcome")
        for(key in listOf("field","oldText","newText"))invalid(GoalToolCatalog.UPDATE,patch(JsonObject(base.filterKeys{it!=key})))
        for(key in listOf("status","evidence","scope","expectedOldTextSha256"))
            invalid(GoalToolCatalog.UPDATE,patch(JsonObject(base+mapOf(key to JsonPrimitive("forged")))))
        invalid(GoalToolCatalog.UPDATE,patch(JsonPrimitive("untyped")))
        invalid(GoalToolCatalog.UPDATE,patch(edit("unknown")))
        invalid(GoalToolCatalog.UPDATE,patch(edit("criterion")))
        invalid(GoalToolCatalog.UPDATE,patch(edit("constraint")))
        invalid(GoalToolCatalog.UPDATE,patch(edit("outcome",criterion)))
        invalid(GoalToolCatalog.UPDATE,patch(edit("criterion",constraint)))
    }

    @Test fun textFragmentLengthsAndNulValidationReachTheRegistryArrayHook(){
        valid(GoalToolCatalog.UPDATE,patch(edit("outcome",old="가".repeat(900),new="나".repeat(2000))))
        for(old in listOf("","가".repeat(901),"a\u0000b"))invalid(GoalToolCatalog.UPDATE,patch(edit("outcome",old=old)))
        for(new in listOf("나".repeat(2001),"a\u0000b"))invalid(GoalToolCatalog.UPDATE,patch(edit("outcome",new=new)))
        invalid(GoalToolCatalog.UPDATE,patch(JsonObject(edit("outcome")+mapOf("oldText" to JsonPrimitive(1)))))
        invalid(GoalToolCatalog.UPDATE,patch(JsonObject(edit("outcome")+mapOf("newText" to JsonNull))))
    }

    @Test fun repeatedFieldIdentityIsRejectedWithoutConfusingDifferentCriteria(){
        for(duplicate in listOf(edit("outcome"),edit("criterion",criterion),edit("constraint",constraint)))
            invalid(GoalToolCatalog.UPDATE,patch(duplicate,duplicate))
        invalid(GoalToolCatalog.UPDATE,patch(edit("criterion",criterion,old="앞부분"),edit("criterion",criterion,old="뒷부분")))
        valid(GoalToolCatalog.UPDATE,patch(edit("criterion",criterion),edit("criterion","criterionId" to JsonPrimitive("other"))))
    }

    @Test fun onlyOneToEightEditsCanBeSubmittedAndWholeReplacementIsExclusive(){
        invalid(GoalToolCatalog.UPDATE,patch())
        val edits=(0..8).map{edit("criterion","criterionId" to JsonPrimitive("criterion-$it"))}
        valid(GoalToolCatalog.UPDATE,patch(*edits.take(8).toTypedArray()))
        invalid(GoalToolCatalog.UPDATE,patch(*edits.toTypedArray()))
        for((key,value)in mapOf("outcome" to JsonPrimitive("전체 결과"),"criteria" to JsonArray(emptyList()),
            "constraints" to JsonArray(emptyList()),"removeCriterionIds" to JsonArray(emptyList())))
            invalid(GoalToolCatalog.UPDATE,JsonObject(patch(edit("outcome"))+mapOf(key to value)))
    }

    @Test fun nativeSchemaAllowsEmptyReplacementOnlyAndEnforcesEachFieldShape(){
        val schema=Json.parseToJsonElement(ToolCallSchema.forTools(setOf(GoalToolCatalog.UPDATE))).jsonObject
        val arguments=schema.getValue("oneOf").jsonArray.single().jsonObject.getValue("properties").jsonObject.getValue("arguments").jsonObject
        val edits=arguments.getValue("properties").jsonObject.getValue("textEdits").jsonObject
        assertEquals(1,edits.getValue("minItems").jsonPrimitive.int);assertEquals(8,edits.getValue("maxItems").jsonPrimitive.int)
        val variants=edits.getValue("items").jsonObject.getValue("oneOf").jsonArray
        assertEquals(3,variants.size)
        for(variant in variants){
            val item=variant.jsonObject;val properties=item.getValue("properties").jsonObject
            val field=properties.getValue("field").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content
            assertFalse(item.getValue("additionalProperties").jsonPrimitive.boolean)
            assertEquals(1,properties.getValue("oldText").jsonObject.getValue("minLength").jsonPrimitive.int)
            assertEquals(0,properties.getValue("newText").jsonObject.getValue("minLength").jsonPrimitive.int)
            assertEquals(field=="criterion",properties.containsKey("criterionId"))
            assertEquals(field=="constraint",properties.containsKey("constraintIndex"))
            val required=item.getValue("required").jsonArray.map{it.jsonPrimitive.content}.toSet()
            assertEquals(properties.keys,required)
        }
    }

    @Test fun generatedGetSchemaKeepsTextBoundsAndTheThreeSupportedFields(){
        val properties=Json.parseToJsonElement(ToolRegistry.schemaJson(listOf(GoalToolCatalog.GET))).jsonArray.single().jsonObject
            .getValue("parameters").jsonObject.getValue("properties").jsonObject
        assertEquals(GoalToolCatalog.textFields,properties.getValue("textField").jsonObject.getValue("enum").jsonArray.map{it.jsonPrimitive.content}.toSet())
        assertEquals(12000,properties.getValue("textOffset").jsonObject.getValue("maximum").jsonPrimitive.int)
        assertEquals(900,properties.getValue("textLimit").jsonObject.getValue("maximum").jsonPrimitive.int)
        assertEquals(12000,properties.getValue("constraintIndex").jsonObject.getValue("maximum").jsonPrimitive.int)
    }
}
