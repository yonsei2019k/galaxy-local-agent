package com.user.localagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic masking of credential VALUES in text sent to a cloud model.
 * This is not intent classification: only secret-looking values are replaced,
 * ordinary phone numbers, emails, times and amounts are preserved.
 */
object CredentialRedaction {
    const val MASK = "[가림]"

    data class Result(val text: String, val count: Int)

    fun notice(count: Int): String = "자격증명으로 보이는 값 ${count}개는 기기 밖으로 보내지 않았습니다."

    private const val NOT_MASK = """(?!\[가림\])"""
    private val pem = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""")
    private val prefixedKey = Regex("""\b(?:sk|pk|rk|ghp|gho|ghu|ghs|github_pat|xox[abprs]|glpat|AKIA|AIza)(?=[-_A-Za-z0-9]*[\d_])[-_A-Za-z0-9]{16,}""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}""")
    private val bearer = Regex("""(\bbearer\s+)$NOT_MASK([A-Za-z0-9._~+/=-]{8,})""", RegexOption.IGNORE_CASE)
    private val englishLabel = Regex(
        """(\b(?:api[_-]?key|access[_-]?token|token|secret|password|passwd|passphrase|authorization)\s*[:=]\s*["']?)(?!bearer\b)$NOT_MASK([^\s"',;]{6,})""",
        RegexOption.IGNORE_CASE,
    )
    private val standaloneWrongPasswordNotice = Regex("비밀번호가 틀렸습니다[.!]?")
    private const val COMMON_WORDS = "변경|재설정|확인|입력|찾기|설정|정책|오류|화"
    private val koreanLabel = Regex(
        """((?:비밀\s*번호|비번|패스워드|암호|(?<![A-Za-z])PIN|핀\s*번호|보안\s*코드|복구\s*(?:키|코드))\s*(?:[:=]|은|는|이|가)?\s*)(?!(?:$COMMON_WORDS))$NOT_MASK([^\s,;]{4,})""",
    )
    private val verification = Regex(
        """((?:인증\s*(?:번호|코드)|OTP|승인\s*번호|확인\s*코드|verification\s*code|one[- ]time\s*(?:password|code))[^\d\n]{0,12})(\d{4,8})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val card = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")

    private val credentialField = Regex(
        """(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|passwd|passphrase|authorization|otp|pin|verification[_-]?code|비밀\s*번호|비번|인증\s*(?:번호|코드))""",
        RegexOption.IGNORE_CASE,
    )

    /** Preserve structured tool results so masked evidence can still be decoded. */
    fun forCloudToolResult(text: String): String {
        val first = text.firstOrNull { !it.isWhitespace() }
        val structured = if (first == '{' || first == '[')
            runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
        if (structured is JsonObject || structured is JsonArray) {
            val masked = maskJson(checkNotNull(structured), 0)
            return if (masked == structured) text else masked.toString()
        }
        val masked = redact(text)
        return if (masked.count == 0) text else masked.text + "\n" + notice(masked.count)
    }

    private fun maskJson(value: JsonElement, depth: Int): JsonElement {
        if (depth == 32) return JsonPrimitive(MASK)
        return when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, field) ->
                if (credentialField.matches(key) && field != JsonNull && field != JsonPrimitive(""))
                    JsonPrimitive(MASK) else maskJson(field, depth + 1)
            })
            is JsonArray -> JsonArray(value.map { maskJson(it, depth + 1) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(redact(value.content).text) else value
        }
    }

    fun redact(text: String): Result {
        if (text.isBlank() || standaloneWrongPasswordNotice.matches(text.trim())) {
            return Result(text, 0)
        }
        var count = 0
        var out = text
        out = pem.replace(out) { count++; MASK }
        out = prefixedKey.replace(out) { count++; MASK }
        out = jwt.replace(out) { count++; MASK }
        out = bearer.replace(out) { count++; it.groupValues[1] + MASK }
        out = englishLabel.replace(out) { count++; it.groupValues[1] + MASK }
        out = koreanLabel.replace(out) { count++; it.groupValues[1] + MASK }
        out = verification.replace(out) { count++; it.groupValues[1] + MASK }
        out = card.replace(out) { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 13..19 && luhn(digits)) { count++; MASK } else m.value
        }
        return Result(out, count)
    }

    private fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) { d *= 2; if (d > 9) d -= 9 }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}
