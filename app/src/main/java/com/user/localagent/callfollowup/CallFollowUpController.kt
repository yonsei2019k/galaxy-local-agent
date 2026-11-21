package com.user.localagent.callfollowup

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.user.localagent.benchmark.RuntimeGate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class PreparationResult { DONE, RETRY, DEFERRED, NEEDS_REVIEW }

/** Detection does not load a model, send a message, mutate a calendar, or switch the selected room. */
class CallFollowUpController(context: Context, private val pipeline: () -> CallFollowUpPipeline) {
    private val context = context.applicationContext
    private val journal = CallFollowUpJournal(this.context)
    private val discovery = CallRecordingDiscovery(this.context)
    private val work get() = WorkManager.getInstance(context)
    private val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null

    suspend fun state(): CallFollowUpState = withContext(Dispatchers.IO) {
        val config = journal.config()
        val ready = readiness(config)
        CallFollowUpState(config.enabled, config.generation, ready, config.status, config.lastCallEndedAtMillis)
    }

    suspend fun configuredSource(): RecordingSourceGrant? = withContext(Dispatchers.IO) { journal.config().source }

    data class SelectedRecordingIntake(val accepted:Int,val skipped:Int)

    /** Explicit system-picker selections only. Each file keeps its independent durable intake. */
    suspend fun requestSelectedRecordings(selection:List<Uri>,expectedGeneration:Long):SelectedRecordingIntake=withContext(Dispatchers.IO) {
        val files=selection.distinct()
        require(files.size in 1..20)
        var accepted=0
        for(uri in files){
            currentCoroutineContext().ensureActive()
            if(journal.config().let{!it.enabled||it.generation!=expectedGeneration})break
            try{
                requestSelectedRecording(uri,expectedGeneration)
                accepted++
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){ /* A failed item does not discard durable requests for other selected files. */ }
        }
        SelectedRecordingIntake(accepted,files.size-accepted)
    }

    /** Explicit owner-selected ONE URI. Automatic enabledAt cutoff is never widened or backdated. */
    suspend fun requestSelectedRecording(uri: Uri): String = requestSelectedRecording(uri,null)

