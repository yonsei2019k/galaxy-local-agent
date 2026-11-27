package com.user.localagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.user.localagent.core.ToolRegistry
import com.user.localagent.core.IntentPlanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** UI snapshots contain no repositories, running jobs or Android folder permissions. */
data class UiProject(
    val id: String,
    val name: String,
    val folderLabel: String = "앱 내부",
    val instructions: String = "",
    val modelId: String = "auto",
    val thinking:Boolean=false,
    val contextSize:Int=4096,
    val memoryMode:String="project",
    val useOwnerMemory:Boolean=true,
    val learnOwnerFacts:Boolean=false,
    val revision:Long=0,
    val disabledTools:Set<String> = emptySet(),
)

data class UiThread(
    val id: String,
    val projectId: String,
    val title: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val updatedAt: Long = 0,
    val runStatus: String = "",
)
data class UiModelChoice(val id:String,val label:String)

enum class UiChatExportFormat { MARKDOWN, JSON, TEXT }

/** Host callbacks act on stable IDs, never on the currently selected mutable room. */
data class ChatNavigationActions(
    val onSelectThread: (String) -> Unit,
    val onNewThread: (String) -> Unit,
    val onSelectProject: (String) -> Unit = {},
    val onSaveProject: (UiProject) -> Unit = {},
    val onChooseProjectFolder: (String) -> Unit = {},
    val onDeleteProject: ((String, Boolean) -> Unit)? = null,
    val onRenameThread: (String, String) -> Unit = { _, _ -> },
    val onPinThread: (String, Boolean) -> Unit = { _, _ -> },
    val onArchiveThread: (String, Boolean) -> Unit = { _, _ -> },
    val onTrashThread: (String) -> Unit = {},
    val onRestoreThread: (String) -> Unit = {},
    val onClearThread: (String, Boolean) -> Unit = { _, _ -> },
    val onDeleteThread: (String, Boolean) -> Unit = { _, _ -> },
    val onDuplicateThread: (String) -> Unit = {},
    val onMoveThread: (String, String) -> Unit = { _, _ -> },
    val onExportThread: (String, UiChatExportFormat) -> Unit = { _, _ -> },
    val onSearchThreads: (suspend (String) -> Set<String>)? = null,
)

data class ChatNavigation(
    val projects: List<UiProject>,
    val threads: List<UiThread>,
    val selectedProjectId: String?,
    val selectedThreadId: String?,
    val actions: ChatNavigationActions,
    val supportsTrash:Boolean=true,
    val modelChoices:List<UiModelChoice> = emptyList(),
)

private enum class RoomFilter(val label: String) { ACTIVE("대화"), ARCHIVED("보관함"), TRASH("휴지통") }
private enum class RoomDialog { RENAME, CLEAR, DELETE, MOVE, EXPORT }

