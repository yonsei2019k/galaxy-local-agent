package com.user.localagent.integrations

import java.net.URI

internal object BrowserLoginRoutes {
    private val naverServices = setOf("map.naver.com", "booking.naver.com", "m.booking.naver.com",
        "smartstore.naver.com", "brand.naver.com", "shopping.naver.com", "mail.naver.com",
        "order.pay.naver.com", "new-m.pay.naver.com")
    private val publicSearchPaths = mapOf(
        "search.naver.com" to setOf("/search.naver"),
        "map.naver.com" to setOf("/p/search", "/v5/search"),
        "shopping.naver.com" to setOf("/search"),
        "search.shopping.naver.com" to setOf("/search"),
        "msearch.shopping.naver.com" to setOf("/search"),
    )

    private fun https(raw: String?): URI? = raw?.let { runCatching { URI(it) }.getOrNull() }
        ?.takeIf { it.scheme == "https" && it.host != null && it.userInfo == null && it.port in setOf(-1, 443) }

    fun isPublicSearch(raw: String?): Boolean {
        val url = https(raw) ?: return false
        return publicSearchPaths[url.host]?.any { url.path == it || url.path.startsWith("$it/") } == true
    }

    fun isDirectNaverSessionOrigin(origin: URI): Boolean =
        origin.scheme == "https" && origin.rawUserInfo == null && origin.port in setOf(-1,443) &&
            origin.host in setOf("nid.naver.com", "www.naver.com", "m.naver.com")

    fun isNaverLoginFrom(requested: URI, actual: String?): Boolean {
        val login = https(actual) ?: return false
        return requested.host in naverServices && login.host == "nid.naver.com" &&
            login.path == "/nidlogin.login"
    }
}
