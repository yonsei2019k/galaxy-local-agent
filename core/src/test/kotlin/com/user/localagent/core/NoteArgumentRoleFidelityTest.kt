package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Role metadata reaches real catalogs without changing authority or schema shape. */
class NoteArgumentRoleFidelityTest {
    private val name="save_note"
    private fun fields()=ToolRegistry.definitions.getValue(name).arguments
    private fun arguments(owner:String?):JsonObject {
        val root=Json.parseToJsonElement(ToolCallSchema.forTools(setOf(name),owner)).jsonObject
        return root.getValue("oneOf").jsonArray.single().jsonObject.getValue("properties").jsonObject.getValue("arguments").jsonObject
    }
    private fun variants(owner:String?):List<JsonObject> {
        val value=arguments(owner)
        return value["oneOf"]?.jsonArray?.map{it.jsonObject}?:listOf(value)
    }

    @Test fun roleDescriptionsReachCompactCatalogAndBothNativePayloadVariants(){
        val compact=Json.parseToJsonElement(ToolUsePolicy.compactCatalog(setOf(name))).jsonArray.single().jsonObject
        val described=compact.getValue("argumentDescriptions").jsonObject
        val owner="제목 ‘정원 일지’로 본문 ‘첫  잎\n새싹 🙂’을 그대로 저장"
        for(field in listOf("content","title","contentQuoteId")){
            val expected=fields().getValue(field).description
            assertTrue(expected.isNotBlank())
            assertEquals(expected,described.getValue(field).jsonPrimitive.content)
            for(variant in variants(owner)){
                val property=variant.getValue("properties").jsonObject[field]?:continue
                assertEquals(expected,property.jsonObject.getValue("description").jsonPrimitive.content)
            }
        }
    }

    @Test fun headingsStayOptionalAndPayloadAlternativesRemainExclusive(){
        assertFalse(fields().getValue("title").required)
        assertFalse(fields().getValue("content").required)
        assertFalse(fields().getValue("contentQuoteId").required)
        for(variant in variants("제목 ‘정원 일지’, 내용 ‘새싹’ 그대로 저장")){
            val required=variant.getValue("required").jsonArray.map{it.jsonPrimitive.content}
            assertEquals(1,required.size);assertTrue(required.single() in setOf("content","contentQuoteId"))
            assertFalse("title" in required);assertFalse(variant.getValue("additionalProperties").jsonPrimitive.boolean)
            val properties=variant.getValue("properties").jsonObject
            assertTrue(("content" in properties) xor ("contentQuoteId" in properties))
            assertTrue("title" in properties);assertTrue("scope" in properties)
        }
    }

    @Test fun modelSelectedFullBodyReferencePreservesBodyAndSeparateTitleExactly(){
        val title="정원  일지";val body="첫  잎\n새싹 🙂"
        val owner="제목 ‘$title’로 본문 ‘$body’을 그대로 저장"
        val quotes=OwnerTextSources.quotes(owner)
        // A host does not guess which quote means body. Both remain data references.
        assertEquals(listOf(title,body),quotes.map{it.text})
        val selected=quotes.single{it.text==body}
        val result=OwnerTextSources.resolve(ToolCall("synthetic",name,buildJsonObject {
            put("contentQuoteId",selected.id);put("title",title)
        }),owner)
        assertEquals(body,result.arguments.getValue("content").jsonPrimitive.content)
        assertEquals(title,result.arguments.getValue("title").jsonPrimitive.content)
        assertFalse("contentQuoteId" in result.arguments)
    }

    @Test fun generatedReportCanUseContentEvenWhenOwnerAlsoQuotedAHeading(){
        val owner="제목 ‘정원 점검’으로 관찰 결과를 정리해 저장"
        val content="새싹 수=7\n물주기=완료"
        val proposed=ToolCall("synthetic",name,buildJsonObject{put("title","정원 점검");put("content",content)})
        assertEquals(proposed,OwnerTextSources.resolve(proposed,owner))
        assertTrue(variants(owner).any{"content" in it.getValue("properties").jsonObject})
    }

    @Test fun noTitleStillWorksAndHostDoesNotInventOneOrAnAccessScope(){
        val plain=ToolCall("synthetic",name,buildJsonObject{put("content","메모 본문")})
        assertEquals(plain,ToolRegistry.validate(plain))
        assertEquals(plain,OwnerTextSources.resolve(plain,null))
        val owner="‘작은  새싹 🙂’ 그대로 저장"
        val quote=OwnerTextSources.quotes(owner).single()
        val copied=OwnerTextSources.resolve(ToolCall("synthetic",name,buildJsonObject{put("contentQuoteId",quote.id)}),owner)
        assertEquals(setOf("content"),copied.arguments.keys)
        assertEquals(quote.text,copied.arguments.getValue("content").jsonPrimitive.content)
        assertEquals(listOf("content"),arguments(null).getValue("required").jsonArray.map{it.jsonPrimitive.content})
    }

    @Test fun shortDescriptionsStayWithinExistingPlannerAndCompactBudgets(){
        val description=ToolRegistry.definitions.getValue(name).description
        assertTrue(description.length<=160)
        assertTrue(SemanticPlannerInstructions.build(setOf(name)).contains(description))
        assertTrue(ToolUsePolicy.compactCatalog(setOf(name)).contains(description))
        assertTrue(ToolCallSchema.forTools(setOf(name),"제목 ‘정원’, 본문 ‘새싹’").length<=65536)
    }
}
