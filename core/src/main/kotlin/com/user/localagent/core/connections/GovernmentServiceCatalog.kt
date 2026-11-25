package com.user.localagent.core.connections

import com.user.localagent.core.Argument
import com.user.localagent.core.Risk
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolDefinition
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import java.net.URI
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed phone entry points. Selecting one does not authorize reading or changing an account. */
enum class GovernmentService(val id: String, val label: String, val url: String, val nextStep: String, val guideSubject: String? = null) {
    CIVIL("gov24", "정부24", "https://www.gov.kr/", "민원명을 선택하고 신청·발급 요건을 확인해 주세요."),
    RESIDENCY("resident_certificate", "정부24 주민등록표 등·초본",
        "https://www.gov.kr/mw/AA020InfoCappView.do?CappBizCD=13100000015", "신청할 서류와 공개할 항목을 선택해 주세요.", "주민등록표"),
    LOCAL_TAX_CLEARANCE("local_tax_clearance", "정부24 지방세 납세증명서",
        "https://www.gov.kr/mw/AA020InfoCappView.do?CappBizCD=13100000056", "지방세 납세증명서의 신청 자격과 방법을 확인해 주세요.", "지방세납세증명"),
    LOCAL_TAX_RECEIPT("local_tax_receipt", "정부24 지방세 납부확인서",
        "https://www.gov.kr/mw/AA020InfoCappView.do?CappBizCD=13110000017", "확인할 지방세 납부 건과 신청 방법을 확인해 주세요.", "지방세납부확인"),
    VEHICLE_REGISTER("vehicle_register", "정부24 자동차 등록원부",
        "https://www.gov.kr/mw/AA020InfoCappView.do?CappBizCD=15000000334", "필요한 자동차 등록원부의 종류와 열람·발급 방법을 확인해 주세요.", "자동차등록원부"),
    FAMILY("family_certificate", "대법원 전자가족관계등록시스템",
        "https://efamily.scourt.go.kr/", "발급할 가족관계 증명서의 종류와 발급 대상을 확인해 주세요."),
    NATIONAL_TAX("hometax", "국세청 홈택스", "https://www.hometax.go.kr/", "납부·고지·환급에서 해당 국세 고지 건을 확인해 주세요."),
    LOCAL_TAX("wetax", "위택스", "https://www.wetax.go.kr/", "지방세 또는 세외수입의 부과 기관과 고지 건을 확인해 주세요."),
    SEOUL_TAX("seoul_etax", "서울시 ETAX", "https://etax.seoul.go.kr/", "서울시 고지 건의 기관·금액과 납부기한을 확인해 주세요."),
    VEHICLE("vehicle_inspection", "한국교통안전공단 사이버검사소",
        "https://www.cyberts.kr/", "자동차검사 예약에서 차량, 검사 종류와 검사소·시간을 확인해 주세요."),
    TRAFFIC("traffic_fine", "경찰청 교통민원24(이파인)", "https://www.efine.go.kr/",
        "최근 단속, 미납과태료 또는 미납범칙금 중 해당 메뉴를 확인해 주세요."),
    CRIMINAL("criminal_fine", "형사사법포털", "https://www.kics.go.kr/", "벌과금 조회에서 본인 고지와 납부 내역을 확인해 주세요."),
    GIRO("giro", "금융결제원 인터넷지로", "https://www.giro.or.kr/",
        "부과 기관이 지정한 경우 해당 고지 건과 납부 내용을 확인해 주세요."),
}

object GovernmentServiceCatalog {
    const val GET = "get_government_service"
    const val OPEN = "open_government_service"

    private val choices = """
        Select one service ID from the current request. gov24 (정부24 or old 민원24), resident_certificate (주민등록 등본·초본),
        local_tax_clearance (지방세 납세증명), local_tax_receipt (지방세 납부확인), vehicle_register (자동차등록원부),
        family_certificate (법원 가족관계증명), hometax (국세, 홈택스·손택스), wetax (지방세·세외수입),
        seoul_etax (서울시 고지), vehicle_inspection (자동차검사), traffic_fine (경찰 단속·교통민원24·이파인),
        criminal_fine (형사 벌과금), giro (only when the official notice directs to 인터넷지로).
        If the issuer or fine type is ambiguous, ask which. Do not guess or pass identity, account or notice numbers.
    """.trimIndent().replace('\n', ' ')

    private val arguments = mapOf("service" to Argument("string", maxLength = 40, description = choices))
    val definitions = listOf(
        ToolDefinition(GET, Risk.LOW, arguments + ("inspect" to Argument("boolean", required = false)),
            "Return the fixed official Korean service link and next step. Set inspect=true to read the official public guide on the phone for resident_certificate, local_tax_clearance, local_tax_receipt or vehicle_register. " +
            "This does not read private records or verify submission or payment. " + choices, timeoutMillis = 45_000),
        ToolDefinition(OPEN, Risk.LOW, arguments,
            "When the owner asks to open or continue on the phone, hand off the fixed official service page. " +
            "The owner completes authentication in the official service. Dispatch is not a successful lookup, reservation, submission or payment. " + choices),
    )

    fun target(call: ToolCall): GovernmentService {
        require(call.name == GET || call.name == OPEN)
        val serviceId = (call.arguments["service"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val allowed = if (call.name == GET) setOf("service", "inspect") else setOf("service")
        require(serviceId != null && call.arguments.keys.all { it in allowed })
        require(call.arguments["inspect"] == null || call.arguments["inspect"] in setOf(JsonPrimitive(true), JsonPrimitive(false)))
        return requireNotNull(GovernmentService.entries.firstOrNull { it.id == serviceId }) {
            "지원 목록의 정부 서비스인지 확인해 주세요."
        }
    }

    fun inspectRequested(call: ToolCall): Boolean =
        call.name == GET && call.arguments["inspect"] == JsonPrimitive(true)

    fun guideObservation(service: GovernmentService, finalUrl: String, title: String, text: String): List<String>? {
        val subject = service.guideSubject ?: return null
        val entry = URI(service.url)
        val observed = runCatching { URI(finalUrl) }.getOrNull() ?: return null
        val matchingCodes = observed.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "CappBizCD" }
        if (!observed.scheme.equals("https", true) || !observed.host.equals(entry.host, true) ||
            observed.rawUserInfo != null || observed.port !in setOf(-1, 443) ||
            observed.rawPath != entry.rawPath || matchingCodes != listOf(entry.rawQuery)) return null
        if (subject !in title.filterNot(Char::isWhitespace) || "발급" !in title ||
            subject !in text.filterNot(Char::isWhitespace)) return null
        val confirmedTopics = listOf("신청방법", "신청자격", "발급서류", "구비서류", "수수료").filter { it in text }
        return confirmedTopics.takeIf { "신청방법" in it && "수수료" in it }
    }

    fun read(call: ToolCall): ToolResult {
        require(call.name == GET)
        val service = target(call)
        val content = buildJsonObject {
            put("service", service.id)
            put("institution", service.label)
            put("officialUrl", service.url)
            put("status", "official_link")
            put("nextStep", service.nextStep)
            put("authentication", "필요한 본인 인증은 휴대폰에서 진행해 주세요.")
        }.toString()
        return ToolResult(call.id, true, content, VerificationState.VERIFIED, "등록된 공식 서비스 주소 확인")
    }
}
