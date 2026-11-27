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
import com.user.localagent.core.CapabilityAvailability
import com.user.localagent.core.ToolCapability
import com.user.localagent.core.connections.ConnectedService
import com.user.localagent.core.connections.ConnectionCatalog
import com.user.localagent.core.connections.ConsumerApp
import com.user.localagent.core.connections.ConsumerAppCatalog
import com.user.localagent.core.connections.GovernmentService
import com.user.localagent.core.connections.LifeServiceCatalog
import com.user.localagent.connections.ConnectionHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

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

/** Icon and tone for a capability card (visual only). */
private fun capabilityIcon(id:String):Pair<Int,ZipTone> = when(id){
    "list_calendar_events","prepare_calendar_event"->com.user.localagent.runtime.R.drawable.zi_calendar to ZipTone.Sun
    "find_free_time"->com.user.localagent.runtime.R.drawable.zi_clock to ZipTone.Sun
    "search_contacts"->com.user.localagent.runtime.R.drawable.zi_person to ZipTone.Mint
    "search_web"->com.user.localagent.runtime.R.drawable.zi_globe to ZipTone.Cobalt
    "search_shopping"->com.user.localagent.runtime.R.drawable.zi_search to ZipTone.Coral
    "open_map"->com.user.localagent.runtime.R.drawable.zi_compass to ZipTone.Mint
    "calculate"->com.user.localagent.runtime.R.drawable.zi_tools to ZipTone.Neutral
    "save_note"->com.user.localagent.runtime.R.drawable.zi_edit to ZipTone.Lilac
    "generate_image"->com.user.localagent.runtime.R.drawable.zi_image to ZipTone.Lilac
    "transcribe_recording"->com.user.localagent.runtime.R.drawable.zi_microphone to ZipTone.Cobalt
    else->com.user.localagent.runtime.R.drawable.zi_sparkle to ZipTone.Neutral
}

