package com.user.localagent.callfollowup

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class CallRecordingDiscovery(context: Context) {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    fun hasAccess(grant: RecordingSourceGrant): Boolean {
        grant.validate()
        return when (grant.kind) {
            RecordingSourceKind.MEDIA_FOLDER -> ContextCompat.checkSelfPermission(context,
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            RecordingSourceKind.SAF_TREE -> {
                val tree = Uri.parse(grant.location)
                DocumentsContract.isTreeUri(tree) && DocumentsContract.getTreeDocumentId(tree).substringAfter(':').isNotBlank() &&
                    resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
            }
        }
    }

    /** Exact selected directory only. Refuse an incomplete enumeration instead of calling it complete. */
    suspend fun list(grant: RecordingSourceGrant, notBeforeMillis: Long): List<CallRecordingObservation> = withContext(Dispatchers.IO) {
        check(hasAccess(grant)) { "선택한 녹음 폴더 접근 권한이 필요합니다." }
        val result = mutableListOf<CallRecordingObservation>()
        when (grant.kind) {
            RecordingSourceKind.MEDIA_FOLDER -> {
                val collection = MediaStore.Audio.Media.getContentUri(grant.volume)
                val fields = arrayOf(MediaStore.Audio.Media._ID, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH)
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DATE_ADDED}>=? AND ${MediaStore.MediaColumns.IS_TRASHED}=0"
                cancellableQuery(collection, fields, selection, arrayOf(grant.location, ((notBeforeMillis + 999) / 1000).toString()),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC,${MediaStore.Audio.Media._ID} DESC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        check(result.size < MAX_FOLDER_ITEMS) { "선택 폴더의 새 녹음이 너무 많습니다. 폴더 범위를 확인해주세요." }
                        if (cursor.text(MediaStore.MediaColumns.RELATIVE_PATH) != grant.location) continue
                        val uri = ContentUris.withAppendedId(collection, cursor.number(MediaStore.Audio.Media._ID))
                        observation(grant, uri, cursor.number(MediaStore.MediaColumns.SIZE),
                            cursor.number(MediaStore.MediaColumns.DATE_MODIFIED) * 1000,
                            cursor.number(MediaStore.MediaColumns.DATE_ADDED) * 1000,
                            cursor.number(MediaStore.MediaColumns.IS_PENDING) != 0L)?.let(result::add)
                    }
                } ?: error("녹음 폴더를 조회하지 못했습니다.")
            }
            RecordingSourceKind.SAF_TREE -> {
                val tree = Uri.parse(grant.location)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val fields = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                var count = 0
                cancellableQuery(children, fields, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        check(++count <= MAX_FOLDER_ITEMS) { "선택 폴더의 항목이 너무 많습니다. 통화 녹음 하위 폴더를 선택해주세요." }
                        if (!cursor.text(DocumentsContract.Document.COLUMN_MIME_TYPE).startsWith("audio/")) continue
                        val modified = cursor.number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (modified < notBeforeMillis) continue
                        val id = cursor.text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        observation(grant, uri, cursor.number(DocumentsContract.Document.COLUMN_SIZE), modified, modified, false)?.let(result::add)
                    }
                } ?: error("선택한 녹음 폴더를 조회하지 못했습니다.")
            }
        }
        result
    }

    /** Membership was established by discovery; live checks query only that immutable selected URI. */
    suspend fun current(grant: RecordingSourceGrant, expected: CallRecordingObservation, enabledAt: Long): CallRecordingObservation? = withContext(Dispatchers.IO) {
        check(hasAccess(grant))
        require(expected.grantId == grant.id && expected.sourceKey == callDigest("${grant.id}\n${expected.uri}"))
        val uri = Uri.parse(expected.uri)
        when (grant.kind) {
            RecordingSourceKind.MEDIA_FOLDER -> {
                require(uri.toString().startsWith(MediaStore.Audio.Media.getContentUri(grant.volume).toString() + "/"))
                val fields = arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.RELATIVE_PATH)
                cancellableQuery(uri, fields, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst() || cursor.text(MediaStore.MediaColumns.RELATIVE_PATH) != grant.location ||
                        cursor.number(MediaStore.MediaColumns.IS_TRASHED) != 0L) null
                    else observation(grant, uri, cursor.number(MediaStore.MediaColumns.SIZE), cursor.number(MediaStore.MediaColumns.DATE_MODIFIED) * 1000,
                        cursor.number(MediaStore.MediaColumns.DATE_ADDED) * 1000, cursor.number(MediaStore.MediaColumns.IS_PENDING) != 0L)
                }
            }
            RecordingSourceKind.SAF_TREE -> {
                val tree = Uri.parse(grant.location)
                require(uri.authority == tree.authority && DocumentsContract.isTreeUri(uri) &&
                    DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(tree))
                val fields = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                cancellableQuery(uri, fields, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst() || cursor.text(DocumentsContract.Document.COLUMN_DOCUMENT_ID) != DocumentsContract.getDocumentId(uri) ||
                        !cursor.text(DocumentsContract.Document.COLUMN_MIME_TYPE).startsWith("audio/")) null
                    else cursor.number(DocumentsContract.Document.COLUMN_LAST_MODIFIED).let { modified ->
                        observation(grant, uri, cursor.number(DocumentsContract.Document.COLUMN_SIZE), modified, modified, false)
                    }
                }
            }
        }
    }

    suspend fun snapshot(grant: RecordingSourceGrant, observation: CallRecordingObservation, enabledAt: Long): StableCallRecording = withContext(Dispatchers.IO) {
        require(!observation.pending && observation.byteCount in 1..MAX_RECORDING_BYTES)
        check(current(grant, observation, enabledAt) == observation) { "녹음 파일이 변경되었거나 삭제되었습니다." }
        val uri = Uri.parse(observation.uri)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                bytes += n
                require(bytes <= MAX_RECORDING_BYTES)
                digest.update(buffer, 0, n)
            }
        } ?: error("선택한 녹음을 열지 못했습니다.")
        require(bytes == observation.byteCount && bytes > 0) { "녹음 파일 쓰기가 아직 끝나지 않았습니다." }
        val duration = containerDurationAtEof(uri)
        check(current(grant, observation, enabledAt) == observation) { "확인 중 녹음 파일이 변경되었습니다." }
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        StableCallRecording(callDigest("${observation.sourceKey}\n$sha"), observation.sourceKey, grant.id, uri.toString(), sha,
            bytes, observation.modifiedAtMillis, duration, System.currentTimeMillis()).validate()
    }

    private suspend fun containerDurationAtEof(uri: Uri): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val audio = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            require(audio.size == 1) { "단일 오디오 트랙의 녹음이 필요합니다." }
            val format = extractor.getTrackFormat(audio.single())
            require(format.containsKey(MediaFormat.KEY_DURATION))
            val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
            require(duration in 1..MAX_RECORDING_DURATION_MILLIS)
            extractor.selectTrack(audio.single())
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var samples = 0L; var lastTime = -1L
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                require(count > 0 && count <= buffer.capacity() && ++samples <= 5_000_000L)
                val time = extractor.sampleTime
                require(time >= 0 && time >= lastTime) { "녹음 샘플의 시간 순서를 확인하지 못했습니다." }
                lastTime = time
                if (!extractor.advance()) break
            }
            require(samples > 0 && lastTime / 1000 >= maxOf(0, duration - 3000)) { "녹음 끝까지 오디오가 준비되지 않았습니다." }
            return duration
        } finally { extractor.release() }
    }

    private fun observation(grant: RecordingSourceGrant, uri: Uri, bytes: Long, modified: Long, added: Long, pending: Boolean): CallRecordingObservation? {
        // Retain unsupported/unfinished metadata in presence checks. It must not become a false deletion.
        return CallRecordingObservation(callDigest("${grant.id}\n$uri"), grant.id, uri.toString(), bytes, modified, added, pending)
    }
    private suspend fun cancellableQuery(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, sort: String?): Cursor? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val cursor = resolver.query(uri, projection, selection, args, sort, signal)
                if (continuation.isActive) continuation.resume(cursor) { _, abandoned, _ -> abandoned?.close() } else cursor?.close()
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
    private fun Cursor.number(name: String): Long = getColumnIndex(name).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
    private fun Cursor.text(name: String): String = getColumnIndex(name).let { if (it < 0 || isNull(it)) "" else getString(it) }
    companion object { private const val MAX_FOLDER_ITEMS = 5000 }
}
