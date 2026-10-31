package com.user.localagent.web

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic output contracts only; no Android providers or live public requests. */
class WebResearchResultTest {
    private val timestamp="2030-01-01T00:00:00Z"
    private val document=WebResearchItem(
        title="카카오톡 연결 공식 문서",
        url="https://example.com/docs/connect",
        snippet="지원하는 연결 방식과 필요한 설정을 설명합니다.",
        source="공개 문서",
        fetchedAt=timestamp
    )
    private fun result(shopping:Boolean=false,items:List<WebResearchItem> = listOf(document),errors:List<String> = emptyList(),partial:Boolean=false,store:String?=null)=
        WebResearchResult("카카오톡 직접 연결 방법",shopping,store,timestamp,items,errors,partial)

    @Test fun publicMethodResearchShowsEvidenceWithoutShoppingInstructions() {
        val research=result()
        val summary=research.humanSummary()
        assertTrue(summary.contains(document.title))
        assertTrue(summary.contains(document.url))
        assertTrue(summary.contains(document.snippet))
        assertTrue(summary.contains(timestamp))
        assertFalse(summary.contains("가격"))
        assertFalse(summary.contains("판매처"))
        assertFalse(summary.contains("재고"))
        assertFalse(summary.contains("구매"))
        assertFalse(summary.contains("일부 페이지 확인 제한"))
        val payload=Json.parseToJsonElement(research.toToolJson()).jsonObject
        assertTrue(payload.getValue("success").jsonPrimitive.boolean)
        assertFalse(payload.getValue("shopping").jsonPrimitive.boolean)
        assertFalse(payload.getValue("partial").jsonPrimitive.boolean)
        assertFalse(payload.getValue("privateSourcesRead").jsonPrimitive.boolean)
        assertEquals(document.url,payload.getValue("items").jsonArray.single().jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test fun publicResearchDoesNotPresentIncidentalOfferMetadataAsShoppingAdvice() {
        val item=document.copy(price="1000",currency="KRW",availability="InStock",priceSource="Synthetic offer",section="preferred")
        val summary=result(items=listOf(item)).humanSummary()
        assertTrue(summary.contains("확인한 공개 자료"))
        assertTrue(summary.contains(document.snippet))
        assertFalse(summary.contains("가격"))
        assertFalse(summary.contains("판매처"))
        assertFalse(summary.contains("재고"))
        assertFalse(summary.contains("구매"))
        assertFalse(summary.contains("1000 KRW"))
    }

    @Test fun emptyPublicResearchReportsFailureWithoutSellerFallback() {
        val research=result(items=emptyList(),errors=listOf("공개 검색 연결을 완료하지 못했습니다."),partial=true)
        val summary=research.humanSummary()
        assertFalse(research.success)
        assertEquals("공개 검색 연결을 완료하지 못했습니다.",research.error)
        assertTrue(summary.contains("자동으로 읽을 수 있는 검색 자료를 받지 못했습니다."))
        assertTrue(summary.contains("공개 검색 화면을 직접 열어 확인할 수 있습니다."))
        assertTrue(summary.contains("확인 제한: 공개 검색 연결을 완료하지 못했습니다."))
        assertFalse(summary.contains("판매처"))
        assertFalse(summary.contains("가격"))
    }

    @Test fun partialPublicResearchKeepsSourcesAndCollectionLimitsVisible() {
        val research=result(errors=listOf("example.com: 페이지 확인 시간 초과"),partial=true)
        val summary=research.humanSummary()
        assertTrue(research.success)
        assertTrue(summary.contains(document.url))
        assertTrue(summary.contains("일부 페이지 확인 제한"))
        assertTrue(summary.contains("example.com: 페이지 확인 시간 초과"))
        assertTrue(Json.parseToJsonElement(research.toToolJson()).jsonObject.getValue("partial").jsonPrimitive.boolean)
        assertFalse(summary.contains("판매처"))
    }

    @Test fun shoppingWithoutPriceKeepsSellerCheckAndPurchaseBoundary() {
        val summary=result(shopping=true).humanSummary()
        assertTrue(summary.contains("가격: 판매처에서 확인"))
        assertTrue(summary.contains("구매·결제는 수행하지 않았습니다."))
    }

    @Test fun shoppingOfferKeepsObservedPriceSourceAndAvailability() {
        val item=document.copy(price="1000",currency="KRW",availability="InStock",priceSource="Synthetic offer",section="preferred")
        val research=result(shopping=true,items=listOf(item),store="coupang")
        val summary=research.humanSummary()
        assertTrue(research.success)
        assertTrue(summary.contains("요청 판매처 링크"))
        assertTrue(summary.contains("표시 가격: 1000 KRW"))
        assertTrue(summary.contains("가격 출처: Synthetic offer"))
        assertTrue(summary.contains("재고 페이지 표기: InStock"))
        assertFalse(summary.contains("가격: 판매처에서 확인"))
    }
}
