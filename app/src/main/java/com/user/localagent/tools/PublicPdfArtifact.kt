package com.user.localagent.tools

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.user.localagent.web.PinnedPublicHttpsTransport
import com.user.localagent.web.PublicWebClient
import com.user.localagent.web.PublicWebSafety
import com.user.localagent.web.PublicWebTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Public, cookie-free retrieval. Only verified originals become visible in the artifact directory. */
internal object PublicPdfArtifact {
    suspend fun save(directory: File, url: String,
        transport: PublicWebTransport = PinnedPublicHttpsTransport(pdf=true),
        publish: suspend (() -> Unit) -> Unit = { commit -> commit() },
        pageCount: (File) -> Int = ::inspectPages): String {
        PublicWebSafety.requireHttps(url)
        val response = transport.get(url, PublicWebClient.MAX_BYTES)
        PublicWebSafety.requireHttps(response.finalUrl)
        require(response.statusCode == 200) { "공개 PDF를 열 수 없어요. 로그인이나 다른 다운로드 경로가 필요한지 확인해 주세요." }
        val type = response.contentType.substringBefore(';').trim().lowercase()
        require(type in setOf("application/pdf","application/octet-stream")) { "다운로드 응답이 PDF 파일이 아니에요." }
        val bytes = response.body
        require(bytes.size in 5..PublicWebClient.MAX_BYTES &&
            String(bytes,0,5,Charsets.US_ASCII) == "%PDF-") { "지원하는 크기의 PDF 원본을 확인하지 못했어요." }
        currentCoroutineContext().ensureActive()
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val target = File(directory,"artifact-${UUID.nameUUIDFromBytes(digest)}.pdf")
        val temporary = File.createTempFile(".pdf-", ".part", directory)
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            val pages = pageCount(temporary)
            require(pages > 0) { "저장할 PDF의 페이지를 확인하지 못했어요." }
            fun verify(file: File) {
                check(file.length() == bytes.size.toLong() &&
                    MessageDigest.isEqual(digest,MessageDigest.getInstance("SHA-256").digest(file.readBytes()))) {
                    "PDF 원본과 보관한 파일의 내용이 달라요."
                }
            }
            verify(temporary)
            currentCoroutineContext().ensureActive()
            var exists = false
            publish {
                try {
                    Files.createLink(target.toPath(), temporary.toPath())
                } catch (_: FileAlreadyExistsException) {
                    exists = true
                }
            }
            // A concurrent download can publish identical bytes. Check off the UI thread.
            if (exists) verify(target)
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
