package com.user.localagent.tools

import com.user.localagent.web.PublicWebClient
import com.user.localagent.web.PublicWebResponse
import com.user.localagent.web.PublicWebTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PublicPdfArtifactTest {
    @get:Rule val tmp = TemporaryFolder()
    private val url = "https://example.org/guide.pdf"
    private val fixture = "%PDF-1.7 synthetic bytes".toByteArray()
    private fun transport(body: ByteArray = fixture, type: String = "application/pdf", status: Int = 200) =
        PublicWebTransport { target, limit ->
            assertEquals(url,target)
            assertEquals(PublicWebClient.MAX_BYTES,limit)
            PublicWebResponse(status,url,type,body)
        }

    @Test fun storesOriginalBytesAndReusesVerifiedDownload() = runBlocking {
        val dir = tmp.newFolder()
        suspend fun save() = Json.parseToJsonElement(
            PublicPdfArtifact.save(dir,url,transport()) { file ->
                assertArrayEquals(fixture,file.readBytes()); 2
            }).jsonObject
        val receipt = save()
        val saved = File(dir,receipt.getValue("fileName").jsonPrimitive.content)
        assertEquals("2",receipt.getValue("pages").jsonPrimitive.content)
        assertArrayEquals(fixture,saved.readBytes())
        assertEquals(receipt,save())
        assertEquals(listOf(saved),dir.listFiles()!!.toList())
    }

    @Test fun malformedResponsesAndUnreadablePagesPublishNothing() = runBlocking {
        val dir = tmp.newFolder()
        for (response in listOf(transport(type="text/html"), transport(status=403),
            transport("not a pdf".toByteArray()),transport(ByteArray(PublicWebClient.MAX_BYTES+1)), transport())) {
            val error = runCatching { PublicPdfArtifact.save(dir,url,response) { 0 } }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(dir.listFiles()!!.isEmpty())
        }
    }

    @Test fun revokedSessionBeforePublicationLeavesNoArtifact()=runBlocking {
        val dir=tmp.newFolder()
        val outcome=runCatching {
            PublicPdfArtifact.save(dir,url,transport(),publish={error("expired")}) { 2 }
        }
        assertTrue(outcome.isFailure)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun failedDownloadAndStaleGrantGiveRetryableRedactedStageWithoutAnArtifact() = runBlocking {
        val dir=tmp.newFolder()
        suspend fun attempt(fetch:PublicWebTransport, publish:suspend (() -> Unit)->Unit) =
            PdfSaveProgress().run {
                execute("call") { PublicPdfArtifact.save(dir,url,fetch,this,publish) { 1 } }
            }
        val failed=attempt(PublicWebTransport { _,_-> error("private.url.invalid private-cookie") }) { it() }
        val stale=attempt(transport()) { error("private.url.invalid private-cookie") }
        for ((result,stage) in listOf(failed to "PDF_FETCH", stale to "PDF_AUTHORITY")) {
            assertFalse(result.success)
            assertTrue(result.effectNotApplied)
            assertEquals(com.user.localagent.core.VerificationState.FAILED,result.verification)
            assertEquals(stage,result.verificationSummary)
            assertFalse(result.content.contains("private"))
            assertTrue(dir.listFiles()!!.isEmpty())
        }
    }

    @Test fun pcOfflineBeforeFetchingIsSafeToPauseAndAnUncertainPublicationCannotBeRetried() = runBlocking {
        val policy=com.user.localagent.core.WebsiteExecutionPolicy
        val progress=PdfSaveProgress()
        val before=progress.execute("fixture") {throw com.user.localagent.compute.PcBrowserNotConnected()}
        assertTrue(policy.waiting("save_browser_pdf",before))
        progress.publicationPossible=true
        val uncertain=progress.execute("fixture") {throw com.user.localagent.compute.PcBrowserNotConnected()}
        assertFalse(policy.waiting("save_browser_pdf",uncertain))
        assertFalse(uncertain.effectNotApplied)
        assertEquals(com.user.localagent.core.VerificationState.UNVERIFIED,uncertain.verification)
    }

    @Test fun exceptionAfterActualPublicationNeverMakesAReplaySafe() = runBlocking {
        val dir=tmp.newFolder()
        val progress=PdfSaveProgress()
        val result=progress.execute("call") {
            PublicPdfArtifact.save(dir,url,transport(),progress,publish={ commit->
                commit(); error("private.url.invalid private-cookie")
            }) { 1 }
        }
        assertFalse(result.success)
        assertFalse(result.effectNotApplied)
        assertEquals(com.user.localagent.core.VerificationState.UNVERIFIED,result.verification)
        assertEquals("PDF_READBACK",result.verificationSummary)
        assertFalse(result.content.contains("private"))
        assertArrayEquals(fixture,dir.listFiles()!!.single().readBytes())
    }

    @Test fun collisionOrCorruptionIsNeverOverwritten() = runBlocking {
        val dir = tmp.newFolder()
        PublicPdfArtifact.save(dir,url,transport()) { 1 }
        val target = dir.listFiles()!!.single()
        target.writeText("different")
        assertTrue(runCatching { PublicPdfArtifact.save(dir,url,transport()) { 1 } }.isFailure)
        assertEquals("different",target.readText())
        assertEquals(listOf(target),dir.listFiles()!!.toList())
    }
}
