package com.user.localagent.tools

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.CancellationException
import com.user.localagent.web.PinnedPublicHttpsTransport
import com.user.localagent.web.PublicWebClient
import com.user.localagent.web.PublicWebSafety
import com.user.localagent.web.PublicWebTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Local, per-call diagnostics. The exception itself can contain private URLs or storage paths. */
internal enum class PdfSaveStage(val notice:String) {
    LINK("현재 페이지에서 PDF 링크와 로그인 상태를 다시 확인해 주세요."),
    FETCH("파일을 받지 못했어요. 원래 사이트에서 PDF 링크가 여전히 열리는지 확인해 주세요."),
    RESPONSE("사이트가 PDF 다운로드에 응답하지 않았어요. 로그인 화면이나 다른 발급 경로가 있는지 확인해 주세요."),
    CONTENT("받은 자료가 지원하는 크기의 PDF 원본인지 확인하지 못했어요."),
    STORAGE("파일 보관 공간을 준비하지 못했어요. 휴대폰 저장 공간을 확인해 주세요."),
    WRITE("임시 파일을 기록하지 못했어요. 휴대폰 저장 공간을 확인해 주세요."),
    PAGES("받은 파일에서 PDF 페이지를 열지 못했어요. 사이트의 원본 파일을 다시 확인해 주세요."),
    VERIFY("내려받은 원본과 임시 파일이 일치하지 않아요."),
    AUTHORITY("저장 직전에 실행 권한이나 페이지 상태가 바뀌었어요. 현재 상태부터 다시 확인해 주세요."),
    PUBLISH("검사한 PDF를 보관함에 넣지 못했어요. 보관함의 저장 상태를 확인해 주세요."),
    READBACK("보관함 파일이 내려받은 원본과 일치하는지 확인하지 못했어요.")
}

internal class PdfSaveProgress {
    var stage = PdfSaveStage.LINK
    var publicationPossible = false

    suspend fun execute(id:String, save:suspend ()->String):ToolResult = try {
        ToolResult(id,true,save(),VerificationState.VERIFIED,"PDF 원본 저장·재조회 확인")
    } catch(cancel:CancellationException) {
        throw cancel
    } catch(_:Exception) {
        val message=if(publicationPossible)
            "PDF가 보관함에 들어갔을 수 있어요. 다시 내려받기 전에 저장 결과부터 확인해 주세요."
            else stage.notice
        ToolResult(id,false,message,if(publicationPossible)VerificationState.UNVERIFIED else VerificationState.FAILED,
            "PDF_${stage.name}",effectNotApplied=!publicationPossible)
    }
}

/** Public, cookie-free retrieval. Only verified originals become visible in the artifact directory. */
internal object PublicPdfArtifact {
    private val publicationLock = Any()

    suspend fun save(directory: File, url: String,
        transport: PublicWebTransport = PinnedPublicHttpsTransport(pdf=true),
        progress: PdfSaveProgress = PdfSaveProgress(),
        publish: suspend (() -> Unit) -> Unit = { commit -> commit() },
        pageCount: (File) -> Int = ::inspectPages): String {
        PublicWebSafety.requireHttps(url)
        progress.stage = PdfSaveStage.FETCH
        val response = transport.get(url, PublicWebClient.MAX_BYTES)
        progress.stage = PdfSaveStage.RESPONSE
        PublicWebSafety.requireHttps(response.finalUrl)
        require(response.statusCode == 200) { "공개 PDF를 열 수 없어요. 로그인이나 다른 다운로드 경로가 필요한지 확인해 주세요." }
        progress.stage = PdfSaveStage.CONTENT
        val type = response.contentType.substringBefore(';').trim().lowercase()
        require(type in setOf("application/pdf","application/octet-stream")) { "다운로드 응답이 PDF 파일이 아니에요." }
        val bytes = response.body
        require(bytes.size in 5..PublicWebClient.MAX_BYTES &&
            String(bytes,0,5,Charsets.US_ASCII) == "%PDF-") { "지원하는 크기의 PDF 원본을 확인하지 못했어요." }
        currentCoroutineContext().ensureActive()
        progress.stage = PdfSaveStage.STORAGE
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val target = File(directory,"artifact-${UUID.nameUUIDFromBytes(digest)}.pdf")
        val temporary = File.createTempFile(".pdf-", ".part", directory)
        try {
            progress.stage = PdfSaveStage.WRITE
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            progress.stage = PdfSaveStage.PAGES
            val pages = pageCount(temporary)
            require(pages > 0) { "저장할 PDF의 페이지를 확인하지 못했어요." }
            fun verify(file: File) {
                check(file.length() == bytes.size.toLong() &&
                    MessageDigest.isEqual(digest,MessageDigest.getInstance("SHA-256").digest(file.readBytes()))) {
                    "PDF 원본과 보관한 파일의 내용이 달라요."
                }
            }
            progress.stage = PdfSaveStage.VERIFY
            verify(temporary)
            currentCoroutineContext().ensureActive()
            progress.stage = PdfSaveStage.AUTHORITY
            publish {
                // The app's document tools run in one process. Publish on the same filesystem
                // under a lock: Android can deny hard links even inside an app-owned directory.
                synchronized(publicationLock) {
                    progress.stage = PdfSaveStage.PUBLISH
                    if (!target.exists()) {
                        progress.publicationPossible = true
                        try {
                            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
                        } catch(error:Exception) {
                            // We can establish non-publication while the directory is locked only
                            // if both path states are known. Unknown/inaccessible is not absence.
                            if (Files.isRegularFile(temporary.toPath()) && Files.notExists(target.toPath()))
                                progress.publicationPossible = false
                            throw error
                        }
                    }
                    progress.stage = PdfSaveStage.READBACK
                }
            }
            verify(target)
            return buildJsonObject {
                put("status","ok"); put("fileName",target.name); put("mimeType","application/pdf"); put("format","pdf")
                put("bytes",bytes.size); put("pages",pages); put("sha256",digest.joinToString("") { "%02x".format(it) })
                put("privateArtifact",true); put("exportOrShareRequiresUserAction",true)
                put("verification","PDF 원본과 저장된 바이트 및 페이지 수 확인")
            }.toString()
        } finally { temporary.delete() }
    }

    private fun inspectPages(file: File): Int =
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer -> renderer.pageCount }
        }
}
