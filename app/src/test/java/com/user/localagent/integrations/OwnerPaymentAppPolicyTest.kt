package com.user.localagent.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerPaymentAppPolicyTest {
    private val checkout = "https://pg-web.nicepay.co.kr/checkout"
    private val issuer = "monimopay://checkout?session=opaque"

    @Test fun acceptsTheExactIssuerDataFromAnOwnerGesture() {
        assertEquals(issuer, OwnerPaymentAppPolicy.issuerData(checkout, issuer, true, true))
        assertEquals(issuer, OwnerPaymentAppPolicy.issuerData("https://web.nicepay.co.kr/demo/v3/mobileReq.jsp", issuer, true, true))
        assertEquals(issuer, OwnerPaymentAppPolicy.issuerData(checkout,
            "intent://checkout?session=opaque#Intent;scheme=monimopay;package=net.ib.android.smcard;end", true, true))
    }

    @Test fun historyUsesObservedMerchantAndIndependentQueryFreeOfficialRoutes() {
        assertEquals("https://m.thankqcamping.com/join/non/ser_reservation.hbb",
            OwnerPaymentAppPolicy.bookingMerchant("https://m.thankqcamping.com/resv/view.hbb?camp=1")?.history)
        assertEquals("https://nol.yanolja.com/member/mypage",OwnerPaymentAppPolicy.bookingMerchant("https://nol.yanolja.com/stay/domestic/1")?.history)
        for (url in listOf("http://m.thankqcamping.com","https://m.thankqcamping.com.evil.test","https://fake@nol.yanolja.com","https://nol.yanolja.com:8000","https://web.nicepay.co.kr/checkout"))
            assertNull(OwnerPaymentAppPolicy.bookingMerchant(url))
        assertEquals("https://www.nicepay.co.kr/cs/transInfo/cardList.do",OwnerPaymentAppPolicy.paymentHistory)
    }

    @Test fun rejectsFramesAndAgentNavigation() {
        assertNull(OwnerPaymentAppPolicy.issuerData(checkout, issuer, false, true))
        assertNull(OwnerPaymentAppPolicy.issuerData(checkout, issuer, true, false))
    }

    @Test fun rejectsLookalikeAndInsecureProcessorOrigins() {
        for (source in listOf("http://pg-web.nicepay.co.kr", "https://pg-web.nicepay.co.kr.example.com",
            "https://pg-web.nicepay.co.kr@elsewhere.example", "https://other@pg-web.nicepay.co.kr",
            "https://pg-web.nicepay.co.kr:444")) {
            assertNull(source, OwnerPaymentAppPolicy.issuerData(source, issuer, true, true))
        }
    }

    @Test fun rejectsArbitraryAppsAndIntentOverrides() {
        for (target in listOf("market://details?id=app", "https://elsewhere.example", "monimopay://host#fragment",
            "mpocket.online.ansimclick://checkout",
            "intent://checkout#Intent;scheme=monimopay;package=another.app;end",
            "intent://checkout#Intent;scheme=monimopay;package=net.ib.android.smcard;component=another.app/.Activity;end",
            "intent://checkout#Intent;scheme=monimopay;package=net.ib.android.smcard;S.browser_fallback_url=https://elsewhere.example;end")) {
            assertNull(target, OwnerPaymentAppPolicy.issuerData(checkout, target, true, true))
        }
    }
}
