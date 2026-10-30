package com.user.localagent.imports

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

sealed interface PhoneRecordResult<out T> {
    data class Ready<T>(val value: T) : PhoneRecordResult<T>
    data class ConnectionRequired(val permission: String, val description: String) : PhoneRecordResult<Nothing>
    data class LimitExceeded(val description: String) : PhoneRecordResult<Nothing>
    data class Unavailable(val description: String) : PhoneRecordResult<Nothing>
}
data class PhoneRecordExcerpt(val atEpochMillis: Long?, val text: String, val type: Int?,
    val maskedAddress: String?, val durationSeconds: Long? = null)
data class PhoneRecordSummary(val totalCount: Long, val firstAtEpochMillis: Long?, val lastAtEpochMillis: Long?,
    val excerpts: List<PhoneRecordExcerpt>, val excerptLimit: Int, val excerptsTruncated: Boolean,
    val scannedCount: Int, val scanLimit: Int, val aggregatesComplete: Boolean,
    val observedDurationSeconds: Long?, val observedTypeCounts: Map<Int, Long>, val scopeDescription: String)
data class PhoneAudioRecord(val displayName: String, val contentUri: String, val durationMillis: Long?)
data class PhoneAudioListing(val totalCount: Long, val items: List<PhoneAudioRecord>, val limit: Int,
    val truncated: Boolean, val scopeDescription: String)
data class PhoneSmsImportResult(val contentSha256: String, val recordCount: Int, val chunkCount: Int,
    val noteIds: List<String>, val duplicate: Boolean, val scopeDescription: String)

/** Injection seam: tests return synthetic cursors and never touch the device's real providers. */
interface PhoneRecordSource {
    fun hasPermission(permission: String): Boolean
    fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, order: String): Cursor?
}
class AndroidPhoneRecordSource(context: Context) : PhoneRecordSource {
    private val context = context.applicationContext
    override fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    override fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, order: String): Cursor? =
        context.contentResolver.query(uri, projection, selection, args, order)
}

/** Read-only official providers. Never requests roles/grants or sends, changes, or deletes provider data. */
class PhoneRecords(context: Context, private val source: PhoneRecordSource = AndroidPhoneRecordSource(context)) {
    suspend fun smsSummary(query: String? = null, start: Long? = null, end: Long? = null, limit: Int = 5): PhoneRecordResult<PhoneRecordSummary> =
        guarded(Manifest.permission.READ_SMS) {
            val filter = filters(Telephony.Sms.DATE, query, start, end, listOf(Telephony.Sms.BODY, Telephony.Sms.ADDRESS))
            val cap = limit.coerceIn(1, 20)
            val cursor = source.query(Telephony.Sms.CONTENT_URI, SMS_COLUMNS, filter.first, filter.second, "${Telephony.Sms.DATE} DESC")
                ?: return@guarded PhoneRecordResult.Unavailable("SMS 제공자가 결과를 반환하지 않았습니다.")
            cursor.use {
                val total = it.count
                val dates = dateBounds(it, Telephony.Sms.DATE)
                val snippets = mutableListOf<PhoneRecordExcerpt>()
                it.moveToPosition(-1)
                while (snippets.size < cap && it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    snippets += PhoneRecordExcerpt(it.long(Telephony.Sms.DATE), bounded(it.string(Telephony.Sms.BODY), 500),
                        it.long(Telephony.Sms.TYPE)?.toInt(), mask(it.string(Telephony.Sms.ADDRESS)))
                }
                PhoneRecordResult.Ready(PhoneRecordSummary(total.toLong(), dates.first, dates.second, snippets, cap, total > snippets.size,
                    snippets.size, cap, true, null, emptyMap(), "Android SMS 제공자에서 허용된 조회 결과 · 총 ${total}개 · 본문 발췌 ${snippets.size}개(각 500자 이하)"))
            }
        }

