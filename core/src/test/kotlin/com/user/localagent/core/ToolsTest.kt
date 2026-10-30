package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolsTest {
    private fun reject(raw:String){assertTrue(runCatching {ToolRegistry.parseAndValidate(raw)}.isFailure)}
    @Test fun validTool(){assertEquals("get_current_datetime",ToolRegistry.parseAndValidate("""{"type":"tool_call","tool":"get_current_datetime","arguments":{}}""").name)}
    @Test fun malformedNeverExecutes(){listOf("not json","[]","{}","{","{\"name\":\"get_current_datetime\",\"arguments\":null}").forEach(::reject)}
    @Test fun unknownNeverExecutes(){reject("""{"type":"tool_call","tool":"send_sms","arguments":{}}""")}
    @Test fun duplicateKeysRejected(){reject("""{"name":"get_current_datetime","name":"save_note","arguments":{}}""");reject("""{"name":"save_note","arguments":{"content":"a","content":"b"}}""")}
    @Test fun unknownArgumentsRejected(){reject("""{"name":"get_current_datetime","arguments":{"run":"shell"}}""")}
    @Test fun unknownEnvelopeRejected(){reject("""{"name":"get_current_datetime","arguments":{},"requiresConfirmation":false}""")}
    @Test fun alarmBounds(){reject("""{"name":"create_alarm","arguments":{"hour":24,"minute":0}}""");reject("""{"name":"create_alarm","arguments":{"hour":"9","minute":0}}""")}
    @Test fun relativeTimeNeedsResolution(){reject("""{"name":"create_reminder","arguments":{"title":"치과","datetime":"내일 오후 3시"}}""")}
    @Test fun timezonePreserved(){val call=ToolRegistry.parseAndValidate("""{"name":"create_reminder","arguments":{"title":"치과","datetime":"2026-11-02T15:00:00+09:00"}}""");assertTrue(call.arguments.getValue("datetime").jsonPrimitive.content.endsWith("+09:00"));assertEquals("Asia/Seoul",ToolRegistry.defaultZone.id)}
    @Test fun dangerousUrlsRejected(){listOf("javascript:alert(1)","file:///data/private","http://example.com","https://user:password@example.com").forEach {url->reject(buildJsonObject{put("name","open_url");put("arguments",buildJsonObject{put("url",url)})}.toString())}}
    @Test fun highRiskCannotBePreapproved(){val call=ToolCall("1","delete_memory",buildJsonObject{put("id","x")});assertTrue(CapabilityPolicy.requiresConfirmation(call,"e","hash",listOf(CapabilityGrant("e","hash","delete_memory","default"))))}
    @Test fun grantBoundToCodeAndScope(){val call=ToolCall("1","save_note",buildJsonObject{put("content","hello");put("scope","work")});val grant=CapabilityGrant("e","hash","save_note","work");assertFalse(CapabilityPolicy.requiresConfirmation(call,"e","hash",listOf(grant)));assertTrue(CapabilityPolicy.requiresConfirmation(call,"e","changed",listOf(grant)));assertTrue(CapabilityPolicy.requiresConfirmation(call,"other","hash",listOf(grant)))}
    @Test fun messengerReadUsesStrictAllowlistAndBoundedLimit(){
        assertEquals("list_messenger_notifications",ToolRegistry.parseAndValidate("""{"name":"list_messenger_notifications","arguments":{"packageName":"com.kakao.talk","limit":5}}""").name)
        reject("""{"name":"list_messenger_notifications","arguments":{"packageName":"com.example.messenger"}}""")
        reject("""{"name":"list_messenger_notifications","arguments":{"packageName":"com.kakao.talk","limit":6}}""")
    }
    @Test fun messengerReplyIsHighRiskAndCannotBePreapproved(){
        val call=ToolRegistry.parseAndValidate("""{"name":"reply_messenger_notification","arguments":{"id":"msg-1","message":"확인했습니다."}}""")
        assertEquals(Risk.HIGH,ToolRegistry.definitions.getValue(call.name).risk)
        assertFalse(CapabilityPolicy.canPreapprove(call.name))
        assertTrue(CapabilityPolicy.requiresConfirmation(call,"extension","code",listOf(CapabilityGrant("extension","code",call.name,"default"))))
    }
    @Test fun requestToolSetBlocksRegisteredButOmittedTools(){
        val call=ToolRegistry.validate(ToolCall("1","save_note",buildJsonObject {put("content","note")}))
        assertTrue(runCatching {ToolRegistry.validateForRequest(call,setOf("get_current_datetime"))}.isFailure)
        assertTrue(runCatching {ToolRegistry.validateForRequest(call,emptySet())}.isFailure)
        assertEquals(call,ToolRegistry.validateForRequest(call,setOf("save_note")))
    }
    @Test fun requestToolSetCannotAuthorizeUnknownTools(){
        val call=ToolCall("1","unknown_tool",buildJsonObject {})
        assertTrue(runCatching {ToolRegistry.validateForRequest(call,setOf("unknown_tool"))}.isFailure)
    }
    private fun readCall(name:String,args:JsonObject=buildJsonObject {}):ToolCall = ToolRegistry.parseAndValidate(buildJsonObject {
        put("name",name);put("arguments",args)
    }.toString())
    private fun rejectRead(name:String,args:JsonObject) = reject(buildJsonObject {put("name",name);put("arguments",args)}.toString())

    @Test fun analysisReadDefaultsDoNotDemandDatesPackagesOrQueries() {
        val names=listOf("list_imported_conversations","search_conversation_imports","get_sms_summary","get_call_log_summary","list_audio_recordings","search_audio_transcripts")
        names.forEach {name ->
            val call=readCall(name)
            assertEquals(name,call.name)
            assertTrue(call.arguments.isEmpty())
            assertEquals(Risk.LOW,ToolRegistry.definitions.getValue(name).risk)
        }
        names.filter {it!="list_imported_conversations"}.forEach {readCall(it,buildJsonObject {put("query","")})}
    }
    @Test fun analysisReadsRejectUnknownArgumentsAndWrongTypes() {
        rejectRead("list_imported_conversations",buildJsonObject {put("query","all")})
        listOf("search_conversation_imports","get_sms_summary","get_call_log_summary","list_audio_recordings","search_audio_transcripts").forEach {name->
            rejectRead(name,buildJsonObject {put("packageName","com.kakao.talk")})
            rejectRead(name,buildJsonObject {put("query",7)})
            rejectRead(name,buildJsonObject {put("query","x".repeat(501))})
            rejectRead(name,buildJsonObject {put("limit","3")})
        }
    }
    @Test fun importedConversationDigestMustBeExactHexSha256() {
        listOf("a".repeat(63),"a".repeat(65),"z".repeat(64),"../../source"," ").forEach {digest->
            rejectRead("search_conversation_imports",buildJsonObject {put("contentSha256",digest)})
        }
        rejectRead("search_conversation_imports",buildJsonObject {put("contentSha256",10)})
        val digest="A1".repeat(32)
        assertEquals(digest,readCall("search_conversation_imports",buildJsonObject {put("contentSha256",digest)}).arguments.getValue("contentSha256").jsonPrimitive.content)
    }
    @Test fun analysisReadLimitsHaveToolSpecificBounds() {
        val maxima=mapOf("search_conversation_imports" to 3,"search_audio_transcripts" to 3,"get_sms_summary" to 5,"get_call_log_summary" to 5,"list_audio_recordings" to 20)
        maxima.forEach {(name,maximum)->
            assertEquals(maximum,readCall(name,buildJsonObject {put("limit",maximum)}).arguments.getValue("limit").jsonPrimitive.int)
            rejectRead(name,buildJsonObject {put("limit",0)})
            rejectRead(name,buildJsonObject {put("limit",maximum+1)})
            rejectRead(name,buildJsonObject {put("limit",1.5)})
        }
    }
    @Test fun nativeRecordDateFiltersAreOptionalOffsetIsoAndOrdered() {
        listOf("get_sms_summary","get_call_log_summary").forEach {name->
            val instant="2026-11-02T15:00:00+09:00"
            readCall(name,buildJsonObject {put("start",instant)})
            readCall(name,buildJsonObject {put("end",instant)})
            readCall(name,buildJsonObject {put("start",instant);put("end",instant)})
            listOf("내일","2026-11-02T15:00:00","2026-02-31T15:00:00+09:00").forEach {bad->
                rejectRead(name,buildJsonObject {put("start",bad)})
            }
            rejectRead(name,buildJsonObject {put("start","2026-11-03T15:00:00+09:00");put("end",instant)})
        }
    }
    @Test fun readSchemasDescribePartialScopeAndPublishValidationBounds() {
        val schema=Json.parseToJsonElement(ToolRegistry.schemaJson(listOf("get_current_datetime","search_conversation_imports","get_sms_summary","list_audio_recordings","search_audio_transcripts"))).jsonArray
        val byName=schema.associate {it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject}
        assertFalse(byName.getValue("get_current_datetime").containsKey("description"))
        listOf("search_conversation_imports","get_sms_summary","list_audio_recordings","search_audio_transcripts").forEach {name->
            assertTrue(byName.getValue(name).getValue("description").jsonPrimitive.content.isNotBlank())
        }
        val imported=byName.getValue("search_conversation_imports").getValue("parameters").jsonObject.getValue("properties").jsonObject
        assertEquals(3,imported.getValue("limit").jsonObject.getValue("maximum").jsonPrimitive.int)
        assertEquals(500,imported.getValue("query").jsonObject.getValue("maxLength").jsonPrimitive.int)
        assertTrue(imported.getValue("contentSha256").jsonObject.getValue("pattern").jsonPrimitive.content.contains("64"))
    }
}

