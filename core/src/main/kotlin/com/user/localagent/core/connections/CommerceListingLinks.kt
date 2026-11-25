package com.user.localagent.core.connections

import java.net.URI

/** Only these listing links may prefer a merchant app. Keep the original URI for handoff. */
object CommerceListingLinks {
    private data class Route(val app: String, val path: Regex, val fields: Set<String>)
    private val stayFields = setOf("checkIn", "checkOut", "checkInDate", "checkOutDate", "adultCount", "childCount", "roomTypeId", "ratePlanId")
    private val routes = mapOf(
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