/** Reusable drawer: the host owns durable changes and reports any write failures. */
@Composable
fun ChatNavigator(
    navigation: ChatNavigation,
    onDismiss: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    conversationActions: (@Composable () -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(RoomFilter.ACTIVE) }
    var projectEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var projectDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var roomDialog by remember { mutableStateOf<Pair<RoomDialog, UiThread>?>(null) }
    val actions = navigation.actions
    var bodyMatches by remember(query){mutableStateOf<Set<String>?>(null)}
    var searchError by remember(query){mutableStateOf("")}
    val searchAction by rememberUpdatedState(actions.onSearchThreads)
    val searchRevision=navigation.threads.map{it.id to it.updatedAt}
    LaunchedEffect(query,searchRevision){
        bodyMatches=null;searchError=""
        if(query.isNotBlank()&&searchAction!=null){
            delay(180)
            try{bodyMatches=searchAction?.invoke(query)}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){searchError="본문 검색을 완료하지 못했습니다. 제목 검색 결과만 표시합니다."}
        }
    }
    val searching=query.isNotBlank()&&actions.onSearchThreads!=null&&bodyMatches==null&&searchError.isEmpty()
    val visible = remember(navigation.threads, query, filter,bodyMatches) {
        navigation.threads.filter { room ->
            val scopeMatches = when (filter) {
                RoomFilter.ACTIVE -> !room.trashed && !room.archived
                RoomFilter.ARCHIVED -> !room.trashed && room.archived
                RoomFilter.TRASH -> room.trashed
            }
            scopeMatches && (query.isBlank() || room.title.contains(query.trim(), ignoreCase = true) || room.id in bodyMatches.orEmpty())
        }.sortedWith(compareByDescending<UiThread> { it.pinned }.thenByDescending { it.updatedAt }.thenBy { it.id })
    }
    ModalDrawerSheet(
        modifier.widthIn(max = 360.dp).fillMaxHeight().testTag("chat_navigator"),
        drawerContainerColor = ZipHapColors.Background,
        drawerContentColor = ZipHapColors.TextPrimary,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ZipHap.Agent", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "대화 목록 닫기" }) { Text("×", style = MaterialTheme.typography.headlineSmall) }
        }
        // The owner removed the drawer-wide "new conversation" button; each project row keeps its own "+".
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { projectEditorId = "" }, modifier = Modifier.testTag("new_project")) { Text("새 프로젝트") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(if(actions.onSearchThreads==null)"제목 검색"else"제목·본문 검색") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).testTag("thread_search"),
            shape = RoundedCornerShape(16.dp),
        )
        if(searching)Text("이 기기의 모든 대화 버전에서 검색 중…",Modifier.padding(horizontal=14.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
        if(searchError.isNotBlank())Text(searchError,Modifier.padding(horizontal=14.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall,color=ZipHapColors.Error)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            RoomFilter.entries.filter{it!=RoomFilter.TRASH||navigation.supportsTrash}.forEach { item -> FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.label) }) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (conversationActions != null && query.isBlank() && filter == RoomFilter.ACTIVE) item(key = "conversation-actions") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) { conversationActions() }
            }
            navigation.projects.forEach { project ->
                val rooms = visible.filter { it.projectId == project.id }
                if (rooms.isNotEmpty() || (query.isBlank() && filter == RoomFilter.ACTIVE)) {
                    item(key = "project:${project.id}") {
                        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { actions.onSelectProject(project.id) }.padding(vertical = 6.dp)) {
                                Text(project.name, color = if (project.id == navigation.selectedProjectId) ZipHapColors.TextPrimary else ZipHapColors.TextSecondary,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(project.folderLabel, color = ZipHapColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { actions.onNewThread(project.id); onDismiss() }, modifier = Modifier.size(36.dp).semantics { contentDescription = "${project.name}에 새 대화" }) { Text("＋") }
                            IconButton(onClick = { projectEditorId = project.id }, modifier = Modifier.size(36.dp).semantics { contentDescription = "${project.name} 프로젝트 설정" }) { Text("⋯") }
                        }
                    }
                    items(rooms, key = { "thread:${it.id}" }) { room ->
                        NavigatorThread(room, selected = room.id == navigation.selectedThreadId, onSelect = {
                            actions.onSelectThread(room.id); onDismiss()
                        }, onDialog = { roomDialog = it to room }, actions = actions,supportsTrash=navigation.supportsTrash)
                    }
                }
            }
            // A migrated room can briefly arrive before its project snapshot. Keep it reachable.
            val known = navigation.projects.map { it.id }.toSet()
            if(visible.any{it.projectId !in known})item(key="unassigned_header"){
                Row(Modifier.fillMaxWidth().padding(start=18.dp,end=8.dp,top=12.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("일반 대화",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=ZipHapColors.TextSecondary)
                }
            }
            items(visible.filter { it.projectId !in known }, key = { "orphan:${it.id}" }) { room ->
                NavigatorThread(room, room.id == navigation.selectedThreadId,
                    onSelect = { actions.onSelectThread(room.id); onDismiss() }, onDialog = { roomDialog = it to room }, actions = actions,supportsTrash=navigation.supportsTrash)
            }
            if (visible.isEmpty()) item { Text(if (query.isBlank()) "아직 대화가 없습니다." else if(searching)"본문 검색 결과를 기다리고 있습니다."else if(searchError.isNotBlank())"제목에서 일치하는 대화가 없습니다."else"일치하는 대화가 없습니다.", Modifier.padding(22.dp), color = ZipHapColors.TextSecondary) }
            item { Spacer(Modifier.height(16.dp)) }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onDismiss(); onOpenImages() }) { Text("이미지 작업실") }
            TextButton(onClick = { onDismiss(); onOpenSettings() }) { Text("설정·참고") }
        }
    }
    projectEditorId?.let { id -> val project=if(id.isBlank())UiProject("", "")else navigation.projects.firstOrNull{it.id==id};if(project!=null)ProjectSettingsDialog(project, navigation.modelChoices,onDismiss = { projectEditorId = null }, onSave = {
        actions.onSaveProject(it); projectEditorId = null
    }, onChooseFolder = { actions.onChooseProjectFolder(project.id) },
        onDelete = actions.onDeleteProject?.takeIf { project.id.isNotBlank() }?.let { { projectEditorId = null; projectDeleteId = project.id } }) }
    projectDeleteId?.let { id -> navigation.projects.firstOrNull { it.id == id }?.let { project ->
        ProjectDeletionDialog(project, roomCount = navigation.threads.count { it.projectId == id }, onDismiss = { projectDeleteId = null }) { eraseDerived ->
            actions.onDeleteProject?.invoke(id, eraseDerived); projectDeleteId = null
        }
    } ?: run { projectDeleteId = null } }
    roomDialog?.let { (kind, room) ->
        when (kind) {
            RoomDialog.RENAME -> NameDialog("대화 이름", room.title, onDismiss = { roomDialog = null }) { title ->
                actions.onRenameThread(room.id, title); roomDialog = null
            }
            RoomDialog.CLEAR, RoomDialog.DELETE -> DestructionDialog(room, permanent = kind == RoomDialog.DELETE, onDismiss = { roomDialog = null }) { eraseDerived ->
                if (kind == RoomDialog.DELETE) actions.onDeleteThread(room.id, eraseDerived) else actions.onClearThread(room.id, eraseDerived)
                roomDialog = null
            }
            RoomDialog.MOVE -> AlertDialog(onDismissRequest = { roomDialog = null }, title = { Text("프로젝트로 이동") }, text = {
                Column { Text("대화와 앞으로의 작업 폴더가 이동합니다. 기존 프로젝트의 공유 기억은 그 프로젝트에 유지됩니다.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={actions.onMoveThread(room.id,"");roomDialog=null},enabled=room.projectId.isNotBlank()){Text("일반 대화로 이동")}
                    navigation.projects.forEach { project ->
                    TextButton(onClick = { actions.onMoveThread(room.id, project.id); roomDialog = null }, enabled = project.id != room.projectId) { Text(project.name) }
                } }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { roomDialog = null }) { Text("취소") } })
            RoomDialog.EXPORT -> AlertDialog(onDismissRequest = { roomDialog = null }, title = { Text("대화 내보내기") }, text = {
                Column {
                    Text("모든 대화 버전과 각 버전의 메시지 순서를 내보냅니다. 공유 원문은 한 번만 수록합니다. 다음 공유 화면에서 저장할 앱을 선택합니다. 첨부 원본과 외부 폴더는 포함하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    UiChatExportFormat.entries.forEach { format -> TextButton(onClick = { actions.onExportThread(room.id, format); roomDialog = null }) { Text(when (format) {
                        UiChatExportFormat.MARKDOWN -> "Markdown (.md)"; UiChatExportFormat.JSON -> "JSON (.json)"; UiChatExportFormat.TEXT -> "텍스트 (.txt)"
                    }) } }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { roomDialog = null }) { Text("취소") } })
        }
    }
}

