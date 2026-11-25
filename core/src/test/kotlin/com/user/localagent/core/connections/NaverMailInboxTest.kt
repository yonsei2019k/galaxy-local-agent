package com.user.localagent.core.connections

import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NaverMailInboxTest {
    private val ready="* OK hello\r\nZ1 OK logged in\r\n"
    @Test fun validatedTlsAndReadOnlyLatestHeaders() {
        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use {
            NaverMailInbox.secure(it)
            assertEquals("HTTPS",it.sslParameters.endpointIdentificationAlgorithm)
            assertTrue(it.soTimeout>0)
        }
        val subject="Subject: =?UTF-8?B?7ZWc6riA?=\r\nFrom: example\r\nDate: yesterday\r\n\r\n"
        val stream=ready+"* 14 EXISTS\r\nZ2 OK read-only\r\n* 14 FETCH (UID 1 BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {"+subject.length+"}\r\n"+subject+")\r\nZ3 OK fetched\r\n"
        val output=ByteArrayOutputStream()
        val messages=NaverMailInbox(stream.byteInputStream(),output).inbox("test","fake-app-password",false)
        val row=messages.single().jsonObject
        assertEquals("한글",row.getValue("subject").jsonPrimitive.content)
        assertEquals(setOf("from","subject","date"),row.keys)
        assertTrue(output.toString().endsWith("Z2 EXAMINE INBOX\r\nZ3 FETCH 5:14 (UID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])\r\n"))
    }
    @Test fun probeAndRejectionNeverReadMessages() {
        val output=ByteArrayOutputStream()
        assertTrue(NaverMailInbox((ready+"* 9 EXISTS\r\nZ2 OK opened\r\n").byteInputStream(),output).inbox("test","fake-app-password",true).isEmpty())
        assertFalse(output.toString().contains("FETCH"))
        assertThrows(IllegalStateException::class.java){
            NaverMailInbox("* OK hi\r\nZ1 NO denied\r\n".byteInputStream(),ByteArrayOutputStream()).inbox("test","fake-app-password",false)
        }
        assertThrows(IllegalArgumentException::class.java){
            NaverMailInbox("".byteInputStream(),ByteArrayOutputStream()).inbox("test\r\nSELECT", "fake-app-password",false)
        }
    }
}
