package com.user.localagent.core

import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Source-only candidate. Schema decoding hints never replace actual workspace authority tests. */
class ProjectWriteSchemaConstraintsTest {
    private val query="Synthetic actual model input with host tool evidence"
    private val a="a".repeat(64);private val b="b".repeat(64)
    private fun hint(vararg rows:ProjectWriteReadBinding)=ProjectWriteSchemaConstraints.fromHostReadSnapshot(query,rows.toList())
    private fun toolArgs(schema:String):JsonObject=Json.parseToJsonElement(schema).jsonObject.getValue("oneOf").jsonArray
        .single{branch->val props=branch.jsonObject["properties"] as? JsonObject
            (props?.get("tool") as? JsonObject)?.get("enum")?.jsonArray?.singleOrNull()?.jsonPrimitive?.content=="write_project_file"}
        .jsonObject.getValue("properties").jsonObject.getValue("arguments").jsonObject
    private fun variants(arguments:JsonObject)=(arguments["oneOf"] as? JsonArray)?.map{it.jsonObject}?:listOf(arguments)
    private fun schema(hint:ProjectWriteSchemaConstraints?)=ProjectWriteSchemaConstraints.schemaOrOriginal(query,hint){ToolCallSchema.forTools(setOf("write_project_file"),projectWrites=it)}
    private fun properties(arg:JsonObject)=arg.getValue("properties").jsonObject

