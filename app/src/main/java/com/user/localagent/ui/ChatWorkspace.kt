package com.user.localagent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.user.localagent.ChatLine
import com.user.localagent.models.InstalledModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Stable observable model label for accessibility and instrumentation assertions. */
val ModelLabel = SemanticsPropertyKey<String>("ModelLabel")

/**
 * Inset contract: caller supplies only top + horizontal safe-drawing insets, with no bottom
 * navigation bar or IME padding. This full-height workspace consumes IME/navigation bottom
 * insets itself, once, around the list and same-row composer. No keyboard height is assumed.
 */
@Composable
fun ChatWorkspace(
    messages: List<ChatLine>,
    busy: Boolean,
    status: String,
    models: List<InstalledModel>,
    selectedModelId: String = "auto",
    onModelChange: (String) -> Unit,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context=LocalContext.current
    val draftStore=remember(context){com.user.localagent.data.DraftStore(context)}
    var draft by rememberSaveable { mutableStateOf(draftStore.read()) }
    LaunchedEffect(draft){val revision=draftStore.reserveWrite();kotlinx.coroutines.delay(250);kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){draftStore.write(draft,revision)}}
    var menuOpen by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followBottom by remember { mutableStateOf(true) }
    var automaticScroll by remember { mutableStateOf(false) }
    val nearBottomPixels = with(LocalDensity.current) { 96.dp.toPx() }
    val viewportHeight by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    val anchorIndex = messages.size + if (busy) 1 else 0

    fun submit() {
        val text = draft.trim()
        if (!busy && text.isNotEmpty() && onSend(text)) {
            draftStore.write("")
            draft = ""
            followBottom = true
        }
    }
    fun navigate(section: Int) {
        menuOpen = false
        focusManager.clearFocus()
        keyboard?.hide()
        onNavigate(section)
    }
    fun prepare(text: String) {
        draft = text
        inputFocus.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(listState, nearBottomPixels) {
        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (scrolling, _, _) ->
            if (scrolling && !automaticScroll) followBottom = isNearBottom(listState, nearBottomPixels)
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text, busy, viewportHeight) {
        if (messages.isNotEmpty() && followBottom && !listState.isScrollInProgress) {
            // Wait for the streaming text/new IME viewport to be measured, then target the
            // trailing anchor. Targeting a long assistant item would scroll to its beginning.
            withFrameNanos { }
            automaticScroll = true
            try { listState.scrollToItem(anchorIndex) } finally { automaticScroll = false }
        }
    }

    Column(
        modifier.fillMaxSize().background(ZipHapColors.Background)
            .imePadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp).semantics { contentDescription = "메뉴" }) {
                    MenuMark(Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf("대화" to 0, "모델" to 1, "기억과 할 일" to 2, "문서" to 3, "자료 연결·분석" to 5, "설정" to 4).forEach { (label, section) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { navigate(section) })
                    }
                }
            }
            Text("ZipHap.Agent", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                color = ZipHapColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WorkspaceModelSelector(models, selectedModelId, onModelChange)
        }
        HorizontalDivider(color = Color(0xFF23262A), thickness = 1.dp)

        if (messages.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("무엇을 도와드릴까요?", style = MaterialTheme.typography.headlineSmall, color = ZipHapColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text("생각부터 문서까지, 이 기기에서.", style = MaterialTheme.typography.bodyMedium, color = ZipHapColors.TextSecondary)
                Spacer(Modifier.height(22.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptChip("글 다듬기") { prepare("아래 글을 자연스럽게 다듬어줘.\n") }
                    PromptChip("문서 요약") { navigate(3) }
                    PromptChip("할 일 정리") { prepare("오늘 할 일을 정리해줘.") }
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("chat_messages"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(messages, key = { it.id }) { line ->
                        ZipHapChatBubble(
                            text = line.text,
                            fromUser = line.role == "user",
                            modifier = Modifier.testTag(if (line.role == "user") "user_message" else "assistant_message"),
                        )
                    }
                    if (busy) {
                        item(key = "workspace_busy") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                CircularProgressIndicator(Modifier.size(12.dp), color = ZipHapColors.TextSecondary, strokeWidth = 1.5.dp)
                                Text("답변을 준비하고 있어요", style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary)
                            }
                        }
                    }
                    item(key = "workspace_bottom_anchor") { Spacer(Modifier.height(1.dp)) }
                }
                if (!followBottom) {
                    Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), shape = RoundedCornerShape(20.dp), color = ZipHapColors.Elevated) {
                        TextButton(onClick = {
                            followBottom = true
                            scope.launch {
                                automaticScroll = true
                                try { listState.animateScrollToItem(anchorIndex) } finally { automaticScroll = false }
                            }
                        }) { Text("최근 답변 ↓", color = ZipHapColors.TextPrimary) }
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.size(4.dp).background(if (busy) ZipHapColors.TextSecondary else ZipHapColors.Mint, CircleShape))
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (status.contains("실패") || status.contains("오류")) ZipHapColors.Error else ZipHapColors.TextSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 3.dp, bottom = 8.dp),
            shape = RoundedCornerShape(26.dp), color = ZipHapColors.Surface,
        ) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = draft,
                    onValueChange = { if(it.length<=12_000)draft = it },
                    modifier = Modifier.weight(1f).focusRequester(inputFocus).testTag("chat_input"),
                    placeholder = { Text("메시지를 입력하세요", style = MaterialTheme.typography.bodyLarge, color = ZipHapColors.TextSecondary) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedTextColor = ZipHapColors.TextPrimary,
                        unfocusedTextColor = ZipHapColors.TextPrimary,
                        cursorColor = ZipHapColors.TextPrimary,
                    ),
                )
                val enabled = busy || draft.isNotBlank()
                Box(
                    Modifier.padding(bottom = 8.dp).size(40.dp).clip(CircleShape)
                        .background(if (enabled) ZipHapColors.TextPrimary else Color(0xFF34383D))
                        .testTag("send_button")
                        .clickable(enabled = enabled, role = Role.Button) { if (busy) onStop() else submit() }
                        .semantics { contentDescription = if (busy) "응답 중지" else "보내기" },
                    contentAlignment = Alignment.Center,
                ) { SendMark(busy, Modifier.size(19.dp), if (enabled) ZipHapColors.Background else Color(0xFF70767E)) }
            }
        }
    }
}

