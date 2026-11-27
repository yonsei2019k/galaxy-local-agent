package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.os.SystemClock
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.automation.ChatGoalStore
import com.user.localagent.core.*
import com.user.localagent.data.LocalDatabase
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.ProjectSettings
import com.user.localagent.engine.RustExecutionJournal
import com.user.localagent.workspace.ProjectWorkspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Each phase uses a private, synthetic SQLCipher fixture. No model, site or owner database is accessed. */
@RunWith(AndroidJUnit4::class)
class GoalContinuityDeviceCanary {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token: String get() {
        val value = InstrumentationRegistry.getArguments().getString("c090Fixture").orEmpty()
        assumeTrue("Supply a new shared c090Fixture UUID for both restart phases",
            value.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
        return value
    }
    private val databaseName get() = "c090-goal-$token.db"
    private val marker get() = File(context.noBackupFilesDir, "c090-goal-$token.marker")
    private val root get() = File(context.noBackupFilesDir, "c090-goal-$token")
    private val prefPrefix get() = "c090-$token-"
    private val fixturePreferences get() = File(root, "used-prefs")
    private val preferenceLock = Any()
    private val openedPreferences = mutableSetOf<String>()
    private fun processIdentity() = "${Process.myPid()}:${Process.getStartUptimeMillis()}"

    private fun fixtureContext() = object : ContextWrapper(context) {
        private fun directory(name: String) = File(root, name).apply { check(isDirectory || mkdirs()) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = directory("files")
        override fun getCacheDir() = directory("cache")
        override fun getNoBackupFilesDir() = directory("no-backup")
        override fun getExternalFilesDir(type: String?) = directory("external")
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
            require(name.matches(Regex("[A-Za-z0-9._-]{1,256}")))
            synchronized(preferenceLock) {
                if (openedPreferences.add(name)) {
                    check(root.isDirectory || root.mkdirs())
                    fixturePreferences.appendText("$name\n")
                }
            }
            return baseContext.getSharedPreferences(prefPrefix + name, mode)
        }
    }

