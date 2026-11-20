package com.user.localagent.web

import com.user.localagent.core.VerificationState
import com.user.localagent.tools.readToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.time.Instant

/** Entirely synthetic injected responses. No live requests, accounts or user datasets. */
class PublicWebClientTest {
    private val timestamp="2030-01-01T00:00:00Z"
    private fun response(url:String,body:String,type:String="text/html")=PublicWebResponse(200,url,type,body.toByteArray())
    private fun client(transport:PublicWebTransport)=PublicWebClient(transport){Instant.parse(timestamp)}
    @Test fun failedSearchProviderFallsBackToObservedYahooProductLink()=runBlocking {
        val web=client {url,_->if(url.contains("duckduckgo"))PublicWebResponse(202,url,"text/html","challenge".toByteArray())else response(url,"""
            <a href="https://www.coupang.com/vp/products/987"><div>쿠팡</div><h3>샤오미 공기청정기 4</h3></a><div><p>Public source snippet</p></div>
        """)}
        val report=web.search("샤오미 공기청정기 쿠팡")
        assertEquals(2,report.requestCount);assertEquals("Yahoo Search",report.items.single().source)
        assertEquals("https://www.coupang.com/vp/products/987",report.items.single().url)
        assertNull(report.items.single().offer)
    }
    @Test fun explicitRequestBudgetStopsBeforeTryingAnotherProvider()=runBlocking {
        var calls=0
        val report=client {url,_->calls++;PublicWebResponse(202,url,"text/html",byteArrayOf())}.search("샤오미 공기청정기",maxRequests=1)
        assertEquals(1,calls);assertEquals(1,report.requestCount);assertTrue(report.items.isEmpty())
    }

    @Test fun generalSearchPreservesObservedCandidatesForSemanticAssessment()=runBlocking {
        val calls=mutableListOf<String>()
        val web=client {url,_->calls+=url;response(url,"""
            <html><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.coupang.com%2Fvp%2Fproducts%2F123&amp;rut=x">샤오미 공기청정기 컴팩트</a>
            <a class="result__snippet">Synthetic public listing snippet</a>
            <a class="result__a" href="https://example.com/tax">홈택스 세금 신고</a></html>
        """)}
        val report=web.search("샤오미 공기청정기 쿠팡")
        assertEquals(1,calls.size)
        assertEquals(1,report.requestCount)
        assertEquals(listOf("https://www.coupang.com/vp/products/123","https://example.com/tax"),report.items.map {it.url})
        assertTrue(report.items.all {it.fetchedAt==timestamp&&it.offer==null})
        assertTrue(report.items.all {it.source=="DuckDuckGo public search"})
    }

    @Test fun explicitSiteScopeStillExcludesOtherHosts()=runBlocking {
        val web=client {url,_->response(url,if(url.contains("duckduckgo"))"<html>No matching items</html>"else
            "<rss><channel><item><title>홈택스 로그인</title><link>https://example.com/tax</link><description>세금 신고</description></item></channel></rss>","text/xml")}
        val report=web.search("샤오미 공기청정기 site:coupang.com")
        assertTrue(report.items.isEmpty())
        assertTrue(report.partial)
        assertTrue(report.errors.isNotEmpty())
        assertEquals(3,report.requestCount)
    }

    @Test fun documentationCandidatesNeedNotRepeatEveryWordOfTheOwnersRequest()=runBlocking {
        val web=client {url,_->response(url,"""
            <a class="result__a" href="https://developers.kakao.com/docs/ko/kakaotalk-message/common">카카오톡 메시지 API</a>
            <a class="result__snippet">서비스 사용자 간 메시지 발송 기능을 소개합니다.</a>
        """)}
        val report=web.search("카카오톡에 직접 연결할 수 있는 방법을 찾아봐",maxRequests=1)
        assertEquals(1,report.requestCount)
        assertEquals("https://developers.kakao.com/docs/ko/kakaotalk-message/common",report.items.single().url)
        assertEquals("카카오톡 메시지 API",report.items.single().title)
        assertEquals(timestamp,report.items.single().fetchedAt)
        assertNull(report.items.single().offer)
    }

