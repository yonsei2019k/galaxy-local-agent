package com.user.localagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.user.localagent.AppContainer
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.core.CapabilityAvailability
import com.user.localagent.core.ToolCapability
import com.user.localagent.core.connections.ConnectedService
import com.user.localagent.core.connections.ConnectionCatalog
import com.user.localagent.core.connections.ConsumerApp
import com.user.localagent.core.connections.ConsumerAppCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

private data class Example(val title:String,val request:String)

private val examples=mapOf(
    "list_calendar_events" to Example("휴대폰 일정 확인","내일 일정 보여줘."),
    "prepare_calendar_event" to Example("캘린더에 일정 추가 준비","모레 오후 3시 일정 추가를 준비해 줘."),
    "find_free_time" to Example("빈 시간 계산","내일 오후 2시부터 5시 사이에 한 시간 비는 때를 찾아 줘. 바쁜 시간은 오후 3시부터 4시야."),
    "search_contacts" to Example("연락처 찾기","연락처에서 이름으로 사람을 찾고 싶어."),
    "search_web" to Example("웹에서 자료 찾기","관심 있는 주제의 공식 자료를 찾아서 출처와 함께 정리해 줘."),
    "search_shopping" to Example("상품과 가격 비교","구매 후보 세 개의 가격과 조건을 출처와 함께 비교해 줘."),
    "open_map" to Example("지도에서 장소 찾기","지도에서 서울역을 열어 줘."),
    "calculate" to Example("계산","3만 원의 15%가 얼마인지 계산해 줘."),
    "save_note" to Example("메모 저장","내일 확인할 일을 메모로 남기고 싶어."),
    "generate_image" to Example("이미지 만들기","햇빛이 들어오는 작은 작업실 이미지를 만들고 싶어."),
    "transcribe_recording" to Example("녹음을 글로 바꾸기","내가 고른 녹음 파일을 글로 바꿔 줘.")
)

private fun ToolCapability.officeStatus()=when(availability){
    CapabilityAvailability.INSTALLED->"기능 확인됨"
    CapabilityAvailability.REQUIRES_PERMISSION->"연결 또는 접근 허용 필요"
    CapabilityAvailability.REQUIRES_MODEL->"모델 준비 필요"
    CapabilityAvailability.UNVALIDATED->"이 환경에서 확인 필요"
    CapabilityAvailability.NOT_IMPLEMENTED->"아직 지원하지 않음"
}

