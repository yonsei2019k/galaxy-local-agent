package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PrivateToolHostIntegrationTest {
    private val id="00000000-0000-0000-0000-000000000001"
    private fun call(name:String,args:JsonObject)=ToolCall("synthetic",name,args)
    private fun validated(name:String,args:JsonObject)=ToolRegistry.validate(call(name,args))
    private fun model(action:String,tools:List<String>,source:String?=null)=buildJsonObject {
        put("action",action);put("goal","사용자의 전체 질문에 원문 근거로 답한다");put("targetType","PERSONAL_RECORD")
        put("requiredTools",JsonArray(tools.map(::JsonPrimitive)));source?.let {put("sourceKind",it)}
    }.toString()
    @Test fun newToolsAreRegisteredAndRecordingSelectionsCannotBeUrisOrPaths() {
        assertTrue(ToolRegistry.definitions.keys.containsAll(PrivateAnalysisToolCatalog.names+RecordingToolCatalog.names))
        validated("transcribe_recording",buildJsonObject {put("recordingId",id)})
        assertTrue(runCatching {validated("transcribe_recording",buildJsonObject {put("recordingId","content://media/external/audio/media/1")})}.isFailure)
        assertTrue(runCatching {validated("transcribe_recording",buildJsonObject {put("recordingId",id);put("uri","content://media/external/audio/media/1")})}.isFailure)
    }
    @Test fun resumeRequiresActualProgressVersionAndDoesNotAcceptANewQuestionOrSource() {
        validated("analyze_sms",buildJsonObject {put("question","원문의 조건을 비교해줘");put("maxChunks",32)})
        validated("analyze_sms",buildJsonObject {put("analysisId",id);put("expectedProcessedChunks",0)})
        assertTrue(runCatching {validated("analyze_sms",buildJsonObject {put("analysisId",id)})}.isFailure)
        assertTrue(runCatching {validated("analyze_sms",buildJsonObject {put("analysisId",id);put("expectedProcessedChunks",1);put("question","다른 질문")})}.isFailure)
        assertTrue(runCatching {validated("analyze_sms",buildJsonObject {put("question","같은 시각");put("start","2028-01-01T00:00:00+09:00");put("end","2028-01-01T00:00:00+09:00")})}.isFailure)
    }
    @Test fun nativeSchemaHasActualAnalysisBoundsAndOpaqueRecordingIdPattern() {
        val schema=Json.parseToJsonElement(ToolRegistry.schemaJson(PrivateAnalysisToolCatalog.names+RecordingToolCatalog.names)).jsonArray.associate {it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("parameters").jsonObject}
        fun field(tool:String,key:String)=schema.getValue(tool).getValue("properties").jsonObject.getValue(key).jsonObject
        assertEquals(32,field("analyze_sms","maxChunks").getValue("maximum").jsonPrimitive.int)
        assertEquals(10000,field("analyze_sms","expectedProcessedChunks").getValue("maximum").jsonPrimitive.int)
        assertEquals(10,field("read_analysis_evidence","limit").getValue("maximum").jsonPrimitive.int)
        assertEquals(3,field("list_analysis_sources","kind").getValue("enum").jsonArray.size)
        assertTrue(field("transcribe_recording","recordingId").getValue("pattern").jsonPrimitive.content.contains("[a-f0-9]"))
        assertTrue(ToolCallSchema.forTools(PrivateAnalysisToolCatalog.names+RecordingToolCatalog.names).length<=65536)
    }
    @Test fun genericNoteCreationAndDestinationChangesCannotForgeProtectedSourceCollections() {
        for(collection in InternalNoteCollections.protected){
            assertTrue(runCatching {validated("save_note",buildJsonObject {put("scope",collection);put("content","synthetic host-looking data")})}.isFailure)
            assertTrue(runCatching {validated("update_note",buildJsonObject {put("id",id);put("scope",collection);put("content","synthetic changed data")})}.isFailure)
        }
        validated("save_note",buildJsonObject {put("scope","ordinary-user-notes");put("content","normal user note")})
    }
    @Test fun modesKeepPublicResearchSeparateAndNewTranscriptionInActionModes() {
        for(tool in PrivateAnalysisToolCatalog.names){
            assertFalse(AgentWorkModePolicy.allows(AgentWorkMode.RESEARCH,tool))
            for(mode in AgentWorkMode.entries.filter {it!=AgentWorkMode.RESEARCH})assertTrue("$mode $tool",AgentWorkModePolicy.allows(mode,tool))
            assertTrue(ToolReadPolicy.isReadOnly(tool))
        }
        for(mode in AgentWorkMode.entries)assertEquals(mode in setOf(AgentWorkMode.ACT,AgentWorkMode.DEBUG),AgentWorkModePolicy.allows(mode,"transcribe_recording"))
        assertFalse(ToolReadPolicy.isReadOnly("transcribe_recording"))
    }
    @Test fun capabilitiesRequireRealPermissionsTextAndFileAsrInsteadOfLiveAsr() {
        fun capability(tool:String,env:CapabilityEnvironment)=ToolCapabilityCatalog.snapshot(env).first {it.id==tool}
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,capability("analyze_sms",CapabilityEnvironment(installedModels=setOf("text"))).availability)
        assertEquals(CapabilityAvailability.REQUIRES_MODEL,capability("analyze_sms",CapabilityEnvironment(grantedPermissions=setOf("android.permission.READ_SMS"))).availability)
        assertEquals(CapabilityAvailability.INSTALLED,capability("analyze_sms",CapabilityEnvironment(grantedPermissions=setOf("android.permission.READ_SMS"),installedModels=setOf("text"))).availability)
        val liveAsrOnly=CapabilityEnvironment(grantedPermissions=setOf("android.permission.READ_MEDIA_AUDIO"),installedModels=setOf("asr"))
        assertEquals(CapabilityAvailability.REQUIRES_MODEL,capability("transcribe_recording",liveAsrOnly).availability)
        assertEquals(CapabilityAvailability.INSTALLED,capability("transcribe_recording",liveAsrOnly.copy(installedModels=setOf("asr_file"))).availability)
        assertTrue(capability("transcribe_recording",liveAsrOnly).offline)
        assertEquals(CapabilityAvailability.INSTALLED,capability("read_analysis_evidence",CapabilityEnvironment()).availability)
    }
    @Test fun transcriptionRequiresItsActualListingButAnalysisDoesNotRequireUnnecessaryDiscovery() {
        val plan=ModelDecisionContract.parse(model("TOOL_TASK",listOf("transcribe_recording")))
        assertTrue(plan.usesPrivateData)
        assertEquals(setOf("list_audio_recordings","transcribe_recording"),SemanticRequestPlanning.allowedTools(plan))
        assertTrue(runCatching {ModelDecisionContract.parse(model("TOOL_TASK",listOf("transcribe_recording")),setOf("transcribe_recording"))}.isFailure)
        val imported=ModelDecisionContract.parse(model("TOOL_TASK",listOf("analyze_conversation_import")),setOf("analyze_conversation_import"))
        assertTrue(imported.usesPrivateData);assertEquals(setOf("analyze_conversation_import"),SemanticRequestPlanning.allowedTools(imported,setOf("analyze_conversation_import")))
        val full=ModelDecisionContract.parse(model("TOOL_TASK",listOf("analyze_conversation_import")))
        assertEquals(setOf("analyze_conversation_import","list_analysis_sources","read_analysis_evidence","list_imported_conversations"),SemanticRequestPlanning.allowedTools(full))
        assertEquals(setOf("analyze_conversation_import"),SemanticRequestPlanning.toTaskGoal(full).requiredTools)
        val withoutEvidence=ToolRegistry.definitions.keys-"read_analysis_evidence"
        val known=ModelDecisionContract.parse(model("TOOL_TASK",listOf("analyze_audio_transcript")),withoutEvidence)
        assertEquals(setOf("analyze_audio_transcript","list_analysis_sources"),SemanticRequestPlanning.allowedTools(known,withoutEvidence))
    }
    @Test fun semanticSourceContractsOfferFullAnalysisAndNeverExportPrivateReadsToPublicSearch() {
        val sms=ModelDecisionContract.parse(model("ANALYZE_RECORDS",listOf("analyze_sms"),"sms"))
        assertTrue(sms.usesPrivateData);assertEquals(SemanticSourceKind.SMS,sms.sourceKind)
        val imported=ModelDecisionContract.parse(model("ANALYZE_RECORDS",listOf("analyze_conversation_import"),"kakao"))
        assertTrue(imported.usesPrivateData)
        val mixed=model("TOOL_TASK",listOf("analyze_sms","search_web")).removeSuffix("}")+",\"publicQuery\":\"private content\"}"
        assertTrue(runCatching {ModelDecisionContract.parse(mixed)}.isFailure)
        assertEquals("audio",IntentPlanning.domainForTool("transcribe_recording"));assertEquals("messages",IntentPlanning.domainForTool("analyze_sms"))
    }
}