    @Test fun shoppingFallbackStillRequiresProductIdentityAndExcludesAccessories()=runBlocking {
        val web=client {url,_->response(url,if(url.contains("danawa"))react(buildJsonArray {})else """
            <a class="result__a" href="https://example.com/wrong-model">샤오미 공기청정기 AC-M16-SC</a>
            <a class="result__a" href="https://example.com/filter">샤오미 공기청정기 AC-M18-SC 호환 필터</a>
            <a class="result__a" href="https://example.com/correct-model">샤오미 공기청정기 AC-M18-SC</a>
        """)}
        val report=web.searchShopping("샤오미 공기청정기 AC-M18-SC",maxRequests=2)
        assertEquals(2,report.requestCount)
        assertEquals("https://example.com/correct-model",report.items.single().url)
        assertNull(report.items.single().offer)
        assertTrue(report.partial)
    }

    @Test fun comparisonFeedKeepsDeviceVariantAndExcludesFilterAdvertisementAndWrongProduct()=runBlocking {
        val records=buildJsonArray {
            add(product("11","샤오미 미에어 4 컴팩트 AC-M18-SC","188810","공기청정기",specs=listOf("공기청정기","16~27㎡","CADR","230㎥/h","필터점검/교체알림")))
            add(product("12","샤오미 공기청정기 호환 필터","7850","공기청정기"))
            add(product("13","샤오미 공기청정기 광고 상품","90000","공기청정기").let {JsonObject(it+ mapOf("isAd" to JsonPrimitive(true)))})
            add(product("14","Synthetic vacuum","1000","청소기"))
        }
        val web=client {url,_->response(url,react(records))}
        val report=web.searchShopping("샤오미 공기청정기")
        assertEquals(1,report.items.size)
        val item=report.items.single()
        assertTrue(item.title.contains("AC-M18-SC"))
        assertEquals("188810",item.offer?.price)
        assertEquals("KRW",item.offer?.currency)
        assertEquals("https://prod.danawa.com/info/?pcode=11",item.url)
        assertTrue(item.offer!!.priceSource.contains("not checkout"))
        assertNull(item.offer?.availability)
        assertEquals(1,report.requestCount)
        assertTrue(item.snippet.startsWith("다나와 표시 사양:"))
        assertTrue(item.snippet.contains("16~27㎡ · CADR · 230㎥/h · 필터점검/교체알림"))
        assertFalse(item.snippet.contains("소음"))
    }

    @Test fun requestedCoupangRetainsActualDirectLinkWithoutInventingPriceFromAnotherMerchant()=runBlocking {
        val records=buildJsonArray {add(product("21","샤오미 공기청정기 4","120000","공기청정기","다른 판매처"))}
        val web=client {url,_->response(url,if(url.contains("danawa"))react(records)else """
            <a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.coupang.com%2Fvp%2Fproducts%2F456" class="result__a">샤오미 공기청정기4 - 쿠팡</a>
            <a class="result__snippet">Public result; price not verified.</a>
        """)}
        val report=web.searchShopping("쿠팡에서 샤오미 공기청정기 최저가 제품링크하나 찾아주고 다른 상품중에 가성비가 가장 좋은 것도 좀 찾아줘","coupang")
        assertEquals(1,report.items.size)
        assertEquals("https://www.coupang.com/vp/products/456",report.items.single().url)
        assertNull(report.items.single().offer)
        assertEquals("쿠팡",report.items.single().merchant)
        assertTrue(report.errors.isNotEmpty())
        assertEquals(2,report.requestCount)
    }

    @Test fun additionalPlannerStoresUseTheirActualHostAndStayWithinSearchBudget()=runBlocking {
        for((store,host)in listOf("11st" to "www.11st.co.kr","gmarket" to "item.gmarket.co.kr","amazon" to "www.amazon.co.jp")){
            val web=client {url,_->response(url,if(url.contains("danawa"))react(buildJsonArray {})else
                "<a class=\"result__a\" href=\"https://$host/products/123\">샤오미 공기청정기 synthetic listing</a>")}
            val report=web.searchShopping("샤오미 공기청정기",store)
            assertEquals(1,report.items.size)
            assertTrue(report.items.single().url.contains(host))
            assertNull(report.items.single().offer)
            assertEquals(2,report.requestCount)
        }
    }

