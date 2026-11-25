package com.user.localagent.integrations

import java.net.URI
import kotlinx.serialization.json.*

internal object NolConnectionGuide {
    const val name = "야놀자(NOL)"
    const val host = "nol.yanolja.com"
    const val entry = "https://nol.yanolja.com/member/mypage"
    const val request = "야놀자(NOL) 계정을 연결하고 싶어요. 공식 로그인 화면을 열어 주세요."
    const val intro = "NOL의 공식 화면을 열어 볼게요. 가입할 때 쓴 방법을 고르면 돼요. 기억나지 않으면 화면에 나오는 계정 찾기부터 함께 볼게요."

    private val authHosts = setOf(host, "accounts.nol.com", "accounts.kakao.com", "kauth.kakao.com",
        "nid.naver.com", "accounts.google.com", "appleid.apple.com", "account.apple.com")

    fun allowsPage(raw: String?): Boolean {
        val uri = raw?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return uri.scheme == "https" && uri.host in authHosts && uri.port in setOf(-1, 443) && uri.rawUserInfo == null
    }

    private val labels = mapOf("kakao" to "카카오", "naver" to "네이버", "google" to "Google",
        "apple" to "Apple", "email" to "이메일")
    private val instructions = mapOf(
        "entry" to "NOL 화면에서 ‘로그인’을 찾아 눌러 주세요. 화면이 안 뜨면 다시 열거나 휴대폰 브라우저에서 확인할 수 있어요.",
        "login" to "가입할 때 쓴 로그인 방법을 골라 주세요. 기억나지 않으면 공식 화면의 ‘계정 찾기’를 눌러 주세요.",
        "recover" to "NOL 계정을 찾는 화면이에요. 가입할 때 쓴 정보를 확인한 뒤 화면에서 인증을 요청해 주세요.",
        "reset" to "비밀번호를 찾거나 바꾸는 화면이에요. 화면에서 계정을 확인하고, 새 비밀번호가 필요하면 이 보안 화면에서 직접 정해 주세요.",
        "app_approve" to "서비스가 앱에서의 확인을 기다리고 있어요. 휴대폰에 표시된 로그인 요청이 맞는지 보고 확인한 뒤 여기로 돌아오세요.",
        "qr" to "공식 화면에 QR 로그인이 표시됐어요. 계정 앱에서 화면에 나온 방법으로 확인해 주세요.",
        "naver_approve" to "2단계 인증에 등록한 휴대폰의 네이버 앱에 로그인 알림이 오는지 확인해 주세요. 알림에서 본인이 시작한 요청을 허용하면 집순이가 이어서 확인할게요.",
        "naver_otp" to "네이버 앱에서 메뉴 → 설정 → 네이버 OTP를 열어 번호를 확인해 주세요. 이 카드의 네이버 입력칸에 직접 입력하면 돼요.",
        "naver_challenge" to "네이버가 추가 확인을 요청했어요. 이 카드에 열린 공식 화면에서 가능한 인증 방법을 확인해 주세요. 마치면 집순이가 로그인 결과를 확인할게요.",
        "naver_qr" to "네이버 앱에서 공식 화면의 QR 안내에 따라 로그인해 주세요. 같은 휴대폰으로 코드를 읽기 어렵다면 화면에 있는 다른 로그인 방법을 선택할 수 있어요.",
        "code" to "문자로 받은 인증번호를 이 보안 화면에 직접 입력하고 확인을 눌러 주세요.",
        "consent" to "사이트가 동의를 요청했어요. 화면에서 필요한 항목과 선택 항목을 확인한 뒤 직접 선택해 주세요.",
        "provider" to "선택한 계정의 공식 인증 화면이에요. 화면의 안내에 따라 직접 확인해 주세요.",
        "error" to "NOL이 지금 이 화면을 열어 주지 않았어요. 잠시 후 다시 시도하거나 휴대폰 브라우저로 열 수 있어요.",
        "connected" to "NOL이 로그인된 계정 화면을 보여줬어요. 같은 휴대폰 브라우저에서 이 연결을 다시 쓸 수 있어요."
    )
    private val notices = mapOf(
        "expired" to "인증 시간이 지났어요. 화면에서 새 번호를 요청할 수 있어요.",
        "invalid" to "입력한 인증번호를 확인해 주세요. 문자로 받은 최신 번호인지도 살펴보세요.",
        "sent" to "공식 화면에 인증번호를 보냈다고 표시됐어요.",
        "rate_limit" to "인증 요청이 많다고 표시됐어요. 화면에 나온 대기 시간을 확인해 주세요.",
        "no_account" to "이 정보로 가입한 계정을 찾지 못했어요. 가입 때 쓴 번호나 로그인 방법을 다시 확인해 주세요.",
        "required" to "사이트가 필수 입력을 요청했어요. 화면에서 표시된 칸을 확인해 주세요.",
        "bad_login" to "로그인 정보를 확인해 달라고 나왔어요. 가입할 때 쓴 방법과 입력한 내용을 다시 확인해 주세요."
    )

