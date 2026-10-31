package com.user.localagent.analysis

import org.junit.Assert.*
import org.junit.Test

class PrivateAnalysisReportBudgetTest {
    @Test fun smallReportKeepsCanonicalWholeBlocksAndHonestCounts() {
        val blocks=listOf("1. 원문 그대로🙂\n","2. 두 번째 인용\n")
        val result=PrivateAnalysisReportBudget.build("전체 질문과 조건\n",blocks.asSequence(),2,"추가 설명\n",4096)
        assertEquals(2,result.includedFindings);assertEquals(0,result.omittedFindings);assertTrue(result.complete)
        assertTrue(result.text.startsWith("전체 질문과 조건\n"+blocks.joinToString("")))
        assertTrue(result.text.contains("이 파일에 수록: 2 / 2"));assertEquals(result.text.toByteArray(Charsets.UTF_8).size,result.utf8Bytes)
    }
    @Test fun byteBudgetOmitsWholeTrailingFindingsWithoutSplittingQuotes() {
        val blocks=(0..9).map {"BEGIN-$it "+"가😀".repeat(45)+" END-$it\n"}
        val result=PrivateAnalysisReportBudget.build("질문 보존\n",blocks.asSequence(),10,maxBytes=2048)
        assertTrue(result.includedFindings in 1..9);assertEquals(10-result.includedFindings,result.omittedFindings)
        assertFalse(result.complete);assertTrue(result.utf8Bytes<=2048)
        for(index in blocks.indices)assertEquals(index<result.includedFindings,result.text.contains(blocks[index]))
        assertFalse(result.text.contains("BEGIN-${result.includedFindings} "))
        assertTrue(result.text.contains("파일 크기 제한으로 생략: ${result.omittedFindings}"))
        assertEquals(result.text.toByteArray(Charsets.UTF_8).size,result.utf8Bytes)
    }
    @Test fun omittedTailIsNotRenderedAfterTheFirstBlockCannotFit() {
        var rendered=0
        val blocks=sequence {rendered++;yield("0 "+"x".repeat(2000)+"\n");error("omitted tail must never be evaluated")}
        val result=PrivateAnalysisReportBudget.build("header\n",blocks,20000,maxBytes=1024)
        assertEquals(1,rendered);assertEquals(0,result.includedFindings);assertEquals(20000,result.omittedFindings)
        assertFalse(result.complete)
    }
    @Test fun largeValidFindingCardinalityFitsDefault20MiBWithoutDroppingJournalItems() {
        val total=21848
        val blocks=sequence {repeat(total){index->yield("$index. "+"f".repeat(500)+"\n원문: "+"q".repeat(500)+"\n근거 ID: "+"a".repeat(64)+"\n\n")}}
        val result=PrivateAnalysisReportBudget.build("합성 전체 자료 분석\n",blocks,total)
        assertTrue(result.utf8Bytes<=PrivateAnalysisReportBudget.MAX_BYTES)
        assertTrue(result.includedFindings<total);assertEquals(total,result.includedFindings+result.omittedFindings)
        assertFalse(result.complete)
    }
    @Test fun oversizedOptionalFooterIsMarkedPartialAndTruncatedAtUnicodeBoundaries() {
        val result=PrivateAnalysisReportBudget.build("질문\n",sequenceOf("1. 인용🙂\n"),1,"가😀".repeat(3000),2048)
        assertTrue(result.footerTruncated);assertFalse(result.complete)
        assertEquals(1,result.includedFindings);assertEquals(0,result.omittedFindings)
        assertTrue(result.text.contains("추가 설명 일부 생략: 예"))
        assertEquals(result.text.toByteArray(Charsets.UTF_8).size,result.utf8Bytes)
        assertTrue(result.utf8Bytes<=2048)
    }
    @Test fun mandatoryQuestionIsNotSilentlyCutAndWrongBlockCountsFail() {
        assertTrue(runCatching {PrivateAnalysisReportBudget.build("x".repeat(1024),emptySequence(),0,maxBytes=1024)}.isFailure)
        assertTrue(runCatching {PrivateAnalysisReportBudget.build("header",sequenceOf("one"),2,maxBytes=4096)}.isFailure)
        assertTrue(runCatching {PrivateAnalysisReportBudget.build("header",sequenceOf("one","two"),1,maxBytes=4096)}.isFailure)
        assertTrue(runCatching {PrivateAnalysisReportBudget.build("header",sequenceOf(""),1,maxBytes=4096)}.isFailure)
    }
    @Test fun reportResultToStringDoesNotExposeQuestionClaimsOrQuotes() {
        val result=PrivateAnalysisReportBudget.build("SYNTHETIC-PRIVATE-QUESTION\n",sequenceOf("SYNTHETIC-PRIVATE-QUOTE\n"),1,maxBytes=4096)
        assertFalse(result.toString().contains("SYNTHETIC-PRIVATE"));assertTrue(result.toString().contains("privateTextOmitted=true"))
    }
}
