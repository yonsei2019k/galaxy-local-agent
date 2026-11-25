package com.user.localagent

import org.junit.Assert.*
import org.junit.Test

class PublicWorkPlanTest {
    @Test fun noticeUsesTheExistingPublicPlanWithoutExposingHiddenContent(){
        val notice=checkNotNull(publicWorkPlanNotice("네 명의 주말 숙소 총액을 비교할게요. 연락처 user@example.com"))
        assertTrue(notice.contains("네 명의 주말 숙소 총액"))
        assertTrue(notice.contains("잠시만 기다려 주세요"))
        assertFalse(notice.contains("example.com"))
        assertNull(publicWorkPlanNotice("<think>private</think>"))
    }

    @Test fun actualTaskExplanationIsPreservedInsteadOfReplacingItWithStatusText(){
        val plan="가족 네 명의 항공권을 찾는 요청으로 이해했습니다. 날짜를 확인하고 총액과 수하물을 비교하겠습니다."
        assertEquals(plan,publicWorkPlanParts(plan,emptyList()).joinToString(""))
        assertEquals(listOf("지킬 조건: 예산 80만원"),publicWorkPlanParts("",listOf("예산 80만원")))
        assertTrue(publicWorkPlanParts("",emptyList()).isEmpty())
    }
    @Test fun redactsBeforeSplittingThePlan(){
        val goal="가".repeat(96)+" user@example.com을 확인합니다."
        val preview=publicWorkPlanParts(goal,emptyList()).joinToString("")
        assertTrue(preview.contains("[이메일]"))
        assertFalse(preview.contains("example.com"))
        assertTrue(publicWorkPlanParts("가".repeat(98)+"<think>private</think>",emptyList()).isEmpty())
    }
    @Test fun longPublicPlanSurvivesTracePreviewLimitWithoutBreakingUnicode(){
        val plan="조건을 비교합니다😀".repeat(60)
        val parts=publicWorkPlanParts(plan,emptyList())
        assertEquals(plan,parts.joinToString(""))
        assertTrue(parts.all{it.length<=200&&!it.last().isHighSurrogate()&&!it.first().isLowSurrogate()})
    }
}
