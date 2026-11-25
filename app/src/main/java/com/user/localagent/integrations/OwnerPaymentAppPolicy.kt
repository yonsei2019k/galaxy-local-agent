package com.user.localagent.integrations

import java.net.URI

internal object OwnerPaymentAppPolicy {
    const val issuerPackage = "net.ib.android.smcard"
    private val checkoutHosts = setOf("pg-web.nicepay.co.kr", "web.nicepay.co.kr")
    private val issuerSchemes = setOf("monimopay", "monimopayauth")

    fun trustedCheckout(raw: String?): Boolean {
        val url = raw?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return url.scheme == "https" && url.host?.lowercase(java.util.Locale.ROOT) in checkoutHosts &&
            url.rawUserInfo == null && url.port in setOf(-1, 443)
    }

    fun issuerData(source: String?, target: String, mainFrame: Boolean, ownerGesture: Boolean): String? {
        if (!mainFrame || !ownerGesture || !trustedCheckout(source) || target.length !in 1..4096) return null
        val data = if (target.startsWith("intent://")) {
            val marker = "#Intent;"
            val separator = target.indexOf(marker)
            if (separator < "intent://".length) return null
            val metadata = target.substring(separator + marker.length).split(';')
            if (metadata.size != 3 || metadata.last() != "end") return null
            val scheme = metadata.singleOrNull { it.startsWith("scheme=") }?.removePrefix("scheme=") ?: return null
            if (scheme !in issuerSchemes || "package=$issuerPackage" !in metadata) return null
            "$scheme:${target.substring("intent:".length, separator)}"
        } else target
        val uri = runCatching { URI(data) }.getOrNull() ?: return null
        return data.takeIf {
            uri.scheme in issuerSchemes && it.startsWith("${uri.scheme}://") &&
                uri.rawUserInfo == null && uri.rawFragment == null
        }
    }
}