@Composable internal fun OfficeCapabilityShelf(
    app:AppContainer,startOnServices:Boolean,onConnections:(String?)->Unit,onContacts:()->Unit,onMaps:()->Unit,onImages:()->Unit,
    calendarReadGranted:Boolean,onCalendarRead:()->Unit,onDraft:(String)->Boolean,modifier:Modifier=Modifier
){
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val context=LocalContext.current
    var capabilities by remember{mutableStateOf<List<ToolCapability>?>(null)}
    var usedAccounts by remember{mutableStateOf(emptySet<ConnectedService>())}
    var connectionOnly by rememberSaveable(startOnServices){mutableStateOf(startOnServices)}
    var query by rememberSaveable{mutableStateOf("")}
    var failure by remember{mutableStateOf(false)}
    var draftPreserved by remember{mutableStateOf(false)}
    LaunchedEffect(app,lifecycle){
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
            try{
                capabilities=app.capabilitySnapshot()
                usedAccounts=withContext(Dispatchers.IO){
                    val verified=ConnectionHub.get(context).verifiedServiceIds()
                    ConnectedService.entries.filterTo(mutableSetOf()){it.id in verified}
                }
                failure=false
            }
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
    val showConnections = matches("앱과 서비스 연결", "메일", "캘린더", "야놀자", "NOL")
    val showContacts = matches("연락처", "주소록")
    val showMaps = matches("위치", "지도", "길찾기")
    val accounts = ConnectedService.entries.filter { matches(it.label) }.sortedByDescending { it in usedAccounts }
    val phoneApps = ConsumerApp.entries.filter { matches(it.label) }
    val publicSites = (GovernmentService.entries.map { it.label } + LifeServiceCatalog.serviceLabels).filter { matches(it) }
    Column(modifier.fillMaxWidth().imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        ZipOutlinedTextField(query,{query=it.take(100)},Modifier.fillMaxWidth(),
            label={Text("이름이나 하고 싶은 일 검색")}, singleLine=true,
            leadingIcon={ZipIcon(com.user.localagent.runtime.R.drawable.zi_search,null,tint=ZipHapColors.TextSecondary,size=22.dp)})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ZipFilterChip(!connectionOnly,{connectionOnly=false},{Text("해볼 일")})
            ZipFilterChip(connectionOnly,{connectionOnly=true},{Text("앱과 서비스")})
        }
        if(failure)ZipHapStatusTag("기능 목록을 확인하지 못했어요.", MaterialTheme.colorScheme.onErrorContainer, MaterialTheme.colorScheme.errorContainer)
        else if(capabilities==null)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(draftPreserved)ZipHapStatusTag("입력 중인 메시지를 그대로 두었어요.")
        LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(10.dp), contentPadding=PaddingValues(bottom=16.dp)){
            if(connectionOnly){
                if(showCalendar)item{
                    ZipHapCard {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        ZipIconTile(com.user.localagent.runtime.R.drawable.zi_calendar,tone=ZipTone.Sun)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            ZipHapSectionTitle("휴대폰 캘린더")
                            if (calendarReadGranted) ZipHapStatusTag("일정 읽기 허용됨", ZipHapColors.Mint, ZipHapColors.MintSoft)
                            if(!calendarReadGranted)TextButton(onClick=onCalendarRead){Text("일정 읽기 허용")}
                        }
                        }
                    }
                }
                if(showConnections)item{
                    SettingsEntry("앱과 서비스 연결",
                        "야놀자(NOL)와 다른 서비스의 연결을 관리해요.",com.user.localagent.runtime.R.drawable.zi_link,onOpen={onConnections(null)})
                }
                if(showContacts)item{
                    SettingsEntry("휴대폰 연락처","연락처를 연결하거나 찾아요.",com.user.localagent.runtime.R.drawable.zi_person,onOpen=onContacts)
                }
                if(showMaps)item{
                    SettingsEntry("위치·지도","현재 위치 권한은 지도 앱에서 설정해요.",com.user.localagent.runtime.R.drawable.zi_compass,onOpen=onMaps)
                }
                items(accounts,key={"account-"+it.id}){service->
                    val all=accountTools.values.filter{it.service==service}.mapNotNull{byId[it.definition.name]}
                    val reads=all.filterNot{accountTools[it.id]?.writes==true}
                    val status=when{
                        service in usedAccounts->"사용한 서비스"
                        reads.any{it.availability==CapabilityAvailability.INSTALLED}->"읽기 연결 확인됨"
                        all.any{it.availability==CapabilityAvailability.REQUIRES_PERMISSION}->"계정 연결 필요"
                        else->"연결 상태 확인 필요"
                    }
                    SettingsEntry(service.label,status,com.user.localagent.runtime.R.drawable.zi_mail,ZipTone.Mint,onOpen={onConnections(service.id)})
                }
                items(phoneApps,key={"phone-"+it.id}){item->
                    val all=consumerTools.filterValues{it==item}.keys.mapNotNull(byId::get)
                    val verified=all.any{it.availability==CapabilityAvailability.INSTALLED}
                    ZipHapCard {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        ZipIconTile(com.user.localagent.runtime.R.drawable.zi_native,tone=ZipTone.Neutral)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            ZipHapSectionTitle(item.label)
                            if(verified)ZipHapStatusTag("휴대폰에서 이용 가능", ZipHapColors.Mint, ZipHapColors.MintSoft)
                            else ZipHapStatusTag("휴대폰 앱 확인 필요")
                            TextButton(onClick={draftPreserved=!onDraft("${item.label} 앱에서 할 일을 도와줘.")}){Text("채팅에서 요청하기")}
                        }
                        }
                    }
                }
                items(publicSites,key={"public-"+it}){service->
                    SettingsEntry(service,"공식 서비스 이용 방법을 채팅에서 확인해요.",com.user.localagent.runtime.R.drawable.zi_globe,
                        onOpen={draftPreserved=!onDraft("${service}에서 필요한 일을 처리할 수 있게 안내해 줘.")})
                }
                if(!showCalendar && !showConnections && !showContacts && !showMaps && accounts.isEmpty() && phoneApps.isEmpty() && publicSites.isEmpty())item{
                    ZipEmptyState(com.user.localagent.runtime.R.drawable.zi_ill_connections,"검색 결과가 없어요.","")
                }
            }else{
                val visibleRows=examples.entries.filter{(id,example)->
                    byId[id]?.availability!=CapabilityAvailability.NOT_IMPLEMENTED&&matches(example.title)
                }
                items(visibleRows,key={it.key}){item->
                    val (id,example)=item
                    val (icon,tone)=capabilityIcon(id)
                    ZipHapCard {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        ZipIconTile(icon,tone=tone)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            ZipHapSectionTitle(example.title)
                            if(id=="generate_image")TextButton(onClick=onImages){Text("이미지 작업실 열기")}
                            else TextButton(onClick={draftPreserved=!onDraft(example.request)}){Text("채팅에 예시 넣기")}
                        }
                        }
                    }
                }
                if(visibleRows.isEmpty() && capabilities!=null)item {
                    ZipEmptyState(com.user.localagent.runtime.R.drawable.zi_ill_web,"검색 결과가 없어요.","")
                }
            }
        }
    }
}
