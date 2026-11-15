package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.analysis.*
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.*
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.imports.ConversationImport
import com.user.localagent.inference.*
import com.user.localagent.models.*
import com.user.localagent.skills.BundledSkillStore
import com.user.localagent.workspace.ProjectWorkspace
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual paired PC, direct analysis component API, one synthetic source/question/chunk.
 * Not a normal NLP/ToolExecutor/Coordinator run, owner-data test or general semantic quality score. */
@RunWith(AndroidJUnit4::class)
class ActualPcPrivateAnalysisCanary {
    private class IsolatedContext(base: Context, private val root: File, private val prefix: String) : ContextWrapper(base) {
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        private fun directory(name: String) = File(root, name).apply { check(isDirectory || mkdirs()) { "QA_DIRECTORY" } }
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getExternalFilesDir(type: String?): File = directory("external")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name.length in 1..512 && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "QA_PREFERENCE_NAME" }
            val own = "$prefix-$name"
            synchronized(preferences) { preferences += own }
            return baseContext.getSharedPreferences(own, mode)
        }
        fun cleanPreferences() {
            synchronized(preferences) { preferences.toList() }.forEach {
                check(it.startsWith("$prefix-") && baseContext.deleteSharedPreferences(it)) { "QA_PREFERENCE_CLEANUP" }
            }
        }
    }

    private fun fixedFailureCode(failure: Throwable): String =
        failure.message?.takeIf { it in FIXED_FAILURE_CODES } ?: "QA_UNCLASSIFIED_FAILURE"
    private companion object {
        val FIXED_FAILURE_CODES = setOf(
            "QA_ACTUAL_GENERATION_UNOBSERVED",
            "QA_ACTUAL_MODEL_PIN",
            "QA_ACTUAL_TERMINAL_INVALID",
            "QA_ACTUAL_TERMINAL_MISSING",
            "QA_ANALYSIS_NOT_VERIFIED",
            "QA_ANALYSIS_PARTIAL",
            "QA_ARTIFACT_BOUNDARY",
            "QA_ARTIFACT_HASH",
            "QA_ARTIFACT_NAME",
            "QA_ARTIFACT_NOT_PRIVATE",
            "QA_AUTHORITY_CHANGED",
            "QA_CAPTURED_THREAD_MISSING",
            "QA_CHECKPOINT_MISMATCH",
            "QA_CHECKPOINT_MISSING",
            "QA_CLEANUP_ARTIFACT_REMAINS",
            "QA_CLEANUP_ARTIFACT_ROSTER_CHANGED",
            "QA_CLEANUP_DATABASE_BOUNDARY",
            "QA_CLEANUP_LEDGER_NOT_ERASED",
            "QA_CLEANUP_MESSAGE_ROSTER",
            "QA_CLEANUP_NOTES_REMAIN",
            "QA_CLEANUP_NOTE_CHANGED",
            "QA_CLEANUP_NOTE_REMAINS",
            "QA_CLEANUP_NOTE_ROSTER_CHANGED",
            "QA_CLEANUP_PARTIAL_FIXTURE_UNKNOWN",
            "QA_CLEANUP_PROJECT_MISSING",
            "QA_CLEANUP_REQUEST_CHANGED",
            "QA_CLEANUP_ROOT_BOUNDARY",
            "QA_CLEANUP_RUN_ROSTER",
            "QA_CLEANUP_SCOPE_CHANGED",
            "QA_CLEANUP_SCOPE_REMAINS",
            "QA_CLEANUP_WORKSPACE_CHANGED",
            "QA_COMMITTED_ROOT_CHANGED",
            "QA_COMMITTED_ROOT_MISSING",
            "QA_COMMITTED_STEP_CHANGED",
            "QA_COMMITTED_STEP_MISSING",
            "QA_DATABASE_BOUNDARY",
            "QA_DATABASE_DELETE_FAILED",
            "QA_DATABASE_REMAINS",
            "QA_DIRECTORY",
            "QA_DIRECT_REQUEST_BINDING_CHANGED",
            "QA_EMPTY_FINDINGS",
            "QA_EVIDENCE_READ_FAILED",
            "QA_EVIDENCE_READ_MISMATCH",
            "QA_EXACT_CITATION_MISMATCH",
            "QA_EXECUTION_LEFT_PC",
            "QA_EXPECTED_VERSION_REQUIRED",
            "QA_FINAL_ARTIFACT_MISSING",
            "QA_FINAL_CONFIRMATION_NOT_QUOTED",
            "QA_FINAL_EVIDENCE_NOT_SELECTED",
            "QA_FIXTURE_BOUNDS",
            "QA_FRESH_CHECKPOINT_MISSING",
            "QA_HEALTH_IDENTITY",
            "QA_IMPORTED_NOTE_MISSING",
            "QA_IMPORT_MISMATCH",
            "QA_INSTALLED_VERSION_MISMATCH",
            "QA_LOCAL_ONLY_REQUIRED",
            "QA_MODEL_CLEANUP_UNKNOWN",
            "QA_OWNER_ACTIVE",
            "QA_OWNER_BECAME_ACTIVE",
            "QA_OWNER_MODEL_RESIDENT",
            "QA_OWNER_QUEUED",
            "QA_PAIRING_LOST",
            "QA_PAIRING_REQUIRED",
            "QA_PC_CHOICE_CHANGED",
            "QA_PC_CHOICE_MISSING",
            "QA_PC_NOT_EMPTY_AND_IDLE",
            "QA_PC_RESOURCE_BUSY",
            "QA_PHONE_LOAD_FORBIDDEN",
            "QA_PREFERENCE_CLEANUP",
            "QA_PREFERENCE_NAME",
            "QA_QUESTION_CHANGED",
            "QA_RECOVERY_ABSENCE_FAILED",
            "QA_RECOVERY_AUTHORITY",
            "QA_RECOVERY_DATABASE_BOUNDARY",
            "QA_RECOVERY_DATABASE_FILE",
            "QA_RECOVERY_DATABASE_NAME",
            "QA_RECOVERY_DATABASE_REMAINS",
            "QA_RECOVERY_FIXTURE_MISSING",
            "QA_RECOVERY_NAMES",
            "QA_RECOVERY_ORIGINAL_OWNER",
            "QA_RECOVERY_OWNER_ACTIVE",
            "QA_RECOVERY_PAIRING",
            "QA_RECOVERY_PC_NOT_IDLE",
            "QA_RECOVERY_PREFERENCE_NOT_EMPTY",
            "QA_RECOVERY_PREFERENCE_REMAINS",
            "QA_RECOVERY_PREFERENCE_ROSTER",
            "QA_RECOVERY_RECHECK_CHANGED",
            "QA_RECOVERY_REPORT_CHANGED",
            "QA_RECOVERY_REPORT_CONTRACT",
            "QA_RECOVERY_REPORT_SHA",
            "QA_RECOVERY_RESOURCE_BUSY",
            "QA_RECOVERY_REVISION",
            "QA_RECOVERY_ROOT_BOUNDARY",
            "QA_RECOVERY_ROOT_NAME",
            "QA_RECOVERY_ROOT_REMAINS",
            "QA_RECOVERY_TREE_BOUNDARY",
            "QA_RECOVERY_TREE_UNREADABLE",
            "QA_RECOVERY_UNKNOWN_CACHE_FILE",
            "QA_RECOVERY_VERSION",
            "QA_REPORT_DIRECTORY",
            "QA_REQUEST_DISAPPEARED",
            "QA_ROOT_BOUNDARY",
            "QA_ROOT_CREATION",
            "QA_ROOT_DELETE_FAILED",
            "QA_ROOT_REMAINS",
            "QA_SOURCE_CHANGED",
            "QA_SOURCE_CHUNK",
            "QA_SOURCE_MISMATCH",
            "QA_UNCLASSIFIED_FAILURE",
            "QA_WORKSPACE_KEY",
            "QA_WORKSPACE_NOT_PRIVATE",
        )
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun writeReport(file: File, value: JsonObject) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) { "QA_REPORT_DIRECTORY" }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }

    @Test fun realPcAnalysisRetainsFinalCorrectionInSavedEvidenceAndReport() = runBlocking<Unit>(Dispatchers.Default) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("actualPcAnalysisCanaryApproved=true required", arguments.getString("actualPcAnalysisCanaryApproved") == "true")
        val expectedVersion = checkNotNull(arguments.getString("actualPcAnalysisExpectedVersion")?.toLongOrNull()) { "QA_EXPECTED_VERSION_REQUIRED" }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val version = base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode
        check(version == expectedVersion) { "QA_INSTALLED_VERSION_MISMATCH" }
        // Initialize only the real application. Never replace shared factories with a second AppContainer.
        val app = (base.applicationContext as LocalAgentApplication).container
        suspend fun ownerQuiet() {
            check(!app.runs.hasActiveRuns.value && !app.runs.busy.value && !app.imageStudio.state.value.running &&
                !RuntimeGate.inference.get() && !RuntimeGate.benchmark.get()) { "QA_OWNER_ACTIVE" }
            check(app.repository.chat.queuedRuns().isEmpty()) { "QA_OWNER_QUEUED" }
        }
        ownerQuiet()
        check(!RuntimeGate.pc.get()) { "QA_PC_RESOURCE_BUSY" }
        check(app.engine.residentModelPath == null) { "QA_OWNER_MODEL_RESIDENT" }
        val settings = PcComputeSettings.get(base)
        val original = checkNotNull(settings.snapshot()) { "QA_PAIRING_REQUIRED" }
        val revision = settings.authorizationRevision
        check(!settings.state.value.claudeText) { "QA_LOCAL_ONLY_REQUIRED" }
        fun authorityUnchanged() {
            val current = checkNotNull(settings.snapshot()) { "QA_PAIRING_LOST" }
            check(settings.authorizationRevision == revision && !settings.state.value.claudeText &&
                current.serverId == original.serverId && current.token == original.token &&
                current.certificateSha256 == original.certificateSha256) { "QA_AUTHORITY_CHANGED" }
        }
        suspend fun health(owner: String?): JsonObject {
            authorityUnchanged()
            val value = withContext(Dispatchers.IO) {
                PcTransport(base).json(original, "/v1/health", timeout = 2_000, computeOwner = owner)
            }
            check(value["service"]?.jsonPrimitive?.content == "ZipHapCompute" &&
                value["serverId"]?.jsonPrimitive?.content == original.serverId &&
                value["compute_owner_binding"]?.jsonPrimitive?.intOrNull == 1) { "QA_HEALTH_IDENTITY" }
            return value
        }
        fun emptyIdle(value: JsonObject) = value["compute_busy"]?.jsonPrimitive?.booleanOrNull == false &&
            value["cleanup_pending"]?.jsonPrimitive?.booleanOrNull == false && value["llmResident"] is JsonNull

        val prefix = "qa-actual-pc-analysis-${UUID.randomUUID()}"
        val parent = base.cacheDir.canonicalFile
        val root = File(parent, prefix).canonicalFile
        check(root.parentFile == parent && root.name == prefix && !root.exists()) { "QA_ROOT_BOUNDARY" }
        val isolatedContext = IsolatedContext(base, root, prefix)
        val databaseName = "$prefix.db"
        val databaseFile = base.getDatabasePath(databaseName).canonicalFile
        check(databaseFile.name == databaseName && databaseFile.parentFile ==
            base.getDatabasePath("qa-actual-pc-analysis-anchor.db").canonicalFile.parentFile && !databaseFile.exists()) { "QA_DATABASE_BOUNDARY" }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val report = File(base.getExternalFilesDir(null) ?: base.filesDir, "benchmarks/actual-pc-analysis-canary.json")
        val startedAt = SystemClock.elapsedRealtime()
        var stage = "initial_health"
        var failureStage: String? = null
        var failureKind: String? = null
        var failureCode: String? = null
        var cleanupFailureCode: String? = null
        var cleanupFailure = false
        var cleanupStage = "not_started"
        var db: LocalDatabase? = null
        var isolated: LocalRepository? = null
        var scoped: LocalRepository? = null
        var project: ProjectRecord? = null
        var thread: ChatThreadRecord? = null
        var request: QueuedChatRequest? = null
        var workspace: ProjectWorkspace? = null
        var workspaceBinding: String? = null
        var busyLease: Closeable? = null
        var engine: AutomaticComputeEngine? = null
        var remotePreparationAttempted = false
        var validated = false
        var modelCleanupVerified = false
        var fixtureCleanupVerified = false
        var preparedFullSha: String? = null
        var sourceBytes = 0
        var processedChunks = 0
        var findingsCount = 0
        var exactCitationCount = 0
        var finalFindingVerified = false
        var finalSelectedVerified = false
        var finalReadbackVerified = false
        var finalArtifactVerified = false
        var artifactHashVerified = false
        var journalReadbackVerified = false
        var sourceUnchangedVerified = false
        var actualPcExecutionObserved = false
        val ownedNotes = linkedMapOf<String, NoteRecord>()
        var originalNotes: Map<String, NoteRecord> = emptyMap()
        val artifacts = linkedMapOf<File, String>()
        fun emit() = writeReport(report, buildJsonObject {
            // Private QA-only recovery locators; callers export only fixed verdict metadata.
            put("privateRuntimeData", true); put("qaCacheRootName", prefix); put("qaDatabaseName", databaseName)
            put("qaComputeOwner", request?.runId); put("qaCapturedRevision", revision)
            put("schemaVersion", 1); put("installedVersion", version); put("expectedVersion", expectedVersion)
            put("stage", stage); put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
            put("passed", validated && failureStage == null && modelCleanupVerified && fixtureCleanupVerified && !cleanupFailure)
            put("validationPassed", validated); put("failureStage", failureStage); put("failureKind", failureKind)
            put("cleanupStage", cleanupStage); put("cleanupFailure", cleanupFailure)
            put("failureCode", failureCode); put("cleanupFailureCode", cleanupFailureCode)
            put("modelCleanupVerified", modelCleanupVerified); put("fixtureCleanupVerified", fixtureCleanupVerified)
            put("plannedSourceCount", 1); put("plannedQuestionCount", 1); put("maxChunks", 1); put("sourceBytes", sourceBytes)
            put("registeredSourceCount", if (originalNotes.size == 2) 1 else 0); put("capturedRequestCreated", request != null)
            put("processedChunks", processedChunks); put("savedFindingCount", findingsCount); put("exactCitationCount", exactCitationCount)
            put("actualPreparedModelSha256", preparedFullSha); put("actualPcExecutionObserved", actualPcExecutionObserved)
            put("journalReadbackVerified", journalReadbackVerified); put("sourceUnchangedVerified", sourceUnchangedVerified)
            put("finalFindingVerified", finalFindingVerified); put("finalSelectedVerified", finalSelectedVerified)
            put("finalReadbackVerified", finalReadbackVerified); put("finalArtifactVerified", finalArtifactVerified)
            put("artifactHashVerified", artifactHashVerified)
            put("directComponentCanary", true); put("syntheticOnly", true); put("normalNlpToolSelectionTested", false)
            put("ownerDatabaseUsedForFixture", false); put("semanticAccuracyVerified", false); put("asrVerified", false)
            put("modelOutputInjected", false); put("completedRunInjected", false)
        })
        emit()
        try {
            withTimeout(600_000) {
                check(emptyIdle(health(null))) { "QA_PC_NOT_EMPTY_AND_IDLE" }
                stage = "fixture_registration"; emit()
                busyLease = app.updates.acquireBusy(prefix)
                ownerQuiet(); authorityUnchanged()
                check(root.mkdir()) { "QA_ROOT_CREATION" }
                System.loadLibrary("sqlcipher")
                val database = Room.databaseBuilder(base, LocalDatabase::class.java, databaseName)
                    .openHelperFactory(SupportOpenHelperFactory(key.clone()))
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build().also { db = it }
                val repository = LocalRepository(database).also { isolated = it }
                val projectSettings = ProjectSettings(modelId = PairedPcTextProfile.SELECTION_ID, contextSize = 8192,
                    useOwnerMemory = false, learnOwnerFacts = false, disabledTools = ToolRegistry.definitions.keys)
                val ownProject = repository.chat.createProject(prefix, projectSettings).also { project = it }
                check(ownProject.workspaceKey == ownProject.id) { "QA_WORKSPACE_KEY" }
                val ownThread = repository.chat.createThread(ownProject.id, prefix).also { thread = it }
                val scope = repository.chat.accessScope(ownThread.id)
                val selected = repository.forThread(ownThread.id).also { scoped = it }
                val ownSpace = ProjectWorkspace(isolatedContext, ownProject.workspaceKey).also { workspace = it }
                val binding = ownSpace.bindingFingerprint().also { workspaceBinding = it }
                check(!ownSpace.state.value.external && ownSpace.list().isEmpty()) { "QA_WORKSPACE_NOT_PRIVATE" }
                val question = "제안과 정정을 모두 읽고, 시험 회의의 마지막 확정 내용을 정확한 원문 한 문장으로 인용해 정리해줘."
                val message = repository.chat.addMessage(ownThread.id, ownThread.activeBranchId, "user", question)
                val run = repository.chat.createRun(ownThread.id, ownThread.activeBranchId, message.id,
                    PairedPcTextProfile.SELECTION_ID, 8192, false)
                val originalRequest = QueuedChatRequest(run.id, ownThread.id, ownThread.activeBranchId, ownProject.id,
                    question, PairedPcTextProfile.SELECTION_ID, false, emptyList(), message.id,
                    workspaceBinding = binding, capturedPcRevision = revision, allowCloudText = false).also { request = it }
                repository.chat.updateRunContext(run.id, originalRequest.toJson())
                // Message insertion touches updatedAt; retain the actual post-capture room snapshot.
                thread = checkNotNull(repository.chat.thread(ownThread.id)) { "QA_CAPTURED_THREAD_MISSING" }
                val actualChoice = checkNotNull(RequestInferenceSelection.pairedText(originalRequest.modelId,
                    originalRequest.capturedPcRevision, originalRequest.allowCloudText, settings.authorizationRevision,
                    settings.snapshot() != null, thinking = false)) { "QA_PC_CHOICE_MISSING" }
                check(actualChoice.model is PairedPcText && actualChoice.options.contextSize == 8192) { "QA_PC_CHOICE_CHANGED" }
                val actualEngine = AutomaticComputeEngine(base, ownerRunId = originalRequest.runId,
                    beforePhoneLoad = { error("QA_PHONE_LOAD_FORBIDDEN") },
                    capturedPcRevision = originalRequest.capturedPcRevision, allowCloudText = false).also { engine = it }

                val finalSentence = "최종 확정: 시험 회의는 2026-12-03 15:20에 가상 온빛관에서 엽니다."
                val fixture = "합성A: 시험 회의는 2026-12-02 10:00, 가상 누리관을 제안합니다.\n" +
                    "합성B: 앞선 날짜와 장소 제안을 취소합니다. 새 일정은 아직 미정입니다.\n" +
                    "합성A: $finalSentence\n"
                val fixtureBytes = fixture.toByteArray(Charsets.UTF_8)
                sourceBytes = fixtureBytes.size
                check(sourceBytes in 1 until 4096 && fixture.length <= 180) { "QA_FIXTURE_BOUNDS" }
                val imported = ConversationImport(isolatedContext, selected, selected::discardIncompleteConversationNotes).importText(fixture)
                check(!imported.duplicate && imported.chunkCount == 1 && imported.noteIds.size == 2 &&
                    imported.contentSha256 == sha(fixtureBytes)) { "QA_IMPORT_MISMATCH" }
                imported.noteIds.forEach { id -> ownedNotes[id] = checkNotNull(selected.getNote(id)) { "QA_IMPORTED_NOTE_MISSING" } }
                originalNotes = ownedNotes.toMap()
                val sources = LocalPrivateAnalysisSources(isolatedContext, selected, scope.scopeKey,
                    cleanupIncomplete = selected::discardIncompletePrivateSourceNotes)
                val snapshot = sources.conversationImport(imported.contentSha256)
                check(snapshot.coreSource.ref.scopeId == scope.scopeKey && snapshot.coreSource.ref.kind == PrivateEvidenceKind.IMPORTED_CONVERSATION &&
                    snapshot.coreSource.ref.contentSha256 == imported.contentSha256 &&
                    snapshot.metadata.getValue("originalFileHashRecomputed").jsonPrimitive.boolean &&
                    snapshot.metadata.getValue("originalFileSha256").jsonPrimitive.content == imported.contentSha256 &&
                    snapshot.metadata.getValue("decodedTextSha256").jsonPrimitive.content == imported.contentSha256 &&
                    snapshot.metadata.getValue("fullImportedText").jsonPrimitive.boolean &&
                    snapshot.metadata.getValue("enumerationComplete").jsonPrimitive.boolean &&
                    !snapshot.metadata.getValue("outsideSelectedFileIncluded").jsonPrimitive.boolean &&
                    !snapshot.metadata.getValue("wholePhoneHistoryVerified").jsonPrimitive.boolean) { "QA_SOURCE_MISMATCH" }
                val page = PrivateAnalysisEvidence.page(snapshot.coreSource, 4096, 256, 1)
                check(page.chunks.size == 1 && page.next == null && page.sourceExhausted && page.chunks.single().text == fixture) { "QA_SOURCE_CHUNK" }
                val commit = AnalysisCheckpointCommit { rootId, expected, rootBody, stepId, stepBody ->
                    selected.commitPrivateAnalysisCheckpoint(rootId, expected, rootBody, stepId, stepBody)
                    val rootNote = checkNotNull(selected.getNote(rootId)) { "QA_COMMITTED_ROOT_MISSING" }
                    check(rootNote.body == rootBody && rootNote.collection == RepositoryAnalysisJournal.COLLECTION) { "QA_COMMITTED_ROOT_CHANGED" }
                    ownedNotes[rootId] = rootNote
                    if (stepId != null) {
                        val note = checkNotNull(selected.getNote(stepId)) { "QA_COMMITTED_STEP_MISSING" }
                        check(note.body == stepBody && note.collection == RepositoryAnalysisJournal.COLLECTION) { "QA_COMMITTED_STEP_CHANGED" }
                        ownedNotes[stepId] = note
                    }
                }
                val journal = RepositoryAnalysisJournal(selected, scope.scopeKey, commit)
                stage = "actual_model_preparation"; emit()
                ownerQuiet(); authorityUnchanged()
                remotePreparationAttempted = true
                val prepared = actualEngine.preparePrivateAnalysis(actualChoice.model, actualChoice.options.privateAnalysisOptions(1024))
                preparedFullSha = prepared.modelSha256
                check(prepared.modelSha256 == PairedPcTextProfile.MODEL_SHA256 &&
                    actualEngine.computeLocation.value == ComputeResource.PC && RuntimeGate.pc.owner.value == run.id) { "QA_ACTUAL_MODEL_PIN" }
                val executor = PrivateAnalysisExecutor(isolatedContext, app, selected, scope.scopeKey, sources, journal,
                    choose = { supplied -> check(supplied == question) { "QA_QUESTION_CHANGED" }; authorityUnchanged(); actualChoice },
                    progress = { authorityUnchanged(); check(!app.runs.hasActiveRuns.value && !app.runs.busy.value) { "QA_OWNER_BECAME_ACTIVE" } },
                    requestEngine = actualEngine)
                stage = "actual_analysis"; emit()
                val result = executor.execute(ToolCall(UUID.randomUUID().toString(), "analyze_conversation_import", buildJsonObject {
                    put("contentSha256", imported.contentSha256); put("question", question); put("maxChunks", 1)
                }))
                val payload = Json.parseToJsonElement(result.content).jsonObject
                // Register only the artifact actually returned by production. No scanning of owner exports.
                val artifact = payload.getValue("artifact").jsonObject
                val exports = File(isolatedContext.filesDir, "exports").canonicalFile
                val name = artifact.getValue("fileName").jsonPrimitive.content
                check(Regex("artifact-[a-f0-9-]{36}\\.txt").matches(name)) { "QA_ARTIFACT_NAME" }
                val file = File(exports, name).canonicalFile
                check(file.parentFile == exports && file.isFile) { "QA_ARTIFACT_BOUNDARY" }
                val declaredHash = artifact.getValue("sha256").jsonPrimitive.content
                val bytes = file.readBytes()
                check(bytes.size.toLong() == artifact.getValue("bytes").jsonPrimitive.long && sha(bytes) == declaredHash) { "QA_ARTIFACT_HASH" }
                artifacts[file] = declaredHash; artifactHashVerified = true
                check(artifact.getValue("privateArtifact").jsonPrimitive.boolean &&
                    artifact.getValue("exportOrShareRequiresUserAction").jsonPrimitive.boolean) { "QA_ARTIFACT_NOT_PRIVATE" }
                check(result.success && result.verification == VerificationState.VERIFIED) { "QA_ANALYSIS_NOT_VERIFIED" }
                stage = "independent_readback"; emit()
                val coverage = payload.getValue("coverage").jsonObject
                check(payload.getValue("status").jsonPrimitive.content == "complete_selected_text" &&
                    payload.getValue("analysisCompleteWithinSelectedText").jsonPrimitive.boolean &&
                    coverage.getValue("completeSelectedText").jsonPrimitive.boolean &&
                    coverage.getValue("knownTextBytes").jsonPrimitive.int == sourceBytes &&
                    coverage.getValue("processedBytes").jsonPrimitive.int == sourceBytes &&
                    coverage.getValue("retrievedBytes").jsonPrimitive.int == sourceBytes &&
                    coverage.getValue("reasons").jsonArray.isEmpty()) { "QA_ANALYSIS_PARTIAL" }
                val analysisId = payload.getValue("analysisId").jsonPrimitive.content
                val stored = checkNotNull(journal.load(analysisId)) { "QA_CHECKPOINT_MISSING" }
                val fresh = checkNotNull(RepositoryAnalysisJournal(selected, scope.scopeKey, commit).load(analysisId)) { "QA_FRESH_CHECKPOINT_MISSING" }
                check(fresh == stored && fresh.source == snapshot.coreSource.ref && fresh.sourceLocator ==
                    snapshot.metadata.getValue("sourceLocator").jsonObject && fresh.question == question &&
                    fresh.modelSha256 == prepared.modelSha256 && fresh.skillSha256 == BundledSkillStore(isolatedContext).read("message-analysis").sha256 &&
                    fresh.sourceExhausted && fresh.nextByte == null && fresh.processed.size == 1) { "QA_CHECKPOINT_MISMATCH" }
                journalReadbackVerified = true; processedChunks = fresh.processed.size
                val findings = fresh.processed.flatMap { it.findings }
                findingsCount = findings.size
                check(findings.isNotEmpty()) { "QA_EMPTY_FINDINGS" }
                findings.forEach { finding ->
                    val cite = finding.citation
                    check(PrivateAnalysisEvidence.validateCitation(snapshot.coreSource, page.chunks,
                        PrivateCitationProposal(cite.chunkId, cite.quote, cite.startByte, cite.endByte)).citation == cite) { "QA_EXACT_CITATION_MISMATCH" }
                    exactCitationCount++
                }
                val finalFindings = findings.filter { it.citation.quote.contains(finalSentence) }
                check(finalFindings.isNotEmpty()) { "QA_FINAL_CONFIRMATION_NOT_QUOTED" }
                finalFindingVerified = true
                val selectedFindings = payload.getValue("findings").jsonArray.map { it.jsonObject }
                check(payload.getValue("overviewSelectionVerified").jsonPrimitive.boolean && selectedFindings.any { item ->
                    finalFindings.any { it.id == item.getValue("evidenceId").jsonPrimitive.content } &&
                        item.getValue("quote").jsonPrimitive.content.contains(finalSentence)
                }) { "QA_FINAL_EVIDENCE_NOT_SELECTED" }
                finalSelectedVerified = true
                finalFindings.forEach { finding ->
                    val read = executor.execute(ToolCall(UUID.randomUUID().toString(), "read_analysis_evidence", buildJsonObject {
                        put("analysisId", analysisId); put("evidenceId", finding.id)
                    }))
                    check(read.success && read.verification == VerificationState.VERIFIED) { "QA_EVIDENCE_READ_FAILED" }
                    val evidence = Json.parseToJsonElement(read.content).jsonObject
                    val item = evidence.getValue("findings").jsonArray.single().jsonObject
                    check(evidence.getValue("sourceTextSha256").jsonPrimitive.content == imported.contentSha256 &&
                        evidence.getValue("analysisId").jsonPrimitive.content == analysisId &&
                        evidence.getValue("semanticAccuracyVerified").jsonPrimitive.boolean == false &&
                        item.getValue("evidenceId").jsonPrimitive.content == finding.id &&
                        item.getValue("quote").jsonPrimitive.content == finding.citation.quote &&
                        item.getValue("startByte").jsonPrimitive.int == finding.citation.startByte &&
                        item.getValue("endByte").jsonPrimitive.int == finding.citation.endByte) { "QA_EVIDENCE_READ_MISMATCH" }
                }
                finalReadbackVerified = true
                val reportText = String(bytes, Charsets.UTF_8)
                check(payload.getValue("reportContainsAllFindings").jsonPrimitive.boolean &&
                    payload.getValue("reportOmittedFindingCount").jsonPrimitive.int == 0 &&
                    payload.getValue("reportFindingCount").jsonPrimitive.int == findings.size &&
                    reportText.contains(finalSentence) && finalFindings.all { reportText.contains(it.id) && reportText.contains(it.citation.quote) }) { "QA_FINAL_ARTIFACT_MISSING" }
                finalArtifactVerified = true
                check(originalNotes.all { (id, note) -> selected.getNote(id) == note } &&
                    sources.conversationImport(imported.contentSha256).coreSource.ref == snapshot.coreSource.ref) { "QA_SOURCE_CHANGED" }
                sourceUnchangedVerified = true
                val terminal = checkNotNull(actualEngine.lastGeneration.value) { "QA_ACTUAL_TERMINAL_MISSING" }
                check(!terminal.mayBeTruncated && terminal.historyTurnsDropped == 0 &&
                    (terminal.reason == GenerationStopReason.EOG ||
                        terminal.reason == GenerationStopReason.SDK_DONE_UNKNOWN && terminal.capReached == false)) { "QA_ACTUAL_TERMINAL_INVALID" }
                actualPcExecutionObserved = actualEngine.computeLocation.value == ComputeResource.PC &&
                    actualEngine.executionLocation == "pc"
                check(actualPcExecutionObserved) { "QA_ACTUAL_GENERATION_UNOBSERVED" }
                actualEngine.preparePrivateAnalysis(actualChoice.model, actualChoice.options.privateAnalysisOptions(1024), prepared)
                check(actualEngine.computeLocation.value == ComputeResource.PC && RuntimeGate.pc.owner.value == run.id) { "QA_EXECUTION_LEFT_PC" }
                val unchanged = checkNotNull(repository.chat.run(run.id)) { "QA_REQUEST_DISAPPEARED" }
                check(unchanged.status == "queued" && QueuedChatRequest.fromJson(unchanged.contextJson) == originalRequest) { "QA_DIRECT_REQUEST_BINDING_CHANGED" }
                ownerQuiet(); authorityUnchanged()
                validated = true; stage = "validation_passed"; emit()
            }
        } catch (failure: Throwable) {
            failureStage = stage
            failureCode = fixedFailureCode(failure)
            failureKind = when (failure) {
                is TimeoutCancellationException -> "TIMEOUT"
                is CancellationException -> "CANCELLED"
                is InferencePrerequisiteMissing -> "PREREQUISITE"
                else -> "REJECTED"
            }
            // Do not copy exception bodies, synthetic model output or private binding values to reports.
        } finally {
            withContext(NonCancellable) {
                try {
                    cleanupStage = "own_engine"
                    engine?.let { actual ->
                        actual.cancel()
                        withTimeout(60_000) { actual.unload() }
                        if (remotePreparationAttempted) {
                            val receipt = actual.lastPcCleanup.value
                            check(receipt?.operation == "unload" && receipt.acknowledged && receipt.cleanupComplete == true &&
                                actual.computeLocation.value == null && RuntimeGate.pc.owner.value != request?.runId && emptyIdle(health(request?.runId))) { "QA_MODEL_CLEANUP_UNKNOWN" }
                        }
                    }
                    modelCleanupVerified = true
                    cleanupStage = "fixture_roster"
                    val repository = isolated
                    val selected = scoped
                    if (repository != null && project != null) {
                        val p = checkNotNull(project)
                        val r = checkNotNull(thread)
                        val current = checkNotNull(repository.chat.project(p.id)) { "QA_CLEANUP_PROJECT_MISSING" }
                        check(current == p && repository.chat.threads.first().filter { it.projectId == p.id } == listOf(r) &&
                            repository.chat.branches(r.id).map { it.id } == listOf(r.activeBranchId)) { "QA_CLEANUP_SCOPE_CHANGED" }
                        val ownRequest = request
                        val rows = repository.chat.runsForThread(r.id)
                        check(rows.size == if (ownRequest == null) 0 else 1) { "QA_CLEANUP_RUN_ROSTER" }
                        ownRequest?.let { expected -> check(rows.single().id == expected.runId && rows.single().status == "queued" &&
                            QueuedChatRequest.fromJson(rows.single().contextJson) == expected) { "QA_CLEANUP_REQUEST_CHANGED" } }
                        check(repository.chat.messages(r.id).first().all { message ->
                            ownRequest != null && (message.id == ownRequest.userMessageId && message.role == "user" && message.text == ownRequest.text ||
                                message.role == "assistant" && message.runId == ownRequest.runId && message.text.isBlank())
                        }) { "QA_CLEANUP_MESSAGE_ROSTER" }
                        val own = checkNotNull(selected)
                        check(own.notes.first().toSet() == ownedNotes.values.toSet() && originalNotes.all { (id, note) -> own.getNote(id) == note } &&
                            repository.reminderIdsForProjectRemoval(p.id).isEmpty()) { "QA_CLEANUP_NOTE_ROSTER_CHANGED" }
                        workspace?.let { space -> check(!space.state.value.external && space.bindingFingerprint() == workspaceBinding && space.list().isEmpty()) { "QA_CLEANUP_WORKSPACE_CHANGED" } }
                        val exports = File(isolatedContext.filesDir, "exports").canonicalFile
                        check(exports.listFiles().orEmpty().map { it.canonicalFile }.toSet() == artifacts.keys && artifacts.all { (file, hash) ->
                            file.parentFile == exports && file.isFile && sha(file.readBytes()) == hash
                        }) { "QA_CLEANUP_ARTIFACT_ROSTER_CHANGED" }
                        // Verify the complete roster before the first deletion; CAS protects each actual note.
                        ownedNotes.values.forEach { note ->
                            check(own.getNote(note.id) == note) { "QA_CLEANUP_NOTE_CHANGED" }
                            own.deleteNote(note.id, expectedUpdatedAt = note.updatedAt)
                            check(own.getNote(note.id) == null) { "QA_CLEANUP_NOTE_REMAINS" }
                        }
                        artifacts.keys.forEach { file -> check(file.delete() && !file.exists()) { "QA_CLEANUP_ARTIFACT_REMAINS" } }
                        check(own.notes.first().isEmpty()) { "QA_CLEANUP_NOTES_REMAIN" }
                        repository.chat.deleteProject(p.id, deleteDerivedMemory = true)
                        workspace?.discard()
                        check(repository.chat.project(p.id) == null && repository.chat.thread(r.id) == null &&
                            ownedNotes.keys.all { repository.getNote(it) == null } && artifacts.keys.none { it.exists() }) { "QA_CLEANUP_SCOPE_REMAINS" }
                        // eraseThread intentionally retains a cancelled run ledger and erases its envelope.
                        val retained = repository.chat.runsForThread(r.id)
                        check(retained.size == rows.size && retained.zip(rows).all { (after, prior) ->
                            after.id == prior.id && after.threadId == prior.threadId && after.branchId == prior.branchId &&
                                after.projectId == prior.projectId && after.userMessageId == prior.userMessageId &&
                                after.status == "cancelled" && after.errorCode == "thread_cleared" && after.contextJson.isEmpty()
                        }) { "QA_CLEANUP_LEDGER_NOT_ERASED" }
                    } else check(repository == null || repository.notes.first().isEmpty()) { "QA_CLEANUP_PARTIAL_FIXTURE_UNKNOWN" }
                    cleanupStage = "own_database_files"
                    db?.close(); db = null
                    check(base.getDatabasePath(databaseName).canonicalFile == databaseFile) { "QA_CLEANUP_DATABASE_BOUNDARY" }
                    if (databaseFile.exists()) check(base.deleteDatabase(databaseName)) { "QA_DATABASE_DELETE_FAILED" }
                    check(!databaseFile.exists()) { "QA_DATABASE_REMAINS" }
                    isolatedContext.cleanPreferences()
                    check(root.canonicalFile == root && root.parentFile == parent && root.name == prefix) { "QA_CLEANUP_ROOT_BOUNDARY" }
                    if (root.exists()) check(root.deleteRecursively()) { "QA_ROOT_DELETE_FAILED" }
                    check(!root.exists()) { "QA_ROOT_REMAINS" }
                    fixtureCleanupVerified = true; cleanupStage = "complete"
                } catch (failure: Throwable) {
                    // Unknown cleanup retains the exact fixture; never widen deletion to owner storage.
                    cleanupFailure = true; cleanupFailureCode = fixedFailureCode(failure)
                } finally {
                    try { db?.close() } catch (failure: Throwable) { cleanupFailure = true; if (cleanupFailureCode == null) cleanupFailureCode = fixedFailureCode(failure) }
                    finally {
                        key.fill(0)
                        try { busyLease?.close() } catch (failure: Throwable) { cleanupFailure = true; if (cleanupFailureCode == null) cleanupFailureCode = fixedFailureCode(failure) }
                        stage = "finished"; emit()
                    }
                }
            }
        }
        check(validated && failureStage == null && modelCleanupVerified && fixtureCleanupVerified && !cleanupFailure) {
            "QA_ACTUAL_PC_ANALYSIS_FAILED: consult content-free stage/cleanup metadata"
        }
    }

    @Test fun recoverExactFailedSyntheticFixtureWithoutChangingItsFailureReport() = runBlocking<Unit>(Dispatchers.Default) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("actualPcAnalysisRecoveryApproved=true required", args.getString("actualPcAnalysisRecoveryApproved") == "true")
        val expectedVersion = checkNotNull(args.getString("actualPcAnalysisRecoveryExpectedVersion")?.toLongOrNull()) { "QA_RECOVERY_VERSION" }
        val expectedRevision = checkNotNull(args.getString("actualPcAnalysisRecoveryExpectedRevision")?.toLongOrNull()) { "QA_RECOVERY_REVISION" }
        val expectedSha = checkNotNull(args.getString("actualPcAnalysisRecoveryReportSha256")) { "QA_RECOVERY_REPORT_SHA" }
        check(Regex("[a-f0-9]{64}").matches(expectedSha)) { "QA_RECOVERY_REPORT_SHA" }
        val rootName = checkNotNull(args.getString("actualPcAnalysisRecoveryCacheRootName")) { "QA_RECOVERY_ROOT_NAME" }
        val databaseName = checkNotNull(args.getString("actualPcAnalysisRecoveryDatabaseName")) { "QA_RECOVERY_DATABASE_NAME" }
        val uuidPattern = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        check(Regex("qa-actual-pc-analysis-$uuidPattern").matches(rootName) && databaseName == "$rootName.db") { "QA_RECOVERY_NAMES" }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode == expectedVersion) { "QA_RECOVERY_VERSION" }
        val app = (base.applicationContext as LocalAgentApplication).container
        val settings = PcComputeSettings.get(base)
        val original = checkNotNull(settings.snapshot()) { "QA_RECOVERY_PAIRING" }
        val parent = base.cacheDir.canonicalFile
        val root = File(parent, rootName).canonicalFile
        check(root.parentFile == parent && root.name == rootName && !java.nio.file.Files.isSymbolicLink(File(parent, rootName).toPath())) { "QA_RECOVERY_ROOT_BOUNDARY" }
        val database = base.getDatabasePath(databaseName).canonicalFile
        val databaseParent = base.getDatabasePath("qa-actual-pc-analysis-anchor.db").canonicalFile.parentFile
        check(database.parentFile == databaseParent && database.name == databaseName &&
            !java.nio.file.Files.isSymbolicLink(base.getDatabasePath(databaseName).toPath())) { "QA_RECOVERY_DATABASE_BOUNDARY" }
        val failureReport = File(base.getExternalFilesDir(null) ?: base.filesDir, "benchmarks/actual-pc-analysis-canary.json")
        val recoveryReport = File(base.getExternalFilesDir(null) ?: base.filesDir, "benchmarks/actual-pc-analysis-recovery.json")
        var stage = "private_report"
        var failedStage: String? = null
        var code: String? = null
        var deletionStarted = false
        var passed = false
        var phone: ComputeResourceBroker.Lease? = null
        var pc: ComputeResourceBroker.Lease? = null
        var updateLease: Closeable? = null
        val recoveryOwner = UUID.randomUUID().toString()
        fun emitRecovery() = writeReport(recoveryReport, buildJsonObject {
            put("schemaVersion", 1); put("stage", stage); put("failedStage", failedStage); put("failureCode", code)
            put("passed", passed); put("deletionStarted", deletionStarted); put("expectedVersion", expectedVersion)
            put("originalFailureRegraded", false); put("ownerDatabaseOpened", false); put("modelInvoked", false)
        })
        fun authority() {
            val current = checkNotNull(settings.snapshot()) { "QA_RECOVERY_PAIRING" }
            check(settings.authorizationRevision == expectedRevision && !settings.state.value.claudeText &&
                current.serverId == original.serverId && current.token == original.token &&
                current.certificateSha256 == original.certificateSha256) { "QA_RECOVERY_AUTHORITY" }
        }
        suspend fun quiet() {
            fun allowed(held: ComputeResourceBroker.Lease?, resource: ComputeResource, gate: OwnedComputeGate): Boolean =
                if (held == null) !gate.get() && gate.owner.value == null else held.resource == resource &&
                    held.owner == recoveryOwner && gate.get() && gate.owner.value == held.owner
            check(!app.runs.hasActiveRuns.value && !app.runs.busy.value && !app.imageStudio.state.value.running &&
                !RuntimeGate.benchmark.get() && allowed(phone, ComputeResource.PHONE, RuntimeGate.inference) &&
                allowed(pc, ComputeResource.PC, RuntimeGate.pc) && app.repository.chat.queuedRuns().isEmpty()) { "QA_RECOVERY_OWNER_ACTIVE" }
        }
        suspend fun idle(owner: String) {
            authority(); quiet()
            val value = withContext(Dispatchers.IO) { PcTransport(base).json(original, "/v1/health", timeout = 2_000, computeOwner = owner) }
            check(value["service"]?.jsonPrimitive?.content == "ZipHapCompute" &&
                value["serverId"]?.jsonPrimitive?.content == original.serverId && value["compute_owner_binding"]?.jsonPrimitive?.intOrNull == 1 &&
                value["compute_busy"]?.jsonPrimitive?.booleanOrNull == false &&
                value["cleanup_pending"]?.jsonPrimitive?.booleanOrNull == false && value["llmResident"] is JsonNull) { "QA_RECOVERY_PC_NOT_IDLE" }
        }
        try {
            emitRecovery()
            val reportBytes = failureReport.readBytes()
            check(reportBytes.size in 1..32_768 && sha(reportBytes) == expectedSha) { "QA_RECOVERY_REPORT_CHANGED" }
            val report = Json.parseToJsonElement(String(reportBytes, Charsets.UTF_8)).jsonObject
            fun flag(name: String) = report[name]?.jsonPrimitive?.booleanOrNull == true
            fun isFalse(name: String) = report[name]?.jsonPrimitive?.booleanOrNull == false
            check(report.getValue("schemaVersion").jsonPrimitive.int == 1 && report.getValue("qaCacheRootName").jsonPrimitive.content == rootName &&
                report.getValue("qaDatabaseName").jsonPrimitive.content == databaseName &&
                report.getValue("qaCapturedRevision").jsonPrimitive.long == expectedRevision &&
                report.getValue("installedVersion").jsonPrimitive.long == expectedVersion &&
                report.getValue("expectedVersion").jsonPrimitive.long == expectedVersion &&
                report.getValue("stage").jsonPrimitive.content == "finished" &&
                report.getValue("cleanupStage").jsonPrimitive.content == "fixture_roster" &&
                isFalse("passed") && flag("cleanupFailure") && isFalse("fixtureCleanupVerified") &&
                report.getValue("failureStage") is JsonNull && flag("privateRuntimeData") && flag("syntheticOnly") &&
                flag("validationPassed") && flag("modelCleanupVerified") && flag("directComponentCanary") &&
                isFalse("ownerDatabaseUsedForFixture") && isFalse("modelOutputInjected") && isFalse("completedRunInjected") &&
                listOf("actualPcExecutionObserved", "journalReadbackVerified", "sourceUnchangedVerified", "finalFindingVerified",
                    "finalSelectedVerified", "finalReadbackVerified", "finalArtifactVerified", "artifactHashVerified").all(::flag) &&
                report.getValue("processedChunks").jsonPrimitive.int == 1 && report.getValue("registeredSourceCount").jsonPrimitive.int == 1 &&
                report.getValue("savedFindingCount").jsonPrimitive.int == 1 && report.getValue("exactCitationCount").jsonPrimitive.int == 1 &&
                report.getValue("actualPreparedModelSha256").jsonPrimitive.content == PairedPcTextProfile.MODEL_SHA256) { "QA_RECOVERY_REPORT_CONTRACT" }
            val originalOwner = report.getValue("qaComputeOwner").jsonPrimitive.content
            check(UUID.fromString(originalOwner).toString() == originalOwner) { "QA_RECOVERY_ORIGINAL_OWNER" }
            authority(); quiet()
            phone = RuntimeGate.compute.tryAcquire(recoveryOwner, ComputeResource.PHONE) ?: error("QA_RECOVERY_RESOURCE_BUSY")
            pc = RuntimeGate.compute.tryAcquire(recoveryOwner, ComputeResource.PC) ?: error("QA_RECOVERY_RESOURCE_BUSY")
            updateLease = app.updates.acquireBusy(recoveryOwner)
            idle(originalOwner)
            stage = "exact_empty_cache_and_opaque_database"
            // The source defect is after source/artifact/project removal. With the random key gone,
            // never pretend to inspect DB rows or accept an unknown remaining plaintext file.
            check(root.isDirectory && database.isFile) { "QA_RECOVERY_FIXTURE_MISSING" }
            fun emptyTree(): Set<String> {
                val paths = linkedSetOf<String>()
                fun visit(directory: File, depth: Int) {
                    check(depth <= 8 && paths.size < 256 && directory.canonicalFile == directory.absoluteFile &&
                        directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath())) { "QA_RECOVERY_TREE_BOUNDARY" }
                    paths += directory.relativeTo(root).path
                    val children = checkNotNull(directory.listFiles()) { "QA_RECOVERY_TREE_UNREADABLE" }
                    children.forEach { child ->
                        check(child.canonicalFile.toPath().startsWith(root.toPath()) && child.isDirectory &&
                            !java.nio.file.Files.isSymbolicLink(child.toPath())) { "QA_RECOVERY_UNKNOWN_CACHE_FILE" }
                        visit(child, depth + 1)
                    }
                }
                visit(root, 0); return paths
            }
            val tree = emptyTree()
            val databaseFiles = listOf("", "-journal", "-wal", "-shm").map { suffix -> File(databaseParent, databaseName + suffix) }
            fun databaseSnapshot() = databaseFiles.filter { it.exists() }.associate { file ->
                check(file.canonicalFile.parentFile == databaseParent && file.canonicalFile == file.absoluteFile &&
                    !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= 32 * 1024 * 1024L) { "QA_RECOVERY_DATABASE_FILE" }
                file.name to sha(file.readBytes())
            }
            val databaseHashes = databaseSnapshot()
            val prefsDirectory = File(base.applicationInfo.dataDir, "shared_prefs").canonicalFile
            val preferencePattern = Regex(Regex.escape(rootName) + "-project-workspace-v1-$uuidPattern\\.xml")
            val preferenceFiles = prefsDirectory.listFiles { file -> file.name.startsWith("$rootName-") }.orEmpty()
            check(preferenceFiles.size <= 2 && preferenceFiles.all { file -> preferencePattern.matches(file.name) &&
                file.canonicalFile.parentFile == prefsDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath()) }) { "QA_RECOVERY_PREFERENCE_ROSTER" }
            val preferences = preferenceFiles.map { it.name.removeSuffix(".xml") }
            check(preferences.all { base.getSharedPreferences(it, 0).all.isEmpty() }) { "QA_RECOVERY_PREFERENCE_NOT_EMPTY" }
            idle(originalOwner)
            check(sha(failureReport.readBytes()) == expectedSha && emptyTree() == tree && databaseSnapshot() == databaseHashes &&
                preferences.all { base.getSharedPreferences(it, 0).all.isEmpty() }) { "QA_RECOVERY_RECHECK_CHANGED" }
            stage = "exact_deletion"; deletionStarted = true; emitRecovery()
            check(base.deleteDatabase(databaseName) && databaseFiles.none { it.exists() }) { "QA_RECOVERY_DATABASE_REMAINS" }
            preferences.forEach { check(base.deleteSharedPreferences(it)) { "QA_RECOVERY_PREFERENCE_REMAINS" } }
            check(root.canonicalFile.parentFile == parent && root.name == rootName) { "QA_RECOVERY_ROOT_BOUNDARY" }
            // Delete empty directories individually; never recurse through a newly appeared file.
            tree.sortedByDescending { it.length }.forEach { relative ->
                val directory = if (relative.isEmpty()) root else File(root, relative)
                check(directory.canonicalFile.toPath().startsWith(root.toPath()) && directory.isDirectory &&
                    !java.nio.file.Files.isSymbolicLink(directory.toPath()) && directory.listFiles()?.isEmpty() == true && directory.delete()) { "QA_RECOVERY_ROOT_REMAINS" }
            }
            check(!root.exists()) { "QA_RECOVERY_ROOT_REMAINS" }
            check(sha(failureReport.readBytes()) == expectedSha && databaseFiles.none { it.exists() } &&
                preferenceFiles.none { it.exists() }) { "QA_RECOVERY_ABSENCE_FAILED" }
            authority(); quiet(); idle(originalOwner)
            passed = true; stage = "complete"; emitRecovery()
        } catch (failure: Throwable) {
            failedStage = stage; code = fixedFailureCode(failure); stage = "failed"; emitRecovery()
        } finally {
            listOf(updateLease, pc, phone).forEach { lease ->
                try { lease?.close() } catch (failure: Throwable) {
                    passed = false; if (failedStage == null) failedStage = "release_owned_lease"
                    if (code == null) code = fixedFailureCode(failure)
                    stage = "failed"; emitRecovery()
                }
            }
        }
        check(passed) { "QA_EXACT_SYNTHETIC_RECOVERY_FAILED: consult content-free stage/code metadata" }
    }

}
