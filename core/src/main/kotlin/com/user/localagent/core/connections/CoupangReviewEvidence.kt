package com.user.localagent.core.connections

import java.net.URI

/** Matches two observed records. Product titles and photos cannot supply missing variant identifiers. */
object CoupangReviewEvidence {
    const val ORDERS = "https://mc.coupang.com/ssr/desktop/order/list"
    const val REVIEWABLE = "https://my.coupang.com/productreview/reviewable"
    const val OPEN_TOOL = "open_coupang_review_workspace"

    enum class Page { ORDERS, REVIEWABLE }
    data class Variant(val productId: String, val itemId: String, val vendorItemId: String) {
        val url: String get() =
            "https://www.coupang.com/vp/products/$productId?itemId=$itemId&vendorItemId=$vendorItemId"
    }

    private val productPath = Regex("/vp/products/([0-9]{1,24})")
    private val numericId = Regex("[0-9]{1,24}")

    private fun official(raw: String): URI? = runCatching { URI(raw) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.host in setOf("www.coupang.com", "mc.coupang.com",
            "my.coupang.com", "login.coupang.com", "m.coupang.com") &&
            it.rawUserInfo == null && it.port in setOf(-1, 443)
    }

    fun navigationAllowed(raw: String): Boolean = official(raw) != null

    fun page(raw: String): Page? {
        val uri = official(raw) ?: return null
        return when {
            uri.host == "mc.coupang.com" && uri.rawPath.trimEnd('/') == "/ssr/desktop/order/list" -> Page.ORDERS
            uri.host == "my.coupang.com" && uri.rawPath.trimEnd('/') == "/productreview/reviewable" -> Page.REVIEWABLE
            else -> null
        }
    }

    fun nextPage(current: String, target: String): String? {
        val sourcePage = page(current) ?: return null
        if (page(target) != sourcePage) return null
        val before = official(current) ?: return null
        val after = official(target) ?: return null
        val key = Regex("page(?:Number|Index)?=([0-9]{1,4})")
        if (after.rawFragment != null) return null
        val afterQuery = key.matchEntire(after.rawQuery.orEmpty()) ?: return null
        val beforeQuery = before.rawQuery?.let { key.matchEntire(it) ?: return null }
        if (beforeQuery != null && beforeQuery.value.substringBefore('=') != afterQuery.value.substringBefore('=')) return null
        val oldNumber = beforeQuery?.groupValues?.get(1)?.toInt() ?: 1
        return target.takeIf { afterQuery.groupValues[1].toInt() == oldNumber + 1 }
    }

    fun variant(raw: String): Variant? {
        val uri = official(raw) ?: return null
        if (uri.host !in setOf("www.coupang.com", "m.coupang.com")) return null
        val product = productPath.matchEntire(uri.rawPath)?.groupValues?.get(1) ?: return null
        val fields = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }
        fun id(key: String) = fields.filter { it.first == key }.singleOrNull()?.second
            ?.takeIf(numericId::matches)
        return Variant(product, id("itemId") ?: return null, id("vendorItemId") ?: return null)
    }
}
