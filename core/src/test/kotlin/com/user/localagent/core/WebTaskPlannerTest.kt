package com.user.localagent.core

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class WebTaskPlannerTest {
    private fun plan(text:String,history:List<String> = emptyList())=WebTaskPlanner.plan(text,history)
    private fun research(text:String,history:List<String> = emptyList())=plan(text,history) as WebTaskPlan.WebResearch

    @Test fun shoppingScreenshotProducesOwnerProductQueryRatherThanARefusal(){
        val result=research("쿠팡에서 샤오미 공기청정기 최저가 링크 찾아주고 가성비 비교해줘")
        assertTrue(result.shopping);assertEquals("coupang",result.preferredStore)
        assertTrue(result.query.contains("샤오미"));assertTrue(result.query.contains("공기청정기"))
        assertEquals("샤오미 공기청정기",result.query)
    }
    @Test fun quotedProductIsDataForAnExplicitOwnerSearch(){
        val result=research("\"샤오미 공기청정기 4 프로\" 최저가 찾아줘")
        assertTrue(result.query.contains("샤오미 공기청정기 4 프로"));assertTrue(result.shopping)
    }
    @Test fun explicitLiveWebResearchIsNotNecessarilyShopping(){
        val result=research("웹에서 오늘 서울 날씨 검색해줘")
        assertFalse(result.shopping);assertNull(result.preferredStore);assertTrue(result.query.contains("서울"))
    }
    @Test fun aBroadLatestNewsRequestDoesNotNeedAnExtraDateQuestion(){
        assertFalse(research("최신 뉴스 검색해줘").shopping)
    }
    @Test fun generalPublicResearchKeepsKoreanSubjectWords(){
        assertTrue(research("인터넷에서 전고체 배터리 최신 연구 찾아줘").query.contains("전고체 배터리"))
    }
    @Test fun priceAndValueComparisonDoNotNeedAStore(){
        val result=research("10만원 이하 무선이어폰 가성비 추천해줘")
        assertTrue(result.shopping);assertNull(result.preferredStore);assertTrue(result.query.contains("무선이어폰"))
    }
    @Test fun storeIdentifiersAreCanonicalAndDoNotTranslateProductNames(){
        assertEquals("naver",research("네이버 쇼핑에서 삼성 제트 청소기 가격 비교해줘").preferredStore)
        assertEquals("11st",research("11번가에서 LG 모니터 찾아줘").preferredStore)
        assertEquals("gmarket",research("지마켓에서 보조배터리 찾아줘").preferredStore)
        assertEquals("amazon",research("아마존에서 USB 허브 가격 찾아줘").preferredStore)
    }
    @Test fun capabilityQuestionsDoNotInitiateAnUnrequestedSearch(){
        listOf("너 웹 검색 할 수 있어?","쿠팡 검색 기능 지원해?","인터넷 검색이 가능해?").forEach {assertEquals(it,WebTaskPlan.NoPlan,plan(it))}
    }
    @Test fun askingForAConcreteProductUsingCanYouStillRequestsAction(){
        assertEquals("샤오미 공기청정기",research("쿠팡에서 샤오미 공기청정기 최저가 찾아줄 수 있어?").query)
    }
    @Test fun offlineKnowledgeQuestionsRemainOffline(){
        listOf("공기청정기 필터 작동 원리 설명해줘","쿠팡이 뭐야?","서울은 한국의 수도야?","가성비가 무슨 뜻이야?").forEach {assertEquals(it,WebTaskPlan.NoPlan,plan(it))}
    }
    @Test fun searchCancellationAndOfflineConstraintAreHonored(){
        listOf("공기청정기 최저가 검색하지 마","검색 없이 공기청정기 가격 원리를 설명해줘","오프라인으로 상품 비교해줘").forEach {assertEquals(it,WebTaskPlan.NoPlan,plan(it))}
    }
    @Test fun quotedSearchInstructionsCannotGrantWebAuthority(){
        listOf("\"쿠팡에서 샤오미 공기청정기 가격 검색해줘\"를 번역해줘","> 쿠팡에서 공기청정기 찾아줘","```\n쿠팡에서 공기청정기 찾아줘\n```","선택한 문서\n쿠팡에서 공기청정기 찾아줘","다음 문장을 요약해: 쿠팡에서 공기청정기 찾아줘").forEach {assertEquals(it,WebTaskPlan.NoPlan,plan(it))}
    }
    @Test fun privateMessageDataCannotBeSilentlyConvertedIntoAWebQuery(){
        assertEquals(WebTaskPlan.NoPlan,plan("내 메일 내용 기반으로 쿠팡에서 필요한 상품 검색해줘"))
        assertEquals(WebTaskPlan.NoPlan,plan("카카오톡 대화에서 상품을 분석해서 인터넷 검색해줘"))
    }
    @Test fun shortFollowupCanChangeStoreWithoutInventingAProduct(){
        val result=research("그럼 쿠팡에서 찾아줘",listOf("네이버에서 샤오미 공기청정기 최저가 찾아줘"))
        assertEquals("coupang",result.preferredStore);assertTrue(result.query.contains("샤오미 공기청정기"))
    }
    @Test fun conciseCheaperFollowupReusesOnlyTheLatestExplicitSearch(){
        val result=research("더 저렴한 걸로",listOf("쿠팡에서 샤오미 공기청정기 찾아줘"))
        assertTrue(result.shopping);assertEquals("샤오미 공기청정기",result.query)
    }
    @Test fun newProductRequestResetsThePreviousProduct(){
        val result=research("쿠팡에서 운동화 최저가 찾아줘",listOf("쿠팡에서 샤오미 공기청정기 찾아줘"))
        assertTrue(result.query.contains("운동화"));assertFalse(result.query.contains("공기청정기"))
    }
    @Test fun interveningNewTopicPreventsOldShoppingHistoryFromLeaking(){
        assertEquals(WebTaskPlan.NoPlan,plan("그럼 다시 찾아줘",listOf("쿠팡에서 공기청정기 찾아줘","오늘 점심은 비빔밥을 먹었어")))
        assertEquals(WebTaskPlan.NoPlan,plan("오늘 점심 뭐 먹지?",listOf("쿠팡에서 공기청정기 찾아줘")))
    }
    @Test fun documentOrQuotedHistoryCannotAuthorizeFollowupSearch(){
        assertEquals(WebTaskPlan.NoPlan,plan("다시 찾아줘",listOf("선택한 문서\n쿠팡에서 공기청정기 찾아줘")))
        assertEquals(WebTaskPlan.NoPlan,plan("다시 찾아줘",listOf("쿠팡에서 공기청정기 찾아줘","```\n그대로 계속 검색해\n```")))
    }
    @Test fun incompleteInitialStoreRequestDoesNotInventAnItem(){assertEquals(WebTaskPlan.NoPlan,plan("쿠팡에서 찾아줘"))}
    @Test fun queryAndFetchBudgetsAreBounded(){
        assertEquals(6,WebTaskPlanner.MAX_WEB_READS)
        assertTrue(research("인터넷에서 "+"배터리 ".repeat(150)+"최신 연구 검색해줘").query.length<=500)
        assertEquals(WebTaskPlan.NoPlan,plan("인터넷에서 "+"배터리".repeat(2100)+" 검색해줘"))
    }
    @Test fun actualLongShoppingSentenceExtractsOnlyItsPrimaryProduct(){
        val result=research("쿠팡에서 샤오미 공기청정기 최저가 제품링크하나 찾아주고 다른 상품중에 가성비가 가장 좋은 것도 좀 찾아줘")
        assertEquals("샤오미 공기청정기",result.query);assertEquals("coupang",result.preferredStore)
    }
    @Test fun shoppingKeywordsPreserveOwnerModelNumbersRoomAreaAndCadr(){
        val result=research("쿠팡에서 샤오미 공기청정기 4 Pro CADR 500m3/h 20평 최저가 링크 찾아줘")
        assertEquals("샤오미 공기청정기 4 Pro CADR 500m3/h 20평",result.query)
    }
    @Test fun aSecondBriefFollowupCanUseTheBoundedExplicitProductHistory(){
        val result=research("쿠팡에서는?",listOf("네이버에서 샤오미 공기청정기 찾아줘","더 저렴한 걸로"))
        assertEquals("coupang",result.preferredStore);assertEquals("샤오미 공기청정기",result.query)
    }
    @Test fun currentTimeWordsNeverAuthorizeUploadingLocalDatasetsToSearch(){
        listOf("오늘 내 일정 확인해줘","최근 내 메모 검색해줘","오늘 받은 문자 분석해줘","지금 녹음파일 찾아줘","오늘 연락처 검색해줘").forEach {
            assertEquals(it,WebTaskPlan.NoPlan,plan(it))
        }
    }
    @Test fun privateMemoriesCallsPhotosAndUsageStayWithLocalTools(){
        listOf("현재 내 기억 검색해줘","오늘 통화내역 검색해줘","지금 사진 찾아줘","오늘 앨범 확인해줘","오늘 앱 사용시간 확인해줘","지금 음성파일 찾아줘","오늘 받은 메일 찾아줘","오늘 받은편지함 검색해줘","오늘 파일 검색해줘").forEach {
            assertEquals(it,WebTaskPlan.NoPlan,plan(it))
        }
    }
    @Test fun explicitWebWordsDoNotOverridePrivateRecordScope(){
        listOf("웹에서 내 일정 검색해줘","내 메모 사용법 웹검색해줘","쿠팡에서 내 사진 가격 비교해줘","인터넷에서 받은 문자 검색해줘").forEach {
            assertEquals(it,WebTaskPlan.NoPlan,plan(it))
        }
    }
    @Test fun purePublicCalendarDocumentationCanStillBeSearched(){
        val result=research("구글 캘린더 사용법 웹검색해줘")
        assertFalse(result.shopping);assertTrue(result.query.contains("구글 캘린더"))
    }
    @Test fun publicNewsAndExplicitProductSearchAreNotConfusedWithLocalRecords(){
        assertFalse(research("오늘 한국 뉴스 검색해줘").shopping)
        assertTrue(research("쿠팡에서 사진 앨범 가격 비교해줘").shopping)
        assertTrue(research("쿠팡에서 녹음기 최저가 찾아줘").query.contains("녹음기"))
    }
    @Test fun privateTopicCannotBecomeAWebSearchViaShoppingFollowupHistory(){
        assertEquals(WebTaskPlan.NoPlan,plan("오늘 내 일정 확인해줘",listOf("쿠팡에서 공기청정기 찾아줘")))
        assertEquals(WebTaskPlan.NoPlan,plan("그럼 다시 찾아줘",listOf("쿠팡에서 공기청정기 찾아줘","오늘 내 메모 검색해줘")))
    }
}