@Composable
private fun WorkspaceModelSelector(models: List<InstalledModel>, selectedId: String, onModelChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val usable = models.filter { it.verified && it.runtime in setOf("litertlm","gguf") }
    val label = if (selectedId == "auto") "자동" else usable.find { it.id == selectedId }?.let(::shortModelLabel) ?: "모델 선택"
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = 36.dp).testTag("model_selector").semantics(mergeDescendants = true) {
                contentDescription = "모델 선택 · $label"
                stateDescription = label
                this[ModelLabel] = label
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = ZipHapColors.TextPrimary)
            Spacer(Modifier.width(6.dp))
            ChevronMark(Modifier.size(9.dp), ZipHapColors.TextSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
            DropdownMenuItem(
                text = { Text("자동") },
                onClick = { expanded = false; onModelChange("auto") },
                modifier = Modifier.testTag("model_option_auto"),
                trailingIcon = { if (selectedId == "auto") Text("✓", color = ZipHapColors.TextSecondary) },
            )
            usable.forEach { model ->
                DropdownMenuItem(
                    text = { Text(shortModelLabel(model)) },
                    onClick = { expanded = false; onModelChange(model.id) },
                    modifier = Modifier.testTag("model_option_${model.id}"),
                    trailingIcon = { if (selectedId == model.id) Text("✓", color = ZipHapColors.TextSecondary) },
                )
            }
        }
    }
}

private fun shortModelLabel(model: InstalledModel): String = when (model.id) {
    "gemma-e2b" -> "E2B"
    "gemma-e4b" -> "E4B"
    "qwen-06b" -> "Qwen 0.6B"
    else -> model.title.substringBefore(" ·").take(24)
}

private fun isNearBottom(state: LazyListState, threshold: Float): Boolean {
    if (!state.canScrollForward) return true
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 2 && last.offset + last.size - info.viewportEndOffset <= threshold
}

@Composable
private fun PromptChip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label, color = ZipHapColors.TextSecondary) },
        colors = AssistChipDefaults.assistChipColors(containerColor = Color.Transparent))
}

@Composable
private fun MenuMark(modifier: Modifier) {
    val color = ZipHapColors.TextPrimary
    Canvas(modifier) {
        drawLine(color, Offset(size.width * .12f, size.height * .32f), Offset(size.width * .88f, size.height * .32f), 1.6.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * .12f, size.height * .68f), Offset(size.width * .7f, size.height * .68f), 1.6.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun ChevronMark(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        drawLine(color, Offset(0f, size.height * .3f), Offset(size.width / 2, size.height * .75f), 1.4.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width / 2, size.height * .75f), Offset(size.width, size.height * .3f), 1.4.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun SendMark(stop: Boolean, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        if (stop) {
            drawRoundRect(color, Offset(size.width * .18f, size.height * .18f), Size(size.width * .64f, size.height * .64f), CornerRadius(2.dp.toPx()))
        } else {
            val stroke = 2.dp.toPx()
            drawLine(color, Offset(size.width / 2, size.height * .88f), Offset(size.width / 2, size.height * .14f), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * .18f, size.height * .46f), Offset(size.width / 2, size.height * .14f), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * .82f, size.height * .46f), Offset(size.width / 2, size.height * .14f), stroke, StrokeCap.Round)
        }
    }
}

