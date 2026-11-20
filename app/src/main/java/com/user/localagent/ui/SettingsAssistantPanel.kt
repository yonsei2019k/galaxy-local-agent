package com.user.localagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingsAssistantPanel(app: AppContainer, open: (SettingsLink) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runner = remember(app, context) { SettingsAssistantRunner(context, app) }
    val scope = rememberCoroutineScope()
    val messages = remember {
        mutableStateListOf(false to SettingsReply("어떤 설정이 궁금하세요? 바꿀 수 있는 설정을 알려드리고, 원하시는 설정을 바꿔드릴게요."))
    }
    var question by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var clarification by remember { mutableStateOf<Pair<String, String>?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, working) {
        list.scrollToItem(if (working) messages.size else messages.lastIndex)
    }
    Column(modifier.fillMaxWidth().imePadding().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages) { (owner, reply) ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (owner) Alignment.End else Alignment.Start) {
                    Card(colors = CardDefaults.cardColors(containerColor =
                        if (owner) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(reply.text, Modifier.padding(14.dp))
                    }
                    reply.link?.let { link -> TextButton(onClick = { open(link) }) { Text(link.label) } }
                }
            }
            if (working) item { Text("설정을 확인하고 있어요…", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                question, { if (it.length <= 600) question = it }, Modifier.weight(1f),
                label = { Text("설정에 대해 물어보기") }, maxLines = 3,
            )
            Button(enabled = !working && question.isNotBlank(), onClick = {
                val request = question.trim()
                question = ""
                if (messages.size >= 25) repeat(2) { messages.removeAt(1) }
                messages.add(true to SettingsReply(request))
                working = true
                scope.launch {
                    try {
                        val previous = clarification
                        clarification = null
                        val reply = runner.ask(request, previous)
                        if (reply.followUp) clarification = (previous?.first ?: request) to reply.text
                        messages.add(false to reply)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        messages.add(false to SettingsReply("설정 요청의 결과를 확인하지 못했어요. 수동 설정에서 현재 값을 확인할 수 있어요."))
                    } finally {
                        working = false
                    }
                }
            }) { Text("보내기") }
        }
    }
}