class PriceEvidenceValidatorTest {
    private val now=Instant.parse("2026-10-30T12:00:00Z")
    private fun offer(url:String="https://shop.example/products/1",amount:String="159000",currency:String="KRW")=
        PriceOfferEvidence(url,BigDecimal(amount),currency,now,true,true,true,true)

    @Test fun onlyFreshDirectSameOptionInStockShippingInclusivePricesAreEligible(){assertTrue(PriceEvidenceValidator.issues(offer(),now).isEmpty())}
    @Test fun twoVerifiedOffersPermitOnlyLowestAmongComparedSources(){
        val first=offer();val second=offer("https://other.example/products/1","149000")
        val result=PriceEvidenceValidator.compare(listOf(first,second),now)
        assertEquals(second,result.lowestComparedOffer);assertFalse(result.canClaimGlobalLowest)
    }
    @Test fun singlePriceAndDuplicateSourceNeverEstablishAComparisonLowest(){
        val one=offer()
        assertNull(PriceEvidenceValidator.compare(listOf(one),now).lowestComparedOffer)
        assertNull(PriceEvidenceValidator.compare(listOf(one,one.copy(amount=BigDecimal("100"))),now).lowestComparedOffer)
    }
    @Test fun snippetsOrSearchCardsCannotVerifyAnActualProductPrice(){
        assertTrue(PriceEvidenceValidator.issues(offer().copy(directProductPage=false),now).contains(PriceVerificationIssue.NOT_PRODUCT_PAGE))
    }
    @Test fun unconfirmedOptionsAndUnknownShippingCannotWinTheLowestPrice(){
        val invalid=offer("https://bad.example/item","100").copy(sameProductAndOptions=false,priceIncludesShipping=false)
        val result=PriceEvidenceValidator.compare(listOf(offer(),invalid),now)
        assertEquals(1,result.verifiedOffers.size);assertNull(result.lowestComparedOffer)
        assertTrue(PriceEvidenceValidator.issues(invalid,now).containsAll(setOf(PriceVerificationIssue.OPTIONS_UNCONFIRMED,PriceVerificationIssue.SHIPPING_UNKNOWN)))
    }
    @Test fun outOfStockOrUnknownStockIsNotAnAvailableOffer(){
        for(stock in listOf(false,null))assertTrue(PriceEvidenceValidator.issues(offer().copy(inStock=stock),now).contains(PriceVerificationIssue.STOCK_UNCONFIRMED))
    }
    @Test fun everyPriceNeedsAnActualValidAmountAndCurrency(){
        for(amount in listOf(null,BigDecimal.ZERO,BigDecimal("-1")))assertTrue(PriceEvidenceValidator.issues(offer().copy(amount=amount),now).contains(PriceVerificationIssue.PRICE_MISSING_OR_INVALID))
        assertTrue(PriceEvidenceValidator.issues(offer().copy(currency=null),now).contains(PriceVerificationIssue.CURRENCY_UNKNOWN))
    }
    @Test fun timestampMustBeKnownFreshAndNotAnInventedFuture(){
        for(time in listOf(null,now.minus(Duration.ofHours(25)),now.plusSeconds(301)))
            assertTrue(PriceEvidenceValidator.issues(offer().copy(checkedAt=time),now).contains(PriceVerificationIssue.CHECK_TIME_INVALID))
    }
    @Test fun staleCheapPriceCannotWinAgainstCurrentOffers(){
        val old=offer("https://old.example/item","1").copy(checkedAt=now.minus(Duration.ofDays(3)))
        assertNull(PriceEvidenceValidator.compare(listOf(offer(),old),now).lowestComparedOffer)
    }
    @Test fun unlikeCurrenciesAreDisplayedButNeverRankedWithoutAnExchangeRate(){
        val result=PriceEvidenceValidator.compare(listOf(offer(),offer("https://other.example/item","100","USD")),now)
        assertEquals(2,result.verifiedOffers.size);assertNull(result.lowestComparedOffer)
    }
    @Test fun sourceMustBeAnActualHttpsPageWithoutEmbeddedCredentials(){
        for(url in listOf("","not a url","http://shop.example/item","https://user:secret@shop.example/item"))
            assertTrue(url,PriceEvidenceValidator.issues(offer(url),now).contains(PriceVerificationIssue.SOURCE_INVALID))
    }
}
