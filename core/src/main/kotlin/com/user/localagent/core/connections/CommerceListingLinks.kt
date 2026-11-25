package com.user.localagent.core.connections

import java.net.URI

/** Only these listing links may prefer a merchant app. Keep the original URI for handoff. */
object CommerceListingLinks {
    private data class Route(val app: String, val path: Regex, val fields: Set<String>)
    private val stayFields = setOf("checkIn", "checkOut", "checkInDate", "checkOutDate", "adultCount", "childCount", "roomTypeId", "ratePlanId")
    /** Exact service homes have no merchant, order or reservation selection to lose. */
    private val serviceHomes = mapOf(
        "https://www.netflix.com" to "com.netflix.mediaclient",
        "https://www.coupang.com" to "com.coupang.mobile",
        "https://www.coupangeats.com" to "com.coupang.mobile.eats",
        "https://www.baemin.com" to "com.sampleapp",
        "https://www.yogiyo.co.kr" to "com.fineapp.yogiyo",
        "https://www.kakaomobility.com/kakaot/index.html" to "com.kakao.taxi",
        "https://app.catchtable.co.kr" to "co.kr.catchtable.android.catchtable_app",
        "https://www.tabling.co.kr" to "com.mealant.tabling",
        "https://map.naver.com" to "com.nhn.android.nmap"
    )
    fun homePackage(link: String): String? = serviceHomes[link.removeSuffix("/")]

    private val routes = mapOf(
        "map.naver.com" to Route("com.nhn.android.nmap", Regex("/p/entry/place/[0-9]+/?"), emptySet()),
        "app.catchtable.co.kr" to Route("co.kr.catchtable.android.catchtable_app", Regex("/ct/shop/[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}/?"), emptySet()),
        "www.tabling.co.kr" to Route("com.mealant.tabling", Regex("/place/[a-fA-F0-9]{24}/?"), emptySet()),
        "www.coupang.com" to Route("com.coupang.mobile", Regex("/vp/products/[0-9]{1,30}"), setOf("itemId", "vendorItemId")),
        "booking.naver.com" to Route("com.nhn.android.nmap", Regex("/booking/(3|5|6|10|12|13|16|17)/bizes/[0-9]+(/items/[0-9]+)?/?"), emptySet()),
        "www.yeogi.com" to Route("kr.goodchoice.abouthere", Regex("/domestic-accommodations/[0-9]+/?"), stayFields),
        "nol.yanolja.com" to Route("com.cultsotry.yanolja.nativeapp", Regex("/stay/domestic/[0-9]+/?"), stayFields)
    )
    private val value = Regex("[0-9][0-9-]{0,29}")

    fun packageFor(link: String): String? {
        if (link.length > 2048) return null
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val route = routes[uri.host] ?: return null
        if (uri.scheme != "https" || uri.rawAuthority != uri.host || uri.rawFragment != null ||
            !route.path.matches(uri.rawPath)) return null
        val seen = mutableSetOf<String>()
        for (entry in uri.rawQuery?.split('&').orEmpty()) {
            val name = entry.substringBefore('=')
            if (name !in route.fields || !seen.add(name) || !value.matches(entry.substringAfter('=', ""))) return null
        }
        return route.app
    }
}