    private suspend fun requestSelectedRecording(uri: Uri,expectedGeneration:Long?): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "직접 선택한 녹음 파일이 필요합니다." }
        val config = journal.config()
        check(config.enabled&&(expectedGeneration==null||config.generation==expectedGeneration)) { "녹음 선택 권한 범위가 변경되었습니다." }
        val grant = checkNotNull(config.source) { "통화 녹음 폴더를 먼저 선택해주세요." }
        check(phonePermission() && discovery.hasAccess(grant)) { "선택한 녹음과 통화 상태 접근 권한이 필요합니다." }
        check(context.getSystemService(UserManager::class.java).isUserUnlocked)
        // Metadata only, exact chosen directory, no recursion, no other folder/audio-body scan.
        val folderMetadata = discovery.list(grant, 0)
        val selectedUri = when (grant.kind) {
            RecordingSourceKind.MEDIA_FOLDER -> {
                val media = if (uri.authority == MediaStore.AUTHORITY) uri
                    else checkNotNull(MediaStore.getMediaUri(context, uri)) { "선택한 파일의 실제 미디어 항목을 확인하지 못했습니다." }
                check(media.authority == MediaStore.AUTHORITY)
                // Equivalent provider URIs may use /file/ or a union volume. Read actual row metadata,
                // then construct this folder's canonical audio URI without parsing a filesystem path.
                context.contentResolver.query(media, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE), null, null, null)?.use { cursor ->
                    check(cursor.moveToFirst())
                    val id = cursor.getLong(0)
                    CallManualIntakePolicy.verifyMediaIdentity(grant.volume, grant.location, cursor.getString(1).orEmpty(),
                        cursor.getString(2).orEmpty(), id, cursor.getString(3).orEmpty())
                    ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(grant.volume), id)
                } ?: error("선택한 파일의 실제 미디어 정보를 읽지 못했습니다.")
            }
            RecordingSourceKind.SAF_TREE -> {
                val hasRead = context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                check(DocumentsContract.isDocumentUri(context, uri))
                val documentId = DocumentsContract.getDocumentId(uri)
                val identities = folderMetadata.map { entry ->
                    val child = Uri.parse(entry.uri)
                    CallManualIntakePolicy.DocumentIdentity(entry.grantId, entry.uri, child.authority.orEmpty(), DocumentsContract.getDocumentId(child))
                }
                Uri.parse(CallManualIntakePolicy.selectDocument(grant.id, uri.authority, documentId, hasRead, identities))
            }
        }
        val observed = CallManualIntakePolicy.select(grant.id, selectedUri.toString(), folderMetadata)
        check(sameGeneration(config)) { "파일 선택 중 통화 후속 설정이 변경되었습니다." }
        val previous = journal.job(observed.sourceKey)
        check(previous?.state !in TOMBSTONES) { "잊기·삭제·취소 처리한 녹음을 자동으로 되살리지 않습니다." }
        if (previous?.generation == config.generation && previous.state == RecordingJobState.ENQUEUED &&
            previous.observation == observed) {
            previous.snapshot?.let { pipeline().find(it.snapshotId) }
            return@withContext observed.sourceKey
        }
        val now = System.currentTimeMillis()
        val saved = journal.updateJob(observed.sourceKey, config.generation) { current ->
            if (current != null && current.generation == config.generation && current.state == RecordingJobState.ENQUEUED &&
                current.observation == observed) current
            else RecordingJob(observed.sourceKey, config.generation, observed, stableSinceMillis = now,
                status = "owner_selected_waiting_for_file_close", ownerSelectedAtMillis = now)
        } ?: error("파일 선택 후 처리 승인이 변경되었습니다.")
        if (saved.state != RecordingJobState.ENQUEUED) enqueueSource(saved)
        observed.sourceKey
    }

    /** Explicit settings action; an ON switch records intent, never fakes an Android grant. */
    suspend fun setEnabled(enabled: Boolean, source: RecordingSourceGrant? = null): CallFollowUpState = withContext(Dispatchers.IO) {
        source?.validate()
        val old = journal.config()
        val nextSource = source ?: old.source
        if (old.enabled == enabled && old.source?.id == nextSource?.id) {
            if (enabled) { schedule(); startObserving(); requestReconciliation("settings_refreshed") }
            return@withContext state()
        }
        val updated = journal.updateConfig { current ->
            current.copy(enabled = enabled, generation = current.generation + 1, source = nextSource,
                enabledAtMillis = if (enabled && (!current.enabled || current.source?.id != nextSource?.id)) System.currentTimeMillis() else current.enabledAtMillis,
                phoneOffHook = false, callStartedAtMillis = 0, lastCallStartedAtMillis = 0,
                lastCallEndedAtMillis = 0, status = if (enabled) "waiting_for_readiness" else "disabled")
        }
        stopObserving()
        work.cancelAllWorkByTag(TAG)
        // Generation was invalidated durably before requesting cancellation of any queued work.
        old.sourceKeys.forEach { key ->
            val job = journal.job(key)
            if (job?.generation == old.generation && job.state !in TOMBSTONES) {
                try { pipeline().invalidate(key, old.generation, if (enabled) "settings_changed" else "disabled") }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Durable generation fence remains authoritative until queue reconciliation. */ }
            }
        }
        if (updated.enabled) { schedule(); startObserving(); requestReconciliation("settings_enabled") }
        state()
    }

    /** Call from application/service startup; WorkManager retains work across normal process death. */
    fun restore() {
        observationScope.launch {
            try {
                if (journal.config().enabled) { schedule(); startObserving(); requestReconciliation("process_restart") }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { /* A locked/corrupt journal cannot grant access; persisted work retries later. */ }
        }
    }

    fun schedule() {
        if (!journal.config().enabled) return
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CallRecordingReconcileWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints()).addTag(TAG).build())
    }

    fun requestReconciliation(reason: String = "owner_refresh") {
        if (!journal.config().enabled) return
        // Reason is a host enum-like diagnostic only, never a source filename or caller number.
        require(reason.matches(Regex("[a-z_]{1,60}")))
        work.enqueueUniqueWork(RECONCILE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CallRecordingReconcileWorker>().setInitialDelay(3, TimeUnit.SECONDS)
                .setConstraints(constraints()).addTag(TAG).build())
    }

    @Synchronized fun startObserving() {
        if (observer != null) return
        val config = journal.config(); val grant = config.source ?: return
        if (!config.enabled || !discovery.hasAccess(grant)) return
        val value = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { requestReconciliation("recording_folder_changed") }
        }
        val uri = if (grant.kind == RecordingSourceKind.MEDIA_FOLDER) MediaStore.Audio.Media.getContentUri(grant.volume) else Uri.parse(grant.location)
        context.contentResolver.registerContentObserver(uri, true, value)
        observer = value
    }

    @Synchronized fun stopObserving() {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    /** Receiver passes only the state. Phone numbers and source text are never read. */
    suspend fun onPhoneState(state: String) = withContext(Dispatchers.IO) {
        if (!phonePermission()) return@withContext
        var ended = false
        journal.updateConfig { config ->
            CallFollowUpPolicy.phoneState(config, state, System.currentTimeMillis()).also {
                ended = it.lastCallEndedAtMillis != config.lastCallEndedAtMillis
            }
        }
        if (ended) requestReconciliation("call_ended")
    }

    suspend fun cancel(sourceKey: String) = invalidate(sourceKey, RecordingJobState.CANCELLED, "owner_cancelled")
    suspend fun forget(sourceKey: String) = invalidate(sourceKey, RecordingJobState.FORGOTTEN, "owner_forgot_source")

    /** Root pipeline must call this before stages and before publishing any report/proposal. */
    fun isAuthorized(snapshot: StableCallRecording, authorization: CallFollowUpAuthorization): Boolean {
        snapshot.validate()
        val config = journal.config()
        val job = journal.job(snapshot.sourceKey)
        return config.enabled && config.generation == authorization.generation &&
            config.source?.id == authorization.sourceGrantId && snapshot.sourceGrantId == authorization.sourceGrantId &&
            phonePermission() && config.source?.let(discovery::hasAccess) == true &&
            context.getSystemService(UserManager::class.java).isUserUnlocked &&
            job?.generation == authorization.generation && job.state !in TOMBSTONES &&
            job.snapshot == snapshot && job.observation.modifiedAtMillis == snapshot.modifiedAtMillis
    }

    /** Revalidate original availability/revision. Actual ASR input bytes still need the SHA contract. */
    suspend fun sourceStillPresent(snapshot: StableCallRecording, authorization: CallFollowUpAuthorization): Boolean {
        if (!isAuthorized(snapshot, authorization)) return false
        val config = journal.config(); val grant = config.source ?: return false
        val present = discovery.hasAccess(grant) && journal.job(snapshot.sourceKey)?.observation?.let {
            discovery.current(grant, it, config.enabledAtMillis) == it
        } == true
        return present && isAuthorized(snapshot, authorization)
    }

    internal suspend fun reconcile(): Boolean = withContext(Dispatchers.IO) {
        withTimeout(120_000) {
            val config = journal.config()
            if (!config.enabled) return@withTimeout false
            val grant = config.source ?: return@withTimeout status(config.generation, "source_not_selected").let { false }
            if (!discovery.hasAccess(grant)) {
                status(config.generation, "source_access_revoked")
                invalidatePending(config, "source_access_revoked")
                return@withTimeout false
            }
            // Only new files since this opt-in. Exact local tree has no recursive traversal.
            val observed = discovery.list(grant, config.enabledAtMillis)
            currentCoroutineContext().ensureActive()
            if (!sameGeneration(config)) return@withTimeout false
            val present = observed.associateBy { it.sourceKey }
            config.sourceKeys.forEach { key ->
                val old = journal.job(key) ?: return@forEach
                if (old.generation == config.generation && old.state !in TOMBSTONES && key !in present) {
                    val current = discovery.current(grant, old.observation, config.enabledAtMillis)
                    if (current == null) invalidate(key, RecordingJobState.SOURCE_DELETED, "source_deleted")
                    else when (CallManualIntakePolicy.recovery(old, current, config.generation)) {
                        CallManualIntakePolicy.Recovery.FIND_EXISTING_PIPELINE -> old.snapshot?.let { pipeline().find(it.snapshotId) }
                        CallManualIntakePolicy.Recovery.PREPARE_SELECTED_SOURCE -> enqueueSource(old)
                        CallManualIntakePolicy.Recovery.NONE -> Unit
                    }
                }
            }
            for (item in observed) {
                currentCoroutineContext().ensureActive()
                if (!sameGeneration(config)) return@withTimeout false
                val old = journal.job(item.sourceKey)
                if (old?.state in TOMBSTONES) continue
                if (!CallFollowUpPolicy.eligible(item, config.enabledAtMillis)) {
                    // Zero-byte/pending files may still close normally. Oversize/unsupported files require review.
                    if (item.byteCount > MAX_RECORDING_BYTES) journal.updateJob(item.sourceKey, config.generation) {
                        RecordingJob(item.sourceKey, config.generation, item, RecordingJobState.NEEDS_REVIEW,
                            System.currentTimeMillis(), status = "recording_exceeds_supported_size")
                    }
                    continue
                }
                if (old?.state == RecordingJobState.ENQUEUED && old.observation.revision == item.revision) {
                    // A process can die between a bounded batch commit and scheduling its next work.
                    old.snapshot?.let { pipeline().find(it.snapshotId) }
                    continue
                }
                val now = System.currentTimeMillis()
                val job = journal.updateJob(item.sourceKey, config.generation) { current ->
                    if (current != null && current.generation == config.generation && current.observation == item) current
                    else RecordingJob(item.sourceKey, config.generation, item, stableSinceMillis = now)
                } ?: continue
                if (job.state != RecordingJobState.NEEDS_REVIEW) enqueueSource(job)
            }
            status(config.generation, if (observed.isEmpty()) "waiting_for_new_recording" else "new_recordings_checked")
            false
        }
    }

    internal suspend fun prepare(sourceKey: String, generation: Long): PreparationResult = withContext(Dispatchers.IO) {
        withTimeout(120_000) {
            val config = journal.config()
            if (!config.enabled || config.generation != generation) return@withTimeout PreparationResult.DONE
            var job = journal.job(sourceKey) ?: return@withTimeout PreparationResult.DONE
            if (job.state in TERMINAL) return@withTimeout PreparationResult.DONE
            val grant = config.source ?: return@withTimeout PreparationResult.DONE
            if (!discovery.hasAccess(grant)) { invalidate(sourceKey, RecordingJobState.SOURCE_DELETED, "source_access_revoked"); return@withTimeout PreparationResult.DONE }
            val ready = readiness(config)
            if (ready != CallFollowUpReadiness.READY) {
                status(generation, ready.name.lowercase())
                return@withTimeout PreparationResult.DEFERRED
            }
            val current = discovery.current(grant, job.observation, config.enabledAtMillis)
            if (current == null) { invalidate(sourceKey, RecordingJobState.SOURCE_DELETED, "source_deleted"); return@withTimeout PreparationResult.DONE }
            val now = System.currentTimeMillis()
            if (current != job.observation || current.pending) {
                journal.updateJob(sourceKey, generation) { old -> (old ?: job).copy(observation = current, state = RecordingJobState.OBSERVED,
                    stableSinceMillis = now, snapshot = null, receipt = null, status = "waiting_for_file_close") }
                return@withTimeout PreparationResult.RETRY
            }
            if (!CallFollowUpPolicy.stable(current, job.stableSinceMillis, now))
                return@withTimeout PreparationResult.RETRY
            if (job.attempts >= CallFollowUpJournal.MAX_ATTEMPTS) {
                journal.updateJob(sourceKey, generation) { checkNotNull(it).copy(state = RecordingJobState.NEEDS_REVIEW, status = "verification_retry_limit") }
                status(generation, "source_requires_review")
                return@withTimeout PreparationResult.NEEDS_REVIEW
            }
            job = journal.updateJob(sourceKey, generation) { old -> checkNotNull(old).copy(state = RecordingJobState.CHECKING,
                attempts = old.attempts + 1, status = "verifying_recording").also {
                    check(old.observation == current) { "확인 전에 녹음 파일 버전이 변경되었습니다." }
                } } ?: return@withTimeout PreparationResult.DONE
            val checkedSnapshot = discovery.snapshot(grant, job.observation, config.enabledAtMillis)
            val snapshot = CallFollowUpPolicy.reuseSameSnapshot(job.snapshot, checkedSnapshot)
            val saved = journal.updateJob(sourceKey, generation) { old -> checkNotNull(old).copy(snapshot = snapshot, status = "ready_to_queue").also {
                check(old.observation == job.observation) { "준비 중 녹음 파일 버전이 변경되었습니다." }
            } }
                ?: return@withTimeout PreparationResult.DONE
            val authorization = CallFollowUpAuthorization(generation, grant.id, config.enabledAtMillis,
                if (job.ownerSelectedAtMillis != null) null else
                    CallFollowUpPolicy.matchingEndedSignal(config, job.observation, snapshot.durationMillis))
            if (!isAuthorized(snapshot, authorization)) return@withTimeout PreparationResult.DONE
            // A prior process may have committed the queue receipt before it could save our journal.
            val receipt = pipeline().find(snapshot.snapshotId) ?: pipeline().enqueue(snapshot, authorization)
            receipt.validate(snapshot)
            if (!isAuthorized(snapshot, authorization)) {
                withContext(NonCancellable) { pipeline().invalidate(sourceKey, generation, "authorization_changed") }
                return@withTimeout PreparationResult.DONE
            }
            journal.updateJob(sourceKey, generation) { old ->
                val actual = checkNotNull(old)
                check(actual.snapshot == saved.snapshot)
                actual.copy(state = RecordingJobState.ENQUEUED, receipt = receipt, status = "queued_private_followup")
            }
            status(generation, "queued_private_followup")
            PreparationResult.DONE
        }
    }

    internal fun preparationFailed(sourceKey: String, generation: Long) {
        journal.updateJob(sourceKey, generation) { old -> checkNotNull(old).copy(
            state = if (old.attempts >= CallFollowUpJournal.MAX_ATTEMPTS) RecordingJobState.NEEDS_REVIEW else RecordingJobState.OBSERVED,
            status = "file_or_queue_verification_pending") }
        status(generation, "file_or_queue_verification_pending")
    }

    private suspend fun invalidate(sourceKey: String, state: RecordingJobState, reason: String) = withContext(Dispatchers.IO) {
        val old = journal.tombstone(sourceKey, state, reason) ?: return@withContext
        work.cancelUniqueWork(workName(sourceKey, old.generation))
        pipeline().invalidate(sourceKey, old.generation, reason)
    }
    private suspend fun invalidatePending(config: CallFollowUpConfig, reason: String) {
        config.sourceKeys.forEach { key ->
            val job = journal.job(key)
            if (job?.generation == config.generation && job.state !in TOMBSTONES) invalidate(key, RecordingJobState.SOURCE_DELETED, reason)
        }
    }
    private fun enqueueSource(job: RecordingJob) {
        work.enqueueUniqueWork(workName(job.sourceKey, job.generation), ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CallRecordingPrepareWorker>()
                .setInputData(workDataOf("sourceKey" to job.sourceKey, "generation" to job.generation))
                .setInitialDelay(STABLE_MILLIS, TimeUnit.MILLISECONDS).setConstraints(constraints()).addTag(TAG).build())
    }
    private fun sameGeneration(expected: CallFollowUpConfig): Boolean = journal.config().let { it.enabled && it.generation == expected.generation && it.source?.id == expected.source?.id }
    private fun status(generation: Long, value: String) { journal.updateConfig { if (it.generation == generation) it.copy(status = value) else it } }
    private fun phonePermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    private suspend fun readiness(config: CallFollowUpConfig): CallFollowUpReadiness {
        if (!config.enabled) return CallFollowUpReadiness.DISABLED
        val source = config.source ?: return CallFollowUpReadiness.SOURCE_NOT_SELECTED
        if (!discovery.hasAccess(source)) return CallFollowUpReadiness.REQUIRES_SOURCE_ACCESS
        if (!phonePermission()) return CallFollowUpReadiness.REQUIRES_PHONE_PERMISSION
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return CallFollowUpReadiness.DEFERRED_DEVICE_STATE
        @Suppress("DEPRECATION") val callState = context.getSystemService(TelephonyManager::class.java)?.callState
        if (callState != TelephonyManager.CALL_STATE_IDLE) return CallFollowUpReadiness.DEFERRED_DEVICE_STATE
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        if (battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) !in 20..100 || power.isPowerSaveMode ||
            power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || RuntimeGate.inference.get() || RuntimeGate.benchmark.get())
            return CallFollowUpReadiness.DEFERRED_DEVICE_STATE
        return when (pipeline().readiness()) {
            PipelineReadiness.READY -> CallFollowUpReadiness.READY
            PipelineReadiness.MODEL_UNAVAILABLE -> CallFollowUpReadiness.MODEL_UNAVAILABLE
            PipelineReadiness.BUSY -> CallFollowUpReadiness.DEFERRED_DEVICE_STATE
            PipelineReadiness.DISABLED -> CallFollowUpReadiness.DISABLED
        }
    }
    companion object {
        private const val TAG = "call-follow-up-v1"
        private const val PERIODIC = "call-follow-up-periodic-v1"
        private const val RECONCILE = "call-follow-up-reconcile-v1"
        private const val STABLE_MILLIS = CallFollowUpPolicy.STABLE_MILLIS
        private val TOMBSTONES = setOf(RecordingJobState.FORGOTTEN, RecordingJobState.CANCELLED, RecordingJobState.SOURCE_DELETED)
        private val TERMINAL = TOMBSTONES + RecordingJobState.ENQUEUED + RecordingJobState.NEEDS_REVIEW
        private fun workName(key: String, generation: Long) = "call-follow-up-source-v1.$key.$generation"
        private fun constraints() = Constraints.Builder().setRequiresBatteryNotLow(true).build()
    }
}
