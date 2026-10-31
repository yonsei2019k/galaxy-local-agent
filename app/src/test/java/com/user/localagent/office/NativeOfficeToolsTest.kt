package com.user.localagent.office

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Synthetic OOXML packages only; no external applications, network or user files. */
class NativeOfficeToolsTest {
    @Test fun docxRoundTripPreservesKoreanXmlEscapesParagraphsTabsAndLineBreaks()=runBlocking {
        fixture {tools,dir->
            val paragraphs=listOf(" 한국어 & <문서> \"따옴표\" ","첫 줄\n둘째 줄\t탭","", "emoji 😀")
            val exported=tools.writeDocx("테스트 제목",paragraphs)
            assertEquals(dir.canonicalFile,exported.file.canonicalFile.parentFile)
            assertEquals(NativeOfficeTools.DOCX_MIME,exported.mimeType)
            val read=tools.readDocx(exported.file.inputStream())
            assertEquals(listOf("테스트 제목")+paragraphs,read.paragraphs)
            assertFalse(read.truncated)
            assertTrue(read.limitations.isNotEmpty())
            assertTrue(entries(exported.file).keys.containsAll(listOf("[Content_Types].xml","_rels/.rels","word/document.xml")))
            val second=tools.writeDocx("두 번째",listOf("다른 파일"))
            assertNotEquals(exported.file.name,second.file.name)
        }
    }

    @Test fun xlsxMultiSheetRoundTripKeepsLiteralFormulaLikeStringsAndTrailingEmptyRows()=runBlocking {
        fixture {tools,_->
            val rows=listOf(listOf("이름","값"),listOf("코드","00123"),listOf("수식처럼 보이는 문자열","=HYPERLINK(\"https://example.test\",\"literal\")"),listOf("여러줄","첫줄\n둘째줄\r끝"),emptyList())
            val exported=tools.writeXlsx(listOf(OfficeSheet("원본",rows),OfficeSheet("다음",listOf(listOf("&<>\"' 😀")))))
            val read=tools.readXlsx(exported.file.inputStream())
            assertEquals(rows,read.sheets[0].rows)
            assertEquals("&<>\"' 😀",read.sheets[1].rows.single().single())
            assertTrue(read.sheets.all {it.formulas.isEmpty()})
            assertFalse(read.truncated)
            val parts=entries(exported.file)
            assertFalse(parts.getValue("xl/worksheets/sheet1.xml").contains("<f>"))
            assertTrue(parts.getValue("xl/worksheets/sheet1.xml").contains("t=\"inlineStr\""))
            assertFalse(parts.values.any {it.contains("TargetMode=\"External\"")})
        }
    }

    @Test fun sharedStringsSparseCoordinatesAndFormulaCachesAreReadWithoutCalculation()=runBlocking {
        fixture {tools,_->
            val sheet="""<worksheet xmlns="$S"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1"><f>SUM(1,2)</f><v>3</v></c><c r="D1"><f>1/0</f></c></row><row r="3"><c r="B3" t="b"><v>1</v></c><c r="C3" t="e"><v>#DIV/0!</v></c></row><row r="4"/></sheetData></worksheet>"""
            val read=tools.readXlsx(ByteArrayInputStream(zip(xlsx(sheet,mapOf("xl/sharedStrings.xml" to "<sst xmlns=\"$S\"><si><r><t>한국어</t></r><r><t> 문자열</t></r></si></sst>")))))
            assertEquals(listOf("한국어 문자열","","3",""),read.sheets.single().rows[0])
            assertEquals(emptyList<String>(),read.sheets.single().rows[1])
            assertEquals(listOf("","TRUE","#DIV/0!"),read.sheets.single().rows[2])
            assertTrue(read.sheets.single().rows[3].isEmpty())
            assertEquals("SUM(1,2)",read.sheets.single().formulas[0].expression)
            assertEquals("3",read.sheets.single().formulas[0].cachedValue)
            assertNull(read.sheets.single().formulas[1].cachedValue)
            val flattened=tools.writeXlsx(read.sheets)
            val returned=tools.readXlsx(flattened.file.inputStream())
            assertEquals(read.sheets.single().rows,returned.sheets.single().rows)
            assertTrue(returned.sheets.single().formulas.isEmpty())
        }
    }

    @Test fun externalRelationshipsAndDtdEntitiesFailBeforeAnyResolution()=runBlocking {
        fixture {tools,_->
            val external=docx("safe").toMutableMap()
            external["word/_rels/document.xml.rels"]="<Relationships xmlns=\"$R\"><Relationship Id=\"external\" Type=\"$O/attachedTemplate\" Target=\"https://example.test/private\" TargetMode=\"External\"/></Relationships>"
            assertTrue(runCatching {tools.readDocx(ByteArrayInputStream(zip(external)))}.isFailure)
            val entity=docx("&xxe;").toMutableMap()
            entity["word/document.xml"]="<!DOCTYPE document [<!ENTITY xxe SYSTEM 'file:///never-read-synthetic-secret'>]>"+entity.getValue("word/document.xml")
            assertTrue(runCatching {tools.readDocx(ByteArrayInputStream(zip(entity)))}.isFailure)
        }
    }

