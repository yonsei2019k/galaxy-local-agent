package com.user.localagent.briefing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.user.localagent.LocalAgentApplication
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Private activity reached by an immutable notification PendingIntent; no exported action receiver. */
class BriefingReviewActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {super.onCreate(savedInstanceState)
        val lightBars=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(lightBars,lightBars)
        val app=(application as LocalAgentApplication).container
        setContent {com.user.localagent.ui.ZipHapTheme {Surface {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(18.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("일정·업무 확인",style=MaterialTheme.typography.headlineSmall)
            BriefingAgendaPanel(app)
        }}}}
    }
}

@Composable fun BriefingAgendaPanel(app:com.user.localagent.AppContainer) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val agenda=remember {BriefingAgenda(context,app)};val scope=rememberCoroutineScope()
    var rows by remember {mutableStateOf(agenda.items())};var message by remember {mutableStateOf("")}
    var calendars by remember {mutableStateOf(emptyList<com.user.localagent.automation.WritableCalendar>())}
    var chosen by remember {mutableStateOf(agenda.state()["calendarId"]?.jsonPrimitive?.longOrNull)}
    var calendarMenu by remember {mutableStateOf(false)}
    var edited by remember {mutableStateOf<JsonObject?>(null)}
    var title by remember {mutableStateOf("")};var start by remember {mutableStateOf("")};var end by remember {mutableStateOf("")};var location by remember {mutableStateOf("")}
    var working by remember {mutableStateOf(false)}
    val reports by app.repository.notes.collectAsState(emptyList())
    LaunchedEffect(Unit){runCatching {app.calendarGateway.listWritableCalendars()}.onSuccess {calendars=it}.onFailure {message=it.message.orEmpty()}}
    Text("자동 등록할 캘린더",style=MaterialTheme.typography.titleMedium)
    Text("처음 선택한 캘린더에만 등록합니다. 수정·취소·충돌과 불명확한 항목은 확인 대기합니다.",style=MaterialTheme.typography.bodySmall)
    Box {
        OutlinedButton(enabled=!working,onClick={calendarMenu=true}){Text(calendars.firstOrNull {it.id==chosen}?.displayName?.let {"$it · 변경"}?:"캘린더 선택")}
        DropdownMenu(expanded=calendarMenu,onDismissRequest={calendarMenu=false},modifier=Modifier.heightIn(max=320.dp)) {
            calendars.forEach {calendar->DropdownMenuItem(text={Text("${if(chosen==calendar.id)"✓ "else""}${calendar.displayName}")},onClick={calendarMenu=false;scope.launch {
                working=true;try {agenda.setCalendar(calendar.id);chosen=calendar.id;message="${calendar.displayName}에 새 확정 일정을 자동 등록합니다."}
                catch(e:Exception){message=e.message.orEmpty()}finally{working=false}
            }})}
        }
    }
    if(calendars.isEmpty())Text("캘린더 접근 권한과 쓸 수 있는 캘린더가 필요합니다.")
    if(message.isNotBlank())Text(message)
    reports.filter {it.collection=="codex-briefings"}.sortedByDescending {it.updatedAt}.take(3).forEach {note->
        Text("최근 일정·업무 보고",style=MaterialTheme.typography.titleMedium)
        Text(note.body.substringAfter("\n\n").substringBefore("\n\n구조화 결과:"),style=MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick={rows=agenda.items()}){Text("결과 새로고침")}
    if(rows.isEmpty())Text("새 자료 분석 결과가 도착하면 이곳에서 확인할 수 있습니다.")
    rows.asReversed().take(100).forEach {item->
        val status=item.getValue("status").jsonPrimitive.content
        if(status!="dismissed")Card {Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(item.getValue("title").jsonPrimitive.content,style=MaterialTheme.typography.titleMedium)
            Text(if(status=="registered")"캘린더 등록·재조회 완료"else"확인 필요: "+item.getValue("reviewReason").jsonPrimitive.content)
            Text("${briefingTimeLabel(item["startIso"]?.jsonPrimitive?.contentOrNull.orEmpty())} ~ ${briefingTimeLabel(item["endIso"]?.jsonPrimitive?.contentOrNull.orEmpty())}")
            Text("장소: "+item.getValue("location").jsonPrimitive.content)
            Text("대상·제출처: "+item.getValue("recipient").jsonPrimitive.content+" · 방법: "+item.getValue("deliveryMethod").jsonPrimitive.content)
            Text("원문 근거: "+item.getValue("quote").jsonPrimitive.content,style=MaterialTheme.typography.bodySmall)
            if(status!="registered")Button(enabled=!working,onClick={edited=item;title=item.getValue("title").jsonPrimitive.content;
                start=item["startIso"]?.jsonPrimitive?.contentOrNull.orEmpty();end=item["endIso"]?.jsonPrimitive?.contentOrNull.orEmpty();location=item.getValue("location").jsonPrimitive.content}){Text("확인·수정")}
            else TextButton(onClick={runCatching {context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(item.getValue("eventUri").jsonPrimitive.content)))}}){Text("캘린더에서 보기")}
        }}
    }
    edited?.let {item->AlertDialog(onDismissRequest={if(!working)edited=null},title={Text("일정 확인·수정")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(title,{title=it},label={Text("일정 이름")})
        OutlinedButton(onClick={pickBriefingTime(context,start){start=it}}){Text("시작: "+briefingTimeLabel(start))}
        OutlinedButton(onClick={pickBriefingTime(context,end){end=it}}){Text("종료: "+briefingTimeLabel(end))}
        OutlinedTextField(location,{location=it},label={Text("장소")})
        if(message.isNotBlank())Text(message)
    }},confirmButton={TextButton(enabled=!working,onClick={scope.launch {working=true;try {
        agenda.review(item.getValue("id").jsonPrimitive.content,title,start,end,location);rows=agenda.items();edited=null;message="저장 후 재조회했습니다."
    }catch(e:Exception){message=e.message.orEmpty()}finally{working=false}}}){Text("확인 후 등록")}},dismissButton={Row {
        TextButton(enabled=!working,onClick={scope.launch {working=true;try {agenda.review(item.getValue("id").jsonPrimitive.content,title,start,end,location,true);rows=agenda.items();edited=null}finally{working=false}}}){Text("등록 안 함")}
        TextButton(enabled=!working,onClick={edited=null}){Text("닫기")}
    }})}
}

private fun briefingTimeLabel(raw:String)=runCatching {java.time.OffsetDateTime.parse(raw).format(java.time.format.DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm"))}.getOrDefault("날짜·시간 선택")
private fun pickBriefingTime(context:android.content.Context,raw:String,done:(String)->Unit) {
    val initial=runCatching {java.time.OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"))}.getOrElse {java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)}
    android.app.DatePickerDialog(context,{_,year,month,day->
        android.app.TimePickerDialog(context,{_,hour,minute->done(java.time.LocalDateTime.of(year,month+1,day,hour,minute).atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString())},initial.hour,initial.minute,true).show()
    },initial.year,initial.monthValue-1,initial.dayOfMonth).show()
}
