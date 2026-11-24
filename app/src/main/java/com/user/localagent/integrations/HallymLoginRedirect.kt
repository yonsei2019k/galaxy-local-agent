package com.user.localagent.integrations

import java.net.URI

/** Upgrade only the official enrolment site's legacy navigation. A POST is never retried. */
internal fun hallymSecureLoginRedirect(url:String,method:String,loginHost:String?):String? {
    val host="hallymedu.co.kr"
    if(method!="GET"||loginHost!=host)return null
    val original=runCatching {URI(url)}.getOrNull()?:return null
    if(!original.scheme.equals("http",ignoreCase=true)||!original.host.equals(host,ignoreCase=true)||
        original.rawUserInfo!=null||original.port !in setOf(-1,80)||original.rawPath.isNullOrBlank()||
        original.rawFragment!=null)return null
    return "https://"+host+original.rawPath+original.rawQuery?.let{"?$it"}.orEmpty()
}