    @Test fun everyOverwriteBranchCouplesOnePathAndItsOwnOriginalHash(){
        val variants=variants(toolArgs(schema(hint(ProjectWriteReadBinding("A.txt",a),ProjectWriteReadBinding("B.txt",b)))))
        val pairs=variants.filter{"expectedSha256" in properties(it)}.associate{v->
            val p=properties(v);assertFalse(v.getValue("additionalProperties").jsonPrimitive.boolean)
            assertTrue("expectedSha256" in v.getValue("required").jsonArray.map{it.jsonPrimitive.content})
            assertFalse(p.getValue("content").jsonObject.containsKey("enum"))
            p.getValue("path").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content to
                p.getValue("expectedSha256").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content
        }
        assertEquals(mapOf("A.txt" to a,"B.txt" to b),pairs);assertEquals(3,variants.size)
    }
    @Test fun creationBranchHasNoHashPropertyAndCannotAcceptAHashThroughAdditionalProperties(){
        val creation=variants(toolArgs(schema(hint(ProjectWriteReadBinding("old.txt",a))))).single{"expectedSha256" !in properties(it)}
        assertFalse(creation.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(setOf("path","content"),creation.getValue("required").jsonArray.map{it.jsonPrimitive.content}.toSet())
        assertFalse(properties(creation).getValue("path").jsonObject.containsKey("enum"))
        val empty=toolArgs(schema(hint()));assertFalse(properties(empty).containsKey("expectedSha256"))
    }
    @Test fun absentHintsKeepLegacySchemasAndAllowedToolNamesExactlyUnchanged(){
        assertEquals(ToolCallSchema.forTools(setOf("write_project_file")),schema(null))
        val names=setOf("save_note","read_project_file")
        assertEquals(ToolCallSchema.forTools(names,"quoted body"),ToolCallSchema.forTools(names,"quoted body",hint(ProjectWriteReadBinding("A.txt",a))))
        assertFalse(ToolCallSchema.forTools(names,projectWrites=hint(ProjectWriteReadBinding("A.txt",a))).contains("write_project_file"))
    }
    @Test fun readOnlyCapturedToolsCannotAcquireWritePermissionFromAHashHint(){
        val readOnly=AgentWorkModePolicy.filterTools(AgentWorkMode.PLAN,setOf("read_project_file","write_project_file"))
        assertEquals(setOf("read_project_file"),readOnly)
        assertFalse(ToolCallSchema.forTools(readOnly,projectWrites=hint(ProjectWriteReadBinding("A.txt",a))).contains("write_project_file"))
        val proposed=ToolCall("fixture","write_project_file",buildJsonObject{put("path","A.txt");put("content","should not execute");put("expectedSha256",a)})
        assertTrue(runCatching{ToolRegistry.validateForRequest(proposed,readOnly)}.isFailure)
    }
    @Test fun questionAndReviewNextSchemasCarryTheSameCoupledHashWithoutChangingOtherActions(){
        val h=hint(ProjectWriteReadBinding("A.txt",a))
        val question=NextActionQuestion.schema(setOf("write_project_file"),"request",h)
        assertEquals(a,properties(variants(toolArgs(question)).first()).getValue("expectedSha256").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content)
        val review=Json.parseToJsonElement(AutonomousReviewPolicy.schema(setOf("write_project_file"),h)).jsonObject
        val next=review.getValue("oneOf").jsonArray.single{it.jsonObject.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content=="review_next"}.jsonObject
        val call=next.getValue("properties").jsonObject.getValue("call").toString()
        assertEquals(toolArgs(ToolCallSchema.forTools(setOf("write_project_file"),projectWrites=h)),toolArgs(call))
    }
    @Test fun staleQueryAmbiguousPairAndOversizedSnapshotsUseOriginalSafeSchema(){
        val original=ToolCallSchema.forTools(setOf("write_project_file"))
        val h=hint(ProjectWriteReadBinding("A.txt",a))
        assertEquals(original,ProjectWriteSchemaConstraints.schemaOrOriginal("different query",h){ToolCallSchema.forTools(setOf("write_project_file"),projectWrites=it)})
        assertEquals(original,schema(hint(ProjectWriteReadBinding("A.txt",a),ProjectWriteReadBinding("A.txt",b))))
        val many=ProjectWriteSchemaConstraints.fromHostReadSnapshot(query,(0..ProjectWriteSchemaConstraints.MAX_BINDINGS).map{ProjectWriteReadBinding("$it.txt",a)})
        assertEquals(original,schema(many))
        for(path in listOf("../x","/x","a\\b","C:/x","a//b"))assertEquals(original,schema(hint(ProjectWriteReadBinding(path,a))))
    }
    @Test fun wholeWrappedSchemaBudgetFallsBackBeforeTheWriterCountsItsFinalString(){
        var hintedBuilds=0
        val final=ProjectWriteSchemaConstraints.schemaOrOriginal(query,hint(ProjectWriteReadBinding("A.txt",a))){h->
            if(h==null)"safe original"else{hintedBuilds++;"x".repeat(ProjectWriteSchemaConstraints.MAX_SCHEMA_CHARS+1)}
        }
        assertEquals(1,hintedBuilds);assertEquals("safe original",final)
        val failure=ProjectWriteSchemaConstraints.schemaOrOriginal(query,hint()){if(it==null)"safe"else throw IllegalArgumentException("wrapped schema exceeds existing cap")}
        assertEquals("safe",failure)
    }
    @Test fun latestActualFailedPartialOrForeignResultCannotReviveAnOlderFullReadToken(){
        fun row(id:String,token:String,partial:Boolean=false,success:Boolean=true,resultId:String=id):ObservedToolOutcome {
            val text="Synthetic full source 😀";val hash=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
            val c=ToolCall(id,"read_project_file",buildJsonObject{put("path","A.txt")})
            val r=ToolResult(resultId,success,buildJsonObject{put("path","A.txt");put("text",text);put("sha256",hash);put("readToken",token);put("truncated",partial);put("untrustedSourceData",true)}.toString(),if(success)VerificationState.VERIFIED else VerificationState.FAILED,"fixture")
            return ObservedToolOutcome(c,r)
        }
        val old=row("old","00000000-0000-0000-0000-000000000001")
        assertEquals("00000000-0000-0000-0000-000000000001",ProjectWriteSchemaConstraints.latestHostReadTokens(listOf(old))["A.txt"])
        for(latest in listOf(row("partial","00000000-0000-0000-0000-000000000002",partial=true),row("failed","00000000-0000-0000-0000-000000000002",success=false),row("foreign","00000000-0000-0000-0000-000000000002",resultId="different")))
            assertNull(ProjectWriteSchemaConstraints.latestHostReadTokens(listOf(old,latest))["A.txt"])
        assertTrue(ProjectWriteSchemaConstraints.latestHostReadTokens(List(WorkSegments.STEP_BUDGET+1){old}).isEmpty())
    }
    @Test fun deterministicPathOrderingPreservesCaseSpacesAndUnicodeAndDoesNotMutateTheRegistry(){
        val original=ToolCallSchema.forTools(setOf("write_project_file"))
        val rows=listOf("A.txt","a.txt","two  spaces.txt","e\u0301.txt","é.txt").map{ProjectWriteReadBinding(it,a)}
        val forward=ProjectWriteSchemaConstraints.fromHostReadSnapshot(query,rows);val backward=ProjectWriteSchemaConstraints.fromHostReadSnapshot(query,rows.reversed())
        assertEquals(schema(forward),schema(backward))
        val paths=variants(toolArgs(schema(forward))).filter{"expectedSha256" in properties(it)}.map{properties(it).getValue("path").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content}
        assertEquals(rows.map{it.path}.sorted(),paths)
        assertEquals(original,ToolCallSchema.forTools(setOf("write_project_file")))
    }
}
