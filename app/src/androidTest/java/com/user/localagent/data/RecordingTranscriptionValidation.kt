package com.user.localagent.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.LocalAgentApplication
import com.user.localagent.audio.*
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import com.user.localagent.imports.PhoneRecordResult
import com.user.localagent.skills.PhoneMediaTools
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly gated. Isolated synthetic SQLCipher DB; no personal recording is opened. No downloads. */
@RunWith(AndroidJUnit4::class)
class RecordingTranscriptionValidation {
    @Test fun selectedDocumentAudioUsesItsOriginalScopeAndPublishesAWholeTranscriptSource() = runBlocking(Dispatchers.IO) {
        assumeTrue("recordingToolsValidationApproved=true required", args().getString("recordingToolsValidationApproved") == "true")
        assumeTrue("selectedAudioAsrValidationApproved=true required", args().getString("selectedAudioAsrValidationApproved") == "true")
        val context = context(); val app = (context.applicationContext as LocalAgentApplication).container
        app.models.refresh()
        assumeTrue("Use the already installed selected ASR model only", runCatching { app.models.asrModel() }.isSuccess)
        assumeTrue("Korean or automatic ASR must already be selected", app.models.asrSelection.value.language in setOf("ko", "auto"))
        val media = PhoneMediaTools(context); var speechFile: File? = null; var ownsGate = false
        try {
            assumeTrue("Existing offline Korean TTS voice required", media.capabilities().offlineKoreanVoices.isNotEmpty())
            ownsGate = RuntimeGate.inference.compareAndSet(false, true); check(ownsGate)
            app.cancelIdleUnload(); app.updates.setBusy(true)
            val speech = media.synthesizeToFile("파일 선택으로 전사하는 합성 검증입니다. 다음 회의는 서울에서 열립니다.")
            speechFile = speech.file
            fixture { repo, _ ->
                val project = repo.chat.createProject("Synthetic selected audio scope")
                val other = repo.chat.createProject("Synthetic selected audio other scope")
                val thread = repo.chat.createThread(project.id); val otherThread = repo.chat.createThread(other.id)
                val captured = repo.chat.accessScope(thread.id)
                val registry = PrivateDataPickerOrigins()
                val token = registry.begin(PrivateDataPickerScope(captured.threadId, thread.activeBranchId, captured.projectId,
                    captured.scopeKey, captured.useOwnerMemory, captured.threadRevision, captured.memoryEnabled), PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
                val origin = registry.consume(token, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION)
                val scoped = repo.forThread(origin.scope.threadId)
                val helper = SelectedAudioTranscription(context, app, scoped, origin.scope.threadId, origin.selectionId,
                    { check(origin.scope.threadId == thread.id) }, scoped::discardIncompleteAudioNotes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", speech.file)
                val result = withTimeout(15 * 60_000L) { helper.transcribe(uri) }
                assertEquals(result, helper.completedResult); assertNull(app.engine.residentModelPath)
                val root = checkNotNull(scoped.getNote(result.manifestNoteId))
                assertEquals(result.manifestSha256, RecordingTranscriptManifest.sha256(root.body))
                val manifest = Json.parseToJsonElement(root.body).jsonObject
                assertEquals(thread.id, manifest.getValue("threadId").jsonPrimitive.content)
                assertEquals("user_selected_document", manifest.getValue("origin").jsonPrimitive.content)
                assertEquals(origin.selectionId, manifest.getValue("pickerSelectionId").jsonPrimitive.content)
                assertEquals(JsonNull, manifest.getValue("requestRunId")); assertFalse(manifest.containsKey("recordingId"))
                val bodies = mutableListOf<String>()
                manifest.getValue("pages").jsonArray.forEach { raw ->
                    val pageMeta = raw.jsonObject; val page = checkNotNull(scoped.getNote(pageMeta.getValue("noteId").jsonPrimitive.content))
                    assertEquals(pageMeta.getValue("sha256").jsonPrimitive.content, RecordingTranscriptManifest.sha256(page.body))
                    Json.parseToJsonElement(page.body).jsonObject.getValue("chunks").jsonArray.forEach { value ->
                        val chunk = value.jsonObject; val note = checkNotNull(scoped.getNote(chunk.getValue("noteId").jsonPrimitive.content))
                        assertEquals(chunk.getValue("noteSha256").jsonPrimitive.content, RecordingTranscriptManifest.sha256(note.body))
                        bodies += note.body
                    }
                }
                assertEquals(result.sourceTextSha256, RecordingTranscriptManifest.sourceTextSha256(bodies))
                assertTrue("Public synthetic fixture keyword must be recognized", bodies.any { it.contains("회의") })
                assertTrue(runCatching { repo.forThread(otherThread.id).getNote(result.manifestNoteId) }.isFailure)
                val count = scoped.notes.first().size
                assertTrue("A duplicate UI callback must not create another transcript", runCatching { helper.transcribe(uri) }.isFailure)
                assertEquals(count, scoped.notes.first().size)
            }
        } finally { withContext(NonCancellable) {
            if (ownsGate) { app.engine.unload(); RuntimeGate.inference.set(false); app.updates.setBusy(false) }
            media.close(); speechFile?.let { check(it.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath())); it.delete() }
        } }
    }

    @Test fun typedPreflightFailuresDoNotEchoPrivateInputLoadModelsOrWriteNotes() = runBlocking(Dispatchers.IO) {
        fixture { repo, _ ->
            val app = (context().applicationContext as LocalAgentApplication).container
            val thread = repo.chat.createThread(title = "Synthetic recording preflight")
            val scoped = repo.forThread(thread.id)
            val store = ListedRecordingStore(thread.id) { "synthetic-preflight-run" }
            val handler = ScopedRecordingTool(context(), app, scoped, store, scoped::discardIncompleteAudioNotes)
            val owns = RuntimeGate.inference.compareAndSet(false, true)
            check(owns) { "Another model operation owns the runtime" }
            val residentBefore = app.engine.residentModelPath
            val notesBefore = scoped.notes.first().size
            try {
                val uri = "content://unlisted-provider/private-synthetic-input"
                val invalid = handler.execute(ToolCall("synthetic-invalid", "transcribe_recording", buildJsonObject { put("uri", uri) }))
                assertFalse(invalid.success)
                assertEquals("invalid_input", Json.parseToJsonElement(invalid.content).jsonObject.getValue("status").jsonPrimitive.content)
                assertFalse(invalid.content.contains(uri))
                val token = UUID.randomUUID().toString()
                val expired = handler.execute(ToolCall("synthetic-expired", "transcribe_recording", buildJsonObject { put("recordingId", token) }))
                assertFalse(expired.success)
                assertEquals("source_expired", Json.parseToJsonElement(expired.content).jsonObject.getValue("status").jsonPrimitive.content)
                assertFalse(expired.content.contains(token))
                assertEquals(residentBefore, app.engine.residentModelPath)
                assertEquals(notesBefore, scoped.notes.first().size)
            } finally { RuntimeGate.inference.set(false) }
        }
    }

    @Test fun rollbackAfterScopeRevisionDeletesOnlyTheExactUnchangedOwnedOutputs() = runBlocking(Dispatchers.IO) {
        fixture { repo, db ->
            val a = repo.chat.createProject("Synthetic recording A")
            val b = repo.chat.createProject("Synthetic recording B")
            val thread = repo.chat.createThread(a.id)
            val foreignThread = repo.chat.createThread(b.id)
            val scoped = repo.forThread(thread.id); val foreign = repo.forThread(foreignThread.id)
            val transcriptId = UUID.randomUUID().toString()
            val body = "전사 자료 $transcriptId · 0–15초\nSynthetic unchanged speech."
            val unchanged = scoped.saveNote("Synthetic owned", body, "audio-transcripts")
            val edited = scoped.saveNote("Synthetic edited", body, "audio-transcripts")
            val outside = foreign.saveNote("Synthetic outside scope", body, "audio-transcripts")
            val ordinary = scoped.saveNote("Synthetic ordinary note", body, "default")
            val expected = listOf(unchanged, edited, outside, ordinary).map {
                IncompleteAudioNote(it.id, RecordingTranscriptManifest.sha256(it.body))
            }
            scoped.saveNote(edited.title, body + " User edit must survive.", "audio-transcripts", edited.id)
            repo.chat.moveThread(thread.id, b.id)
            assertTrue(runCatching { scoped.getNote(unchanged.id) }.isFailure)
            val cleanup = scoped.discardIncompleteAudioNotes(transcriptId, expected)
            assertEquals(1, cleanup.deleted); assertEquals(2, cleanup.retainedChanged); assertEquals(1, cleanup.retainedUnverified)
            assertFalse(cleanup.verified)
            assertNull(db.dao().noteById(unchanged.id))
            assertTrue(db.dao().noteById(edited.id)!!.body.endsWith("User edit must survive."))
            assertNotNull(db.dao().noteById(outside.id)); assertNotNull(db.dao().noteById(ordinary.id))
        }
    }

    @Test fun cachedPhoneAsrProcessesOneSyntheticListedMediaStoreRecordingAndVerifiesTheManifest() = runBlocking(Dispatchers.IO) {
        assumeTrue("recordingToolsValidationApproved=true required", args().getString("recordingToolsValidationApproved") == "true")
        assumeTrue("recordingAsrValidationApproved=true required", args().getString("recordingAsrValidationApproved") == "true")
        val context = context()
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        assumeTrue("Existing Android audio permission required", ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
        val app = (context.applicationContext as LocalAgentApplication).container
        app.models.refresh()
        assumeTrue("Use an already installed selected ASR model only", runCatching { app.models.asrModel() }.isSuccess)
        assumeTrue("Korean or automatic ASR language must already be selected", app.models.asrSelection.value.language in setOf("ko", "auto"))
        val media = PhoneMediaTools(context)
        var audioUri: Uri? = null; var speechFile: File? = null; var ownsGate = false
        try {
            assumeTrue("Existing offline Korean TTS voice required", media.capabilities().offlineKoreanVoices.isNotEmpty())
            ownsGate = RuntimeGate.inference.compareAndSet(false, true)
            check(ownsGate) { "Another model operation owns the runtime" }
            app.cancelIdleUnload(); app.updates.setBusy(true)
            val speech = media.synthesizeToFile("이것은 합성 음성 검증입니다. 다음 회의는 서울에서 열립니다.")
            speechFile = speech.file
            val label = "ZipHapSyntheticRecording-${UUID.randomUUID()}.wav"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, label); put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Recordings/ZipHapValidation")
                put(MediaStore.Audio.Media.IS_RECORDING, 1); put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val created = checkNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
            audioUri = created
            context.contentResolver.openOutputStream(created, "w")!!.use { output -> speech.file.inputStream().use { it.copyTo(output) } }
            context.contentResolver.update(created, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val metadata = when (val read = app.phoneRecords.recordAudio(query = label, limit = 20)) {
                is PhoneRecordResult.Ready -> read.value.items.single { it.displayName == label }
                else -> error("Synthetic MediaStore recording was not returned by the real provider listing")
            }
            fixture { repo, _ ->
                val project = repo.chat.createProject("Synthetic ASR scope")
                val thread = repo.chat.createThread(project.id)
                val scoped = repo.forThread(thread.id)
                val store = ListedRecordingStore(thread.id) { "synthetic-asr-run" }
                val selected = store.register(listOf(RecordingListingItem(metadata.displayName, metadata.contentUri, metadata.durationMillis))).single()
                val handler = ScopedRecordingTool(context, app, scoped, store, scoped::discardIncompleteAudioNotes)
                fun request() = ToolCall(UUID.randomUUID().toString(), "transcribe_recording", buildJsonObject { put("recordingId", selected.recordingId) })
                val result = withTimeout(15 * 60_000L) { handler.execute(request()) }
                assertTrue("Actual phone ASR handler must verify its own output", result.success)
                assertEquals(VerificationState.VERIFIED, result.verification)
                assertNull("ASR must unload before text analysis", app.engine.residentModelPath)
                val output = Json.parseToJsonElement(result.content).jsonObject
                val root = checkNotNull(scoped.getNote(output.getValue("manifestNoteId").jsonPrimitive.content))
                assertEquals(output.getValue("manifestSha256").jsonPrimitive.content, RecordingTranscriptManifest.sha256(root.body))
                val manifest = Json.parseToJsonElement(root.body).jsonObject
                assertEquals(thread.id, manifest.getValue("threadId").jsonPrimitive.content)
                assertFalse(manifest.getValue("wordTimestamps").jsonPrimitive.boolean)
                assertFalse(manifest.getValue("speakerDiarization").jsonPrimitive.boolean)
                val bodies = mutableListOf<String>(); var firstChunkId: String? = null
                manifest.getValue("pages").jsonArray.forEach { raw ->
                    val pageMeta = raw.jsonObject; val page = checkNotNull(scoped.getNote(pageMeta.getValue("noteId").jsonPrimitive.content))
                    assertEquals(pageMeta.getValue("sha256").jsonPrimitive.content, RecordingTranscriptManifest.sha256(page.body))
                    Json.parseToJsonElement(page.body).jsonObject.getValue("chunks").jsonArray.forEach { value ->
                        val chunk = value.jsonObject
                        val note = checkNotNull(scoped.getNote(chunk.getValue("noteId").jsonPrimitive.content))
                        if (firstChunkId == null) firstChunkId = note.id
                        assertEquals(chunk.getValue("noteSha256").jsonPrimitive.content, RecordingTranscriptManifest.sha256(note.body))
                        bodies += note.body
                    }
                }
                assertEquals(manifest.getValue("sourceTextSha256").jsonPrimitive.content, RecordingTranscriptManifest.sourceTextSha256(bodies))
                assertTrue("Public synthetic fixture keyword must be recognized", bodies.any { it.contains("회의") })
                val reused = handler.execute(request())
                assertTrue(reused.success)
                assertTrue(Json.parseToJsonElement(reused.content).jsonObject.getValue("reusedVerifiedResult").jsonPrimitive.boolean)
                val edited = checkNotNull(scoped.getNote(checkNotNull(firstChunkId)))
                scoped.saveNote(edited.title, edited.body + " User-edited synthetic text.", edited.collection, edited.id)
                assertFalse("Changed transcript must not reuse stale verification", handler.execute(request()).success)
                assertTrue(scoped.getNote(edited.id)!!.body.endsWith("User-edited synthetic text."))
            }
        } finally {
            withContext(NonCancellable) {
                if (ownsGate) { app.engine.unload(); RuntimeGate.inference.set(false); app.updates.setBusy(false) }
                media.close()
                speechFile?.let { check(it.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath())); it.delete() }
                audioUri?.let { uri ->
                    context.contentResolver.delete(uri, null, null)
                    context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use {
                        assertEquals("Synthetic MediaStore fixture must be absent after cleanup", 0, it.count)
                    }
                }
            }
        }
    }

    private fun args() = InstrumentationRegistry.getArguments()
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fixture(block: suspend (LocalRepository, LocalDatabase) -> Unit) {
        assumeTrue("recordingToolsValidationApproved=true required", args().getString("recordingToolsValidationApproved") == "true")
        val name = "recording-tool-qa-${UUID.randomUUID()}.db"
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        System.loadLibrary("sqlcipher")
        val db = Room.databaseBuilder(context(), LocalDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try { db.openHelper.writableDatabase; block(LocalRepository(db), db) }
        finally { db.close(); key.fill(0); check(name.startsWith("recording-tool-qa-")); context().deleteDatabase(name) }
    }
}
