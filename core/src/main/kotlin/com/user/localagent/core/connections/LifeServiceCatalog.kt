package com.user.localagent.core.connections

import com.user.localagent.core.ToolCall
import kotlinx.serialization.json.JsonPrimitive

/** Fixed phone handoffs to official service pages. */
object LifeServiceCatalog {
    private const val OPEN = "open_url"

    private val urls = mapOf(
        "skt" to "https://www.tworld.co.kr/web/home",
        "kt" to "https://my.kt.com/",
        "lguplus" to "https://www.lguplus.com/",
        "insurance_lookup" to "https://cont.insure.or.kr/cont_web/",
        "koreanair" to "https://www.koreanair.com/",
        "asiana" to "https://flyasiana.com/",
        "jejuair" to "https://www.jejuair.net/",
        "jinair" to "https://www.jinair.com/",
        "trinityairways" to "https://www.trinityairways.com/",
        "naver_mail" to "https://mail.naver.com/",
        "kakao_support" to "https://cs.kakao.com/",
    )

    fun url(call: ToolCall): String {
        require(call.name == OPEN && call.arguments.keys == setOf("service"))
        val service = (call.arguments["service"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return requireNotNull(urls[service]) { "공식 연결 목록에서 서비스를 골라 주세요." }
    }
}
