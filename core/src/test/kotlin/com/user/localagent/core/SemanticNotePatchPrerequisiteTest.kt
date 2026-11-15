package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Capability/privacy regressions only; no execution, repository, owner data or intent language rules. */
class SemanticNotePatchPrerequisiteTest {
    private val offered=setOf("patch_note","search_notes","read_note")
    private fun sparse(tools:Set<String> = setOf("patch_note"),privateClaim:Boolean?=null,query:String="",action:String="TOOL_TASK")=buildJsonObject{
        put("action",action);put("goal","선택한 메모의 한 문구를 고치고 나머지를 보존한다");put("targetType","PERSONAL_RECORD")
        put("requiredTools",JsonArray(tools.map(::JsonPrimitive)));put("constraints",JsonArray(listOf(JsonPrimitive("다른 메모는 변경하지 않는다"))))
        privateClaim?.let{put("usesPrivateData",it)};if(query.isNotEmpty())put("publicQuery",query)
    }
    private fun plan(available:Set<String> = offered)=ModelDecisionContract.parse(sparse().toString(),available)
    private fun strict(p:SemanticRequestPlan,privateData:Boolean=p.usesPrivateData,query:String=p.publicQuery)=
        JsonObject(Json.parseToJsonElement(SemanticRequestPlanning.contextJson(p)).jsonObject+mapOf("usesPrivateData" to JsonPrimitive(privateData),"publicQuery" to JsonPrimitive(query)))
    private fun rejected(action:()->Unit){try{action();fail("Expected rejected capability/privacy request")}catch(_:IllegalArgumentException){}}

