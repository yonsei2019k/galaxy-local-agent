package com.user.localagent

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit recovery of this harness's synthetic finish fixture after a native process abort. */
@RunWith(AndroidJUnit4::class)
class NativeQaFixtureRecovery {
    @Test fun recoverExactlyBoundSyntheticFinishFixtures() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("nativeQaFixtureRecoveryApproved") == "true")
        val after = requireNotNull(arguments.getString("fixtureCreatedAfter")).toLong()
        val before = requireNotNull(arguments.getString("fixtureCreatedBefore")).toLong()
        val expectedCount = requireNotNull(arguments.getString("expectedFixtureCount")).toInt()
        require(before >= after && before - after <= 600_000L && expectedCount in 0..2)
        val app = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LocalAgentApplication).container
        val chat = app.repository.chat
        val prefix = "QA synthetic finish "
        val expected = "사용자 원문 조건 🙂\n".repeat(700) + "마지막 조건 그대로 보존"
        val candidates = chat.threads.first().filter { thread ->
            thread.projectId == null && thread.createdAt in after..before &&
                thread.title.startsWith(prefix) && runCatching {
                    UUID.fromString(thread.title.removePrefix(prefix)).toString() == thread.title.removePrefix(prefix)
                }.getOrDefault(false)
        }
        assertEquals(expectedCount, candidates.size)
        // Validate every candidate before deleting any row.
        for (thread in candidates) {
            val runs = chat.runsForThread(thread.id)
            require(runs.size == 1)
            val run = runs.single()
            val request = QueuedChatRequest.fromJson(run.contextJson)
            require(run.modelId == "synthetic-no-weights" && run.projectId == null && run.createdAt in after..before)
            require(request.runId == run.id && request.threadId == thread.id && request.branchId == thread.activeBranchId)
            require(request.userMessageId == run.userMessageId && request.projectId == null)
            require(request.text == expected && !request.allowTools && !request.allowCloudText)
            require(request.attachments.isEmpty() && request.previousSteps.isEmpty())
            require(chat.fullMessageText(requireNotNull(run.userMessageId)) == expected)
        }
        withContext(NonCancellable) {
            for (thread in candidates) {
                chat.deleteThread(thread.id, deleteDerivedMemory = true)
                assertNull(chat.thread(thread.id))
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putInt("ownedSyntheticFixturesRemoved", candidates.size) })
    }
}
