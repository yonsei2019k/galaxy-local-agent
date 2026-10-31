package com.user.localagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.user.localagent.core.CapabilityAvailability
import com.user.localagent.core.ToolCapability
import com.user.localagent.skills.BundledSkillMetadata
import com.user.localagent.skills.BundledSkillStore
import com.user.localagent.skills.SkillReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only signed-bundle browser. Registry presence and runtime access are displayed separately. */
@Composable fun SkillLibraryDialog(
    store:BundledSkillStore,
    toolStatuses:List<ToolCapability> = emptyList(),
    onDismiss:()->Unit
) {
    var metadata by remember(store){mutableStateOf<List<BundledSkillMetadata>>(emptyList())}
    var selected by remember(store){mutableStateOf<BundledSkillMetadata?>(null)}
    var content by remember(store){mutableStateOf<SkillReadResult?>(null)}
    var error by remember(store){mutableStateOf<String?>(null)}
    var loading by remember(store){mutableStateOf(true)}
    var query by remember(store){mutableStateOf("")}
    LaunchedEffect(store){
        try{metadata=withContext(Dispatchers.IO){store.catalog()}}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){error="번들 스킬 목록을 검증하지 못했습니다."}
        finally{loading=false}
    }
    LaunchedEffect(store,selected?.id){
        content=null
        val target=selected?:return@LaunchedEffect
        loading=true;error=null
        try{content=withContext(Dispatchers.IO){store.read(target.id)}}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){error="스킬 본문의 무결성을 확인하지 못해 표시하지 않았습니다."}
        finally{loading=false}
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.9f),shape=RoundedCornerShape(24.dp),tonalElevation=6.dp){
            Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text(selected?.title?:"스킬 라이브러리",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    if(selected!=null)TextButton(onClick={selected=null;error=null}){Text("목록")}
                    TextButton(onClick=onDismiss){Text("닫기")}
                }
                Text("앱에 포함된 작업 지침입니다. 스킬을 읽어도 실행 권한이나 연결 계정이 추가되지 않습니다.",style=MaterialTheme.typography.bodySmall)
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                if(selected==null){
                    OutlinedTextField(query,{query=it.take(200)},Modifier.fillMaxWidth(),label={Text("이름·설명 검색")},singleLine=true)
                    val rows=metadata.filter {query.isBlank()||listOf(it.id,it.title,it.description,it.category).any {value->value.contains(query.trim(),ignoreCase=true)}}
                    Text("번들 ${metadata.size}개 · 표시 ${rows.size}개",style=MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        items(rows,key={it.id}){item->
                            OutlinedCard(onClick={selected=item},modifier=Modifier.fillMaxWidth()){
                                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                                    Text(item.title,style=MaterialTheme.typography.titleMedium)
                                    Text(item.description,style=MaterialTheme.typography.bodyMedium)
                                    Text("${item.category} · 지침 v${item.version} · 관련 도구 ${item.tools.size}개",style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }else{
                    val entry=selected!!
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        item {Text(entry.description,style=MaterialTheme.typography.bodyMedium)}
                        item {Text("지침 설치됨 · 서명된 앱 번들 · ${entry.id} v${entry.version}",style=MaterialTheme.typography.labelMedium)}
                        if(entry.tools.isNotEmpty())item {
                            Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                                Text("관련 도구와 실제 접근 상태",style=MaterialTheme.typography.titleSmall)
                                entry.tools.forEach {name->
                                    val state=toolStatuses.firstOrNull {it.id==name}
                                    Text("$name · 등록됨 · ${state?.availability?.skillStatusLabel()?:"권한·연결 상태 별도 확인"}",style=MaterialTheme.typography.bodySmall)
                                }
                                Text("등록과 선행 조건 표시는 해당 작업의 성공·완료 검증과 다릅니다.",style=MaterialTheme.typography.labelSmall)
                            }
                        }
                        if(entry.limitations.isNotEmpty())item {
                            Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
                                Text("지원 범위",style=MaterialTheme.typography.titleSmall)
                                entry.limitations.forEach {Text("• $it",style=MaterialTheme.typography.bodySmall)}
                            }
                        }
                        content?.let {read->item {
                            HorizontalDivider()
                            SelectionContainer {Text(read.body,style=MaterialTheme.typography.bodyMedium)}
                            Text("SHA-256 ${read.sha256}",style=MaterialTheme.typography.labelSmall)
                        }}
                        if(entry.sourceUrls.isNotEmpty())item {
                            Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
                                Text("참고 원천 · 자동 접속 없음",style=MaterialTheme.typography.titleSmall)
                                SelectionContainer {Text(entry.sourceUrls.joinToString("\n"),style=MaterialTheme.typography.bodySmall)}
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun CapabilityAvailability.skillStatusLabel()=when(this){
    CapabilityAvailability.INSTALLED->"선행 조건 확인됨"
    CapabilityAvailability.REQUIRES_PERMISSION->"시스템 권한·계정 연결 필요"
    CapabilityAvailability.REQUIRES_MODEL->"검증된 모델 필요"
    CapabilityAvailability.NOT_IMPLEMENTED->"실행 기능 미구현"
    CapabilityAvailability.UNVALIDATED->"실제 연결·실행 검증 필요"
}
