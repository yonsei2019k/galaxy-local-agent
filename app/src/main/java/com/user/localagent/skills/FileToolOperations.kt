package com.user.localagent.skills

import android.content.Context
import androidx.core.content.FileProvider
import com.user.localagent.office.NativeOfficeTools
import com.user.localagent.office.OfficeSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class FileArtifact(val file:File,val mimeType:String,val format:String,val bytes:Long,val verificationSummary:String,val sha256:String?=null) {
    /** No private absolute path, document content, account data, URI grant or external side effect. */
    fun toolResult():String=buildJsonObject {
        put("status","ok");put("fileName",file.name);put("mimeType",mimeType);put("format",format)
        put("bytes",bytes);put("verification",verificationSummary);put("privateArtifact",true)
        sha256?.let { put("sha256",it) }
        put("exportOrShareRequiresUserAction",true)
    }.toString()
}

/** Typed chat file operations. Only creates new private artifacts; never opens, sends or overwrites user files. */
class FileToolOperations(context:Context,private val officeTools:NativeOfficeTools,private val skills:BuiltinSkills) {
    private val context=context.applicationContext
    suspend fun createDocument(format:String,title:String,content:String):FileArtifact=withContext(Dispatchers.IO) {
        val extension=format.trim().lowercase(Locale.ROOT)
        require(extension in MIME_TYPES){"지원하는 형식은 DOCX, XLSX, PDF, TXT, CSV입니다."}
        val heading=title.trim();require(heading.length in 1..200){"문서 제목은 1~200자입니다."}
        require(content.length<=12_000){"한 문서의 생성 본문은 12,000자 이하입니다."}
        validateText(heading);validateText(content)
        val directory=File(context.filesDir,"exports").canonicalFile
        require(directory.isDirectory||directory.mkdirs())
        val target=File(directory,"artifact-${UUID.randomUUID()}.$extension")
        check(target.canonicalFile.parentFile==directory&&!target.exists())
        currentCoroutineContext().ensureActive();check(target.createNewFile())
        var officeTemporary:File?=null;var complete=false
        try {
            val verification=when(extension){
                "docx"->{
                    val paragraphs=content.replace("\r\n","\n").replace('\r','\n').split('\n')
                    val generated=officeTools.writeDocx(heading,paragraphs);officeTemporary=generated.file
                    copyOwnOffice(generated.file,target,"docx")
                    val returned=officeTools.readDocx(target.inputStream())
                    check(!returned.truncated&&returned.paragraphs==listOf(heading)+paragraphs){"DOCX 저장 후 본문 재조회가 일치하지 않습니다."}
                    "DOCX 본문 ${returned.paragraphs.size}개 문단 저장·재조회 확인 · 단순 텍스트 서식"
                }
                "xlsx"->{
                    val rows=BuiltinSkills.parseCsv(content)
                    val sheetName=safeSheetName(heading)
                    val generated=officeTools.writeXlsx(listOf(OfficeSheet(sheetName,rows)));officeTemporary=generated.file
                    copyOwnOffice(generated.file,target,"xlsx")
                    val returned=officeTools.readXlsx(target.inputStream())
                    check(!returned.truncated&&returned.sheets.size==1&&returned.sheets.single().rows==rows&&returned.sheets.single().formulas.isEmpty()){"XLSX 저장 후 문자열 셀 재조회가 일치하지 않습니다."}
                    "XLSX ${rows.size}행 문자열 값 저장·재조회 확인 · CSV 입력 · 수식 계산·서식 재현 없음"
                }
                "pdf"->{
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",target)
                    val pages=skills.writePdf(uri,content,heading)
                    val inspected=skills.inspectPdf(uri,includeText=false)
                    check(pages>0&&inspected.pageCount==pages&&inspected.pages.all {it.width>0&&it.height>0}){"PDF 저장 후 페이지 재조회를 확인하지 못했습니다."}
                    check(target.inputStream().use {input->val header=ByteArray(5);input.read(header)==5&&header.toString(Charsets.US_ASCII)=="%PDF-"})
                    "PDF ${pages}쪽 생성·페이지 재조회 확인 · 단순 텍스트 배치 · OCR·고급 서식 없음"
                }
                "txt"->{
                    val expected=content.toByteArray(Charsets.UTF_8);target.writeBytes(expected)
                    verifyBytes(target,expected)
                    "UTF-8 TXT ${expected.size}바이트 저장·SHA-256 재조회 확인"
                }
                "csv"->{
                    val rows=BuiltinSkills.parseCsv(content);val encoded=BuiltinSkills.encodeCsv(rows)
                    val expected=encoded.toByteArray(Charsets.UTF_8);target.writeBytes(expected);verifyBytes(target,expected)
                    check(BuiltinSkills.parseCsv(target.readText(Charsets.UTF_8))==BuiltinSkills.parseCsv(encoded))
                    "CSV ${rows.size}행 저장·구문 및 SHA-256 재조회 확인 · 수식 접두어는 일반 문자열로 비활성화"
                }
                else->error("지원하지 않는 형식")
            }
            currentCoroutineContext().ensureActive()
            check(target.isFile&&target.length() in 0..20L*1024*1024)
            val pdfHash = if (extension == "pdf") {
                val digest = MessageDigest.getInstance("SHA-256")
                target.inputStream().use { input ->
                    val block = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(block)
                        if (read < 0) break
                        digest.update(block,0,read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } else null
            complete=true
            FileArtifact(target,MIME_TYPES.getValue(extension),extension,target.length(),verification,pdfHash)
        }finally {
            officeTemporary?.let {temporary->if(isOwnOffice(temporary,extension))temporary.delete()}
            if(!complete&&target.canonicalFile.parentFile==directory&&ARTIFACT_NAME.matches(target.name))target.delete()
        }
    }
    private suspend fun copyOwnOffice(source:File,target:File,extension:String) {
        require(isOwnOffice(source,extension)){"앱이 방금 생성한 Office 임시 파일만 복사합니다."}
        require(source.length() in 1..20L*1024*1024)
        source.inputStream().use {input->target.outputStream().use {output->
            val buffer=ByteArray(16*1024);var bytes=0L
            while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;bytes+=count;require(bytes<=20L*1024*1024);output.write(buffer,0,count)}
        }}
    }
    private fun isOwnOffice(file:File,extension:String)=
        file.canonicalFile.parentFile==File(context.filesDir,"office-exports").canonicalFile&&
            file.name.matches(Regex("ziphap-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.$extension"))
    private fun verifyBytes(file:File,expected:ByteArray) {
        require(file.length()==expected.size.toLong())
        val digest=MessageDigest.getInstance("SHA-256")
        val actual=file.inputStream().use {input->val buffer=ByteArray(16*1024);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)};digest.digest()}
        check(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(expected),actual)){"파일 저장 후 SHA-256이 일치하지 않습니다."}
    }
    private fun validateText(value:String) {
        var index=0
        while(index<value.length){val point=value.codePointAt(index);require(point==9||point==10||point==13||point in 0x20..0xD7FF||point in 0xE000..0xFFFD||point in 0x10000..0x10FFFF){"문서에 저장할 수 없는 문자입니다."};index+=Character.charCount(point)}
    }
    private fun safeSheetName(title:String):String {
        val cleaned=title.map {if(it in "[]:*?/\\"||it.code<32)' 'else it}.joinToString("").trim().trim('\'')
        var value=cleaned.take(31)
        if(value.lastOrNull()?.isHighSurrogate()==true)value=value.dropLast(1)
        return value.trim().trim('\'').ifBlank {"Sheet1"}
    }
    companion object {
        private val MIME_TYPES=mapOf("docx" to NativeOfficeTools.DOCX_MIME,"xlsx" to NativeOfficeTools.XLSX_MIME,
            "pdf" to "application/pdf","txt" to "text/plain","csv" to "text/csv")
        private val ARTIFACT_NAME=Regex("artifact-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:docx|xlsx|pdf|txt|csv)")
    }
}