    suspend fun callLogSummary(query: String? = null, start: Long? = null, end: Long? = null, limit: Int = 5): PhoneRecordResult<PhoneRecordSummary> =
        guarded(Manifest.permission.READ_CALL_LOG) {
            val filter = filters(CallLog.Calls.DATE, query, start, end, listOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME))
            val cap = limit.coerceIn(1, 20)
            val cursor = source.query(CallLog.Calls.CONTENT_URI, CALL_COLUMNS, filter.first, filter.second, "${CallLog.Calls.DATE} DESC")
                ?: return@guarded PhoneRecordResult.Unavailable("통화 기록 제공자가 결과를 반환하지 않았습니다.")
            cursor.use {
                val total = it.count
                val dates = dateBounds(it, CallLog.Calls.DATE)
                val snippets = mutableListOf<PhoneRecordExcerpt>()
                val types = mutableMapOf<Int, Long>()
                var scanned = 0
                var duration = 0L
                it.moveToPosition(-1)
                while (scanned < MAX_SMS_ROWS && it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val type = it.long(CallLog.Calls.TYPE)?.toInt() ?: 0
                    val seconds = (it.long(CallLog.Calls.DURATION) ?: 0).coerceAtLeast(0)
                    duration = if (Long.MAX_VALUE - duration < seconds) Long.MAX_VALUE else duration + seconds
                    types[type] = (types[type] ?: 0) + 1
                    if (snippets.size < cap) {
                        val masked = mask(it.string(CallLog.Calls.NUMBER))
                        snippets += PhoneRecordExcerpt(it.long(CallLog.Calls.DATE), "통화 유형 $type · ${seconds}초", type, masked, seconds)
                    }
                    scanned++
                }
                PhoneRecordResult.Ready(PhoneRecordSummary(total.toLong(), dates.first, dates.second, snippets, cap, total > snippets.size,
                    scanned, MAX_SMS_ROWS, scanned == total, duration, types.toMap(),
                    "Android 통화 기록 조회 결과 · 총 ${total}개 · 시간·유형·통화 길이만 집계 · 번호는 끝 4자리 이하로 가림 · 집계 ${scanned}개"))
            }
        }

    /** Lists only MediaStore entries flagged IS_RECORDING; never opens an audio stream or private path. */
    suspend fun recordAudio(query: String? = null, limit: Int = 100): PhoneRecordResult<PhoneAudioListing> {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return guarded(permission) {
            require(query == null || query.length <= 500)
            val cap = limit.coerceIn(1, 100)
            val clauses = mutableListOf("${MediaStore.Audio.Media.IS_RECORDING} = 1")
            val args = mutableListOf<String>()
            if (!query.isNullOrBlank()) { clauses += "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? ESCAPE '!'"; args += "%${escape(query)}%" }
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val cursor = source.query(uri, AUDIO_COLUMNS, clauses.joinToString(" AND "), args.takeIf { it.isNotEmpty() }?.toTypedArray(), "${MediaStore.Audio.Media.DATE_ADDED} DESC")
                ?: return@guarded PhoneRecordResult.Unavailable("녹음 메타데이터 제공자가 결과를 반환하지 않았습니다.")
            cursor.use {
                val total = it.count
                val items = mutableListOf<PhoneAudioRecord>()
                while (items.size < cap && it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = it.long(MediaStore.Audio.Media._ID) ?: continue
                    if (id < 0) continue
                    items += PhoneAudioRecord(bounded(it.string(MediaStore.Audio.Media.DISPLAY_NAME), 200),
                        ContentUris.withAppendedId(uri, id).toString(), it.long(MediaStore.Audio.Media.DURATION)?.takeIf { duration -> duration >= 0 })
                }
                PhoneRecordResult.Ready(PhoneAudioListing(total.toLong(), items, cap, total > items.size,
                    "MediaStore가 녹음으로 표시하고 Android가 허용한 오디오 메타데이터 · ${items.size}/${total}개 · 음성 내용 분석은 수행하지 않음"))
            }
        }
    }

    /** Explicit whole-provider SMS snapshot, bounded before persistence; never copies it into long memory. */
    suspend fun importSmsToRepository(repository: LocalRepository, progress: (Int, Int) -> Unit = { _, _ -> }): PhoneRecordResult<PhoneSmsImportResult> = SMS_IMPORT_LOCK.withLock {
        val created = mutableListOf<String>()
        try {
            guarded(Manifest.permission.READ_SMS) {
                val cursor = source.query(Telephony.Sms.CONTENT_URI, SMS_COLUMNS, null, null, "${Telephony.Sms._ID} ASC")
                    ?: return@guarded PhoneRecordResult.Unavailable("SMS 제공자가 결과를 반환하지 않았습니다.")
                val text = StringBuilder()
                var count = 0
                cursor.use {
                    if (it.count > MAX_SMS_ROWS) return@guarded PhoneRecordResult.LimitExceeded("한 번의 SMS 가져오기는 100,000개 이하를 지원합니다. 기존 자료는 변경하지 않았습니다.")
                    var bytes = 0L
                    while (it.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        count++
                        if (count > MAX_SMS_ROWS) return@guarded PhoneRecordResult.LimitExceeded("SMS 가져오기 개수 상한을 넘었습니다.")
                        val line = buildJsonObject {
                            put("source_id", it.long(Telephony.Sms._ID)); put("at_epoch_millis", it.long(Telephony.Sms.DATE))
                            put("type", it.long(Telephony.Sms.TYPE)); put("address_masked", mask(it.string(Telephony.Sms.ADDRESS)))
                            put("body", it.string(Telephony.Sms.BODY))
                        }.toString() + "\n"
                        bytes += line.toByteArray(Charsets.UTF_8).size
                        if (bytes > MAX_SMS_BYTES) return@guarded PhoneRecordResult.LimitExceeded("한 번의 SMS 가져오기는 20 MiB 이하를 지원합니다. 기존 자료는 변경하지 않았습니다.")
                        text.append(line)
                    }
                }
                val raw = text.toString()
                val sha = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
                val scope = "Android가 허용한 SMS 제공자 조회 시점의 ${count}개 · MMS·메신저 기록은 별도 자료"
                if (count == 0) return@guarded PhoneRecordResult.Ready(PhoneSmsImportResult(sha, 0, 0, emptyList(), false, scope))
                val chunks = mutableListOf<String>()
                var position = 0
                while (position < raw.length) {
                    currentCoroutineContext().ensureActive()
                    var end = minOf(position + 8000, raw.length)
                    if (end < raw.length && raw[end - 1].isHighSurrogate() && raw[end].isLowSurrogate()) end--
                    chunks += raw.substring(position, end)
                    position = end
                }
                val notes = repository.notes.first().filter { it.collection == SMS_COLLECTION }.mapNotNull { note ->
                    val meta = runCatching { Json.parseToJsonElement(note.body.substringBefore('\n')) as? JsonObject }.getOrNull()
                    if (meta != null && meta.string("type") == SMS_TYPE) note to meta else null
                }
                val duplicate = notes.firstOrNull { (_, meta) ->
                    if (meta.string("kind") != "manifest" || meta.string("sha256") != sha) false else {
                        val saved = notes.filter { it.second.string("run") == meta.string("run") && it.second.string("kind") == "chunk" && it.second.string("sha256") == sha }
                            .sortedBy { it.second.integer("chunk") }
                        saved.size == chunks.size && saved.map { it.second.integer("chunk") } == (1..chunks.size).toList() &&
                            saved.zip(chunks).all { (item, original) -> item.first.body.substringAfter('\n', "") == original }
                    }
                }
                if (duplicate != null) {
                    val ids = notes.filter { it.second.string("run") == duplicate.second.string("run") }.map { it.first.id }
                    return@guarded PhoneRecordResult.Ready(PhoneSmsImportResult(sha, count, chunks.size, ids, true, scope))
                }
                val run = UUID.randomUUID().toString()
                fun header(kind: String, chunk: Int? = null) = buildJsonObject {
                    put("type", SMS_TYPE); put("kind", kind); put("sha256", sha); put("run", run)
                    put("record_count", count); put("chunks", chunks.size); if (chunk != null) put("chunk", chunk)
                    put("source", "android_sms_provider"); put("scope", "runtime_granted_sms_snapshot")
                }
                chunks.forEachIndexed { index, part ->
                    currentCoroutineContext().ensureActive()
                    val id = UUID.randomUUID().toString(); created += id
                    repository.saveNote("SMS 자료 ${sha.take(12)} · ${index + 1}/${chunks.size}", header("chunk", index + 1).toString() + "\n" + part, SMS_COLLECTION, id)
                    progress(index + 1, chunks.size)
                }
                currentCoroutineContext().ensureActive()
                val manifestId = UUID.randomUUID().toString(); created += manifestId
                repository.saveNote("SMS 자료 ${sha.take(12)} · 범위", header("manifest").toString(), SMS_COLLECTION, manifestId)
                currentCoroutineContext().ensureActive()
                PhoneRecordResult.Ready(PhoneSmsImportResult(sha, count, chunks.size, created.toList(), false, scope))
            }.also { result ->
                if (result !is PhoneRecordResult.Ready && created.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) { created.forEach { repository.deleteNote(it) } }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                created.forEach { id -> try { repository.deleteNote(id) } catch (cleanup: Exception) { error.addSuppressed(cleanup) } }
            }
            throw error
        }
    }

    private suspend fun <T> guarded(permission: String, action: suspend () -> PhoneRecordResult<T>): PhoneRecordResult<T> {
        if (!source.hasPermission(permission)) return required(permission)
        return withContext(Dispatchers.IO) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { required(permission) }
            catch (_: IllegalArgumentException) { PhoneRecordResult.Unavailable("조회 조건 또는 제공자 열을 확인할 수 없습니다. 시스템에서 허용한 자료를 다시 선택해주세요.") }
            catch (_: Exception) { PhoneRecordResult.Unavailable("Android 제공자 조회를 완료하지 못했습니다. 권한과 연결 상태를 확인해주세요.") }
        }
    }
    private fun required(permission: String): PhoneRecordResult.ConnectionRequired {
        val label = when (permission) {
            Manifest.permission.READ_SMS -> "문자 읽기"
            Manifest.permission.READ_CALL_LOG -> "통화 기록 읽기"
            else -> "오디오 자료 읽기"
        }
        return PhoneRecordResult.ConnectionRequired(permission, "Android의 $label 연결을 확인해야 합니다. 시스템 권한을 확인한 뒤 다시 시도해주세요.")
    }
    private fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.integer(name: String) = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    private fun filters(date: String, query: String?, start: Long?, end: Long?, columns: List<String>): Pair<String?, Array<String>?> {
        require(query == null || query.length <= 500); require(start == null || start >= 0); require(end == null || end >= 0)
        require(start == null || end == null || start <= end)
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        if (start != null) { clauses += "$date >= ?"; args += start.toString() }
        if (end != null) { clauses += "$date <= ?"; args += end.toString() }
        if (!query.isNullOrBlank()) { clauses += columns.joinToString(" OR ", "(", ")") { "$it LIKE ? ESCAPE '!'" }; repeat(columns.size) { args += "%${escape(query)}%" } }
        return clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ") to args.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
    private fun dateBounds(cursor: Cursor, column: String): Pair<Long?, Long?> {
        if (!cursor.moveToFirst()) return null to null
        val latest = cursor.long(column)
        cursor.moveToLast()
        return cursor.long(column) to latest
    }
    private fun Cursor.long(name: String): Long? { val index = getColumnIndex(name); return if (index < 0 || isNull(index)) null else getLong(index) }
    private fun Cursor.string(name: String): String { val index = getColumnIndex(name); return if (index < 0 || isNull(index)) "" else getString(index).orEmpty() }
    private fun escape(value: String) = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")
    private fun mask(value: String): String? = value.filter { it.isDigit() }.takeLast(4).takeIf { it.isNotEmpty() }?.let { "***$it" }
    private fun bounded(value: String, max: Int): String {
        var end = minOf(value.length, max)
        if (end < value.length && end > 0 && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        return value.substring(0, end)
    }
    companion object {
        const val SMS_COLLECTION = "native-sms"
        const val MAX_SMS_ROWS = 100_000
        const val MAX_SMS_BYTES = 20 * 1024 * 1024
        private const val SMS_TYPE = "native-sms-import-v1"
        private val SMS_IMPORT_LOCK = Mutex()
        private val SMS_COLUMNS = arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY)
        private val CALL_COLUMNS = arrayOf(CallLog.Calls._ID, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.NUMBER)
        private val AUDIO_COLUMNS = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION)
    }
}


