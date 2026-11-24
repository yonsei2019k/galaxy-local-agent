package com.user.localagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var connectionOnly by remember{mutableStateOf(false)}
    var query by remember{mutableStateOf("")}
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
    Column(modifier.fillMaxWidth().imePadding(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(!connectionOnly,{connectionOnly=false},{Text("추천")})
            FilterChip(connectionOnly,{connectionOnly=true},{Text("앱과 연결")})
        }
        OutlinedTextField(query,{query=it.take(100)},Modifier.fillMaxWidth(),
            label={Text("이름이나 하고 싶은 일 검색")},singleLine=true)
        if(failure)Text("지금 사용할 수 있는 기능을 확인하지 못했어요.",color=MaterialTheme.colorScheme.error)
        else if(capabilities==null)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(draftPreserved)Text("입력 중인 메시지가 있어 그대로 두었어요.")
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(connectionOnly){
                if(matches("휴대폰 캘린더","일정","Android"))item{
                    OutlinedCard(Modifier.fillMaxWidth()){
                        Column(Modifier.padding(12.dp)){
                            Text("휴대폰 캘린더",style=MaterialTheme.typography.titleMedium)
                            Text(if(calendarReadGranted)"일정 읽기 허용됨"else"내 일정을 보려면 Android에서 읽기를 허용해 주세요.")
                            if(!calendarReadGranted)TextButton(onClick=onCalendarRead){Text("일정 읽기 허용")}
                        }
                    }
                }
                if(matches("앱과 컴퓨터 연결","PC","메일","캘린더","메신저","Codex","Claude"))item{
                    OutlinedCard(onClick=onConnections,modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(12.dp)){
                            Text("앱과 컴퓨터 연결 관리",style=MaterialTheme.typography.titleMedium)
                            Text(if(pc.paired)"등록한 컴퓨터가 있어요. 연결 상태와 사용할 계정을 확인할 수 있어요."
                                else "연결할 계정, 내 컴퓨터, 메신저를 선택해요.")
                        }
                    }
                }
                items(ConnectedService.entries.filter{service->matches(service.label,service.id)},key={"account-"+it.id}){service->
                    val all=accountTools.values.filter{it.service==service}.mapNotNull{byId[it.definition.name]}
                    val reads=all.filterNot{accountTools[it.id]?.writes==true}
                    val status=when{
                        reads.any{it.availability==CapabilityAvailability.INSTALLED}->"읽기 연결 확인됨"
                        all.any{it.availability==CapabilityAvailability.REQUIRES_PERMISSION}->"계정 연결 필요"
                        else->"연결 상태 확인 필요"
                    }
                    OutlinedCard(onClick=onConnections,modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(12.dp)){Text(service.label);Text(status,style=MaterialTheme.typography.bodySmall)}
                    }
                }
                items(ConsumerApp.entries.filter{matches(it.label,it.id)},key={"phone-"+it.id}){item->
                    val all=consumerTools.filterValues{it==item}.keys.mapNotNull(byId::get)
                    val verified=all.any{it.availability==CapabilityAvailability.INSTALLED}
                    OutlinedCard(Modifier.fillMaxWidth()){
                        Column(Modifier.padding(12.dp)){
                            Text(item.label)
                            Text(if(verified)"사용 가능한 동작 있음"else"휴대폰 앱과 지원 동작 확인 필요",style=MaterialTheme.typography.bodySmall)
                            all.forEach{Text(it.description,style=MaterialTheme.typography.bodySmall)}
                        }
                    }
                }
            }else{
                val rows=detected.filter{item->matches(examples[item.id]?.title.orEmpty(),item.title,item.description,item.limitation)}
                    .sortedBy{if(it.id in examples)0 else if(it.availability==CapabilityAvailability.NOT_IMPLEMENTED)2 else 1}
                items(rows,key={it.id}){item->
                    val example=examples[item.id]
                    OutlinedCard(Modifier.fillMaxWidth()){
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(example?.title?:item.title,style=MaterialTheme.typography.titleMedium)
                            Text(item.officeStatus(),style=MaterialTheme.typography.labelMedium)
                            Text(item.limitation,style=MaterialTheme.typography.bodySmall)
                            if(item.id=="generate_image")TextButton(onClick=onImages){Text("이미지 작업실 열기")}
                            else if(example!=null)TextButton(onClick={draftPreserved=!onDraft(example.request)}){Text("채팅에 예시 넣기")}
                        }
                    }
                }
                if(rows.isEmpty()&&capabilities!=null)item{Text("검색 결과가 없어요.")}
            }
        }
    }
}
