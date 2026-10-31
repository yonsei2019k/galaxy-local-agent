package com.user.localagent

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.data.DraftStore
import com.user.localagent.workspace.ChatAttachments
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic files/IDs only. This test never exports, prints or asserts real attachment text. */
@RunWith(AndroidJUnit4::class)
class ChatAttachmentsScopeValidation {
    private fun approved() = assumeTrue(
        "Synthetic attachment scope validation is explicitly gated",
        InstrumentationRegistry.getArguments().getString("attachmentScopeApproved") == "true",
    )

    @Test fun consumedRequestSurvivesRoomSwitchAndRestartWithoutLeakingChips() = runBlocking {
        approved()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val token = UUID.randomUUID().toString()
        val threadA = "validation-a-$token"
        val threadB = "validation-b-$token"
        val runId = "validation-run-$token"
        val sourceDir = File(context.cacheDir, "shared").apply { check(isDirectory || mkdirs()) }
        val sourceA = File(sourceDir, "scope-a-$token.txt").apply { writeText("SYNTHETIC_THREAD_A_DOCUMENT", Charsets.UTF_8) }
        val sourceB = File(sourceDir, "scope-b-$token.txt").apply { writeText("SYNTHETIC_THREAD_B_DOCUMENT", Charsets.UTF_8) }
        val sourceExtra = File(sourceDir, "scope-extra-$token.txt").apply { writeText("SYNTHETIC_ADDITIONAL_FILE\n".repeat(40_000), Charsets.UTF_8) }
        fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        try {
            val first = ChatAttachments(context, app)
            first.selectThread(threadA)
            first.add(listOf(uri(sourceA)))
            val itemsA = first.state.value.toList()
            assertEquals(1, itemsA.size)
            first.consumeForRequest(runId, threadA, itemsA)
            assertTrue(first.state.value.isEmpty())
            first.selectThread(threadB)
            first.add(listOf(uri(sourceB)))
            val itemsB = first.state.value.toList()
            assertEquals(1, itemsB.size)

            val quotedA = first.prepareInCurrentRequest("선택한 문서를 요약해줘", itemsA, runId)
            assertTrue("A request must retain its explicitly leased synthetic source", quotedA.contains("SYNTHETIC_THREAD_A_DOCUMENT"))
            assertFalse("The selected B source must not enter A's request", quotedA.contains("SYNTHETIC_THREAD_B_DOCUMENT"))
            first.restoreToThread(threadA, itemsA)
            assertEquals(itemsB, first.state.value)
            first.releaseRequest(runId)
            assertEquals(itemsB, first.state.value)

            first.selectThread(threadA)
            val copying = async(start = CoroutineStart.UNDISPATCHED) { first.add(listOf(uri(sourceExtra))) }
            first.selectThread(threadB)
            copying.await()
            assertEquals("A provider copy remains assigned to its originating room", itemsB, first.state.value)

            // A new service instance uses encrypted metadata, not the previous object's RAM.
            val restarted = ChatAttachments(context, app)
            restarted.selectThread(threadA)
            assertEquals(2, restarted.state.value.size)
            assertTrue(restarted.state.value.any { it.id == itemsA.single().id })
            val quotedRestoredA = restarted.prepareInCurrentRequest("선택한 문서를 요약해줘", itemsA)
            assertTrue(quotedRestoredA.contains("SYNTHETIC_THREAD_A_DOCUMENT"))
            restarted.selectThread(threadB)
            assertEquals(itemsB, restarted.state.value)
            restarted.add(listOf(uri(sourceA)),expectedThreadId=threadA)
            assertEquals("A picker result keeps its launch room after B becomes selected",itemsB,restarted.state.value)
            restarted.selectThread(threadA);assertEquals(3,restarted.state.value.size)
            restarted.selectThread(threadB)
            restarted.clearThread(threadA)
            assertEquals(itemsB, restarted.state.value)
            val quotedB = restarted.prepareInCurrentRequest("선택한 문서를 요약해줘", itemsB)
            assertTrue(quotedB.contains("SYNTHETIC_THREAD_B_DOCUMENT"))
            assertFalse(quotedB.contains("SYNTHETIC_THREAD_A_DOCUMENT"))
            val removedA = File(context.filesDir, "shared/chat-attachments/attachment-${itemsA.single().id}.input")
            assertFalse("A cleared synthetic source must be removed after its lease is released", removedA.exists())
        } finally {
            // Reload latest metadata and clear only this test's random scopes. Existing owner
            // slots, active request leases and source files remain outside the cleanup set.
            val cleanup = ChatAttachments(context, app)
            cleanup.releaseRequest(runId)
            cleanup.clearThread(threadA)
            cleanup.clearThread(threadB)
            sourceA.delete(); sourceB.delete(); sourceExtra.delete()
        }
    }

