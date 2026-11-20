package com.user.localagent.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CredentialRedactionTest {
    private fun one(text: String, secret: String) {
        val r = CredentialRedaction.redact(text)
        assertFalse(r.text, r.text.contains(secret))
        assertTrue(r.text.contains(CredentialRedaction.MASK))
        assertEquals(r.text, 1, r.count)
    }

    @Test fun pemBlock() {
        val t = "a\n-----BEGIN PRIVATE KEY-----\nFAKEFAKE1234\nFAKE5678\n-----END PRIVATE KEY-----\nb"
        one(t, "FAKEFAKE1234")
        assertEquals("a\n[가림]\nb", CredentialRedaction.redact(t).text)
    }

    @Test fun prefixedKey() = one("키 sk-FAKEFAKEFAKEFAKE1234 끝", "FAKEFAKEFAKEFAKE1234")

    @Test fun jwt() = one("t eyJFAKEFAKEFAKE.FAKEFAKEFAKE1.FAKE12345 x", "FAKEFAKEFAKE1")

    @Test fun englishLabel() {
        one("password: FAKEpass123", "FAKEpass123")
        assertEquals("api_key=[가림]", CredentialRedaction.redact("api_key=FAKEKEY123").text)
        one("Authorization: Bearer FAKEtoken12345", "FAKEtoken12345")
        one("use Bearer FAKEtoken12345 now", "FAKEtoken12345")
    }

    @Test fun koreanLabel() {
        one("비밀번호: FAKE1234", "FAKE1234")
        one("비밀번호는 test9999 입니다", "test9999")
    }

    @Test fun verificationCode() {
        val r = CredentialRedaction.redact("[Web발신] 인증번호 [482913]를 입력")
        assertEquals("[Web발신] 인증번호 [[가림]]를 입력", r.text)
        assertEquals(1, r.count)
    }

    @Test fun cardLuhn() {
        one("카드 4111 1111 1111 1111 결제", "4111 1111 1111 1111")
        val bad = CredentialRedaction.redact("카드 4111 1111 1111 1112")
        assertEquals("카드 4111 1111 1111 1112", bad.text)
        assertEquals(0, bad.count)
    }

    @Test fun ordinaryValuesPreserved() {
        listOf(
            "010-0000-0000", "owner@example.com", "14:30", "25,000원", "주문번호 12345678",
            "비밀번호 변경 안내", "the password policy changed", "token count 12",
        ).forEach {
            val r = CredentialRedaction.redact(it)
            assertEquals(it, r.text)
            assertEquals(it, 0, r.count)
        }
    }

    @Test fun mixedAndIdempotentAndNotice() {
        val t = "password: FAKEpass123 그리고 인증번호 123456 전화 010-0000-0000"
        val r = CredentialRedaction.redact(t)
        assertEquals(2, r.count)
        assertTrue(r.text.contains("010-0000-0000"))
        assertEquals(0, CredentialRedaction.redact(r.text).count)
        assertEquals("자격증명으로 보이는 값 2개는 기기 밖으로 보내지 않았습니다.", CredentialRedaction.notice(2))
        assertEquals(CredentialRedaction.Result("  ", 0), CredentialRedaction.redact("  "))
    }

    @Test fun structuredToolResultsKeepValidJsonAndMaskCredentialFields() {
        val input = """{"items":[{"password":"a \"quoted\" value","otp":1234,"body":"인증번호 654321","phone":"010-0000-0000"}],"status":"ok"}"""
        val expected = """{"items":[{"password":"[가림]","otp":"[가림]","body":"인증번호 [가림]","phone":"010-0000-0000"}],"status":"ok"}"""
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(CredentialRedaction.forCloudToolResult(input)))
        val harmless = """{ "status": "ok", "password": null }"""
        assertEquals(harmless, CredentialRedaction.forCloudToolResult(harmless))
    }
}
