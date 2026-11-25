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

    @Test fun campgroundMapUsesOnlyTheExactImageOnThePublicMapHost(){
        fun snapshot(image:String):String{
            val card=buildJsonObject {
                put("kind","camp_sites");put("version",1)
                put("items",buildJsonArray { add(buildJsonObject {
                    put("mapUrl","https://example.org/guide")
                    put("mapImageUrl",image)
                }) })
            }
            val fence="`".repeat(3)
            return buildJsonObject {
                put("threadId","thread");put("branchId","branch")
                put("messages",buildJsonArray {add(buildJsonObject {
                    put("id","message");put("role","assistant")
                    put("text","${fence}service-result\n$card\n$fence")
                })})
            }.toString()
        }
        val valid="https://example.org/layout.png"
        fun candidate(image:String)=StayPhotoLoader.candidate(snapshot(image),"thread","branch","message",0,map=true)
        assertEquals(StayPhotoLoader.Candidate("https://example.org/guide",valid,map=true),candidate(valid))
        assertNull(candidate("https://unrelated.example.com/layout.png"))
        assertNull(candidate(valid+"?session=sensitive"))
    }

    @Test fun localRecognitionDropsUnmatchedAmbiguousAndOutOfImageLabels(){
        val exact=StayPhotoLoader.SitePin("A-04",40.0,65.0)
        assertEquals(listOf(exact),StayPhotoLoader.matchedSitePins(setOf("A-04","A-12","A-02"),listOf(
            exact,exact.copy(site="A-09"),exact.copy(site="A-I2"),
            exact.copy(site="A-12"),exact.copy(site="A-12",x=42.0),exact.copy(site="A-02",x=480.0),
        )))
    }
}