    @Test fun encryptedDraftRevisionsAreIndependentForDifferentScopes() {
        approved()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val first = DraftStore(context, "validation-draft-a-$token")
        val second = DraftStore(context, "validation-draft-b-$token")
        try {
            val revisionA = first.reserveWrite()
            val revisionB = second.reserveWrite()
            first.write("SYNTHETIC_DRAFT_A", revisionA)
            second.write("SYNTHETIC_DRAFT_B", revisionB)
            assertEquals("SYNTHETIC_DRAFT_A", first.readStrict())
            assertEquals("SYNTHETIC_DRAFT_B", second.readStrict())
            val stale = first.reserveWrite()
            val latest = first.reserveWrite()
            first.write("SYNTHETIC_NEW_A", latest)
            first.write("SYNTHETIC_STALE_A", stale)
            assertEquals("SYNTHETIC_NEW_A", DraftStore(context, "validation-draft-a-$token").readStrict())
            assertEquals("SYNTHETIC_DRAFT_B", second.readStrict())
        } finally { first.write(""); second.write("") }
    }

    @Test fun committedHistoryRetainsCopiesAcrossReleaseRestartAndConversationClone() = runBlocking {
        approved()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val token = UUID.randomUUID().toString()
        val threadA = "history-a-$token"
        val threadB = "history-b-$token"
        val runId = "history-run-$token"
        val resumedRunId = "history-resume-$token"
        val capturedRunId = "history-capture-$token"
        val source = File(File(context.cacheDir, "shared").apply { check(isDirectory || mkdirs()) }, "history-validation-$token.txt")
            .apply { writeText("SYNTHETIC_COMMITTED_ATTACHMENT_HISTORY", Charsets.UTF_8) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
        try {
            val first = ChatAttachments(context, app)
            first.selectThread(threadA)
            first.add(listOf(uri))
            val items = first.state.value.toList()
            val copy = File(context.filesDir, "shared/chat-attachments/attachment-${items.single().id}.input")
            first.consumeForRequest(runId, threadA, items)
            first.retainForHistory(runId, threadA)
            first.releaseRequest(runId)
            assertTrue("Completed requests retain their committed history source", copy.isFile)
            assertTrue(first.state.value.isEmpty())

            val restarted = ChatAttachments(context, app)
            restarted.selectThread(threadA)
            assertTrue("History must not become unsent draft chips", restarted.state.value.isEmpty())
            assertEquals(items, restarted.historyForThread(threadA))
            assertTrue(runCatching { restarted.prepareInCurrentRequest("선택한 문서 분석", items, resumedRunId) }.isFailure)
            restarted.recoverRequest(resumedRunId, threadA, items)
            restarted.releaseRequest(resumedRunId)
            assertTrue("Another room cannot acquire a source by its attachment ID", runCatching { restarted.recoverRequest(resumedRunId, threadB, items) }.isFailure)

            restarted.duplicateThread(threadA, threadB)
            restarted.clearThread(threadA)
            assertTrue("Clearing the source conversation preserves its clone's file", copy.isFile)
            val cloned = ChatAttachments(context, app)
            assertEquals(items, cloned.historyForThread(threadB))
            cloned.selectThread(threadB)
            assertTrue(cloned.state.value.isEmpty())
            cloned.clearThread(threadB)
            assertFalse("The final history reference releases the private source copy", copy.exists())

            // A UI chip can be removed during the database enqueue. The host's preallocated
            // request lease still protects the selected immutable copy until commit/retention.
            cloned.selectThread(threadA)
            cloned.add(listOf(uri))
            val captured = cloned.state.value.toList()
            val capturedCopy = File(context.filesDir, "shared/chat-attachments/attachment-${captured.single().id}.input")
            cloned.captureForRequest(capturedRunId, threadA, captured)
            cloned.remove(captured.single().id)
            assertTrue(capturedCopy.isFile)
            cloned.retainForHistory(capturedRunId, threadA)
            cloned.consumeForRequest(capturedRunId, threadA, captured)
            cloned.releaseRequest(capturedRunId)
            assertTrue(capturedCopy.isFile)
            cloned.clearThread(threadA)
            assertFalse(capturedCopy.exists())
        } finally {
            val cleanup = ChatAttachments(context, app)
            cleanup.releaseRequest(runId)
            cleanup.releaseRequest(resumedRunId)
            cleanup.releaseRequest(capturedRunId)
            cleanup.clearThread(threadA)
            cleanup.clearThread(threadB)
            source.delete()
        }
    }

    @Test fun prefixForkKeepsOnlyItsMessageSourcesAcrossRecursiveForkAndOwnerDeletion()=runBlocking {
        approved()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val token=UUID.randomUUID().toString()
        val sourceThread="prefix-source-$token";val fork="prefix-fork-$token";val nested="prefix-nested-$token";val foreign="prefix-other-$token"
        val threads=listOf(sourceThread,fork,nested,foreign)
        val runs=(1..4).map{"prefix-run-$it-$token"}
        val sources=(1..4).map{index->File(File(context.cacheDir,"shared").apply{check(isDirectory||mkdirs())},"prefix-$index-$token.txt").apply{writeText("SYNTHETIC_PREFIX_SOURCE_$index",Charsets.UTF_8)}}
        val firstMessage="prefix-message-first-$token";val lateMessage="prefix-message-late-$token";val legacyMessage="prefix-message-legacy-$token"
        val copiedFirst="prefix-copy-first-$token";val copiedLegacy="prefix-copy-legacy-$token"
        fun cached(item:com.user.localagent.workspace.ChatAttachment)=File(context.filesDir,"shared/chat-attachments/attachment-${item.id}.input")
        try {
            val files=ChatAttachments(context,app)
            suspend fun attach(index:Int,thread:String,message:String?):com.user.localagent.workspace.ChatAttachment {
                files.selectThread(thread)
                files.add(listOf(FileProvider.getUriForFile(context,"${context.packageName}.files",sources[index])))
                val item=files.state.value.single()
                files.consumeForRequest(runs[index],thread,listOf(item));files.retainForHistory(runs[index],thread,message);files.releaseRequest(runs[index])
                return item
            }
            val first=attach(0,sourceThread,firstMessage)
            val late=attach(1,sourceThread,lateMessage)
            val legacy=attach(2,sourceThread,null)
            val other=attach(3,foreign,"foreign-message-$token")
            val result=files.forkHistory(sourceThread,fork,mapOf(firstMessage to copiedFirst,legacyMessage to copiedLegacy),
                mapOf(legacyMessage to listOf(legacy,other)))
            assertEquals(2,result.retainedFiles);assertEquals(0,result.unmappedSourceFiles)
            assertEquals(setOf(first.id,legacy.id),files.historyForThread(fork).map{it.id}.toSet())
            files.clearThread(sourceThread)
            assertTrue(cached(first).isFile);assertTrue(cached(legacy).isFile);assertFalse(cached(late).exists());assertTrue(cached(other).isFile)
            val reopened=ChatAttachments(context,app)
            val nestedResult=reopened.forkHistory(fork,nested,mapOf(copiedFirst to "nested-message-$token"))
            assertEquals(1,nestedResult.retainedFiles)
            assertEquals(listOf(first),reopened.historyForThread(nested))
            reopened.clearThread(fork)
            assertTrue(cached(first).isFile);assertFalse(cached(legacy).exists());assertTrue(cached(other).isFile)
            reopened.clearThread(nested);assertFalse(cached(first).exists())
        } finally {
            val cleanup=ChatAttachments(context,app)
            runs.forEach{cleanup.releaseRequest(it)}
            threads.forEach{cleanup.clearThread(it)}
            sources.forEach{it.delete()}
        }
    }

    @Test fun failedSubmissionLeaseCannotRestoreOrRetainAfterItsThreadWasCleared()=runBlocking {
        approved()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val token=UUID.randomUUID().toString();val thread="clear-fence-$token";val run="clear-fence-run-$token"
        val source=File(File(context.cacheDir,"shared").apply{check(isDirectory||mkdirs())},"clear-fence-$token.txt").apply{writeText("SYNTHETIC_SUBMISSION_CLEAR_FENCE",Charsets.UTF_8)}
        try {
            val first=ChatAttachments(context,app)
            first.selectThread(thread);first.add(listOf(FileProvider.getUriForFile(context,"${context.packageName}.files",source)))
            val items=first.state.value.toList();val copy=File(context.filesDir,"shared/chat-attachments/attachment-${items.single().id}.input")
            first.consumeForRequest(run,thread,items)
            val reopened=ChatAttachments(context,app);reopened.selectThread(thread)
            reopened.clearThread(thread)
            val afterClear=ChatAttachments(context,app);afterClear.selectThread(thread)
            assertFalse(afterClear.restoreRequestDraft(run,thread))
            assertTrue(runCatching{afterClear.retainForHistory(run,thread,"cleared-message-$token")}.isFailure)
            assertTrue(afterClear.state.value.isEmpty());assertTrue(afterClear.historyForThread(thread).isEmpty())
            assertTrue("The outstanding request still owns its file until cleanup",copy.isFile)
            afterClear.releaseRequest(run)
            assertFalse(copy.exists())
        } finally {
            val cleanup=ChatAttachments(context,app);cleanup.releaseRequest(run);cleanup.clearThread(thread);source.delete()
        }
    }
}
