package com.user.localagent.core

import java.time.ZonedDateTime
import java.util.Locale

/** One host choice. A source name alone cannot turn public research into a private-data read. */
sealed interface RequestExecutionPlan {
    data class PublicResearch(val plan: WebTaskPlan.WebResearch) : RequestExecutionPlan
    data class NativeAnalysis(val plan: AssistantTaskPlan) : RequestExecutionPlan
    data object Model : RequestExecutionPlan
}

object RequestExecutionPlanner {
    fun plan(text: String, previousUserRequests: List<String> = emptyList(), now: ZonedDateTime): RequestExecutionPlan {
        if (RequestActionSemantics.explicitLocalWriteTool(text) != null) return RequestExecutionPlan.Model
        if (ProjectPaths.isBoundFileRequest(text) || DocumentTaskPlanner.plan(text) != null) return RequestExecutionPlan.Model
        val web = WebTaskPlanner.plan(text, previousUserRequests)
        if (web is WebTaskPlan.WebResearch) return RequestExecutionPlan.PublicResearch(web)
        val native = AssistantTaskPlanner.plan(text, previousUserRequests, now)
        return if (native == AssistantTaskPlan.NoPlan) RequestExecutionPlan.Model else RequestExecutionPlan.NativeAnalysis(native)
    }
}

/** Shared action semantics; this is neither permission to read private records nor a completion claim. */
internal object RequestActionSemantics {
    private val quotes = Regex(""""(?:\\.|[^"\\])*"|'[^']*'|“[^”]*”|‘[^’]*’|`[^`]*`""")
    private val documentation = Regex("""방법|방식|수단|대안|사용법|설정법|설정방법|작동원리|개발문서|공식문서|매뉴얼|가이드|howto|wayto|method|approach|alternative|documentation|setupguide|manual|(?<![a-z])api(?![a-z])|(?<![a-z])sdk(?![a-z])|오류해결|도움말""")
    private val privateContent = Regex("""(?:메시지|메세지|문자|메일|대화|녹음|전사문|파일|연락처)(?:의)?(?:내용|원문)|(?:메시지|메세지|문자|메일|대화|녹음|전사문|파일|연락처)(?:들|기록|내역|자료)?(?:전체|전부|모두|모든|최근|과거)?(?:을|를)?(?:에서|중|기반|참고|바탕|분석해서|분석하고|읽고|읽어서|찾아|조회해|검색해)|(?:내용|원문)(?:을|를)?(?:기반|참고|바탕)|개인정보|(?:my|our)(?:kakaotalk|kakao|telegram|gmail)?(?:messages?|chats?|emails?|notes|files|contacts|calendar)|(?:messages?|chats?|conversation|emails?|notes|files)(?:content|history|records)|(?:from|using|basedon)(?:my|our|the)?(?:kakaotalk|kakao|telegram|gmail)?(?:messages?|chats?|emails?|notes|files)|(?:read|review|analyze)(?:my|our|the)?(?:kakaotalk|kakao|telegram|gmail)?(?:messages?|chats?|emails?|files)""")
    private val connectionManagement = Regex("""(?:연결|연동)(?:상태|여부|권한|설정|해|하자|하고싶)|(?:연결|연동)(?:을|를)?(?:해|설정|시작)|(?:연결|연동)(?:이|은|는)?(?:되어|돼|됐)|(?:알림접근|접근권한)(?:상태|설정|허용|켜)""")
    private val publicSubjects = listOf(
        "카카오톡" to Regex("카카오톡|카카오|카톡|kakaotalk|kakao"),
        "텔레그램" to Regex("텔레그램|telegram"),
        "메디통" to Regex("메디통|meditong"),
        "Gmail" to Regex("gmail|지메일"),
        "네이버 메일" to Regex("네이버메일"),
        "이메일" to Regex("이메일|메일"),
        "구글 캘린더" to Regex("구글캘린더|googlecalendar"),
    )
    private val excludedSubject = Regex("""(?:카카오톡|카카오|카톡|kakaotalk|kakao|텔레그램|telegram|메디통|meditong|gmail|지메일|네이버메일|이메일|메일|구글캘린더)(?:의|에|은|는|을|를|이|가)?(?:연결|연동|앱|서비스)?(?:은|는|을|를|이|가)?(?:말고|아니라|제외하고|빼고)""")

    fun ownerInput(raw: String): String {
        if (raw.isBlank() || raw.length > 6000) return ""
        var text = raw.trim()
        if (Regex("""^(?:선택한|가져온|첨부한)\s*(?:문서|파일|텍스트|메시지|대화\s*자료)""").containsMatchIn(text)) return ""
        listOf("```", "\"\"\"", "<document>", "<untrusted>").map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()?.let { text = text.substring(0, it) }
        text = text.lineSequence().filterNot { it.trimStart().startsWith('>') }.joinToString(" ")
        Regex("""(?:아래|다음)(?:의)?\s*(문장|텍스트|문서|내용|대화|인용|로그|메시지)(?:를|을|는|은|:|\s)""").find(text)?.let { text = text.substring(0, it.range.first) }
        return text.trim()
    }

    fun ownerAuthority(raw: String): String = ownerInput(raw).replace(quotes, " ").lowercase(Locale.ROOT)
    fun ownerText(raw: String): String = ownerAuthority(raw).replace(Regex("\\s+"), "")

    fun explicitLocalWriteTool(raw: String): String? {
        val input = ownerInput(raw)
        val boundary = input.indexOfFirst { it == ':' || it == '：' }
        if (boundary < 0) return null
        val command = input.substring(0, boundary).lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        if (command == "교정") return "save_memory"
        val target = when {
            Regex("^메모(?:를|로|에)?").containsMatchIn(command) -> "note"
            Regex("^기억(?:을|으로|에)?").containsMatchIn(command) -> "memory"
            else -> return null
        }
        return when {
            Regex("수정|고쳐|변경").containsMatchIn(command) && target == "note" -> "update_note"
            Regex("저장|적어|기록|남겨|추가|기억해").containsMatchIn(command) -> "save_$target"
            else -> null
        }
    }

    fun hasDocumentation(authority: String): Boolean = documentation.containsMatchIn(authority)
    fun usesPrivateContent(authority: String): Boolean = privateContent.containsMatchIn(authority)
    fun isConnectionManagement(authority: String): Boolean = connectionManagement.containsMatchIn(authority)
    fun publicSubject(authority: String): String? = publicSubjects.firstOrNull { it.second.containsMatchIn(authority.replace(excludedSubject, "")) }?.first
    fun excludesSubject(authority: String, subject: String): Boolean = publicSubjects.firstOrNull { it.first == subject }?.second?.let { names -> excludedSubject.findAll(authority).any { names.containsMatchIn(it.value) } } == true
    fun nonAnalysisRequest(raw: String): Boolean {
        if (explicitLocalWriteTool(raw) != null) return false
        val authority = ownerText(raw)
        return authority.isNotEmpty() && !usesPrivateContent(authority) &&
            (hasDocumentation(authority) || isConnectionManagement(authority))
    }
}
