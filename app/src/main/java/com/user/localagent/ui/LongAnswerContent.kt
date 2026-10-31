package com.user.localagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Continuation parts are durable answer segments, distinct from display-only text blocks. */
data class UiAnswerPart(val sequence: Int, val text: String)
enum class UiAnswerPhase { STREAMING, CONTINUING, COMPLETE, PAUSED, CANCELLED, FAILED }
data class UiAnswerState(
    val phase: UiAnswerPhase = UiAnswerPhase.COMPLETE,
    val parts: List<UiAnswerPart> = emptyList(),
    val detail: String = "",
    val canContinue: Boolean = false,
)

/** Host callbacks resolve the canonical full message by ID, including all persisted parts. */
data class UiMessageActions(
    val onEdit: ((String, String) -> Unit)? = null,
    val onRegenerate: ((String) -> Unit)? = null,
    val onFork: ((String) -> Unit)? = null,
    val onCopy: ((String) -> Unit)? = null,
    val onExport: ((String) -> Unit)? = null,
)

internal data class LongAnswerBlock(
    val text: String,
    val partIndex: Int,
    val blockIndex: Int,
    val startsPart: Boolean,
    val partCount: Int,
)

private val imageMarker = Regex("\\[\\[image:image-[a-f0-9-]{36}\\.png]]")
private val fileMarker = Regex("\\[\\[file:artifact-[a-f0-9-]{36}\\.(?:docx|xlsx|pdf|txt|csv)]]")

internal fun stripChatArtifactMarkers(text: String): String = text.replace(imageMarker, "").replace(fileMarker, "")
    .replace("[[screen:sms-schedules]]", "")
    .replace("[[screen:imports]]", "")
    .replace("[[screen:web-research]]", "")

/**
 * UI pagination only: concatenating these strings reproduces the input exactly. Boundaries do
 * not split a surrogate pair, and no take()/trim()/ellipsis can silently discard an answer.
 */
internal fun splitLongAnswer(text: String, targetChars: Int = 2400): List<String> {
    require(targetChars >= 128)
    if (text.length <= targetChars) return listOf(text)
    val blocks = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(text.length, start + targetChars)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            val space = text.lastIndexOf(' ', end - 1)
            val readableBoundary = if (newline >= start + targetChars / 2) newline + 1 else if (space >= start + targetChars / 2) space + 1 else end
            end = readableBoundary
            if (end > start && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        blocks += text.substring(start, end)
        start = end
    }
    return blocks
}

internal fun answerDisplayBlocks(text: String, state: UiAnswerState?, fromUser: Boolean): List<LongAnswerBlock> {
    val parts = canonicalAnswerParts(text, if (fromUser) null else state)
    return parts.flatMapIndexed { partIndex, part ->
        val display = if (fromUser) part.text else stripChatArtifactMarkers(part.text)
        splitLongAnswer(display).mapIndexed { index, block ->
            LongAnswerBlock(block, partIndex, index, index == 0, parts.size)
        }
    }
}

/** Saved body is authoritative; cached generation parts provide boundaries, never replacement text. */
internal fun canonicalAnswerParts(text: String, state: UiAnswerState?): List<UiAnswerPart> {
    val cached = state?.parts?.takeIf { it.isNotEmpty() } ?: return listOf(UiAnswerPart(1, text))
    val result = ArrayList<UiAnswerPart>(cached.size + 1)
    var start = 0
    for (part in cached) {
        if (start == text.length) break
        val length = minOf(part.text.length, text.length - start)
        if (length == 0) continue
        // A stale/different answer state cannot partition a newer canonical answer incorrectly.
        if (!text.regionMatches(start, part.text, 0, length)) return listOf(UiAnswerPart(1, text))
        result += UiAnswerPart(part.sequence, text.substring(start, start + length))
        start += length
    }
    if (start < text.length) result += UiAnswerPart((result.lastOrNull()?.sequence ?: 0) + 1, text.substring(start))
    return result.ifEmpty { listOf(UiAnswerPart(1, text)) }
}

@Composable
internal fun AnswerPartLabel(partIndex: Int, partCount: Int) {
    Text("답변 ${partIndex + 1} / $partCount", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = ZipHapColors.TextSecondary)
}

@Composable
internal fun AnswerContinuationStatus(messageId: String, state: UiAnswerState?, onContinue: (String) -> Unit) {
    if (state == null || (state.phase == UiAnswerPhase.COMPLETE && state.parts.size < 2 && state.detail.isBlank())) return
    val phase = when (state.phase) {
        UiAnswerPhase.STREAMING -> "작성 중"
        UiAnswerPhase.CONTINUING -> "이어서 작성 중"
        UiAnswerPhase.COMPLETE -> "답변 완료"
        UiAnswerPhase.PAUSED -> "이어쓰기 일시 정지"
        UiAnswerPhase.CANCELLED -> "중단됨 · 작성된 내용은 유지됩니다"
        UiAnswerPhase.FAILED -> "작성 중 오류 · 저장된 부분은 유지됩니다"
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("answer_continuation_$messageId"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(phase, style = MaterialTheme.typography.labelMedium, color = if (state.phase == UiAnswerPhase.FAILED) ZipHapColors.Error else ZipHapColors.TextSecondary)
            if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary)
        }
        if (state.canContinue) TextButton(onClick = { onContinue(messageId) }) { Text("이어서 쓰기") }
    }
}

@Composable
internal fun MessageActionRow(messageId: String, text: String, fromUser: Boolean, actions: UiMessageActions?, busy: Boolean) {
    if (actions == null) return
    var menu by remember(messageId) { mutableStateOf(false) }
    var editing by remember(messageId) { mutableStateOf(false) }
    var editedText by remember(messageId, text) { mutableStateOf(text) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        if (actions.onCopy != null) TextButton(onClick = { actions.onCopy.invoke(messageId) }, modifier = Modifier.testTag("copy_message_$messageId")) { Text("복사", color = ZipHapColors.TextSecondary) }
        Column {
            TextButton(onClick = { menu = true }, modifier = Modifier.testTag("message_actions_$messageId")) { Text("⋯", color = ZipHapColors.TextSecondary) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (fromUser && actions.onEdit != null) DropdownMenuItem(text = { Text("메시지 수정") }, onClick = { menu = false; editedText = text; editing = true }, enabled = !busy)
                if (actions.onRegenerate != null) DropdownMenuItem(text = { Text(if (fromUser) "이 요청 다시 실행" else "답변 다시 생성") }, onClick = { menu = false; actions.onRegenerate.invoke(messageId) }, enabled = !busy)
                if (actions.onFork != null) DropdownMenuItem(text = { Text("여기서 새 대화로 분기") }, onClick = { menu = false; actions.onFork.invoke(messageId) })
                if (actions.onExport != null) DropdownMenuItem(text = { Text("답변을 파일로 저장") }, onClick = { menu = false; actions.onExport.invoke(messageId) })
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("메시지 수정") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(editedText, { editedText = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), minLines = 3, maxLines = 10)
            Text("수정한 내용으로 다시 실행합니다. 이미 실행된 일정·파일 작업이 자동으로 되돌려지지는 않습니다.", style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary)
        }
    }, confirmButton = { TextButton(onClick = { actions.onEdit?.invoke(messageId, editedText.trim()); editing = false }, enabled = editedText.isNotBlank()) { Text("수정 후 실행") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("취소") } })
}
