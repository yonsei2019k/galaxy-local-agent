package com.user.localagent.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BoundPdfGetTest {
    private val source="https://fixture.example.org/courses"
    private val target="https://fixture.example.org/certificates/original.pdf"

    @Test fun onlyObservedPlainSameOriginPdfLinksQualify() {
        assertTrue(BoundPdfGet.eligible(source,target))
        for (candidate in listOf(target.replace("fixture","else"),target+"?id=9",target+"#p",
            source+"/redirect.php",source+"/../private/doc.pdf",source+"/%2Ffile.pdf",
            target.replace("https:","http:"),target.replace("original.pdf","../other.pdf"))) {
            assertFalse(candidate,BoundPdfGet.eligible(source,candidate))
        }
    }

    @Test fun checksExactDestinationBeforeReadingSessionAndRejectsExpiredOrInvalidSession()=runBlocking {
        var reads=0
        var active=true
        val bound=BoundPdfGet(target) { reads++; check(active); "session=fixture" }
        for(other in listOf(target+"2",target.replace("fixture","other"))) {
            assertTrue(runCatching { bound.headerFor(other) }.isFailure)
        }
        assertEquals(0,reads)
        assertEquals("Cookie: session=fixture\r\n",bound.headerFor(target))
        active=false
        assertTrue(runCatching { bound.headerFor(target) }.isFailure)
        assertTrue(runCatching { BoundPdfGet(target){"bad\r\nvalue"}.headerFor(target) }.isFailure)
    }
}