    @Test fun normalHttpHyperlinkPreservesVisibleDocxTextWithoutFollowingItsTarget()=runBlocking {
        fixture {tools,_->
            val parts=docx("before").toMutableMap()
            parts["word/document.xml"]="<w:document xmlns:w=\"$W\" xmlns:r=\"$O\"><w:body><w:p><w:r><w:t>이전 </w:t></w:r><w:hyperlink r:id=\"link1\"><w:r><w:t>표시 링크 텍스트</w:t></w:r></w:hyperlink><w:r><w:t> 이후</w:t></w:r></w:p></w:body></w:document>"
            parts["word/_rels/document.xml.rels"]="<Relationships xmlns=\"$R\"><Relationship Id=\"link1\" Type=\"$O/hyperlink\" Target=\"https://example.invalid/never-open-this\" TargetMode=\"External\"/></Relationships>"
            val read=tools.readDocx(ByteArrayInputStream(zip(parts)))
            assertEquals("이전 표시 링크 텍스트 이후",read.text)
            assertFalse(read.text.contains("example.invalid"))
            val copied=tools.writeDocx("복사",read.paragraphs)
            assertFalse(entries(copied.file).values.any {it.contains("example.invalid")||it.contains("TargetMode=\"External\"")})
            parts["word/_rels/document.xml.rels"]=parts.getValue("word/_rels/document.xml.rels").replace("https://example.invalid/never-open-this","file:///never-open-this")
            assertTrue(runCatching {tools.readDocx(ByteArrayInputStream(zip(parts)))}.isFailure)
        }
    }

    @Test fun traversalCaseCollisionsAndMacroPayloadsAreRejected()=runBlocking {
        fixture {tools,_->
            for(extra in listOf(mapOf("../escape.xml" to "bad"),mapOf("word/vbaProject.bin" to "synthetic"),mapOf("word/embeddings/payload.xml" to "bad"),mapOf("word/a.xml" to "a","WORD/A.XML" to "b"))){
                assertTrue(runCatching {tools.readDocx(ByteArrayInputStream(zip(docx("safe")+extra)))}.isFailure)
            }
        }
    }

    @Test fun expandedZipByteAndEntryBoundsRejectSmallCompressedBombs()=runBlocking {
        fixture {tools,_->
            val bytes=ByteArrayOutputStream()
            ZipOutputStream(bytes).use {output->
                output.putNextEntry(ZipEntry("padding.txt"));val block=ByteArray(64*1024)
                repeat(320){output.write(block)};output.write(0);output.closeEntry()
            }
            assertTrue(bytes.size()<100_000)
            assertTrue(runCatching {tools.readDocx(ByteArrayInputStream(bytes.toByteArray()))}.isFailure)
            val tooMany=ByteArrayOutputStream()
            ZipOutputStream(tooMany).use {output->repeat(2001){index->output.putNextEntry(ZipEntry("entry$index.xml"));output.closeEntry()}}
            assertTrue(runCatching {tools.readDocx(ByteArrayInputStream(tooMany.toByteArray()))}.isFailure)
        }
    }

    @Test fun oversizedSparseCoordinatesAreReportedAsTruncatedWithoutAllocatingTheirGrid()=runBlocking {
        fixture {tools,_->
            val sheet="<worksheet xmlns=\"$S\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>kept</t></is></c></row><row r=\"5001\"><c r=\"A5001\"><v>9</v></c></row></sheetData></worksheet>"
            val read=tools.readXlsx(ByteArrayInputStream(zip(xlsx(sheet))))
            assertTrue(read.truncated)
            assertEquals(listOf(listOf("kept")),read.sheets.single().rows)
        }
    }

    @Test fun invalidSheetNamesDuplicateNamesAndXmlControlCharactersFailWithoutExport()=runBlocking {
        fixture {tools,dir->
            assertTrue(runCatching {tools.writeXlsx(listOf(OfficeSheet("bad/name",emptyList())))}.isFailure)
            assertTrue(runCatching {tools.writeXlsx(listOf(OfficeSheet("Sheet",emptyList()),OfficeSheet("sheet",emptyList())))}.isFailure)
            assertTrue(runCatching {tools.writeDocx("valid",listOf("bad\u0000text"))}.isFailure)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    private suspend fun fixture(block:suspend(NativeOfficeTools,File)->Unit){
        val directory=Files.createTempDirectory("ziphap-office-unit-").toFile()
        try {block(NativeOfficeTools(directory),directory)}finally{directory.listFiles().orEmpty().forEach {it.delete()};directory.delete()}
    }
    private fun docx(text:String)=linkedMapOf(
        "[Content_Types].xml" to "<Types xmlns=\"$C\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
        "_rels/.rels" to "<Relationships xmlns=\"$R\"><Relationship Id=\"main\" Type=\"$O/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
        "word/document.xml" to "<w:document xmlns:w=\"$W\"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>")
    private fun xlsx(sheet:String,extra:Map<String,String> = emptyMap())=linkedMapOf(
        "[Content_Types].xml" to "<Types xmlns=\"$C\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>",
        "_rels/.rels" to "<Relationships xmlns=\"$R\"><Relationship Id=\"main\" Type=\"$O/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
        "xl/workbook.xml" to "<workbook xmlns=\"$S\" xmlns:r=\"$O\"><sheets><sheet name=\"Synthetic\" sheetId=\"1\" r:id=\"s1\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"$R\"><Relationship Id=\"s1\" Type=\"$O/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
        "xl/worksheets/sheet1.xml" to sheet)+extra
    private fun zip(parts:Map<String,String>):ByteArray=ByteArrayOutputStream().also {bytes->ZipOutputStream(bytes).use {output->parts.forEach {(name,text)->output.putNextEntry(ZipEntry(name));output.write(text.toByteArray());output.closeEntry()}}}.toByteArray()
    private fun entries(file:File):Map<String,String> {val result=linkedMapOf<String,String>();ZipInputStream(file.inputStream()).use {zip->while(true){val entry=zip.nextEntry?:break;result[entry.name]=zip.readBytes().toString(Charsets.UTF_8)}};return result}
    companion object {
        private const val W="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val S="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val O="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val R="http://schemas.openxmlformats.org/package/2006/relationships"
        private const val C="http://schemas.openxmlformats.org/package/2006/content-types"
    }
}
