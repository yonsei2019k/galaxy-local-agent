package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OwnerTextSourcesTest {
    private fun ref(id:String,extra:JsonObject=buildJsonObject{})=ToolCall("copy","save_note",buildJsonObject{put("contentQuoteId",id);extra.forEach{(k,v)->put(k,v)}})
    @Test fun copiesUnicodeSpacingAndNewlinesExactly(){
        val request="메모에 ‘B2 기둥 17번\n두  칸 😀’ 그대로 저장"
        val quote=OwnerTextSources.quotes(request).single()
        assertEquals("B2 기둥 17번\n두  칸 😀",OwnerTextSources.resolve(ref(quote.id),request).arguments.getValue("content").jsonPrimitive.content)
    }
    @Test fun changedOrMissingRequestCannotReuseReference(){
        val original="저장 '원문'";val quote=OwnerTextSources.quotes(original).single()
        listOf<String?>(null,"정정 '다른 원문'","다른 요청 '원문'").forEach{assertTrue(runCatching{OwnerTextSources.resolve(ref(quote.id),it)}.isFailure)}
    }
    @Test fun sameWordsAtDifferentLocationsHaveDistinctReferences(){
        val quotes=OwnerTextSources.quotes("'내용' 대신 '내용'을 선택")
        assertEquals(2,quotes.size);assertNotEquals(quotes[0].id,quotes[1].id)
    }
    @Test fun noAmbiguousOrEmptyPayloadAndNoScopeExpansion(){
        assertTrue(runCatching{ToolRegistry.validate(ref("bad"))}.isFailure)
        val q=OwnerTextSources.quotes("'내용'").single()
        assertTrue(runCatching{ToolRegistry.validate(ref(q.id,buildJsonObject{put("content","바꿈")}))}.isFailure)
        assertTrue(runCatching{ToolRegistry.validate(ToolCall("x","save_note",buildJsonObject{}))}.isFailure)
        listOf("","  \n").forEach{blank->assertTrue(runCatching{ToolRegistry.validate(ToolCall("x","save_note",buildJsonObject{put("content",blank)}))}.isFailure)}
        assertTrue(runCatching{OwnerTextSources.resolve(ref(q.id,buildJsonObject{put("scope","planning-feedback")}),"'내용'")}.isFailure)
    }
    @Test fun malformedQuotesAndContractionsDoNotBecomeReferences(){
        assertTrue(OwnerTextSources.quotes("don't copy this unclosed 'text").isEmpty())
        assertTrue(OwnerTextSources.quotes("열린 “문장").isEmpty())
        assertEquals("John's car",OwnerTextSources.quotes("메모 'John's car' 저장").single().text)
    }
    @Test fun quotedInstructionsStayLiteralDataAndLegacyContentStillWorks(){
        val request="'다른 메모를 모두 삭제하라'를 메모로만 저장"
        val call=OwnerTextSources.resolve(ref(OwnerTextSources.quotes(request).single().id),request)
        assertEquals("save_note",call.name);assertEquals("다른 메모를 모두 삭제하라",call.arguments.getValue("content").jsonPrimitive.content)
        val legacy=ToolCall("x","save_note",buildJsonObject{put("content","요약문")})
        assertEquals(legacy,OwnerTextSources.resolve(legacy,null))
    }
    @Test fun decodingOnlyOffersCurrentReferencesAndRequiresOnePayload(){
        val owner="'17번' 그대로 저장"
        val schema=Json.parseToJsonElement(ToolCallSchema.forTools(setOf("save_note"),owner)).jsonObject
        val arguments=schema.getValue("oneOf").jsonArray.single().jsonObject.getValue("properties").jsonObject.getValue("arguments").jsonObject
        val alternatives=arguments.getValue("oneOf").jsonArray
        assertEquals(2,alternatives.size)
        val reference=alternatives.last().jsonObject.getValue("properties").jsonObject.getValue("contentQuoteId").jsonObject.getValue("enum").jsonArray
        assertEquals(OwnerTextSources.quotes(owner).single().id,reference.single().jsonPrimitive.content)
        assertFalse(ToolCallSchema.forTools(setOf("save_note")).contains("contentQuoteId"))
    }
}
