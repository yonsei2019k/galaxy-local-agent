package com.user.localagent.integrations

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLoginRoutesTest {
    @Test fun onlyKnownPublicSearchPathsUseGuestFallback() {
        assertTrue(BrowserLoginRoutes.isPublicSearch("https://map.naver.com/p/search/camping"))
        assertTrue(BrowserLoginRoutes.isPublicSearch("https://search.shopping.naver.com/search/all?query=camping"))
        assertFalse(BrowserLoginRoutes.isPublicSearch("https://booking.naver.com/booking/3/bizes/123"))
        assertFalse(BrowserLoginRoutes.isPublicSearch("https://mail.naver.com/"))
        assertFalse(BrowserLoginRoutes.isPublicSearch("https://map.naver.com/p/search-account"))
        assertFalse(BrowserLoginRoutes.isPublicSearch("https://map.naver.com.example.org/p/search/camping"))
        assertFalse(BrowserLoginRoutes.isPublicSearch("https://someone@map.naver.com/p/search/camping"))
    }

    @Test fun directLoginReadbackOnlyAllowsOfficialNaverOrigins() {
        assertTrue(BrowserLoginRoutes.isDirectNaverSessionOrigin(URI("https://nid.naver.com")))
        assertTrue(BrowserLoginRoutes.isDirectNaverSessionOrigin(URI("https://www.naver.com")))
        assertTrue(BrowserLoginRoutes.isDirectNaverSessionOrigin(URI("https://m.naver.com")))
        assertFalse(BrowserLoginRoutes.isDirectNaverSessionOrigin(URI("https://booking.naver.com")))
        assertFalse(BrowserLoginRoutes.isDirectNaverSessionOrigin(URI("https://www.naver.com.example.org")))
        assertFalse(BrowserLoginRoutes.isDirectNaverSessionOrigin(URI("http://www.naver.com")))
        assertFalse(BrowserLoginRoutes.isDirectNaverSessionOrigin(URI("https://www.naver.com:9443")))
    }

    @Test fun onlyExactNaverServiceCanBindObservedNaverLogin() {
        val booking = URI("https://booking.naver.com")
        val login = "https://nid.naver.com/nidlogin.login?url=opaque"
        assertTrue(BrowserLoginRoutes.isNaverLoginFrom(booking, login))
        assertFalse(BrowserLoginRoutes.isNaverLoginFrom(URI("https://example.org"), login))
        assertFalse(BrowserLoginRoutes.isNaverLoginFrom(booking, "https://nid.naver.com.example.org/nidlogin.login"))
        assertFalse(BrowserLoginRoutes.isNaverLoginFrom(booking, "http://nid.naver.com/nidlogin.login"))
        assertFalse(BrowserLoginRoutes.isNaverLoginFrom(booking, "https://nid.naver.com/other"))
    }
}