    @Test fun explicitlySelectedPatchOffersOnlyAvailablePrerequisitesAndKeepsGoal() {
        val p=plan();assertEquals(setOf("patch_note"),p.requiredTools)
        assertEquals(offered,SemanticRequestPlanning.allowedTools(p,offered))
        val goal=SemanticRequestPlanning.toTaskGoal(p)
        assertEquals(setOf("patch_note"),goal.requiredTools);assertEquals(p.goal,goal.ownerRequest)
        assertTrue(goal.successCriteria.containsAll(p.constraints))
        val intent=SemanticRequestPlanning.toIntentPlan(p,IntentCapabilities(availableTools=offered))
        assertEquals(setOf("search_notes","read_note"),intent.recommendedNextTools.toSet())
        assertTrue("patch_note" in intent.deferredTools);assertTrue(IntentSource.NOTES in intent.sources)
    }
    @Test fun missingOrDisabledReadsAreNeverRestoredIntoTheClosure() {
        for(available in listOf(setOf("patch_note"),setOf("patch_note","search_notes"),setOf("patch_note","read_note"))){
            val p=plan(available);assertEquals(available,SemanticRequestPlanning.allowedTools(p,available))
            assertEquals(setOf("patch_note"),SemanticRequestPlanning.toTaskGoal(p).requiredTools)
            assertTrue(p.usesPrivateData) // Intrinsic read remains private even with no child read offered.
        }
    }
    @Test fun unrelatedAvailablePrivateToolsDoNotBecomeDefaultReads() {
        val available=offered+setOf("get_sms_summary","search_memories","search_web","save_note")
        assertEquals(offered,SemanticRequestPlanning.allowedTools(plan(available),available))
        val save=ModelDecisionContract.parse(sparse(setOf("save_note")).toString(),available)
        assertEquals(setOf("save_note"),SemanticRequestPlanning.allowedTools(save,available));assertFalse(save.usesPrivateData)
    }
    @Test fun sparsePatchCanonicalizesPrivacyFromIntrinsicCapabilityEvenWhenClaimedFalse() {
        for(claim in listOf<Boolean?>(null,false,true)){
            val p=ModelDecisionContract.parse(sparse(privateClaim=claim).toString(),offered)
            assertTrue(p.usesPrivateData);assertEquals("",p.publicQuery)
            assertEquals(p,SemanticRequestPlanning.parse(SemanticRequestPlanning.contextJson(p),offered))
        }
        assertTrue(SemanticRequestPlanning.isPrivateRead("patch_note"))
        assertFalse(ToolReadPolicy.isReadOnly("patch_note"));assertTrue(ToolReadPolicy.isReadOnly("read_note"))
    }
    @Test fun strictFalsePrivacyOrPublicQueryCannotHideIntrinsicOrClosureReads() {
        for(available in listOf(offered,setOf("patch_note"))){
            val p=plan(available)
            rejected{SemanticRequestPlanning.parse(strict(p,privateData=false).toString(),available)}
            rejected{SemanticRequestPlanning.parse(strict(p,query="외부 검색어").toString(),available)}
        }
    }
    @Test fun patchAndPublicSearchCannotAutomaticallyCombineInEitherContract() {
        val available=offered+"search_web"
        try{ModelDecisionContract.parse(sparse(setOf("patch_note","search_web"),false,"공개 검색어").toString(),available);fail()}
        catch(error:ModelDecisionException){assertEquals("mixed_private_public",error.code)}
        val p=plan(available).copy(requiredTools=setOf("patch_note","search_web"),publicQuery="공개 검색어",usesPrivateData=true)
        rejected{SemanticRequestPlanning.contextJson(p)}
        rejected{ModelDecisionContract.parse(sparse(setOf("patch_note","search_web"),false,"공개 검색어",action="PUBLIC_RESEARCH").toString(),available)}
    }
    @Test fun sparseUnusedQueryIsRemovedWithoutGrantingExternalTools() {
        val p=ModelDecisionContract.parse(sparse(query="SYNTHETIC_UNUSED_QUERY").toString(),offered)
        assertEquals("",p.publicQuery);assertTrue(p.usesPrivateData)
        assertEquals(offered,SemanticRequestPlanning.allowedTools(p,offered))
        assertFalse(SemanticRequestPlanning.contextJson(p).contains("SYNTHETIC_UNUSED_QUERY"))
    }
    @Test fun unavailableMutationUnknownToolsAndNoToolsCannotPass() {
        rejected{plan(emptySet())};rejected{plan(setOf("search_notes","read_note"))}
        rejected{ModelDecisionContract.parse(sparse(setOf("unknown_tool")).toString(),offered)}
        rejected{ModelDecisionContract.parse(sparse().toString(),offered+"unknown_host_tool")}
        rejected{ModelDecisionContract.parse(sparse(emptySet()).toString(),offered)}
    }
    @Test fun readOnlyAndCapturedLegacyRestrictionsCannotAcquirePatchAuthority() {
        val readOnly=offered.filter{AgentWorkModePolicy.allows(AgentWorkMode.PLAN,it)}.toSet()
        assertFalse("patch_note" in readOnly);rejected{plan(readOnly)}
        val call=ToolCall("patch-call","patch_note",buildJsonObject{
            put("id","note-demo");put("scope","team");put("expectedUpdatedAt","7");put("oldText","needle");put("newText","changed")
        })
        rejected{ToolRegistry.validateForRequest(call,emptySet())}
        rejected{ToolRegistry.validateForRequest(call,setOf("search_notes"))}
        assertFalse(CapabilityPolicy.canPreapprove(call.name))
        assertTrue(CapabilityPolicy.requiresConfirmation(call,"builtin","host-code",emptyList()))
    }
    @Test fun offeredPrerequisitesDoNotManufactureActualOriginalReadOrCurrentVersionProof() {
        assertEquals(offered,SemanticRequestPlanning.allowedTools(plan(),offered))
        val binding=NoteReadBinding("run-demo","thread-demo","branch-demo","scope-demo")
        val session=NoteReadPatchSession(binding)
        val source=NoteSource("note-demo",binding.scopeKey,"team","title","needle\r\n"+"tail ".repeat(260),7)
        val patch=NotePatchRequest(source.id,source.collection,7,"needle","changed")
        rejected{session.previewPatch(source,patch)}
        val page=session.read(source,NoteReadRequest(source.id,source.collection,7))
        val entry=buildJsonObject{put("tool","read_note");put("success",true);put("verification","VERIFIED");put("result",page.modelResult())}
        session.acceptDelivered(listOf(entry),"source omitted");rejected{session.previewPatch(source,patch)}
        session.acceptDelivered(listOf(entry),JsonArray(listOf(entry)).toString())
        val edit=session.previewPatch(source,patch);assertEquals(source.body.replaceFirst("needle","changed"),edit.afterBody)
        rejected{session.previewPatch(source.copy(updatedAt=8),patch)}
        rejected{session.previewPatch(source.copy(body=source.body+"new"),patch)}
    }
    @Test fun goalAndExistingReadbackPairRemainExplicitRatherThanAnAddedApiRecipe() {
        val p=plan();val goal=SemanticRequestPlanning.toTaskGoal(p)
        assertEquals(setOf("patch_note"),goal.requiredTools)
        val binding=RequiredReadbackObligations.Context("run-demo","thread-demo",null,"scope-demo",null)
        val explicitWhole=checkNotNull(RequiredReadbackObligations.select(null,binding,setOf("patch_note","read_note")))
        assertEquals(setOf(RequiredReadbackObligations.Pair.NOTE_PATCH),explicitWhole.selected)
        assertFalse(RequiredReadbackObligations.assess(explicitWhole,binding,emptyList(),0,offered,18,1,requestedFinish=true).readbackSatisfied)
        assertEquals("note_body_v1",RequiredReadbackObligations.Pair.NOTE_PATCH.kind)
    }
}