@Composable internal fun OfficeCapabilityShelf(
    app:AppContainer,onConnections:()->Unit,onImages:()->Unit,
    calendarReadGranted:Boolean,onCalendarRead:()->Unit,onDraft:(String)->Boolean,modifier:Modifier=Modifier
){
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val context=LocalContext.current
    val pc by remember(context){PcComputeSettings.get(context).state}.collectAsState()
    var capabilities by remember{mutableStateOf<List<ToolCapability>?>(null)}
    var showAll by rememberSaveable { mutableStateOf(false) }
    var connectionOnly by rememberSaveable{mutableStateOf(false)}
    var query by rememberSaveable{mutableStateOf("")}
    var failure by remember{mutableStateOf(false)}
    var draftPreserved by remember{mutableStateOf(false)}
    LaunchedEffect(app,lifecycle){
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
            try{capabilities=app.capabilitySnapshot();failure=false}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){failure=true}
            awaitCancellation()
        }
    }
    val detected=capabilities.orEmpty()
    val byId=detected.associateBy{it.id}
    val accountTools=ConnectionCatalog.tools
    val consumerTools=ConsumerAppCatalog.appForTool
    val text=query.trim()
    fun matches(vararg fields:String)=text.isEmpty()||fields.any{it.contains(text,ignoreCase=true)}
    val showCalendar = matches("휴대폰 캘린더", "일정", "Android")
    val showConnections = matches("앱과 컴퓨터 연결", "PC", "메일", "캘린더", "메신저", "Codex", "Claude")
    val accounts = ConnectedService.entries.filter { matches(it.label, it.id) }
    val phoneApps = ConsumerApp.entries.filter { matches(it.label, it.id) }
    Column(modifier.fillMaxWidth().imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(query,{query=it.take(100)},Modifier.fillMaxWidth(),
            label={Text("이름이나 하고 싶은 일 검색")}, singleLine=true, shape = MaterialTheme.shapes.medium)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(!connectionOnly && !showAll,{connectionOnly=false;showAll=false},{Text("추천")})
            FilterChip(!connectionOnly && showAll,{connectionOnly=false;showAll=true},{Text("전체 기능")})
            FilterChip(connectionOnly,{connectionOnly=true},{Text("앱과 연결")})
        }
        if(failure)ZipHapStatusTag("기능 목록을 확인하지 못했어요.", MaterialTheme.colorScheme.onErrorContainer, MaterialTheme.colorScheme.errorContainer)
        else if(capabilities==null)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(draftPreserved)ZipHapStatusTag("입력 중인 메시지를 그대로 두었어요.")
        LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(10.dp), contentPadding=PaddingValues(bottom=16.dp)){
            if(connectionOnly){
                if(showCalendar)item{
                    ZipHapCard {
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                            ZipHapSectionTitle("휴대폰 캘린더")
                            if (calendarReadGranted) ZipHapStatusTag("일정 읽기 허용됨", ZipHapColors.Mint, ZipHapColors.MintSoft)
                            if(!calendarReadGranted)TextButton(onClick=onCalendarRead){Text("일정 읽기 허용")}
                        }
                    }
                }
                if(showConnections)item{
                    SettingsEntry("앱과 컴퓨터 연결",
                        if(pc.paired)"등록한 컴퓨터와 사용할 계정을 확인해요." else "계정, 컴퓨터, 메신저를 연결해요.",onConnections)
                }
                items(accounts,key={"account-"+it.id}){service->
                    val all=accountTools.values.filter{it.service==service}.mapNotNull{byId[it.definition.name]}
                    val reads=all.filterNot{accountTools[it.id]?.writes==true}
                    val status=when{
                        reads.any{it.availability==CapabilityAvailability.INSTALLED}->"읽기 연결 확인됨"
                        all.any{it.availability==CapabilityAvailability.REQUIRES_PERMISSION}->"계정 연결 필요"
                        else->"연결 상태 확인 필요"
                    }
                    SettingsEntry(service.label,status,onConnections)
                }
                items(phoneApps,key={"phone-"+it.id}){item->
                    val all=consumerTools.filterValues{it==item}.keys.mapNotNull(byId::get)
                    val verified=all.any{it.availability==CapabilityAvailability.INSTALLED}
                    ZipHapCard {
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                            ZipHapSectionTitle(item.label)
                            if(verified)ZipHapStatusTag("사용 가능한 동작 있음", ZipHapColors.Mint, ZipHapColors.MintSoft)
                            else ZipHapStatusTag("휴대폰 앱과 지원 동작 확인 필요")
                            all.forEach{Text(it.description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        }
                    }
                }
                if(!showCalendar && !showConnections && accounts.isEmpty() && phoneApps.isEmpty())item{
                    ZipHapCard { ZipHapSectionTitle("검색 결과가 없어요.") }
                }
            }else{
                val rows=detected.filter{item->matches(examples[item.id]?.title.orEmpty(),item.title,item.description,item.limitation)}
                    .sortedBy{if(it.id in examples)0 else if(it.availability==CapabilityAvailability.NOT_IMPLEMENTED)2 else 1}
                val browsing = text.isEmpty() && !showAll
                val visibleRows = if (browsing) rows.filter { it.id in examples && it.availability != CapabilityAvailability.NOT_IMPLEMENTED } else rows
                items(visibleRows,key={it.id}){item->
                    val example=examples[item.id]
                    ZipHapCard {
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                            ZipHapSectionTitle(example?.title?:item.title)
                            when(item.availability){
                                CapabilityAvailability.INSTALLED -> ZipHapStatusTag(item.officeStatus(), ZipHapColors.Mint, ZipHapColors.MintSoft)
                                CapabilityAvailability.REQUIRES_PERMISSION, CapabilityAvailability.REQUIRES_MODEL ->
                                    ZipHapStatusTag(item.officeStatus(), ZipHapColors.SunText, ZipHapColors.SunSoft)
                                else -> ZipHapStatusTag(item.officeStatus())
                            }
                            if(item.limitation.isNotBlank())Text(item.limitation,style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(item.id=="generate_image")TextButton(onClick=onImages){Text("이미지 작업실 열기")}
                            else if(example!=null)TextButton(onClick={draftPreserved=!onDraft(example.request)}){Text("채팅에 예시 넣기")}
                        }
                    }
                }
                if(visibleRows.isEmpty() && capabilities!=null)item {
                    ZipHapCard { ZipHapSectionTitle(if(browsing) "전체 기능에서 할 일을 찾아보세요." else "검색 결과가 없어요.") }
                }
            }
        }
    }
}
