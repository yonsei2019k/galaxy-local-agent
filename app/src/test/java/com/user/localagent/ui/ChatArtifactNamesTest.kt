package com.user.localagent.ui

import org.junit.Assert.*
import org.junit.Test

class ChatArtifactNamesTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    @Test fun everyDistinctArtifactIsKeptInResponseOrder(){
        val text="[[file:artifact-$a.pdf]] [[file:artifact-$b.docx]] [[file:artifact-$a.pdf]] [[image:image-$b.png]] [[image:image-$a.png]]"
        assertEquals(listOf("artifact-$a.pdf","artifact-$b.docx"),chatFileNames(text))
        assertEquals(listOf("image-$b.png","image-$a.png"),chatImageNames(text))
    }
    @Test fun pathsMalformedIdsAndUnsupportedFilesAreNotActionable(){
        assertTrue(chatFileNames("[[file:../artifact-$a.pdf]] [[file:artifact-$a.exe]] [[file:artifact-------------------------------------.pdf]]").isEmpty())
        assertTrue(chatImageNames("[[image:../image-$a.png]] [[image:image-$a.svg]]").isEmpty())
    }
    @Test fun quotedMarkerCannotBorrowAnArtifactFromAnotherRun(){
        val own="artifact-$a.pdf"
        val other="artifact-$b.pdf"
        val text="[[file:$other]] [[file:$own]]"
        val proof=kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(own)))
        assertEquals(listOf(own),verifiedChatFileNames(text,listOf(other,own),proof))
        assertTrue(verifiedChatFileNames(text,listOf(other),proof).isEmpty())
        assertTrue(verifiedChatFileNames("[[file:$other]]",listOf(own),proof).isEmpty())
    }
    @Test fun outputCardCountIsBounded(){
        val text=(1..100).joinToString {"[[file:artifact-00000000-0000-0000-0000-${it.toString().padStart(12,'0')}.pdf]]"}
        assertEquals(16,chatFileNames(text).size)
    }
}