    data class Status(val key:String,val text:String,val notices:List<String>,val siteNotices:List<String> = emptyList(),
        val connected:Boolean=false)

    fun isNaverApproval(host: String?, stage: String?) =
        host == "nid.naver.com" && stage in setOf("naver_approve", "naver_otp", "naver_qr", "naver_challenge")

    fun isNaverApproval(stage: String?) = isNaverApproval("nid.naver.com", stage)

    fun read(raw: String?, result: JsonObject): Status? {
        if (!allowsPage(raw)) return null
        val page = URI(raw)
        fun text(key: String) = (result[key] as? JsonPrimitive)?.contentOrNull
        var stage = text("stage") ?: return null
        if (isNaverApproval(stage) && !isNaverApproval(page.host,stage)) return null
        val provider = text("foundProvider")?.let(labels::get)
        val mask = text("maskedAccount")?.takeIf { it.length<=75 && Regex("[A-Za-z0-9._%+-]{1,24}[*•]{2,}[A-Za-z0-9._%+-]{0,20}@[A-Za-z0-9.-]+[.][A-Za-z]{2,}").matches(it) }
        if (stage in setOf("found", "connected") && page.host !in setOf(host, "accounts.nol.com")) stage = "provider"
        if (stage == "connected" && (page.host != host || page.path.trimEnd('/') !in setOf("/member/mypage", "/mypage"))) stage = "entry"
        val choices = (result["providers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(labels::get) }.distinct()
        val details = (result["notices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(notices::get) }.distinct()
        val plain = when {
            stage == "found" && provider != null ->
                "NOL에서 ${provider}로 가입한 계정${mask?.let { " ($it)" }.orEmpty()}을 찾았어요. 같은 방법으로 로그인해 주세요."
            stage == "found" -> "NOL이 계정 찾기 결과를 보여줬어요. 공식 화면에서 가입에 사용한 로그인 방법을 확인해 주세요."
            stage == "login" && choices.isNotEmpty() -> instructions.getValue(stage) + " 화면에서 확인한 방법은 ${choices.joinToString(", ")}예요."
            else -> instructions[stage] ?: return null
        }
        val siteNotices=(result["additional"] as? JsonArray).orEmpty().take(12).mapNotNull {
            (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf { value->
                value.length in 4..180 && value.none(Char::isISOControl) &&
                    !Regex("[@<>]|https?:|(?:[0-9][ -]?){4}|비밀 ?번호\\s*[:=]|인증번호\\s*[:=]",RegexOption.IGNORE_CASE).containsMatchIn(value)
            }
        }.distinct()
        val guidance = if (stage == "naver_approve") details +
            "알림이 안 오면 네이버 앱의 로그인 상태와 알림 설정을 확인해 주세요. 네이버 화면에서 알림을 최대 3회 다시 보내거나 OTP로 진행할 수 있어요."
            else details
        return Status(stage,plain,guidance,siteNotices,stage=="connected")
    }
}