    private fun cleanFixture() {
        require(marker.isFile)
        if (context.getDatabasePath(databaseName).exists()) assertTrue(context.deleteDatabase(databaseName))
        if (fixturePreferences.isFile) for (name in fixturePreferences.readLines().distinct()) {
            require(name.matches(Regex("[A-Za-z0-9._-]{1,256}")))
            context.deleteSharedPreferences(prefPrefix + name)
        }
        check(root.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        if (root.exists()) assertTrue(root.deleteRecursively())
        assertTrue(marker.delete())
    }

    private fun openFixture(): LocalDatabase {
        System.loadLibrary("sqlcipher")
        val fixtureKey = MessageDigest.getInstance("SHA-256").digest("c090-synthetic-$token".toByteArray())
        return Room.databaseBuilder(context, LocalDatabase::class.java, databaseName)
            .openHelperFactory(SupportOpenHelperFactory(fixtureKey))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
    }

    @Test fun seedRunningParents() = runBlocking(Dispatchers.IO) {
        check(!context.getDatabasePath(databaseName).exists() && marker.createNewFile()) {
            "Fixture already exists. Use a fresh UUID."
        }
        val db = openFixture()
        try {
            val repo = LocalRepository(db)
            seedParent(repo, "resolved")
            seedParent(repo, "inflight")
        } finally {
            db.close()
        }
        marker.writeText(processIdentity())
    }

    private suspend fun seedParent(repo: LocalRepository, kind: String) {
        val project = repo.chat.createProject("Synthetic $kind", ProjectSettings(useOwnerMemory = false,
            disabledTools = ToolRegistry.definitions.keys - "read_note"))
        val room = repo.chat.createThread(project.id, "Synthetic $kind")
        val access = repo.chat.accessScope(room.id)
        val user = repo.chat.addMessage(room.id, room.activeBranchId, "user", "Read the synthetic fixture note")
        val run = repo.chat.createRun(room.id, room.activeBranchId, user.id, "synthetic-local", runId = "c090-$kind")
        val goal = ChatGoalStore(repo).saveGuarded(access, GoalDefinition(
            threadId = room.id, projectId = project.id, outcome = user.text,
            criteria = listOf(GoalCriterion(text = "Confirm the second synthetic observation")),
            status = GoalStatus.ACTIVE, linkedRunIds = listOf(run.id)))
        val segment = GoalSegmentBinding(goal.id, goal.updatedAt, goalWorkDigest(goal), access.scopeKey,
            access.threadRevision, access.useOwnerMemory, access.memoryEnabled, run.id, null, null, 1,
            System.currentTimeMillis(), 0, 0)
        val scoped = repo.forThread(room.id)
        val note = scoped.saveNote("C090 synthetic note", "Scoped test data")
        var request = QueuedChatRequest(run.id, room.id, room.activeBranchId, project.id, user.text, run.modelId,
            false, emptyList(), user.id, goal = goal, goalSegment = segment,
            workspaceBinding = ProjectWorkspace(fixtureContext(), project.workspaceKey).bindingFingerprint())
        repo.chat.updateRunContext(run.id, request.toJson())
        repo.chat.startRun(run.id)
        val clock = GoalActiveClock()
        val binding = goalJournalBinding(request, access.scopeKey).toString()
        val call = ToolCall("read-fixture", "read_note", buildJsonObject { put("id", note.id) })
        val journal = RustExecutionJournal(binding, localGoalJournalProfile(request))
        GoalEffectRecorder(journal, { binding }) { checkpoint ->
            val envelope = Json.parseToJsonElement(request.toJson()).jsonObject
            repo.chat.checkpointRun(run.id, JsonObject(envelope + mapOf(
                "sharedEngineState" to Json.parseToJsonElement(checkpoint),
                "goalActiveMs" to JsonPrimitive(clock.activeMs()))).toString())
        }.use { recorder ->
            assertTrue(recorder.begin(call))
            if (kind == "resolved") {
                val read = checkNotNull(scoped.getNote(note.id))
                assertEquals(note, read)
                val evidence = StepEvidence(call.name, VerificationState.VERIFIED, "Exact synthetic note matched")
                request = request.copy(previousSteps = listOf(HarnessStep(call, evidence)))
                recorder.finish(ToolResult(call.id, true, read.body, evidence.state, evidence.summary))
            }
        }
    }

    @Test fun verifyAfterProcessRestart() = runBlocking(Dispatchers.IO) {
        check(marker.isFile && context.getDatabasePath(databaseName).isFile)
        assertNotEquals("Start this phase in a new instrumentation process", marker.readText(), processIdentity())
        val db = openFixture()
        try {
            val repo = LocalRepository(db)
            val resolvedRow = checkNotNull(repo.chat.run("c090-resolved"))
            val unknownRow = checkNotNull(repo.chat.run("c090-inflight"))
            assertEquals("running", resolvedRow.status)
            assertEquals("running", unknownRow.status)
            val originalGoals = listOf(resolvedRow, unknownRow).associate { row ->
                row.id to checkNotNull(ChatGoalStore(repo).load(row.threadId))
            }
            val selected = dispatchRecoveredChild(repo)
            for (kind in listOf("resolved", "inflight")) {
                val row = checkNotNull(repo.chat.run("c090-$kind"))
                assertEquals("interrupted", row.status)
                assertEquals(if (kind == "resolved") "process_restarted" else GoalAutopilotPauseReason.UNKNOWN_EFFECT.code, row.errorCode)
                val access = repo.chat.accessScope(row.threadId)
                val goal = originalGoals.getValue(row.id)
                val unknown = goalRunEffectsUnresolved(row, access.scopeKey)
                val now = System.currentTimeMillis()
                val decision = goalRestartDecision(row, goal, access.scopeKey, now, unknown, true)
                if (kind == "inflight") {
                    assertTrue(unknown)
                    assertEquals(GoalSegmentDecision.Pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT), decision)
                    val saved = checkNotNull(ChatGoalStore(repo).loadGuarded(access))
                    assertEquals(GoalStatus.PAUSED, saved.status)
                    assertEquals(listOf(row.id), saved.linkedRunIds)
                    assertEquals(1, repo.chat.runsForThread(row.threadId).size)
                } else {
                    assertFalse(unknown)
                    val next = decision as GoalSegmentDecision.Continue
                    assertEquals(row.id, next.binding.parentRunId)
                    assertEquals(2, next.binding.segmentIndex)
                    assertEquals("read_note", next.ledger.single().step.tool)
                    assertEquals(goalRunActiveMs(row.contextJson, row.startedAt, row.finishedAt), next.binding.activeElapsedMs)
                    assertEquals(next.binding, selected.goalSegment)
                    val child = checkNotNull(repo.chat.run(selected.runId))
                    assertNotNull(child.startedAt)
                    assertEquals("EXECUTION_INITIALIZATION_FAILED", child.errorCode)
                    assertEquals(row.threadId, child.threadId)
                    assertEquals(listOf(row.id, selected.runId), checkNotNull(ChatGoalStore(repo).loadGuarded(access)).linkedRunIds)
                    assertFalse(selected.allowCloudText || selected.allowExternalAgent)
                    requireGoalLaunchBinding(selected, selected.goal, row, false, now, true)
                }
            }
            assertEquals(0, repo.chat.recoverInterruptedRuns())
        } finally {
            db.close()
        }
        cleanFixture()
    }

