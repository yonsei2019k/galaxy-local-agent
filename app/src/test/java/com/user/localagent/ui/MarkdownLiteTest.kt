package com.user.localagent.ui

import com.user.localagent.ui.MarkdownLite.Block
import org.junit.Assert.*
import org.junit.Test

class MarkdownLiteTest {
    @Test fun boldMarkersAreRemovedAndStyled(){
        val spans=MarkdownLite.inline("결과는 **42**입니다")
        assertEquals("결과는 42입니다",spans.joinToString(""){it.text})
        assertTrue(spans.single{it.text=="42"}.bold)
    }
    @Test fun arithmeticAndSnakeCaseStayLiteral(){
        assertEquals("2*3*4 = 24",MarkdownLite.inline("2*3*4 = 24").joinToString(""){it.text})
        assertEquals("file_name_v2",MarkdownLite.inline("file_name_v2").joinToString(""){it.text})
        assertEquals("**미완성",MarkdownLite.inline("**미완성").joinToString(""){it.text})
    }
    @Test fun blocksCoverHeadingsListsCodeAndTables(){
        val blocks=MarkdownLite.parse("## 요약\n- 첫째 **중요**\n  - 하위\n1. 하나\n```\nval x=1\n```\n| 이름 | 값 |\n|---|---|\n| a | 1 |\n---\n> 인용")
        assertEquals(Block.Heading(2,listOf(MarkdownLite.Span("요약"))),blocks[0])
        val item=blocks[1] as Block.ListItem
        assertEquals("•",item.marker);assertTrue(item.spans.any{it.bold&&it.text=="중요"})
        assertEquals(1,(blocks[2] as Block.ListItem).depth)
        assertEquals("1.",(blocks[3] as Block.ListItem).marker)
        assertEquals(Block.Code("val x=1"),blocks[4])
        val table=blocks[5] as Block.Table
        assertEquals(2,table.rows.size);assertEquals("1",table.rows[1][1].single().text)
        assertEquals(Block.Rule,blocks[6])
        assertTrue(blocks[7] is Block.Quote)
    }
    @Test fun inlineCodeAndParagraphLinesArePreserved(){
        val p=MarkdownLite.parse("첫 줄\n둘째 `code` 줄").single() as Block.Paragraph
        assertEquals("첫 줄\n둘째 code 줄",p.spans.joinToString(""){it.text})
        assertTrue(p.spans.single{it.text=="code"}.code)
    }
}
