package com.user.localagent.web

import java.net.URI
import com.user.localagent.core.BrowserNavigationRule

/** The browser host supplies only a fresh cookie for this particular observed TLS document. */
internal class BoundPdfGet(val target: String, private val freshCookie: suspend () -> String) {
    suspend fun headerFor(destination: String): String {
        check(target == destination) { "선택한 PDF 주소가 변경됐어요." }
        val value = freshCookie()
        check(value.length in 1..8192 && value.all { it.code in 0x20..0x7e }) { "이 문서의 로그인 상태를 확인할 수 없어요." }
        return "Cookie: $value\r\n"
    }

    companion object {
        fun eligible(source: String, target: String): Boolean {
            if (target.isBlank() || !BrowserNavigationRule.sameSiteLink(source,target)) return false
            val path = URI(target).rawPath
            return path.endsWith(".pdf",ignoreCase=true) && path.none { it in "%;\\" } &&
                path.split('/').none { it=="." || it==".." } && !path.contains("//")
        }
    }
}