    /** Let normal startup recover rows, link the new run, commit it and start it. Stop at engine admission. */
    private suspend fun dispatchRecoveredChild(repo: LocalRepository): QueuedChatRequest {
        val isolated = fixtureContext()
        val app = AppContainer(isolated, repositoryOverride = repo, assistantWorkEnabled = false)
        val selected = CompletableDeferred<QueuedChatRequest>()
        val coordinator = withContext(Dispatchers.Main) {
            ChatRunCoordinator(isolated, app, workerEngineFactory = { request ->
                check(request.goalSegment?.parentRunId == "c090-resolved")
                check(selected.complete(request))
                error("Synthetic test stops before choosing any model")
            }, contactLookup = { _, _ -> error("Unexpected contact lookup") },
                externalIntentLauncher = { error("Unexpected external launch") })
        }
        try {
            return withTimeout(30_000) {
                val child = selected.await()
                while (repo.chat.run(child.runId)?.errorCode != "EXECUTION_INITIALIZATION_FAILED") delay(25)
                child
            }
        } finally {
            coordinator.scope.coroutineContext[Job]?.cancelAndJoin()
            app.cancelIdleUnload()
        }
    }

    /** Explicit cleanup after a failed run is limited to the supplied synthetic fixture UUID. */
    @Test fun discardSyntheticFixture() {
        cleanFixture()
    }

    /** The full run spends 30 real minutes monitoring only a fixed synthetic in-memory condition. */
    @Test fun realActiveClockExcludesApprovalWaitAtNativeLimit() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("c090Clock")
        assumeTrue("Use c090Clock=full or boundary", mode in setOf("full", "boundary"))
        val limit = GoalSegmentBinding.MAX_DURATION_MS
        assertEquals(1_800_000L, limit)
        val baseline = if (mode == "full") 0L else limit - 2_000L
        val segment = GoalSegmentBinding("fixture", 0, "a".repeat(64), "scope", 0, false, false,
            "fixture-run", null, null, 1, System.currentTimeMillis(), 0, 0)
        val clock = GoalActiveClock(baselineMs = baseline)
        val start = SystemClock.elapsedRealtime()
        var waitingMs = 0L
        clock.excluding {
            val before = clock.activeMs()
            val waitStart = SystemClock.elapsedRealtime()
            delay(2_500)
            waitingMs = SystemClock.elapsedRealtime() - waitStart
            assertEquals(before, clock.activeMs())
            assertNull(goalBudgetStop(segment, 0, clock.activeMs(), 0, true))
        }
        var checks = 0
        while (true) {
            val activeMs = clock.activeMs()
            val stop = goalBudgetStop(segment, 0, activeMs, 0, true)
            if (stop != null) {
                assertEquals(GoalAutopilotPauseReason.TIME_BUDGET, stop)
                assertEquals(limit, activeMs)
                break
            }
            checks++
            delay(minOf(1_000L, limit - activeMs))
        }
        val wallMs = SystemClock.elapsedRealtime() - start
        assertTrue(waitingMs >= 2_500 && wallMs >= limit - baseline + waitingMs - 20 && checks > 0)
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("c090ClockEvidence", "mode=$mode baselineMs=$baseline activeMs=${clock.activeMs()} approvalWaitMs=$waitingMs wallMs=$wallMs checks=$checks")
        })
    }
}
