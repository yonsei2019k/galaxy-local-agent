package com.user.localagent.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DraftRevisionsTest {
    @Test fun separateRootsWithTheSameFilenameDoNotInvalidateEachOthersPendingWrite() {
        val root=Files.createTempDirectory("public-draft-revision-qa-").toFile()
        try{
            val first=DraftRevisions.forFile(File(root,"a/chat-draft.enc"));val second=DraftRevisions.forFile(File(root,"b/chat-draft.enc"))
            val reserved=first.incrementAndGet();second.incrementAndGet();second.incrementAndGet()
            assertEquals(reserved,first.get());assertNotSame(first,second)
        }finally{assertTrue(root.delete())}
    }
    @Test fun alternatePathsToTheSameActualFileShareCancellationOrdering() {
        val root=Files.createTempDirectory("public-draft-alias-qa-").toFile()
        try{
            val first=DraftRevisions.forFile(File(root,"chat-draft.enc"));val alias=DraftRevisions.forFile(File(root,"./chat-draft.enc"))
            val reserved=first.incrementAndGet();alias.incrementAndGet()
            assertSame(first,alias);assertNotEquals(reserved,first.get())
        }finally{assertTrue(root.delete())}
    }
}
