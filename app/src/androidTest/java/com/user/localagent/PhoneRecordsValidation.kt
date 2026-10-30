package com.user.localagent

import android.Manifest
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.imports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Every provider is injected. These tests never query SMS, CallLog, or MediaStore on the device. */
@RunWith(AndroidJUnit4::class)
class PhoneRecordsValidation {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = (context.applicationContext as LocalAgentApplication).container.repository
    private class FakeSource : PhoneRecordSource {
        var granted = true
        var throwSecurity = false
        var queryCount = 0
        var rows = emptyList<Map<String, Any?>>()
        var syntheticCount: Int? = null
        var lastUri: Uri? = null
        var projection = emptyArray<String>()
        var selection: String? = null
        var args: Array<String>? = null
        override fun hasPermission(permission: String) = granted
        override fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, order: String): Cursor {
            queryCount++
            if (throwSecurity) throw SecurityException("Synthetic revocation")
            lastUri = uri; this.projection = projection; this.selection = selection; this.args = args
            syntheticCount?.let { count -> return object : MatrixCursor(projection) { override fun getCount() = count } }
            val key = if (order.startsWith("_id")) "_id" else if (order.startsWith("date_added")) "date_added" else "date"
            val sorted = if (order.endsWith(" ASC")) rows.sortedBy { (it[key] as? Number)?.toLong() ?: 0 }
                else rows.sortedByDescending { (it[key] as? Number)?.toLong() ?: 0 }
            return MatrixCursor(projection).apply { sorted.forEach { row -> addRow(projection.map { row[it] }.toTypedArray()) } }
        }
    }
    private fun sms(id: Long, date: Long, body: String) = mapOf<String, Any?>(
        Telephony.Sms._ID to id, Telephony.Sms.DATE to date, Telephony.Sms.TYPE to 1,
        Telephony.Sms.ADDRESS to "synthetic-1234", Telephony.Sms.BODY to body)
    private fun <T> value(result: PhoneRecordResult<T>): T {
        assertTrue(result is PhoneRecordResult.Ready<*>)
        return (result as PhoneRecordResult.Ready<T>).value
    }

    @Test fun missingOrRevokedPermissionsReturnConnectionWithoutFallbackReads() = runBlocking {
        val fake = FakeSource().apply { granted = false }
        val records = PhoneRecords(context, fake)
        val sms = records.smsSummary()
        assertTrue(sms is PhoneRecordResult.ConnectionRequired)
        assertEquals(Manifest.permission.READ_SMS, (sms as PhoneRecordResult.ConnectionRequired).permission)
        assertTrue(records.callLogSummary() is PhoneRecordResult.ConnectionRequired)
        assertTrue(records.recordAudio() is PhoneRecordResult.ConnectionRequired)
        assertEquals(0, fake.queryCount)
        fake.granted = true; fake.throwSecurity = true
        assertTrue(records.smsSummary() is PhoneRecordResult.ConnectionRequired)
        assertEquals(1, fake.queryCount)
    }

    @Test fun smsDefaultsCoverGrantedProviderAndBoundExcerptsWithoutLeakingAddress() = runBlocking {
        val fake = FakeSource().apply { rows = listOf(sms(1, 1000, "합성 하나"), sms(2, 2000, "합성 둘"), sms(3, 3000, "가".repeat(499) + "😀")) }
        val result = value(PhoneRecords(context, fake).smsSummary(limit = 2))
        assertNull(fake.selection); assertNull(fake.args)
        assertEquals(Telephony.Sms.CONTENT_URI, fake.lastUri)
        assertEquals(3L, result.totalCount)
        assertEquals(1000L, result.firstAtEpochMillis)
        assertEquals(3000L, result.lastAtEpochMillis)
        assertEquals(2, result.excerpts.size)
        assertTrue(result.excerptsTruncated)
        assertTrue(result.excerpts.all { it.text.length <= 500 && it.maskedAddress == "***1234" })
        assertFalse(result.excerpts.first().text.last().isHighSurrogate())
    }

    @Test fun callAggregatesAndUserContactFilterUseBoundArguments() = runBlocking {
        val fake = FakeSource().apply { rows = listOf(
            mapOf("_id" to 1, "date" to 1000, "type" to CallLog.Calls.INCOMING_TYPE, "duration" to 15, "number" to "synthetic-1234"),
            mapOf("_id" to 2, "date" to 2000, "type" to CallLog.Calls.MISSED_TYPE, "duration" to 0, "number" to "synthetic-5678"),
            mapOf("_id" to 3, "date" to 3000, "type" to CallLog.Calls.INCOMING_TYPE, "duration" to 30, "number" to "synthetic-1234")) }
        val result = value(PhoneRecords(context, fake).callLogSummary("합성%_!", 10, 9999))
        assertEquals(45L, result.observedDurationSeconds)
        assertEquals(2L, result.observedTypeCounts[CallLog.Calls.INCOMING_TYPE])
        assertTrue(result.aggregatesComplete)
        assertTrue(fake.selection!!.contains("?"))
        assertFalse(fake.selection!!.contains("합성"))
        assertEquals(listOf("10", "9999", "%합성!%!_!!%", "%합성!%!_!!%"), fake.args!!.toList())
        assertTrue(result.excerpts.all { it.text.length <= 500 && it.maskedAddress!!.startsWith("***") })
        assertFalse(fake.projection.contains(CallLog.Calls.CACHED_NAME))
    }

    @Test fun audioListsAtMost100MetadataUrisAndNeverProjectsFilePaths() = runBlocking {
        val fake = FakeSource().apply { rows = (1..140).map { mapOf("_id" to it, "_display_name" to "synthetic-recording-$it.m4a", "duration" to 1000L, "date_added" to it) } }
        val result = value(PhoneRecords(context, fake).recordAudio(limit = 200))
        assertEquals(140L, result.totalCount)
        assertEquals(100, result.items.size)
        assertTrue(result.truncated)
        assertTrue(result.items.all { it.contentUri.startsWith("content://media/") && it.durationMillis == 1000L })
        assertTrue(fake.selection!!.contains(MediaStore.Audio.Media.IS_RECORDING))
        assertFalse(fake.projection.any { it.equals("_data", ignoreCase = true) })
    }

    @Test fun smsByteBudgetRejectsSyntheticPayloadBeforeAnyPersistence() = runBlocking {
        val before = repository.notes.first().map { it.id }.toSet()
        val fake = FakeSource().apply { rows = listOf(sms(1, 1000, "x".repeat(PhoneRecords.MAX_SMS_BYTES + 1))) }
        var progressCalls = 0
        val result = PhoneRecords(context, fake).importSmsToRepository(repository) { _, _ -> progressCalls++ }
        assertTrue(result is PhoneRecordResult.LimitExceeded)
        assertEquals(0, progressCalls)
        assertEquals(before, repository.notes.first().map { it.id }.toSet())
    }
    @Test fun syntheticSmsSnapshotDeduplicatesAndCancellationPreservesExistingNotes() = runBlocking {
        val token = "qa-sms-" + UUID.randomUUID()
        val fake = FakeSource().apply { rows = listOf(sms(1, 1000, "$token\n" + "합성 본문".repeat(3000)), sms(2, 2000, "$token 회신")) }
        val records = PhoneRecords(context, fake)
        val keep = repository.saveNote("QA phone preservation", token, "__qa__")
        val own = mutableSetOf<String>()
        try {
            val first = value(records.importSmsToRepository(repository)); own += first.noteIds
            assertEquals(2, first.recordCount)
            assertTrue(first.chunkCount > 1)
            val again = value(records.importSmsToRepository(repository))
            assertTrue(again.duplicate)
            assertEquals(first.noteIds.toSet(), again.noteIds.toSet())
            val noteRows = repository.notes.first().filter { it.id in first.noteIds }
            assertTrue(noteRows.all { it.collection == "native-sms" })
            val jsonl = noteRows.map { it to Json.parseToJsonElement(it.body.substringBefore('\n')).jsonObject }
                .filter { it.second["kind"]?.jsonPrimitive?.content == "chunk" }.sortedBy { it.second.getValue("chunk").jsonPrimitive.int }
                .joinToString("") { it.first.body.substringAfter('\n') }
            assertEquals(2, jsonl.lineSequence().filter { it.isNotBlank() }.count())
            assertTrue(jsonl.contains("source_id")); assertFalse(jsonl.contains("synthetic-1234"))
            val before = repository.notes.first().map { it.id }.toSet()
            fake.rows = listOf(sms(9, 9000, "$token cancel\n" + "합성".repeat(9000)))
            val child = launch { records.importSmsToRepository(repository) { done, _ -> if (done == 1) coroutineContext.cancel() } }
            child.join(); assertTrue(child.isCancelled)
            assertEquals(before, repository.notes.first().map { it.id }.toSet())
            assertEquals(token, repository.getNote(keep.id)?.body)
            fake.syntheticCount = 100_001
            assertTrue(records.importSmsToRepository(repository) is PhoneRecordResult.LimitExceeded)
            assertEquals(before, repository.notes.first().map { it.id }.toSet())
        } finally { withContext(NonCancellable) { own.forEach { repository.deleteNote(it) }; repository.deleteNote(keep.id) } }
    }
}