    @Test fun onlyStructuredOffersWithCurrencyBecomeObservedPrices()=runBlocking {
        val web=client {url,_->response(url,"""
            <title>Synthetic product</title><div>Other marketing price 999 won</div>
            <script type="application/ld+json">{"@type":"Product","offers":[
            {"@type":"Offer","price":"100000","priceCurrency":"KRW","availability":"https://schema.org/InStock","priceValidUntil":"2030-01-03","shippingDetails":{"shippingRate":{"value":2500,"currency":"KRW"}}},
            {"@type":"Offer","price":"1"}]}</script>
        """)}
        val page=web.fetchPage("https://example.com/product")
        assertTrue(page.untrusted)
        assertEquals(1,page.offers.size)
        assertEquals("100000",page.offers.single().price)
        assertEquals("InStock",page.offers.single().availability)
        assertEquals("2030-01-03",page.offers.single().validThrough)
        assertEquals("https://example.com/product",page.offers.single().sourceUrl)
        assertFalse(page.text.contains("shippingDetails"))
    }

    @Test fun pageFailuresDistinguishHttpChallengeMediaAndEmptyBody() = runBlocking {
        val url = "https://example.com/public"
        suspend fun auditCode(response: PublicWebResponse): String {
            val failure = runCatching { client { _, _ -> response }.fetchPage(url) }.exceptionOrNull()
            return (failure as PublicPageReadException).auditCode
        }
        assertEquals("HTTP_STATUS_403", auditCode(response(url, "Forbidden").copy(statusCode = 403)))
        assertEquals("HTTP_STATUS", auditCode(response(url, "").copy(statusCode = 999)))
        assertEquals("UNSUPPORTED_CONTENT", auditCode(response(url, "data", "application/octet-stream")))
        assertEquals("ACCESS_CHALLENGE", auditCode(response(url, "<html>Verify that you are human</html>")))
        assertEquals("ACCESS_CHALLENGE", auditCode(response(url, "<title>Just a moment…</title>")))
        assertEquals("ACCESS_CHALLENGE", auditCode(response(url, "<body>자동입력 방지 문자를 입력해주세요</body>")))
        val emptyPage = client { _, _ -> response(url, "<html><!-- no text --><script>loadContent()</script></html>") }
        assertTrue(emptyPage.fetchPage(url).text.isBlank())
        val result = emptyPage.readToolResult("empty", url)
        assertFalse(result.success)
        assertEquals(VerificationState.FAILED, result.verification)
        assertEquals("NO_READABLE_CONTENT", result.verificationSummary)
        assertTrue(result.effectNotApplied)
    }

    @Test fun anOfferOnlyPageRemainsUsableForStructuredPriceResearch() = runBlocking {
        val web = client { url, _ -> response(url, """
            <script type="application/ld+json">
            {"@type":"Product","offers":{"@type":"Offer","price":"2500","priceCurrency":"KRW"}}
            </script>
        """.trimIndent()) }
        val page = web.fetchPage("https://example.com/product")
        assertTrue(page.text.isBlank())
        assertEquals("2500", page.offers.single().price)
        val result = web.readToolResult("offer", page.url)
        assertTrue(result.success)
        assertEquals(VerificationState.VERIFIED, result.verification)
        val body = Json.parseToJsonElement(result.content).jsonObject
        val offer = body.getValue("offers").jsonArray.single().jsonObject
        assertEquals("2500", offer.getValue("price").jsonPrimitive.content)
        assertEquals("KRW", offer.getValue("currency").jsonPrimitive.content)
        assertEquals(page.url, body.getValue("url").jsonPrimitive.content)
        assertTrue(body.getValue("untrustedSourceData").jsonPrimitive.boolean)
    }

