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

    const val NAVER_LOGIN_ORIGIN = "https://nid.naver.com"

    fun naverReturnPage(raw: String?): String? {
        val url = https(raw)?.takeIf { it.host in naverServices } ?: return null
        // Start a fresh service-side sign-in instead of saving a redirect query or OAuth state.
        return "https://"+url.host+url.rawPath.ifEmpty { "/" }
    }

    private val authQueryNames=setOf("accesstoken","refreshtoken","idtoken","token","session","sessionid",
        "code","authcode","authorizationcode","state","nonce","oauth","oauthtoken","oauthverifier",
        "redirecturi","redirecturl","returnurl","callback","codechallenge","codeverifier","password","passwd","otp",
        "authorization","credential","apikey","signature","sig","samlrequest","samlresponse","relaystate")

    fun naverTaskPage(raw:String?):String? {
        val start=naverReturnPage(raw)?:return null
        return taskPageWithQuery(start,https(raw)?.rawQuery)
    }

    fun pairedTaskPage(raw:String?,origin:String):String? {
        if(!com.user.localagent.core.WebAgentPolicy.urlAllowed(raw))return null
        val url=https(raw)?:return null
        if("https://"+url.host.lowercase()!=origin)return null
        val path=url.rawPath.ifEmpty { "/" }
        if(origin=="https://hallymedu.co.kr"&&url.rawQuery==null&&url.rawFragment==null&&
            path.lowercase() in setOf("/new_home/my/","/new_home/my/my/"))return "$origin/new_home/my/my/"
        return taskPageWithQuery(origin+path,url.rawQuery)
    }

    private fun taskPageWithQuery(start:String,query:String?):String? {
        if(query==null)return start
        if(query.length>4096)return null
        val taskQuery=query.split('&').filter {parameter->
            val name=runCatching { java.net.URLDecoder.decode(parameter.substringBefore('='),"UTF-8") }.getOrNull()
            val normalized=name?.lowercase()?.filter(Char::isLetterOrDigit)
            !normalized.isNullOrEmpty()&&normalized !in authQueryNames
        }.joinToString("&")
        return if(taskQuery.isEmpty())start else "$start?$taskQuery"
    }

    fun isNaverLoginFrom(requested: URI, actual: String?): Boolean {
        val login = https(actual) ?: return false
        return requested.host in naverServices && login.host == "nid.naver.com" &&
            login.path == "/nidlogin.login"
    }
}
