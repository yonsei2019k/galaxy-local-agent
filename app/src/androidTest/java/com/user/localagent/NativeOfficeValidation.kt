package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.office.NativeOfficeTools
import com.user.localagent.office.OfficeSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android ZIP/XML implementation, isolated synthetic app-private files only. */
@RunWith(AndroidJUnit4::class)
class NativeOfficeValidation {
    @Test fun nativeDocxAndXlsxRoundTripsUsePrivateFilesAndLiteralValues()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"office-qa-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val tools=NativeOfficeTools(directory)
            val doc=tools.writeDocx("기기 문서 확인",listOf("한국어 & <XML> 😀","첫 줄\n둘째 줄\t탭"))
            assertTrue(doc.file.length()>0)
            assertEquals(directory.canonicalFile,doc.file.canonicalFile.parentFile)
            val document=tools.readDocx(doc.file.inputStream())
            assertEquals(listOf("기기 문서 확인","한국어 & <XML> 😀","첫 줄\n둘째 줄\t탭"),document.paragraphs)
            assertFalse(document.truncated)
            val rows=listOf(listOf("항목","값"),listOf("식별자","00123"),listOf("실행 안 하는 문자열","=1+1"),emptyList())
            val book=tools.writeXlsx(listOf(OfficeSheet("기기 확인",rows)))
            val workbook=tools.readXlsx(book.file.inputStream())
            assertEquals(rows,workbook.sheets.single().rows)
            assertTrue(workbook.sheets.single().formulas.isEmpty())
            assertFalse(workbook.truncated)
            assertTrue(context.getSharedPreferences("validated-tools",android.content.Context.MODE_PRIVATE).edit().putBoolean("document.docx",true).putBoolean("spreadsheet.xlsx",true).commit())
        }finally {
            // This invocation's isolated directory contains only its own synthetic exports.
            directory.listFiles().orEmpty().forEach {check(it.canonicalFile.parentFile==directory.canonicalFile);it.delete()}
            check(directory.delete())
        }
    }
}
