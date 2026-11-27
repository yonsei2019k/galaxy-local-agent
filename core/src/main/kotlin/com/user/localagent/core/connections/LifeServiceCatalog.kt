package com.user.localagent.core.connections

import com.user.localagent.core.ToolCall
import kotlinx.serialization.json.JsonPrimitive

/** Fixed phone handoffs to official service pages. */
object LifeServiceCatalog {
    private const val OPEN = "open_url"

    private data class Site(val label:String,val url:String,val listedElsewhere:Boolean=false)

    private val sites = mapOf(
        "netflix" to Site("넷플릭스","https://www.netflix.com/",listedElsewhere=true),
        "coupang" to Site("쿠팡","https://www.coupang.com/",listedElsewhere=true),
        "coupang_eats" to Site("쿠팡이츠","https://www.coupangeats.com/",listedElsewhere=true),
        "skt" to Site("SK텔레콤 T world","https://www.tworld.co.kr/web/home"),
        "kt" to Site("마이 케이티","https://my.kt.com/"),
        "lguplus" to Site("LG유플러스","https://www.lguplus.com/"),
        "srt_booking" to Site("SRT","https://eticket.srail.kr/hpg/hra/01/selectScheduleList.do?pageId=TK0100000000",listedElsewhere=true),
        "seoul_reservations" to Site("서울시 공공서비스 예약","https://yeyak.seoul.go.kr/web/main.do"),
        "silson24" to Site("실손24","https://www.silson24.or.kr/claim/web/"),
        "insurance_lookup" to Site("내보험찾아줌","https://cont.insure.or.kr/cont_web/"),
        "koreanair" to Site("대한항공","https://www.koreanair.com/"),
        "asiana" to Site("아시아나항공","https://flyasiana.com/"),
        "jejuair" to Site("제주항공","https://www.jejuair.net/"),
        "jinair" to Site("진에어","https://www.jinair.com/"),
        "trinityairways" to Site("트리니티항공","https://www.trinityairways.com/"),
        "naver_mail" to Site("네이버 메일","https://mail.naver.com/",listedElsewhere=true),
        "kakao_support" to Site("카카오 고객센터","https://cs.kakao.com/"),
    )

    val serviceLabels:List<String> = sites.values.filterNot(Site::listedElsewhere).map(Site::label)

    fun url(call: ToolCall): String {
        require(call.name == OPEN && call.arguments.keys == setOf("service"))
        val service = (call.arguments["service"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return requireNotNull(sites[service]) { "공식 연결 목록에서 서비스를 골라 주세요." }.url
    }
}
