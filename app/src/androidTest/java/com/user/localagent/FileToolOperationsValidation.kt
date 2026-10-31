package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.office.NativeOfficeTools
import com.user.localagent.skills.BuiltinSkills
import com.user.localagent.skills.FileArtifact
import com.user.localagent.skills.FileToolOperations
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in synthetic artifacts only; no external export, application launch, user file or account access. */
@RunWith(AndroidJUnit4::class)
class FileToolOperationsValidation {
    @Test fun everyChatFormatProducesAReadBackVerifiedPrivateArtifact()=runBlocking {
        assumeTrue("Explicit fileToolOperationsApproved=true is required",
            InstrumentationRegistry.getArguments().getString("fileToolOperationsApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val office=NativeOfficeTools(context);val builtins=BuiltinSkills(context)
        val operations=FileToolOperations(context,office,builtins)
        val title="QA 문서 ${UUID.randomUUID()}"
        val created=mutableListOf<FileArtifact>()
        try {
            for(format in listOf("docx","xlsx","pdf","txt","csv")){
                val content=if(format in listOf("xlsx","csv"))"항목,값\n코드,00123\n문자열,=1+1\n"else "한국어 & <문서> 😀\n실제 파일을 만들고 다시 확인합니다."
                val artifact=operations.createDocument(format,title,content);created+=artifact
                assertEquals(format,artifact.format)
                assertTrue(artifact.file.isFile)
                assertEquals(artifact.file.length(),artifact.bytes)
                assertEquals(File(context.filesDir,"exports").canonicalFile,artifact.file.canonicalFile.parentFile)
                val result=Json.parseToJsonElement(artifact.toolResult()).jsonObject
                assertEquals(artifact.file.name,result["fileName"]?.jsonPrimitive?.content)
                assertEquals("ok",result["status"]?.jsonPrimitive?.content)
                assertTrue(result["exportOrShareRequiresUserAction"]?.jsonPrimitive?.boolean==true)
                assertFalse(artifact.toolResult().contains(context.filesDir.absolutePath))
                when(format){
                    "docx"->assertEquals(listOf(title)+content.split('\n'),office.readDocx(artifact.file.inputStream()).paragraphs)
                    "xlsx"->{val book=office.readXlsx(artifact.file.inputStream());assertEquals("=1+1",book.sheets.single().rows[2][1]);assertTrue(book.sheets.single().formulas.isEmpty())}
                    "pdf"->assertTrue(artifact.verificationSummary.contains("페이지 재조회"))
                    "txt"->assertEquals(content,artifact.file.readText(Charsets.UTF_8))
                    "csv"->{val rows=BuiltinSkills.parseCsv(artifact.file.readText(Charsets.UTF_8));assertEquals("'=1+1",rows[2][1]);assertEquals("00123",rows[1][1])}
                }
            }
            assertEquals(5,created.map {it.file.name}.distinct().size)
        }finally {
            for(artifact in created){check(artifact.file.canonicalFile.parentFile==File(context.filesDir,"exports").canonicalFile);check(artifact.file.name.startsWith("artifact-"));artifact.file.delete()}
        }
    }

    @Test fun unsupportedFormatsOversizedInputAndInvalidCsvNeverPublishArtifacts()=runBlocking {
        assumeTrue("Explicit fileToolOperationsApproved=true is required",
            InstrumentationRegistry.getArguments().getString("fileToolOperationsApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val operations=FileToolOperations(context,NativeOfficeTools(context),BuiltinSkills(context))
        val directory=File(context.filesDir,"exports")
        val before=directory.listFiles().orEmpty().map {it.name}.toSet()
        assertTrue(runCatching {operations.createDocument("exe","QA","not executable")}.isFailure)
        assertTrue(runCatching {operations.createDocument("txt","QA","x".repeat(12_001))}.isFailure)
        assertTrue(runCatching {operations.createDocument("txt","QA","bad\u0000text")}.isFailure)
        assertTrue(runCatching {operations.createDocument("xlsx","QA","value,\"unclosed")}.isFailure)
        assertEquals(before,directory.listFiles().orEmpty().map {it.name}.toSet())
    }
}