@Composable
private fun NavigatorThread(room: UiThread, selected: Boolean, onSelect: () -> Unit, onDialog: (RoomDialog) -> Unit, actions: ChatNavigationActions,supportsTrash:Boolean) {
    var menu by remember(room.id) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp), color = if (selected) ZipHapColors.Elevated else ZipHapColors.Background, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onSelect).padding(start = 12.dp, top = 11.dp, bottom = 11.dp).testTag("thread_${room.id}")) {
                Text((if (room.pinned) "⌑  " else "") + room.title.ifBlank { "새 대화" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (room.runStatus.isNotBlank()) Text(room.runStatus, color = ZipHapColors.Mint, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp).semantics { contentDescription = "대화 작업 · ${room.title}" }) { Text("⋯") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    fun close(action: () -> Unit) { menu = false; action() }
                    if (room.trashed) {
                        DropdownMenuItem(text = { Text("복원") }, onClick = { close { actions.onRestoreThread(room.id) } })
                        DropdownMenuItem(text = { Text("영구 삭제", color = ZipHapColors.Error) }, onClick = { close { onDialog(RoomDialog.DELETE) } })
                    } else {
                        DropdownMenuItem(text = { Text("이름 변경") }, onClick = { close { onDialog(RoomDialog.RENAME) } })
                        DropdownMenuItem(text = { Text(if (room.pinned) "고정 해제" else "고정") }, onClick = { close { actions.onPinThread(room.id, !room.pinned) } })
                        DropdownMenuItem(text = { Text(if (room.archived) "보관 해제" else "보관") }, onClick = { close { actions.onArchiveThread(room.id, !room.archived) } })
                        DropdownMenuItem(text = { Text("대화 복제") }, onClick = { close { actions.onDuplicateThread(room.id) } })
                        DropdownMenuItem(text = { Text("프로젝트로 이동") }, onClick = { close { onDialog(RoomDialog.MOVE) } })
                        DropdownMenuItem(text = { Text("내보내기") }, onClick = { close { onDialog(RoomDialog.EXPORT) } })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("내용 비우기") }, onClick = { close { onDialog(RoomDialog.CLEAR) } })
                        if(supportsTrash)DropdownMenuItem(text = { Text("휴지통으로 이동") }, onClick = { close { actions.onTrashThread(room.id) } })
                        else DropdownMenuItem(text = { Text("영구 삭제",color=ZipHapColors.Error) }, onClick = { close { onDialog(RoomDialog.DELETE) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { if (it.length <= 200) name = it }, singleLine = true, label = { Text("이름") })
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun ProjectSettingsDialog(project: UiProject,modelChoices:List<UiModelChoice>, onDismiss: () -> Unit, onSave: (UiProject) -> Unit, onChooseFolder: () -> Unit, onDelete: (() -> Unit)? = null) {
    var name by rememberSaveable(project.id) { mutableStateOf(project.name) }
    var instructions by rememberSaveable(project.id) { mutableStateOf(project.instructions) }
    var model by rememberSaveable(project.id) { mutableStateOf(project.modelId) }
    val revision by rememberSaveable(project.id){mutableStateOf(project.revision)}
    var modelsOpen by remember{mutableStateOf(false)}
    var ownerMemory by rememberSaveable(project.id){mutableStateOf(project.useOwnerMemory)}
    var ownerLearning by rememberSaveable(project.id){mutableStateOf(project.learnOwnerFacts)}
    var thinking by rememberSaveable(project.id){mutableStateOf(project.thinking)}
    var disabledTools by rememberSaveable(project.id,stateSaver=listSaver<Set<String>,String>(save={it.toList()},restore={it.toSet()})){mutableStateOf(project.disabledTools)}
    var toolsExpanded by rememberSaveable(project.id){mutableStateOf(false)}
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (project.id.isBlank()) "새 프로젝트" else "프로젝트 설정") }, text = {
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { if (it.length <= 200) name = it }, singleLine = true, label = { Text("프로젝트 이름") })
            OutlinedTextField(instructions, { if (it.length <= 4000) instructions = it }, label = { Text("이 프로젝트의 응답 지침") }, minLines = 2, maxLines = 5)
            Column {
                val choices=listOf(UiModelChoice("auto","자동"))+modelChoices.filter{it.id!="auto"}
                TextButton(onClick={modelsOpen=true}){Text("기본 모델 · ${choices.firstOrNull{it.id==model}?.label?:"현재 모델(설치 확인 필요)"}")}
                DropdownMenu(expanded=modelsOpen,onDismissRequest={modelsOpen=false}){choices.forEach{choice->DropdownMenuItem(text={Text(choice.label)},onClick={model=choice.id;modelsOpen=false})}}
            }
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(thinking,{thinking=it});Text("깊게 생각",style=MaterialTheme.typography.bodyMedium)}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(ownerMemory,{ownerMemory=it});Text("승인된 개인 기억 참고",style=MaterialTheme.typography.bodyMedium)}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(ownerLearning,{ownerLearning=it});Text("이 프로젝트에서 개인 기억 후보 만들기",style=MaterialTheme.typography.bodyMedium)}
            Text("프로젝트 자료는 별도 범위에 저장됩니다. 기억 후보가 자동 승인되지는 않습니다.",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
            HorizontalDivider()
            TextButton(onClick={toolsExpanded=!toolsExpanded}){Text("사용 도구 · ${ToolRegistry.definitions.keys.count{it !in disabledTools}}/${ToolRegistry.definitions.size} ${if(toolsExpanded)"⌃"else"⌄"}")}
            if(toolsExpanded){
                Text("선택한 도구를 이 프로젝트에서 사용합니다. 휴대전화 권한과 실행 확인은 별도로 적용됩니다.",style=MaterialTheme.typography.bodySmall,color=ZipHapColors.TextSecondary)
                ToolRegistry.definitions.keys.groupBy{IntentPlanning.domainForTool(it)}.toSortedMap().forEach{(domain,names)->
                    Text(toolDomainLabel(domain),style=MaterialTheme.typography.labelLarge)
                    names.forEach{tool->Row(Modifier.fillMaxWidth().clickable{disabledTools=if(tool in disabledTools)disabledTools-tool else disabledTools+tool},verticalAlignment=Alignment.CenterVertically){
                        Checkbox(tool !in disabledTools,{enabled->disabledTools=if(enabled)disabledTools-tool else disabledTools+tool},modifier=Modifier.testTag("project_tool_$tool"))
                        Text(toolLabel(tool),style=MaterialTheme.typography.bodyMedium)
                    }}
                }
            }
            Text("폴더 · ${project.folderLabel}", style = MaterialTheme.typography.bodySmall)
            if (project.id.isNotBlank()) TextButton(onClick = onChooseFolder) { Text("휴대전화 폴더 선택") }
            else Text("프로젝트를 만든 뒤 폴더를 연결할 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary)
            if (onDelete != null) {
                HorizontalDivider()
                TextButton(onClick = onDelete, modifier = Modifier.testTag("delete_project")) { Text("프로젝트 삭제", color = ZipHapColors.Error) }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(project.copy(name = name.trim(), instructions = instructions.trim(), modelId = model.trim().ifBlank { "auto" },thinking=thinking,useOwnerMemory=ownerMemory,learnOwnerFacts=ownerLearning,disabledTools=disabledTools,revision=revision)) }, enabled = name.isNotBlank()) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

private fun toolDomainLabel(domain:String)=when(domain){"messages"->"문자·메신저";"calendar"->"일정·알림";"documents"->"문서";"projects"->"프로젝트 파일";"images"->"이미지";"notes"->"메모";"memory"->"개인 기억";"audio"->"녹음";"web"->"웹·쇼핑";else->"기본 도구"}
internal fun toolLabel(name:String)=when(name){
    "open_app_browser_page","open_pc_browser_page"->"사이트 열기"
    "read_app_browser_page","read_pc_browser_page"->"사이트 화면 확인"
    "app_browser_action","act_on_pc_browser_page"->"사이트 조작"
    "wait_for_goal_condition"->"다음 확인 예약"
    "save_browser_pdf","save_public_pdf"->"PDF 저장"
    "send_gmail_message","reply_gmail_message"->"Gmail 발송"
    "search_gmail_messages","read_gmail_message"->"Gmail 메일 확인"
    "create_google_calendar_event"->"구글 캘린더 일정 등록"
    "list_google_calendar_events"->"구글 캘린더 일정 확인"
    "get_current_datetime"->"현재 날짜·시간";"list_project_files"->"프로젝트 파일 목록";"read_project_file"->"프로젝트 파일 읽기";"write_project_file"->"프로젝트 파일 저장"
    "create_document"->"문서 파일 만들기";"search_web"->"공개 웹 검색";"search_shopping"->"상품·가격 검색";"read_webpage"->"웹페이지 읽기";"generate_image"->"로컬 이미지 생성"
    "search_conversation_history"->"집합 대화 기록 조회";"list_imported_conversations"->"가져온 대화 목록";"search_conversation_imports"->"가져온 대화 검색";"get_sms_summary"->"문자 조회·요약";"analyze_sms"->"문자 내용 분석"
    "get_call_log_summary"->"통화 내역 조회";"list_audio_recordings"->"녹음 파일 목록";"search_audio_transcripts"->"녹음 전사 검색";"list_calendar_events"->"일정 조회";"prepare_calendar_event"->"일정 작성"
    "create_reminder"->"리마인더 생성";"create_alarm"->"알람 생성";"save_note"->"메모 저장";"search_notes"->"메모 검색";"search_contacts"->"연락처 검색";com.user.localagent.core.KakaoShare.TOOL->"카카오톡 공유 준비";"prepare_sms"->"문자 초안 작성";"prepare_email"->"이메일 초안 작성"
    "open_map"->"지도 열기";"open_url"->"웹페이지 열기";"save_memory"->"개인 기억 저장";"search_memories"->"개인 기억 검색";"delete_memory"->"개인 기억 삭제";"update_note"->"메모 수정";"update_reminder"->"리마인더 수정"
    "delete_note"->"메모 삭제";"delete_reminder"->"리마인더 삭제";"list_messenger_notifications"->"연결한 메신저 알림 조회";"reply_messenger_notification"->"연결한 메신저 답장";else->name
}

/** Project deletion needs its own explicit confirmation listing what goes and what stays. */
@Composable
private fun ProjectDeletionDialog(project: UiProject, roomCount: Int, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var derived by remember(project.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("프로젝트 영구 삭제") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("‘${project.name}’ 프로젝트와 그 안의 대화 ${roomCount}개(보관함 포함)의 메시지, 방 목표, 첨부 복사본, 앱 전용 프로젝트 폴더를 삭제합니다. 진행 중인 작업은 중단되며 되돌릴 수 없습니다.")
            Text("연결한 휴대전화 폴더의 파일은 삭제하지 않고 연결만 해제합니다.", style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary)
            Row(Modifier.fillMaxWidth().clickable { derived = !derived }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = derived, onCheckedChange = { derived = it })
                Text("이 프로젝트에서 얻은 개인 기억도 삭제", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(derived) }, modifier = Modifier.testTag("confirm_delete_project")) { Text("영구 삭제", color = ZipHapColors.Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun DestructionDialog(room: UiThread, permanent: Boolean, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var derived by remember(room.id, permanent) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (permanent) "대화 영구 삭제" else "대화 내용 비우기") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("‘${room.title.ifBlank { "새 대화" }}’의 메시지, 저장된 방 목표와 첨부 복사본을 삭제합니다. 진행 중인 작업은 중단됩니다. 외부 프로젝트 폴더의 파일은 유지됩니다.")
            Row(Modifier.fillMaxWidth().clickable { derived = !derived }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = derived, onCheckedChange = { derived = it })
                Text("이 대화에서 얻은 개인 기억도 삭제", style = MaterialTheme.typography.bodyMedium)
            }
            if (!derived) Text("기억 관리에서 승인한 개인 기억은 유지됩니다.", style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary)
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(derived) }) { Text(if (permanent) "영구 삭제" else "내용 비우기", color = ZipHapColors.Error) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
