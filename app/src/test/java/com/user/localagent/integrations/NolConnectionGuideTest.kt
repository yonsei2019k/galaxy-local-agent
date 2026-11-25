package com.user.localagent.integrations

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class NolConnectionGuideTest {
    private fun status(address:String,stage:String,vararg notes:String) =
        NolConnectionGuide.read(address,buildJsonObject {
            put("stage",stage)
            put("foundProvider","kakao")
            put("maskedAccount","demo****@example.com")
            put("notices",JsonArray(notes.map(::JsonPrimitive)))
        })

    @Test fun onlyOfficialTopLevelPagesCanProvideGuidance() {
        assertFalse(NolConnectionGuide.allowsPage("https://nol.yanolja.com.example.org/member/mypage"))
        assertFalse(NolConnectionGuide.allowsPage("https://other@nol.yanolja.com/member/mypage"))
        assertFalse(NolConnectionGuide.allowsPage("http://nol.yanolja.com/member/mypage"))
        assertNull(status("https://example.org/","connected"))
        assertFalse(checkNotNull(status("https://accounts.kakao.com/login","connected")).connected)
    }

    @Test fun connectionNeedsTheObservedNolAccountPage() {
        assertTrue(checkNotNull(status(NolConnectionGuide.entry,"connected")).connected)
        assertFalse(checkNotNull(status("https://nol.yanolja.com/member/login","connected")).connected)
        assertFalse(checkNotNull(status(NolConnectionGuide.entry,"code","sent")).connected)
    }

    @Test fun naverApprovalRemainsPendingAndShowsOfficialFallback() {
        val naver="https://nid.naver.com/nidlogin.login"
        val approval=checkNotNull(status(naver,"naver_approve"))
        assertFalse(approval.connected)
        assertTrue(approval.notices.single().contains("최대 3회"))
        assertTrue(checkNotNull(status(naver,"naver_otp")).text.contains("설정 → 네이버 OTP"))
        assertNull(status(NolConnectionGuide.entry,"naver_approve"))
    }

    @Test fun explainsOnlyVerifiedProviderAndUsesAllowlistedNotices() {
        val found=checkNotNull(status(NolConnectionGuide.entry,"found","expired","provider_supplied_instruction"))
        assertTrue(found.text.contains("카카오"))
        assertTrue(found.text.contains("demo****@example.com"))
        assertEquals(1,found.notices.size)
        val raw=buildJsonObject {put("stage","found");put("foundProvider","kakao");put("maskedAccount","full@example.com")}
        assertFalse(checkNotNull(NolConnectionGuide.read(NolConnectionGuide.entry,raw)).text.contains("full@example.com"))
    }
}
