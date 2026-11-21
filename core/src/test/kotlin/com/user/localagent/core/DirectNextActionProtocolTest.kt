package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DirectNextActionProtocolTest {
    private val notes=setOf("search_notes","read_note","patch_note")
    private val answer="""{"type":"answer"}"""
    private val read="""{"type":"tool_call","id":"qa-call","tool":"read_note","arguments":{"id":"qa-note","scope":"team","expectedUpdatedAt":"7","limit":900}}"""
    private fun rejected(raw:String,tools:Set<String> = notes){assertTrue(raw,runCatching{DirectNextActionProtocol.parse(raw,tools)}.isFailure)}

    @Test fun eligibilityRequiresOptInStructuredCompleteRegisteredSmallDetailSet(){
        assertTrue(DirectNextActionProtocol.eligible(true,true,notes,notes))
        assertFalse(DirectNextActionProtocol.eligible(false,true,notes,notes))
        assertFalse(DirectNextActionProtocol.eligible(true,false,notes,notes))
        assertFalse(DirectNextActionProtocol.eligible(true,true,notes,setOf("read_note")))
        assertFalse(DirectNextActionProtocol.eligible(true,true,setOf("read_note"),notes))
        assertFalse(DirectNextActionProtocol.eligible(true,true,emptySet(),emptySet()))
        assertFalse(DirectNextActionProtocol.eligible(true,true,setOf("unregistered"),setOf("unregistered")))
        val six=setOf("read_note","search_notes","patch_note","save_note","update_note","delete_note")
        assertTrue(DirectNextActionProtocol.eligible(true,true,six,six))
        assertFalse(DirectNextActionProtocol.eligible(true,true,six+"get_current_datetime",six+"get_current_datetime"))
    }

    @Test fun schemaKeepsEveryOriginalToolAndQuestionBranchUnchanged(){
        val owner="메모에 '인용된  원문 😀' 저장"
        val tools=notes+"save_note"
        val existing=Json.parseToJsonElement(NextActionQuestion.schema(tools,owner)).jsonObject
        val fresh=Json.parseToJsonElement(DirectNextActionProtocol.schema(tools,owner)).jsonObject
        val oldBranches=existing.getValue("oneOf").jsonArray;val branches=fresh.getValue("oneOf").jsonArray
        assertEquals(oldBranches.toList(),branches.dropLast(1))
        assertEquals(existing.filterKeys{it!="oneOf"},fresh.filterKeys{it!="oneOf"})
        assertEquals(oldBranches.size+1,branches.size)
        val signal=branches.last().jsonObject
        assertEquals(false,signal.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(setOf("type"),signal.getValue("properties").jsonObject.keys)
        assertEquals(listOf(JsonPrimitive("type")),signal.getValue("required").jsonArray.toList())
        assertEquals(DirectNextActionProtocol.schema(tools,owner),DirectNextActionProtocol.schema(tools.reversed().toSet(),owner))
    }

    @Test fun schemaPreservesGenerationLocalProjectWriteConstraints(){
        val proof=ProjectWriteSchemaConstraints.fromHostReadSnapshot("actual query",listOf(ProjectWriteReadBinding("known.txt","1".repeat(64))))
        val tools=setOf("write_project_file")
        val before=Json.parseToJsonElement(NextActionQuestion.schema(tools,projectWrites=proof)).jsonObject.getValue("oneOf").jsonArray
        val after=Json.parseToJsonElement(DirectNextActionProtocol.schema(tools,projectWrites=proof)).jsonObject.getValue("oneOf").jsonArray
        assertEquals(before.toList(),after.dropLast(1))
    }

    @Test fun exactAnswerIsAFieldlessSignalAndNeverARegisteredExecutableTool(){
        assertSame(DirectNextActionProtocol.Decision.Answer,DirectNextActionProtocol.parse(answer,notes))
        assertSame(DirectNextActionProtocol.Decision.Answer,DirectNextActionProtocol.parse(" \r\n$answer\t",notes))
        assertTrue(runCatching{ToolRegistry.parseAndValidate(answer)}.isFailure)
        assertFalse("answer" in ToolRegistry.definitions)
        assertTrue(DirectNextActionProtocol.instruction.contains("not reply text or completion evidence"))
    }

    @Test fun toolArgumentsAndKnownOptionalIdUseTheOriginalRegistryValidator(){
        val parsed=DirectNextActionProtocol.parse(read,notes) as DirectNextActionProtocol.Decision.Tool
        assertEquals(ToolRegistry.parseAndValidate(read),parsed.call)
        val noId="""{"type":"tool_call","tool":"search_notes","arguments":{"query":"same  literal 😀","limit":5}}"""
        val found=(DirectNextActionProtocol.parse(noId,notes) as DirectNextActionProtocol.Decision.Tool).call
        assertEquals("search_notes",found.name);assertEquals("same  literal 😀",found.arguments.getValue("query").jsonPrimitive.content)
        assertTrue(found.id.isNotBlank())
    }

    @Test fun unofferedRegisteredToolsAndInvalidExistingArgumentsAreRejected(){
        rejected(read,setOf("search_notes"))
        rejected(read.replace("\"limit\":900","\"limit\":\"900\""))
        rejected(read.replace("\"limit\":900","\"limit\":901"))
        rejected(read.replace("\"expectedUpdatedAt\":\"7\"","\"expectedUpdatedAt\":7"))
        rejected("""{"type":"tool_call","tool":"nonexistent","arguments":{}}""")
        rejected("""{"name":"read_note","arguments":{"id":"qa","scope":"team","expectedUpdatedAt":"7"}}""")
    }

    @Test fun publicExplanationIsOptionalAndDoesNotBecomeAToolArgument(){
        val explanation="같은 이름의 메모 중 어느 것을 바꿀지 확인하려고 원문을 읽겠습니다."
        val tool=Json.parseToJsonElement(read).jsonObject
        fun parse(extra:JsonElement)= (DirectNextActionProtocol.parse(
            JsonObject(tool+("publicExplanation" to extra)).toString(),notes
        ) as DirectNextActionProtocol.Decision.Tool).call
        val original=ToolRegistry.parseAndValidate(read)
        val explained=parse(JsonPrimitive(explanation))
        assertEquals(explanation,explained.publicExplanation)
        assertEquals(original,explained.copy(publicExplanation=null))
        assertEquals(original,parse(JsonPrimitive(7)))
        assertEquals(original,parse(JsonPrimitive("x".repeat(221))))
    }

    @Test fun clarificationKeepsExistingQuestionValidationAndTrimming(){
        val raw="""{"type":"clarification","question":"  어느 날짜인가요? 😀  "}"""
        assertEquals(NextActionQuestion.parse(raw),(DirectNextActionProtocol.parse(raw,notes) as DirectNextActionProtocol.Decision.Clarification).question)
        listOf("", " ", "<think>private</think>질문", "x".repeat(301),"question"+0.toChar()+"tail").forEach{question->
            rejected(buildJsonObject{put("type","clarification");put("question",question)}.toString())
        }
        rejected("""{"type":"clarification","question":7}""")
    }

    @Test fun additionalFieldsCannotSmuggleTextToolsOrCompletionEvidence(){
        listOf(
            """{"type":"answer","text":"done"}""",
            """{"type":"answer","id":"qa"}""",
            """{"type":"answer","tool":"patch_note","arguments":{}}""",
            """{"type":"answer","question":"skip?"}""",
            """{"type":"clarification","question":"who?","tool":"patch_note"}""",
            read.dropLast(1)+",\"extra\":true}"
        ).forEach{rejected(it)}
    }

    @Test fun duplicateAndEscapedDuplicateKeysAtEveryBranchAreRejected(){
        listOf(
            """{"type":"answer","type":"answer"}""",
            """{"type":"answer","\u0074ype":"answer"}""",
            """{"type":"clarification","question":"a","question":"b"}""",
            read.replace("\"limit\":900","\"limit\":900,\"limit\":2"),
            read.replace("\"id\":\"qa-note\"","\"id\":\"qa-note\",\"\\u0069d\":\"other\"")
        ).forEach{rejected(it)}
    }

    @Test fun proseFencesTrailingDocumentsAndTruncatedJsonAreNeverExtracted(){
        listOf("say $answer", "```json\n$answer\n```", "$answer done", "$answer$answer",
            "{\"type\":\"answer\"", "{\"type\":\"clarification\",\"question\":\"who", read.dropLast(2),
            "", "   ", "{\"type\":\"answer\",}").forEach{rejected(it)}
    }

    @Test fun stringWrappedJsonAndNonObjectDocumentsAreRejected(){
        listOf(JsonPrimitive(answer).toString(),"\"answer\"","[]","[$answer]","null","true","7").forEach{rejected(it)}
    }

    @Test fun discriminatorMustBeAnExactKnownJsonString(){
        listOf("true","7","null","[]","{}").forEach{rejected("{\"type\":$it}")}
        listOf("ANSWER","Answer"," answer ","complete","review_complete","unknown").forEach{type->
            rejected(buildJsonObject{put("type",type)}.toString())
        }
    }

    @Test fun oversizedResponsesAndInvalidHostToolSetsFailClosed(){
        rejected(" ".repeat(DirectNextActionProtocol.MAX_RAW_CHARS)+answer)
        val seven=setOf("read_note","search_notes","patch_note","save_note","update_note","delete_note","get_current_datetime")
        for(tools in listOf(emptySet(),setOf("unregistered"),seven)){
            rejected(answer,tools);assertTrue(runCatching{DirectNextActionProtocol.schema(tools)}.isFailure)
        }
    }

    @Test fun quotedActionJsonInsideQuestionRemainsQuestionData(){
        val question="여기 적힌 {\"type\":\"answer\"}는 무슨 뜻인가요?"
        val raw=buildJsonObject{put("type","clarification");put("question",question)}.toString()
        assertEquals(DirectNextActionProtocol.Decision.Clarification(question),DirectNextActionProtocol.parse(raw,notes))
    }
}
