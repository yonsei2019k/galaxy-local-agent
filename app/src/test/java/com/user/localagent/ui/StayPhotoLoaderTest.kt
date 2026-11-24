package com.user.localagent.ui

import java.net.URI
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StayPhotoLoaderTest {
    private val property = "https://www.yeogi.com/domestic-accommodations/76460"
    private val media = "https://image.withstatic.com/fixture/a.jpg"

    @Test fun onlyTheVisibleAssistantRowOnTheSameBranchCanRequestAPhoto() {
        val record = buildJsonObject {
            put("version",1)
            put("currency","KRW")
            put("stays",buildJsonArray {
                add(buildJsonObject {put("url","https://example.org/first")})
                add(buildJsonObject {put("url",property);put("photoUrl",media)})
            })
        }
        val fence = "`".repeat(3)
        fun snapshot(role:String)=buildJsonObject {
            put("threadId","thread")
            put("branchId","branch")
            put("messages",buildJsonArray { add(buildJsonObject {
                put("id","message")
                put("role",role)
                put("text",fence+"json\nexample\n"+fence+"\n"+fence+"stays\n"+record+"\n"+fence)
            }) })
        }.toString()

        assertEquals(StayPhotoLoader.Candidate(property,media),
            StayPhotoLoader.candidate(snapshot("assistant"),"thread","branch","message",1))
        assertNull(StayPhotoLoader.candidate(snapshot("assistant"),"thread","other","message",1))
        assertNull(StayPhotoLoader.candidate(snapshot("user"),"thread","branch","message",1))
        assertNull(StayPhotoLoader.candidate(snapshot("assistant"),"thread","branch","message",2))
    }

    @Test fun findsAnHttpsMetadataImageAndRejectsUnrelatedImpersonation() {
        assertEquals("https://www.yeogi.com/media/a.jpg?x=1&y=2",
            StayPhotoLoader.ogImage(property,"<head><meta content='/media/a.jpg?x=1&amp;y=2' property='og:image'></head>")?.toString())
        assertNull(StayPhotoLoader.ogImage(property,"<meta property='og:image' content='http://example.org/a.jpg'>"))
        assertTrue(StayPhotoLoader.allowedDirect(URI(property),URI(media)))
        assertFalse(StayPhotoLoader.allowedDirect(URI(property),URI("https://image.withstatic.com.example.org/a.jpg")))
        assertEquals(media+"?format=webp&height=360&width=560",StayPhotoLoader.thumbnailUrl(URI(media)).toString())
    }
}
