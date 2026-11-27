package com.user.localagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import com.user.localagent.runtime.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingsAssistantPanel(
    app:AppContainer, character:CharacterPreference, open:(SettingsLink)->Unit, moveToChat:(String)->Boolean, modifier:Modifier=Modifier,
) {
    val context = LocalContext.current
    val runner = remember(app, context) { SettingsAssistantRunner(context, app) }
    val scope = rememberCoroutineScope()
    val messages = remember {
        mutableStateListOf(false to SettingsReply("업데이트, 음성 인식, 앱 연결 같은 설정을 물어보세요."))
    }
    var question by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var previousExchange by remember { mutableStateOf<Pair<String, String>?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, working) {
        list.scrollToItem(if (working) messages.size else messages.lastIndex)
    }
    Column(modifier.fillMaxWidth().imePadding().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(messages) { index, (owner, reply) ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (owner) Alignment.End else Alignment.Start) {
                    if (owner) {
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = ZipHapColors.Elevated,
                            contentColor = ZipHapColors.TextPrimary) {
                            Text(reply.text, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        ZipHapCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (index == 0) SettingsPortrait(character)
                                Text(reply.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    reply.link?.let { link -> TextButton(onClick = { open(link) }) {
                        Text(link.label, color = ZipHapTokens.CobaltText)
                        ZipIcon(R.drawable.zi_chevron_right, null, tint = ZipHapTokens.CobaltText, size = 18.dp)
                    } }
                    reply.chatDraft?.let { request ->
                        TextButton(enabled=!working,onClick={
                            if(!moveToChat(request)){
                                val detail="일반 대화가 준비되지 않았거나 이미 작성 중인 내용이 있어요. 요청은 여기에 남겨뒀어요."
                                if(messages.last().second.text!=detail){
                                    if(messages.size>=25)messages.removeAt(1)
                                    messages.add(false to SettingsReply(detail))
                                }
                            }
                        }){Text("일반 대화로 옮기기")}
                    }
                }
            }
            if (working) item {
                Text("설정을 확인하고 있어요.", Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        ZipOutlinedButton(enabled=!working,onClick={
            if(messages.size>=25) repeat(2){messages.removeAt(1)}
            messages.add(true to SettingsReply("업데이트 확인"))
            working=true
            scope.launch {
                try {
                    val reply=runner.checkUpdates()
                    previousExchange="업데이트 확인" to reply.text
                    messages.add(false to reply)
                }finally {working=false}
            }
        }){Text("업데이트 확인")}
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZipOutlinedTextField(
                question, { if (it.length <= 600) question = it }, Modifier.weight(1f),
                label = { Text("설정에 대해 물어보기") }, maxLines = 3,
            )
            ZipFilledButton(enabled = !working && question.isNotBlank(), onClick = {
                val request = question.trim()
                question = ""
                if (messages.size >= 25) repeat(2) { messages.removeAt(1) }
                messages.add(true to SettingsReply(request))
                working = true
                scope.launch {
                    try {
                        val reply = runner.ask(request, previousExchange)
                        previousExchange = request to reply.text.take(600)
                        messages.add(false to reply)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        messages.add(false to SettingsReply("설정 요청의 결과를 확인하지 못했어요. 수동 설정을 열거나 일반 대화에서 이어갈 수 있어요.",
                            SettingsLink.MANUAL,chatDraft=request))
                    } finally {
                        working = false
                    }
                }
            }) { Text("보내기") }
        }
    }
}

@Composable
private fun SettingsPortrait(character: CharacterPreference) {
    val resources = LocalContext.current.resources
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, resources, character) {
        value = null
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                resources.openRawResource(R.raw.jipsooni_portraits).use { stream ->
                    val decoder = BitmapRegionDecoder.newInstance(stream) ?: return@use null
                    try {
                        val (column, row) = character.portraitCell()
                        val side = 128
                        val bounds = Rect(column * side, row * side, (column + 1) * side, (row + 1) * side)
                        decoder.decodeRegion(bounds, null)?.asImageBitmap()
                    } finally {
                        decoder.recycle()
                    }
                }
            } catch (_: java.io.IOException) {
                null
            }
        }
    }
    Box(
        Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(ZipHapColors.Soft),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) }
    }
}
