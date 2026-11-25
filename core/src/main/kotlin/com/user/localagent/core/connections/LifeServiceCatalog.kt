package com.user.localagent.core.connections

import com.user.localagent.core.ToolCall
import kotlinx.serialization.json.JsonPrimitive

/** Fixed phone handoffs to official service pages. */
object LifeServiceCatalog {
    private const val OPEN = "open_url"

    private val urls = mapOf(
        "netflix" to "https://www.netflix.com/",
        "coupang" to "https://www.coupang.com/",
        "coupang_eats" to "https://www.coupangeats.com/",
        "skt" to "https://www.tworld.co.kr/web/home",
        "kt" to "https://my.kt.com/",
        "lguplus" to "https://www.lguplus.com/",
        "srt_booking" to "https://eticket.srail.kr/hpg/hra/01/selectScheduleList.do?pageId=TK0100000000",
        "seoul_reservations" to "https://yeyak.seoul.go.kr/web/main.do",
        "silson24" to "https://www.silson24.or.kr/claim/web/",
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
