package com.user.localagent

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.skills.EmailFileTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

/** Only our synthetic EML file. No account, mail provider, contacts or outgoing message. */
@RunWith(AndroidJUnit4::class)
class EmailNativeValidation {
    @Test fun selectedSyntheticEmlHasDecodedHeadersBodyAndAttachmentMetadata()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.filesDir,"exports").apply {check(isDirectory||mkdirs())}
        val file=File(directory,"email-qa-${UUID.randomUUID()}.eml")
        val title="합성 이메일 확인";val body="오늘 회의는 오전 열 시입니다."
        try {
            file.writeText("From: synthetic@example.invalid\r\nTo: recipient@example.invalid\r\nSubject: =?UTF-8?B?${Base64.getEncoder().encodeToString(title.toByteArray())}?=\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=owned-test\r\n\r\n--owned-test\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n${Base64.getEncoder().encodeToString(body.toByteArray())}\r\n--owned-test\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=sample.bin\r\nContent-Transfer-Encoding: base64\r\n\r\nAAEC\r\n--owned-test--\r\n")
            val data=EmailFileTools(context).readEml(FileProvider.getUriForFile(context,"${context.packageName}.files",file))
            assertEquals(title,data.subject);assertEquals(body,data.bodyText.trim())
            assertEquals(1,data.attachments.size);assertEquals(3L,data.attachments.single().decodedSizeBytes)
            assertTrue(data.untrustedData)
            assertTrue(context.getSharedPreferences("validated-tools",android.content.Context.MODE_PRIVATE).edit().putBoolean("email.read_eml",true).commit())
        }finally {check(file.canonicalFile.parentFile==directory.canonicalFile);check(!file.exists()||file.delete())}
    }
}
