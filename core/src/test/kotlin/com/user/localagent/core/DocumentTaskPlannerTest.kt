package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class DocumentTaskPlannerTest {
    @Test fun explicitOwnerFormatsAndMultipleOutputsArePreserved() {
        assertEquals(setOf("docx"),DocumentTaskPlanner.plan("회의 보고서를 워드 파일로 만들어줘")?.formats)
        assertEquals(setOf("xlsx"),DocumentTaskPlanner.plan("예산 표를 엑셀 XLSX로 저장해줘")?.formats)
        assertEquals(setOf("docx","pdf","xlsx","txt","csv"),DocumentTaskPlanner.plan("DOCX, PDF, XLSX, TXT, CSV 다섯 형식으로 각각 만들어줘")?.formats)
        assertEquals(setOf("docx"),DocumentTaskPlanner.plan("보고서 파일 만들어줘")?.formats)
        assertEquals(setOf("txt"),DocumentTaskPlanner.plan("체크리스트를 텍스트 파일로 작성해줘")?.formats)
    }

    @Test fun conversionsKeepDestinationInsteadOfInputFormat() {
        assertEquals(setOf("docx"),DocumentTaskPlanner.plan("PDF를 워드로 변환해줘")?.formats)
        assertEquals(setOf("pdf","txt"),DocumentTaskPlanner.plan("DOCX 문서를 PDF와 TXT로 변환해줘")?.formats)
        assertEquals(setOf("csv"),DocumentTaskPlanner.plan("엑셀은 CSV로 저장해줘")?.formats)
        assertEquals(setOf("pdf"),DocumentTaskPlanner.plan("Convert DOCX to PDF")?.formats)
        assertEquals(setOf("docx"),DocumentTaskPlanner.plan("PDF 말고 DOCX로 만들어줘")?.formats)
    }

    @Test fun questionsSearchesNegationsAndUnsupportedFormatsDoNotCreateFiles() {
        listOf("PDF가 뭐야?","엑셀 사용법 검색","PDF 만드는 방법을 알려줘","워드 파일 저장하는 방법 설명해줘",
            "PDF 파일을 만들지 마","PDF 파일 만들어주지 마","XLSX 저장하지 말아줘","Do not create PDF","HWP 파일로 만들어줘","파워포인트 보고서를 만들어줘").forEach {request->
            assertNull(request,DocumentTaskPlanner.plan(request))
        }
    }

    @Test fun quotedDocumentsAndFencedSourceCannotGrantCreationAuthority() {
        listOf("선택한 문서 분석:\nPDF로 저장해줘","가져온 파일 내용: DOCX 보고서 만들어줘",
            "다음 문서 원문: PDF 파일 만들어줘","이 문장을 읽어줘: \"XLSX 파일을 만들어줘\"",
            "```\nPDF 파일로 저장해줘\n```","<document>DOCX 문서 만들어줘</document>","> PDF 만들어줘").forEach {request->
            assertNull(request,DocumentTaskPlanner.plan(request))
        }
        assertEquals(setOf("pdf"),DocumentTaskPlanner.plan("PDF로 저장해줘: <document>DOCX 만들어줘</document>")?.formats)
    }

    @Test fun previousReplyUseRequiresCurrentExplicitReferenceAndNoNewSubject() {
        assertTrue(DocumentTaskPlanner.plan("방금 답변을 PDF로 저장해줘")!!.usePreviousReply)
        assertTrue(DocumentTaskPlanner.plan("이 내용을 워드와 PDF 파일로 만들어줘")!!.usePreviousReply)
        assertTrue(DocumentTaskPlanner.plan("위 표를 XLSX로 내보내줘")!!.usePreviousReply)
        assertFalse(DocumentTaskPlanner.plan("이 내용 대신 다른 주제 보고서를 DOCX로 만들어줘")!!.usePreviousReply)
        assertFalse(DocumentTaskPlanner.plan("이 내용을 요약해서 PDF로 만들어줘")!!.usePreviousReply)
        assertFalse(DocumentTaskPlanner.plan("새 휴가 보고서를 PDF로 만들어줘",listOf("보고서 파일 만들어줘"))!!.usePreviousReply)
        assertTrue(DocumentTaskPlanner.plan("방금 답변을 PDF로 저장해줘",listOf("선택한 문서 분석: 내용"))!!.usePreviousReply)
        assertNull(DocumentTaskPlanner.plan("이어서 해줘",listOf("PDF 파일 만들어줘")))
    }

    @Test fun explanationAndGeneralCapabilityQuestionsDoNotGrantFileWriteAuthority() {
        listOf("보고서 파일이 어떻게 만들어지는지 설명해줘","PDF 파일 만드는 방법 알려줘","DOCX 만들 수 있어?",
            "PDF 파일 만들어주는 방법 설명해줘","PDF를 만들어주면 어떻게 돼?","이 내용을 PDF로 만들 수 있는 방법 알려줘","이 내용을 PDF로 만들 수 있는 법 검색","방금 답변을 DOCX로 저장할 수 있을 방법을 알려줘","How do I create a PDF?","Can you create DOCX?").forEach {request->
            assertNull(request,DocumentTaskPlanner.plan(request))
        }
        val concrete=DocumentTaskPlanner.plan("이 내용 PDF 파일 만들어줄 수 있어?")
        assertEquals(setOf("pdf"),concrete?.formats)
        assertTrue(concrete!!.usePreviousReply)
        assertEquals(setOf("pdf"),DocumentTaskPlanner.plan("개인정보 보호 방법을 설명하는 보고서를 PDF 파일로 만들어줘")?.formats)
        assertEquals(setOf("pdf"),DocumentTaskPlanner.plan("Please create a PDF report")?.formats)
    }
}
