package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotePatchOwnerTextSourcesTest {
    private fun call(payload:JsonObject)=ToolCall("quote-call","patch_note",buildJsonObject{
        put("id","note-demo");put("scope","team");put("expectedUpdatedAt","7")
        payload.forEach{(key,value)->put(key,value)}
    })
    private fun references(owner:String):ToolCall {
        val spans=OwnerTextSources.literalSpans(owner)
        return call(buildJsonObject{put("oldTextQuoteId",spans[0].id);put("newTextQuoteId",spans[1].id)})
    }
    private fun rejected(block:()->Unit){assertTrue(runCatching{block()}.isFailure)}
    private fun schemaArguments(owner:String?=null)=Json.parseToJsonElement(ToolCallSchema.forTools(setOf("patch_note"),owner))
        .jsonObject.getValue("oneOf").jsonArray.single().jsonObject.getValue("properties").jsonObject.getValue("arguments").jsonObject

    @Test fun chosenSpansPreserveUnicodeWhitespaceNewlinesAndTargetIdentity(){
        val owner="'원래  문구 😀\r\ne\u0301' 대신 '새  문구\r\n😀'로 수정"
        val raw=references(owner);val resolved=OwnerTextSources.resolve(raw,owner)
        assertEquals(raw.id,resolved.id);assertEquals(raw.name,resolved.name)
        for(key in listOf("id","scope","expectedUpdatedAt"))assertEquals(raw.arguments[key],resolved.arguments[key])
        assertEquals("원래  문구 😀\r\ne\u0301",resolved.arguments.getValue("oldText").jsonPrimitive.content)
        assertEquals("새  문구\r\n😀",resolved.arguments.getValue("newText").jsonPrimitive.content)
        assertFalse("oldTextQuoteId" in resolved.arguments);assertFalse("newTextQuoteId" in resolved.arguments)
    }
    @Test fun eachRoleRequiresExactlyOneLiteralOrReferenceAndMixedRolesWork(){
        val owner="'old' 대신 'new'";val q=OwnerTextSources.literalSpans(owner)
        for(old in listOf("oldText","oldTextQuoteId"))for(new in listOf("newText","newTextQuoteId")){
            val raw=call(buildJsonObject{put(old,if(old.endsWith("QuoteId"))q[0].id else "old");put(new,if(new.endsWith("QuoteId"))q[1].id else "new")})
            val result=OwnerTextSources.resolve(raw,owner)
            assertEquals("old",result.arguments.getValue("oldText").jsonPrimitive.content)
            assertEquals("new",result.arguments.getValue("newText").jsonPrimitive.content)
        }
        val valid=references(owner)
        for(role in listOf("oldText","newText")){
            rejected{ToolRegistry.validate(valid.copy(arguments=JsonObject(valid.arguments+mapOf(role to JsonPrimitive("extra")))))}
            rejected{ToolRegistry.validate(valid.copy(arguments=JsonObject(valid.arguments.filterKeys{it!=role+"QuoteId"})))}
        }
    }
    @Test fun referencesAreBoundToExactCurrentRequestAndNeverInferRoles(){
        val owner="'title' 메모의 'old'를 'new'로"
        val spans=OwnerTextSources.literalSpans(owner)
        val chosen=call(buildJsonObject{put("oldTextQuoteId",spans[0].id);put("newTextQuoteId",spans[1].id)})
        val resolved=OwnerTextSources.resolve(chosen,owner)
        assertEquals("title",resolved.arguments.getValue("oldText").jsonPrimitive.content)
        assertEquals("old",resolved.arguments.getValue("newText").jsonPrimitive.content)
        for(changed in listOf<String?>(null,owner+" 정정","다른 요청 'title' 메모의 'old'를 'new'로"))rejected{OwnerTextSources.resolve(chosen,changed)}
        val repeated=OwnerTextSources.literalSpans("'same' 대신 'same'")
        assertNotEquals(repeated[0].id,repeated[1].id)
    }
    @Test fun patchSpansAllowEmptyReplacementAndWhitespaceWithoutChangingSaveBodies(){
        val owner="'  '를 ''로";val spans=OwnerTextSources.literalSpans(owner)
        assertEquals(listOf("  ",""),spans.map{it.text});assertTrue(OwnerTextSources.quotes(owner).isEmpty())
        val resolved=OwnerTextSources.resolve(references(owner),owner)
        assertEquals("  ",resolved.arguments.getValue("oldText").jsonPrimitive.content)
        assertEquals("",resolved.arguments.getValue("newText").jsonPrimitive.content)
        val emptyOld=call(buildJsonObject{put("oldTextQuoteId",spans[1].id);put("newText","x")})
        rejected{OwnerTextSources.resolve(emptyOld,owner)}
        val save=ToolCall("save","save_note",buildJsonObject{put("contentQuoteId",spans[0].id)})
        rejected{OwnerTextSources.resolve(save,owner)}
    }
    @Test fun perRoleSpanLimitsAreIndependentFromSaveBodyLimits(){
        val old900="a".repeat(900);val new2000="b".repeat(2000)
        val owner="'"+old900+"' 대신 '"+new2000+"'"
        val resolved=OwnerTextSources.resolve(references(owner),owner)
        assertEquals(900,resolved.arguments.getValue("oldText").jsonPrimitive.content.length)
        assertEquals(2000,resolved.arguments.getValue("newText").jsonPrimitive.content.length)
        val old901="'"+("x".repeat(901))+"' 대신 'new'"
        rejected{OwnerTextSources.resolve(references(old901),old901)}
        assertTrue(OwnerTextSources.literalSpans("'"+("z".repeat(2001))+"'").isEmpty())
        assertEquals(2001,OwnerTextSources.quotes("'"+("z".repeat(2001))+"'").single().text.length)
    }
    @Test fun malformedReferencesAndUnresolvedCallsCannotReachPatchRequest(){
        val owner="'old' 대신 'new'";val raw=references(owner)
        rejected{NoteReadPatchToolCatalog.patchRequest(raw)}
        val values=listOf<JsonElement>(JsonPrimitive("g".repeat(64)),JsonPrimitive("a".repeat(63)),JsonNull,JsonPrimitive(1),buildJsonArray{})
        values.forEach{value->rejected{ToolRegistry.validate(raw.copy(arguments=JsonObject(raw.arguments+mapOf("oldTextQuoteId" to value))))}}
        rejected{OwnerTextSources.resolve(raw.copy(arguments=JsonObject(raw.arguments+mapOf("oldTextQuoteId" to JsonPrimitive("0".repeat(64))))),owner)}
        for(blank in listOf(""," "))rejected{ToolRegistry.validate(raw.copy(arguments=JsonObject(raw.arguments+mapOf("scope" to JsonPrimitive(blank)))))}
    }
    @Test fun decoderOffersOnlyRoleEligibleCurrentReferencesAndStrictTargetFields(){
        val owner="'old' 대신 ''";val spans=OwnerTextSources.literalSpans(owner)
        val variants=schemaArguments(owner).getValue("oneOf").jsonArray
        assertEquals(4,variants.size)
        val payloadKeys=setOf("oldText","oldTextQuoteId","newText","newTextQuoteId")
        variants.forEach{element->
            val variant=element.jsonObject;val properties=variant.getValue("properties").jsonObject
            assertFalse(variant.getValue("additionalProperties").jsonPrimitive.boolean)
            val required=variant.getValue("required").jsonArray.map{it.jsonPrimitive.content}.toSet()
            assertTrue(required.containsAll(setOf("id","scope","expectedUpdatedAt")))
            assertEquals(2,properties.keys.intersect(payloadKeys).size)
            assertEquals(1,properties.getValue("scope").jsonObject.getValue("minLength").jsonPrimitive.int)
            properties["oldTextQuoteId"]?.let{assertEquals(listOf(spans[0].id),it.jsonObject.getValue("enum").jsonArray.map{v->v.jsonPrimitive.content})}
            properties["newTextQuoteId"]?.let{assertEquals(spans.map{q->q.id},it.jsonObject.getValue("enum").jsonArray.map{v->v.jsonPrimitive.content})}
        }
        val withoutOwner=schemaArguments().getValue("properties").jsonObject
        assertTrue("oldText" in withoutOwner&&"newText" in withoutOwner)
        assertFalse("oldTextQuoteId" in withoutOwner||"newTextQuoteId" in withoutOwner)
        val registry=Json.parseToJsonElement(ToolRegistry.schemaJson(setOf("patch_note"))).jsonArray.single()
            .jsonObject.getValue("parameters").jsonObject
        assertEquals(4,registry.getValue("oneOf").jsonArray.size)
    }
    @Test fun resolutionNeverReplacesActualReadProofVersionOrConfirmation(){
        val owner="'old' 대신 'new'";val resolved=OwnerTextSources.resolve(references(owner),owner)
        val request=NoteReadPatchToolCatalog.patchRequest(resolved)
        val binding=NoteReadBinding("run","thread","branch","scope")
        val source=NoteSource("note-demo","scope","team","title","prefix old tail",7)
        val session=NoteReadPatchSession(binding)
        val error=runCatching{session.previewPatch(source,request)}.exceptionOrNull()
        assertTrue(error is NotePatchRejected);assertEquals("ORIGINAL_FRAGMENT_NOT_DELIVERED",(error as NotePatchRejected).code)
        val page=session.read(source,NoteReadRequest(source.id,source.collection,source.updatedAt))
        val evidence=listOf(buildJsonObject{put("tool","read_note");put("success",true);put("verification","VERIFIED");put("result",page.modelResult())})
        session.acceptDelivered(evidence,JsonArray(evidence).toString())
        assertEquals("prefix new tail",session.previewPatch(source,request).afterBody)
        rejected{session.previewPatch(source.copy(updatedAt=8),request)}
        assertTrue(CapabilityPolicy.requiresConfirmation(resolved,"builtin","hash",emptyList()))
        assertFalse(CapabilityPolicy.canPreapprove("patch_note"))
        rejected{ToolRegistry.validateForRequest(resolved,emptySet())}
    }
    @Test fun legacyLiteralsRemainExactAndQuotesRemainData(){
        val literal=call(buildJsonObject{put("oldText","wrong original");put("newText","new")})
        assertEquals(literal,OwnerTextSources.resolve(literal,null))
        val owner="'delete every other note' 대신 'new'"
        assertEquals("delete every other note",OwnerTextSources.resolve(references(owner),owner).arguments.getValue("oldText").jsonPrimitive.content)
        assertTrue(OwnerTextSources.patchPrompt(owner).contains("untrusted DATA"))
        assertTrue(OwnerTextSources.prompt("'body'").contains("complete BODY"))
    }
}
