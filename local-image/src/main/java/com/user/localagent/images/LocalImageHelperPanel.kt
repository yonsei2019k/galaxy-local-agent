package com.user.localagent.images

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.user.localagent.models.GemmaMediaCatalog
import com.user.localagent.models.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** Explicit file preparation only: no model load, inference, deletion or cross-app copying. */
@Composable
internal fun LocalImageHelperPanel(
    manager: ModelManager,
    imageRunning: Boolean,
    isImageRunning: () -> Boolean,
    onPreparingChanged: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val installed by manager.models.collectAsState()
    val selected by manager.mediaSelection.collectAsState()
    val progress by manager.progress.collectAsState()
    val candidates = remember(manager) {
        manager.candidates.filter { it.id in GemmaMediaCatalog.modelIds && it.runtime == "gguf" }
    }
    var preparing by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf("한국어 장면 설명을 정리할 보조 모델을 선택해주세요.") }

    fun begin(action: suspend () -> String) {
        if (preparing || isImageRunning()) return
        preparing = true
        onPreparingChanged(true)
        message = "모델 파일을 확인하고 있습니다."
        operation = scope.launch {
            try {
                message = action()
                onStatus(message)
            } catch (cancel: CancellationException) {
                message = "준비를 중단했습니다. 받은 부분 파일은 다음 다운로드에 사용됩니다."
                onStatus(message)
                throw cancel
            } catch (error: Exception) {
                message = error.message?.take(240) ?: "보조 모델을 준비하지 못했습니다. 다시 확인해주세요."
                onStatus(message)
            } finally {
                operation = null
                preparing = false
                onPreparingChanged(false)
            }
        }
    }

    // Local index/hash refresh is allowed; downloading and selecting require the buttons below.
    LaunchedEffect(manager) {
        begin {
            manager.refresh()
            "설치된 파일을 확인했습니다. 검증된 보조 모델을 선택해주세요."
        }
    }
    LaunchedEffect(imageRunning) {
        if (imageRunning && preparing) operation?.cancel()
    }

    Dialog(onDismissRequest = { if (!preparing) onClose() }, properties = DialogProperties(
        dismissOnBackPress = !preparing, dismissOnClickOutside = !preparing
    )) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("한국어 보조 모델", style = MaterialTheme.typography.titleLarge)
                Text("모델과 사진 지원 파일(mmproj)을 각각 내려받습니다. 큰 저장공간이 필요하며, 정확한 파일별 크기는 진행에 표시됩니다. 기존 집합 앱의 파일은 복사하거나 삭제하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
                Text("이 화면은 파일만 준비하고 모델을 메모리에 올리지 않습니다. 다른 앱의 휴대폰 모델 작업과 동시에 계산하지 마세요. 앱 간 동시 계산 조정은 아직 지원하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
                Text(message, style = MaterialTheme.typography.bodyMedium)
                progress?.takeIf { preparing }?.let { current ->
                    Text(current.title, style = MaterialTheme.typography.labelMedium)
                    Text((if (current.phase == "hash") "무결성 확인 " else "다운로드 ") +
                        "${helperBytes(current.bytes)} / ${helperBytes(current.total)}",
                        style = MaterialTheme.typography.bodySmall)
                    if (current.total > 0) LinearProgressIndicator(
                        progress = { (current.bytes.toDouble() / current.total).coerceIn(0.0, 1.0).toFloat() },
                        modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                candidates.forEach { candidate ->
                    val current = installed.firstOrNull { it.id == candidate.id }
                    val ready = current != null && manager.mediaReady(current)
                    val active = ready && selected.vision == candidate.id
                    HorizontalDivider()
                    Text(candidate.title, style = MaterialTheme.typography.titleSmall)
                    Text("출처: ${candidate.repository}", style = MaterialTheme.typography.bodySmall)
                    Text(if (active) "검증 완료 · 현재 보조 모델" else if (ready) "모델·지원 파일 검증 완료" else "모델·지원 파일 준비 필요",
                        style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !preparing && !imageRunning && !active, onClick = {
                        begin {
                            manager.refresh()
                            val before = manager.models.value.firstOrNull { it.id == candidate.id }
                            if (before == null || !manager.mediaReady(before)) manager.downloadBundle(candidate)
                            manager.refresh()
                            check(!isImageRunning()) { "이미지 작업이 끝난 뒤 보조 모델을 선택해주세요." }
                            val verified = manager.models.value.firstOrNull { it.id == candidate.id }
                            check(verified != null && manager.mediaReady(verified)) { "모델과 지원 파일 검증을 완료하지 못했습니다." }
                            manager.selectMedia("vision", candidate.id)
                            "${candidate.title}을 한국어 보조 모델로 선택했습니다. 파일만 준비했으며 추론은 실행하지 않았습니다."
                        }
                    }) { Text(if (ready) "보조 모델로 선택" else "파일 내려받고 선택") }
                }
                if (preparing) OutlinedButton(onClick = {
                    message = "중단 중입니다. 현재 파일 작업이 정리될 때까지 기다려주세요."
                    operation?.cancel()
                }) { Text("준비 중단") }
                TextButton(enabled = !preparing, onClick = onClose) { Text("닫기") }
            }
        }
    }
}

private fun helperBytes(bytes: Long): String = if (bytes < 0) "크기 확인 중" else
    String.format(Locale.ROOT, "%.1f MiB", bytes.toDouble() / (1024 * 1024))
