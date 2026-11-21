package com.user.localagent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.user.localagent.AppContainer
import com.user.localagent.audio.PrivateDataPickerOrigin
import com.user.localagent.audio.PrivateDataPickerPurpose
import com.user.localagent.audio.PrivateDataPickerScope
import com.user.localagent.imports.ConversationImport
import com.user.localagent.imports.ConversationImportCatalogItem
import com.user.localagent.imports.PhoneRecordResult
import kotlinx.coroutines.*

/** User-directed imports and permission-gated phone snapshots. Nothing is read on composition. */
@Composable
fun DataAnalysisPanel(app: AppContainer, onAsk: (String, String) -> Unit, onTranscribe: (Uri, PrivateDataPickerOrigin) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { mutableStateListOf<ConversationImportCatalogItem>() }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("가져올 자료를 직접 선택하거나, 필요한 휴대전화 접근 권한을 따로 허용하세요.") }
    var deleteTarget by remember { mutableStateOf<Pair<ConversationImportCatalogItem, PrivateDataPickerScope>?>(null) }
    var smsGranted by remember { mutableStateOf(false) }
    var callGranted by remember { mutableStateOf(false) }
    var audioGranted by remember { mutableStateOf(false) }
    var smsResult by remember { mutableStateOf("") }
    var callResult by remember { mutableStateOf("") }
    var audioResult by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var textPickerToken by rememberSaveable { mutableStateOf<String?>(null) }
    var audioPickerToken by rememberSaveable { mutableStateOf<String?>(null) }
    var importOperation by remember { mutableStateOf<Job?>(null) }
    val selectedThreadId by app.runs.selectedThreadId.collectAsState()
    val selectionReady by app.runs.selectionReady.collectAsState()
    val modelBusy by app.runs.busy.collectAsState()
    val threads by app.repository.chat.threads.collectAsState(emptyList())
    val projects by app.repository.chat.projects.collectAsState(emptyList())
    val panelThreadId = selectedThreadId // Freeze the rendered origin in retained button callbacks.
    val thread = threads.firstOrNull { it.id == panelThreadId }
    val project = projects.firstOrNull { it.id == thread?.projectId }
    val catalogKey = listOf(panelThreadId, thread?.revision, thread?.activeBranchId, thread?.projectId, project?.revision)
    val latestCatalogKey by rememberUpdatedState(catalogKey)
    var loadedCatalogKey by remember { mutableStateOf<List<Any?>?>(null) }
    var catalogScope by remember { mutableStateOf<PrivateDataPickerScope?>(null) }
    var catalogLoading by remember { mutableStateOf(false) }
    var catalogLoadToken by remember { mutableStateOf<Any?>(null) }
    val visibleCatalog = if (loadedCatalogKey == catalogKey) catalog.toList() else emptyList()
    val pickerPending = textPickerToken != null || audioPickerToken != null
    val panelReady = selectionReady && panelThreadId.isNotBlank() && thread != null

    fun displaysScope(origin: PrivateDataPickerScope): Boolean =
        app.runs.selectedThreadId.value == origin.threadId && latestCatalogKey.getOrNull(0) == origin.threadId &&
            latestCatalogKey.getOrNull(1) == origin.threadRevision &&
            latestCatalogKey.getOrNull(2) == origin.branchId && latestCatalogKey.getOrNull(3) == origin.projectId

    suspend fun scopedImports(origin: PrivateDataPickerScope): ConversationImport {
        requirePrivateDataUiScope(app.repository, origin)
        val repository = app.repository.forThread(origin.threadId)
        requirePrivateDataUiScope(app.repository, origin)
        return ConversationImport(context, repository, discardIncompleteNotes = repository::discardIncompleteConversationNotes)
    }

    suspend fun <T> monitorOrigin(origin: PrivateDataPickerOrigin, block: suspend () -> T): T = coroutineScope {
        app.validatePrivateDataOrigin(origin)
        val operation = checkNotNull(currentCoroutineContext()[Job])
        val guard = launch(Dispatchers.Default) {
            while (isActive) {
                delay(200)
                try { app.validatePrivateDataOrigin(origin) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { operation.cancel(CancellationException("원래 자료 선택 범위가 변경되었습니다.")); return@launch }
            }
        }
        try { block() } finally { withContext(NonCancellable) { guard.cancelAndJoin() } }
    }

    fun launchPicker(purpose: PrivateDataPickerPurpose, launch: () -> Unit) {
        if (busy || pickerPending) return
        val clickedThreadId = panelThreadId
        if (!app.runs.selectionReady.value || app.runs.selectedThreadId.value != clickedThreadId) {
            status = "자료를 연결할 대화를 먼저 확인해주세요."; return
        }
        busy = true
        scope.launch {
            var token: String? = null
            try {
                val origin = capturePrivateDataUiScope(app.repository, clickedThreadId)
                check(app.runs.selectionReady.value && app.runs.selectedThreadId.value == clickedThreadId)
                token = app.privateDataPickerOrigins.begin(origin, purpose)
                when (purpose) {
                    PrivateDataPickerPurpose.CONVERSATION_IMPORT -> textPickerToken = token
                    PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION -> audioPickerToken = token
                }
                launch()
            } catch (cancel: CancellationException) {
                token?.let { app.privateDataPickerOrigins.cancel(it) }
                when (purpose) {
                    PrivateDataPickerPurpose.CONVERSATION_IMPORT -> textPickerToken = null
                    PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION -> audioPickerToken = null
                }
                throw cancel
            } catch (_: Exception) {
                token?.let { app.privateDataPickerOrigins.cancel(it) }
                when (purpose) {
                    PrivateDataPickerPurpose.CONVERSATION_IMPORT -> textPickerToken = null
                    PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION -> audioPickerToken = null
                }
                status = "원래 대화에서 파일 선택을 다시 시작해주세요."
            } finally { busy = false }
        }
    }

    fun refreshPermissions() {
        smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        callGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        audioGranted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) { refreshPermissions() }
    LaunchedEffect(app, catalogKey, selectionReady, refreshKey) {
        val loadToken = Any(); catalogLoadToken = loadToken
        catalog.clear(); loadedCatalogKey = null; catalogScope = null; deleteTarget = null
        catalogLoading = false
        if (!panelReady) return@LaunchedEffect
        catalogLoading = true
        val originKey = catalogKey
        val originThreadId = panelThreadId
        try {
            val origin = capturePrivateDataUiScope(app.repository, originThreadId)
            val entries = scopedImports(origin).catalog()
            requirePrivateDataUiScope(app.repository, origin)
            if (latestCatalogKey == originKey && catalogLoadToken === loadToken && app.runs.selectedThreadId.value == originThreadId) {
                catalog.addAll(entries); catalogScope = origin; loadedCatalogKey = originKey
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (latestCatalogKey == originKey && catalogLoadToken === loadToken) status = "현재 대화 범위의 가져온 자료를 확인하지 못했습니다." }
        finally { if (catalogLoadToken === loadToken) catalogLoading = false }
    }

    val textPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        val files = selected.distinct()
        val token = textPickerToken
        textPickerToken = null
        if (files.isEmpty()) { token?.let { app.privateDataPickerOrigins.cancel(it) } }
        else if (token == null) { status = "원래 파일 선택 요청을 확인할 수 없습니다. 파일을 다시 선택해주세요." }
        else {
            val origin = runCatching { app.privateDataPickerOrigins.consume(token, PrivateDataPickerPurpose.CONVERSATION_IMPORT) }.getOrNull()
            if (origin == null) status = "원래 파일 선택 요청을 확인할 수 없습니다. 파일을 다시 선택해주세요."
            else {
                busy = true
                lateinit var operation: Job
                operation = scope.launch(start = CoroutineStart.LAZY) {
                    var importer: ConversationImport? = null
                    if (displaysScope(origin.scope)) { progress = "파일 확인 중"; status = "선택한 텍스트 파일을 원래 대화에 가져오는 중입니다." }
                    try {
                        val repository = app.privateDataRepository(origin)
                        importer = ConversationImport(context, repository, discardIncompleteNotes = repository::discardIncompleteConversationNotes)
                        var imported = 0
                        var existing = 0
                        var rejected = 0
                        var messages = 0L
                        monitorOrigin(origin) {
                            for ((index, uri) in files.withIndex()) {
                                app.validatePrivateDataOrigin(origin)
                                try {
                                    val result = checkNotNull(importer).importUri(uri) { done, total ->
                                        context.mainExecutor.execute {
                                            if (displaysScope(origin.scope)) progress = "파일 ${index+1}/${files.size} · 자료 조각 $done/$total"
                                        }
                                    }
                                    if (result.duplicate) existing++ else { imported++; messages+=result.messageCount }
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) {
                                    app.validatePrivateDataOrigin(origin)
                                    if (importer?.lastCleanupResult?.verified == false)
                                        error("이 파일의 정리 상태를 확인할 수 없어 나머지 가져오기를 멈췄습니다.")
                                    rejected++
                                }
                            }
                        }
                        app.validatePrivateDataOrigin(origin)
                        if (displaysScope(origin.scope)) {
                            status = "새 파일 ${imported}개 · 중복 ${existing}개 · 미처리 ${rejected}개 · 새 메시지 ${messages}건"
                        }
                        refreshKey++ // Reload the displayed repository; never publish another scope's counts.
                    } catch (cancel: CancellationException) {
                        if (displaysScope(origin.scope)) status = if (importer?.lastCleanupResult?.verified == false)
                            "가져오기를 중단했습니다. 변경됐거나 정리를 확인하지 못한 자료가 있습니다." else "가져오기를 중단했습니다."
                        throw cancel
                    } catch (error: Exception) {
                        if (displaysScope(origin.scope)) status = error.message?.take(200) ?: "파일을 가져오지 못했습니다."
                    } finally { busy = false; progress = ""; if (importOperation === operation) importOperation = null }
                }
                importOperation = operation; operation.start()
            }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val token = audioPickerToken
        audioPickerToken = null
        if (uri == null) { token?.let { app.privateDataPickerOrigins.cancel(it) } }
        else if (token == null) { status = "원래 오디오 선택 요청을 확인할 수 없습니다. 파일을 다시 선택해주세요." }
        else {
            val origin = runCatching { app.privateDataPickerOrigins.consume(token, PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION) }.getOrNull()
            if (origin == null) status = "원래 오디오 선택 요청을 확인할 수 없습니다. 파일을 다시 선택해주세요."
            else { busy = true; scope.launch {
                try { app.privateDataRepository(origin); onTranscribe(uri, origin) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { status = "원래 대화 범위가 변경되어 전사를 시작하지 않았습니다. 파일을 다시 선택해주세요." }
                finally { busy = false }
            } }
        }
    }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions(); smsResult = if (granted) "문자 접근이 허용되었습니다. 아래 버튼을 눌러 가져오기를 시작하세요." else "문자 접근이 허용되지 않았습니다."
    }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions(); callResult = if (granted) "통화 기록 메타데이터 접근이 허용되었습니다." else "통화 기록 접근이 허용되지 않았습니다."
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions(); audioResult = if (granted) "녹음 파일 메타데이터 접근이 허용되었습니다." else "오디오 접근이 허용되지 않았습니다."
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        item { com.user.localagent.briefing.BriefingPanel(app) }
        item {
            ZipHapHeader("자료 분석", if (busy) progress.ifBlank { "작업 중" } else status, busy)
        }
        item {
            ZipHapCard {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("대화 파일 가져오기", style = MaterialTheme.typography.titleMedium)
                    Text("카카오톡 등에서 내보낸 텍스트·CSV 파일을 선택합니다. 가져온 파일 범위만 보관하고, 전체 메신저 대화에 접근하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { launchPicker(PrivateDataPickerPurpose.CONVERSATION_IMPORT) {
                        textPicker.launch(arrayOf("text/plain", "text/csv", "application/octet-stream"))
                    } }, enabled = !busy && !pickerPending && panelReady) {
                        Text("대화 파일 선택")
                    }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(progress, style = MaterialTheme.typography.bodySmall)
                    }
                    if (importOperation?.isActive == true) TextButton(onClick = { importOperation?.cancel() }) { Text("가져오기 중단") }
                }
            }
        }
        item {
            ZipHapCard {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("휴대전화 자료", style = MaterialTheme.typography.titleMedium)
                    Text("문자와 통화 기록은 각각 Android 권한이 필요합니다. 허용을 눌러도 즉시 읽지 않으며, 가져오기를 다시 눌렀을 때만 처리합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PermissionLine("문자", smsGranted, smsResult,
                        onGrant = { smsPermission.launch(Manifest.permission.READ_SMS) },
                        actionLabel = if (smsGranted) "허용됨" else "문자 접근 허용",
                        onAction = {
                            val clickedThreadId = panelThreadId
                            scope.launch {
                                busy = true; progress = "문자 자료 가져오는 중"; smsResult = ""
                                try {
                                    check(app.runs.selectedThreadId.value == clickedThreadId && app.runs.selectionReady.value)
                                    val origin = capturePrivateDataUiScope(app.repository, clickedThreadId)
                                    val repository = app.repository.forThread(origin.threadId)
                                    requirePrivateDataUiScope(app.repository, origin)
                                    val result = app.phoneRecords.importSmsToRepository(repository) { done, total ->
                                        context.mainExecutor.execute { if (displaysScope(origin)) progress = "문자 자료 조각 $done / $total" }
                                    }
                                    requirePrivateDataUiScope(app.repository, origin)
                                    if (displaysScope(origin)) when (result) {
                                        is PhoneRecordResult.Ready -> smsResult = "가져오기 완료 · ${result.value.recordCount}개 문자 · ${result.value.chunkCount}개 조각${if (result.value.duplicate) " · 기존 자료와 동일" else ""}. 본문은 이 화면에 표시하지 않습니다."
                                        is PhoneRecordResult.ConnectionRequired -> smsResult = result.description
                                        is PhoneRecordResult.LimitExceeded -> smsResult = result.description
                                        is PhoneRecordResult.Unavailable -> smsResult = result.description
                                    }
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { if (app.runs.selectedThreadId.value == clickedThreadId) smsResult = error.message ?: "문자 자료를 가져오지 못했습니다." }
                                finally { busy = false; progress = "" }
                            }
                        }, actionEnabled = smsGranted && !busy && !pickerPending && panelReady)
                    PermissionLine("통화 기록", callGranted, callResult,
                        onGrant = { callPermission.launch(Manifest.permission.READ_CALL_LOG) },
                        actionLabel = if (callGranted) "통화 기록 요약" else "통화 기록 접근 허용",
                        onAction = {
                            scope.launch {
                                busy = true
                                try {
                                    callResult = when (val result = app.phoneRecords.callLogSummary(limit = 1)) {
                                        is PhoneRecordResult.Ready -> "조회 범위 ${result.value.totalCount}건 · 통화 시간·유형만 요약합니다. ${if (result.value.aggregatesComplete) "집계 완료" else "일부 자료만 집계"}"
                                        is PhoneRecordResult.ConnectionRequired -> result.description
                                        is PhoneRecordResult.LimitExceeded -> result.description
                                        is PhoneRecordResult.Unavailable -> result.description
                                    }
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { callResult = error.message ?: "통화 기록을 조회하지 못했습니다." }
                                finally { busy = false }
                            }
                        }, actionEnabled = callGranted && !busy)
                    PermissionLine("녹음 파일", audioGranted, audioResult,
                        onGrant = { audioPermission.launch(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) },
                        actionLabel = "녹음 메타데이터 조회",
                        onAction = {
                            scope.launch {
                                busy = true
                                try {
                                    audioResult = when (val result = app.phoneRecords.recordAudio(limit = 1)) {
                                        is PhoneRecordResult.Ready -> "녹음으로 표시된 파일 ${result.value.totalCount}개를 확인했습니다. 파일명과 음성 내용은 표시하지 않습니다."
                                        is PhoneRecordResult.ConnectionRequired -> result.description
                                        is PhoneRecordResult.LimitExceeded -> result.description
                                        is PhoneRecordResult.Unavailable -> result.description
                                    }
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { audioResult = error.message ?: "녹음 메타데이터를 조회하지 못했습니다." }
                                finally { busy = false }
                            }
                        }, actionEnabled = audioGranted && !busy)
                    OutlinedButton(onClick = { launchPicker(PrivateDataPickerPurpose.AUDIO_TRANSCRIPTION) {
                        audioPicker.launch(arrayOf("audio/*"))
                    } }, enabled = !busy && !pickerPending && panelReady && !modelBusy) {
                        Text("오디오 파일 직접 선택 · 추가 권한 불필요")
                    }
                    Text("녹음 분석은 파일을 직접 선택해야 시작됩니다. 알림 기록은 권한을 허용한 시점 이후부터만 볼 수 있으며, 카카오톡 전체 기록은 내보낸 파일로 가져오세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("가져온 대화 자료", style = MaterialTheme.typography.titleLarge)
        }
        if (visibleCatalog.isEmpty()) item {
            val message = when {
                !panelReady -> "자료를 연결할 대화를 먼저 선택해주세요."
                catalogLoading -> "현재 대화에 연결한 자료를 확인하는 중입니다."
                loadedCatalogKey != catalogKey -> "자료 목록을 확인하지 못했습니다. 원래 대화를 다시 확인해주세요."
                else -> "아직 가져온 자료가 없습니다. 분석할 파일을 직접 선택하면 여기에 범위와 인식 결과가 표시됩니다."
            }
            ZipHapCard { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(visibleCatalog, key = { it.contentSha256 }) { entry ->
            ZipHapCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("자료 ${entry.contentSha256.take(12)}", style = MaterialTheme.typography.titleMedium)
                    Text("${entry.messageCount}개 메시지 · ${entry.chunkCount}개 조각 · ${entry.recognizedFormat}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val dates = listOfNotNull(entry.firstTimestamp, entry.lastTimestamp).distinct()
                    if (dates.isNotEmpty()) Text("인식한 기간: ${dates.joinToString(" ~ ")}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val origin = catalogScope
                            if (origin == null || !displaysScope(origin)) status = "현재 대화의 자료 목록을 다시 확인해주세요."
                            else scope.launch {
                                try {
                                    requirePrivateDataUiScope(app.repository, origin)
                                    check(displaysScope(origin))
                                    onAsk("가져온 대화 자료 ${entry.contentSha256}를 요약하고, 확인 가능한 범위와 기간을 밝히고, 다시 찾을 수 있도록 핵심 발견을 자료의 정확한 인용과 함께 저장해줘.", origin.threadId)
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { status = "현재 대화의 자료 목록을 다시 확인해주세요." }
                            }
                        }, enabled = !busy && !pickerPending) { Text("이 자료 분석") }
                        OutlinedButton(onClick = {
                            catalogScope?.takeIf(::displaysScope)?.let { deleteTarget = entry to it }
                        }, enabled = !busy && !pickerPending) { Text("삭제") }
                    }
                }
            }
        }
    }

    deleteTarget?.let { (target, origin) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("가져온 자료를 삭제할까요?") },
            text = { Text("자료 ${target.contentSha256.take(12)}와 저장된 조각을 삭제합니다. 원본 파일은 변경하지 않습니다.") },
            confirmButton = { TextButton(onClick = {
                deleteTarget = null
                scope.launch {
                    busy = true
                    try {
                        check(displaysScope(origin))
                        scopedImports(origin).deleteImport(target.contentSha256)
                        requirePrivateDataUiScope(app.repository, origin)
                        if (displaysScope(origin)) {
                            catalog.removeAll { it.contentSha256 == target.contentSha256 }
                            status = "선택한 가져오기 자료를 삭제했습니다."
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { if (displaysScope(origin)) status = error.message ?: "자료를 삭제하지 못했습니다." }
                    finally { busy = false }
                }
            }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun PermissionLine(
    title: String,
    granted: Boolean,
    message: String,
    onGrant: () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(if (granted) "권한 허용됨" else "권한 필요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!granted) OutlinedButton(onClick = onGrant) { Text("접근 허용") }
                Button(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
            }
        }
    }
}