    @Test fun pageTransportDiagnosticsUseOnlyFixedCodes() = runBlocking {
        val detail = "https://example.com/?private=synthetic"
        val failures = mapOf(
            java.net.SocketTimeoutException(detail) to PublicPageReadReason.TIMEOUT,
            java.net.UnknownHostException(detail) to PublicPageReadReason.DNS,
            javax.net.ssl.SSLException(detail) to PublicPageReadReason.TLS,
            java.io.IOException(detail) to PublicPageReadReason.CONNECTION,
            java.util.zip.ZipException(detail) to PublicPageReadReason.RESPONSE_ERROR,
            UnsafePublicWebUrlException() to PublicPageReadReason.URL_REJECTED,
            IllegalArgumentException(detail) to PublicPageReadReason.RESPONSE_ERROR
        )
        for ((exception, expected) in failures) {
            val actual = PublicPageReadReason.from(exception)
            assertEquals(expected, actual)
            assertFalse(actual.userMessage.contains(detail))
            val result = client { _, _ -> throw exception }.readToolResult("failed", "https://example.com/public")
            assertEquals(expected.name, result.verificationSummary)
            assertEquals(expected.userMessage, result.content)
            assertTrue(result.effectNotApplied)
        }
    }

    @Test fun pageReadToolPropagatesCancellation() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val web = client { _, _ -> throw cancellation }
        val failure = runCatching { web.readToolResult("cancelled", "https://example.com/public") }.exceptionOrNull()
        assertSame(cancellation, failure)
    }

    @Test fun pageTextAndByteLimitsAreEnforced()=runBlocking {
        val web=client {url,_->response(url,"<p>"+"a".repeat(30_000)+"</p>")}
        assertEquals(PublicWebClient.MAX_TEXT,web.fetchPage("https://example.com/public").text.length)
        var rejected=false
        try {client {url,_->PublicWebResponse(200,url,"text/html",ByteArray(PublicWebClient.MAX_BYTES+1))}.fetchPage("https://example.com/public")}
        catch(_:IllegalStateException){rejected=true}
        assertTrue(rejected)
    }

    @Test fun unsafeSchemesCredentialsPrivateLiteralAndRedirectAreRejectedBeforeUse()=runBlocking {
        val urls=listOf("http://example.com/","file:///private","https://user:password@example.com/","https://127.0.0.1/",
            "https://192.168.1.1/","https://169.254.169.254/","https://100.100.100.200/","https://[::1]/","https://localhost/","https://example.local/","https://example.com:8443/")
        var called=0
        val web=client {url,_->called++;response(url,"not reached")}
        urls.forEach {url->var unsafe=false;try{web.fetchPage(url)}catch(_:UnsafePublicWebUrlException){unsafe=true};assertTrue(url,unsafe)}
        assertEquals(0,called)
        var unsafe=false
        try {client {_,_->response("https://10.0.0.1/","private")}.fetchPage("https://example.com/")}
        catch(_:UnsafePublicWebUrlException){unsafe=true}
        assertTrue(unsafe)
    }

    @Test fun resolvedPrivateAddressIsRejectedBeforeOpeningSocket()=runBlocking {
        val transport=PinnedPublicHttpsTransport {listOf(InetAddress.getByAddress(byteArrayOf(127,0,0,1)))}
        var unsafe=false
        try {transport.get("https://example.com/",1024)}catch(_:UnsafePublicWebUrlException){unsafe=true}
        assertTrue(unsafe)
    }

    @Test fun personalContactAndCredentialQueriesAreNotSentToSearch()=runBlocking {
        var calls=0;val web=client {url,_->calls++;response(url,"")}
        listOf("person@example.com lookup","010-1234-5678 검색","api_key=synthetic-secret","문자본문 검색").forEach {query->
            var rejected=false;try{web.search(query)}catch(_:IllegalArgumentException){rejected=true};assertTrue(rejected)
        }
        assertEquals(0,calls)
    }

    private fun product(id:String,name:String,price:String,category:String,merchant:String="",specs:List<String> = listOf(category))=buildJsonObject {
        put("prodType",1);put("productSeq",id);put("productName",name);put("minPrice",price);put("shopName",merchant)
        put("summary",buildJsonObject {put("desc",buildJsonArray {specs.forEach {add(it)}})});put("postPrice",0)
    }
    private fun react(records:JsonArray):String {
        val flight="7:"+buildJsonObject {put("products",records)}.toString()
        return "<script>self.__next_f.push("+buildJsonArray {add(1);add(flight)}.toString()+")</script>"
    }
}
