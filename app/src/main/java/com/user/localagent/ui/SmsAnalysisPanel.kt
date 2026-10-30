package com.user.localagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import com.user.localagent.automation.WritableCalendar
import com.user.localagent.core.ScheduleCandidate
import com.user.localagent.core.ScheduleExtraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun SmsAnalysisPanel(app:AppContainer,onStatus:(String)->Unit) {
    val state by app.smsAnalysis.state.collectAsState()
    val scope=rememberCoroutineScope()
    var calendars by remember{mutableStateOf<List<WritableCalendar>>(emptyList())}
    var calendarId by remember{mutableStateOf<Long?>(null)}
    var calendarError by remember{mutableStateOf<String?>(null)}
    var calendarMenu by remember{mutableStateOf(false)}
    val selected=remember{mutableStateMapOf<String,Boolean>()}
    val edited=remember{mutableStateMapOf<String,ScheduleCandidate>()}
    var confirmSave by remember{mutableStateOf(false)}
    var saving by remember{mutableStateOf(false)}
    var saveJob by remember{mutableStateOf<Job?>(null)}
    var deleteId by remember{mutableStateOf<String?>(null)}
    var clearAll by remember{mutableStateOf(false)}
    var savedSummary by remember{mutableStateOf("")}
    val formatter=remember{DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")}
    fun display(iso:String?)=iso?.let {runCatching {OffsetDateTime.parse(it).atZoneSameInstant(ScheduleExtraction.zone).toLocalDateTime().format(formatter)}.getOrNull()}.orEmpty()
    fun loadCalendars(){scope.launch {
        try {calendars=app.calendarGateway.listWritableCalendars();if(calendarId==null)calendarId=calendars.firstOrNull()?.id;calendarError=if(calendars.isEmpty())"쓸 수 있는 캘린더가 없습니다. 캘린더 앱의 계정을 확인해주세요."else null}
        catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){calendarError=error.message?.take(300)?:"캘린더 권한을 확인해주세요."}
    }}
    LaunchedEffect(app){loadCalendars()}
    val chosen=state.candidates.map {edited[it.id]?:it}.filter {selected[it.id]==true}
    LazyColumn(Modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("문자 전체 분석과 일정",style=MaterialTheme.typography.titleLarge)
            Text("Android SMS 제공자의 실제 본문을 읽어 통계와 일정 후보를 찾습니다. 처음 몇 개 발췌만 읽는 방식이 아닙니다. MMS·메신저는 별도 자료입니다.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(enabled=!state.running&&!saving,onClick={selected.clear();edited.clear();if(!app.smsAnalysis.startScan())onStatus("이미 문자 분석 중입니다.")}){Text("전체 문자 분석")}
                OutlinedButton(enabled=!state.running&&!saving,onClick={selected.clear();edited.clear();app.smsAnalysis.startScan(LocalDate.now(ScheduleExtraction.zone).year)}){Text("올해 받은 문자")}
            }
            if(state.running){LinearProgressIndicator(progress={if(state.totalCount>0)(state.scannedCount.toFloat()/state.totalCount).coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth());TextButton(onClick={app.smsAnalysis.cancel()}){Text("분석 중단")}}
            Text(state.status,style=MaterialTheme.typography.bodyMedium)
            if(state.scannedCount>0){Text("수신 ${state.incomingCount} · 발신 ${state.outgoingCount} · 기타 ${state.scannedCount-state.incomingCount-state.outgoingCount}",style=MaterialTheme.typography.bodySmall);Text(state.categories.entries.joinToString(" · "){"${it.key} ${it.value}"},style=MaterialTheme.typography.bodySmall)}
            if(state.firstAtMillis!=null&&state.lastAtMillis!=null)Text("실제 확인 기간 ${Instant.ofEpochMilli(state.firstAtMillis!!).atZone(ScheduleExtraction.zone).toLocalDate()} ~ ${Instant.ofEpochMilli(state.lastAtMillis!!).atZone(ScheduleExtraction.zone).toLocalDate()}",style=MaterialTheme.typography.bodySmall)
            if(state.truncated)Text("개수·용량 상한 또는 오류로 일부 범위만 분석했습니다.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }}}
        item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("검토한 일정만 등록",style=MaterialTheme.typography.titleMedium)
            Box {OutlinedButton(onClick={calendarMenu=true},enabled=!saving){Text(calendars.firstOrNull {it.id==calendarId}?.let {"${it.displayName} · ${it.accountName}"}?:"캘린더 선택")};DropdownMenu(expanded=calendarMenu,onDismissRequest={calendarMenu=false}){calendars.forEach {calendar->DropdownMenuItem(text={Text("${calendar.displayName} · ${calendar.accountName}")},onClick={calendarId=calendar.id;calendarMenu=false})}}}
            calendarError?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            TextButton(onClick={loadCalendars()}){Text("캘린더 다시 확인")}
            Text("종료 시각은 기본 1시간이며 수정할 수 있습니다. 날짜·시간이 애매한 후보는 먼저 확인해주세요. 선택만으로는 캘린더에 쓰지 않습니다.",style=MaterialTheme.typography.bodySmall)
            Row{
                TextButton(enabled=!state.running&&!saving,onClick={state.candidates.map {edited[it.id]?:it}.filter {!it.needsReview&&it.startIso!=null&&runCatching {OffsetDateTime.parse(it.startIso).toInstant().isAfter(Instant.now())}.getOrDefault(false)}.forEach {selected[it.id]=true}}){Text("확실한 미래 일정 선택")}
                TextButton(onClick={selected.clear()}){Text("선택 해제")}
            }
            Button(enabled=!state.running&&!saving&&chosen.isNotEmpty()&&calendarId!=null,onClick={confirmSave=true}){Text(if(saving)"캘린더에 저장 중"else"선택 ${chosen.size}개 등록 검토")}
            if(saving&&saveJob!=null)TextButton(enabled=saveJob?.isActive==true,onClick={saveJob?.cancel();savedSummary="등록 중단을 요청했습니다. 이미 저장된 일정은 유지합니다."}){Text("등록 중단")}
            if(savedSummary.isNotBlank())Text(savedSummary,style=MaterialTheme.typography.bodyMedium)
            TextButton(enabled=!state.running&&!saving&&state.candidates.isNotEmpty(),onClick={clearAll=true}){Text("저장된 일정 후보 모두 삭제")}
        }}}
        items(state.candidates,key={it.id}) {original->
            val candidate=edited[original.id]?:original
            var startText by remember(original.id){mutableStateOf(display(candidate.startIso))}
            var endText by remember(original.id){mutableStateOf(display(candidate.endIso))}
            var editError by remember(original.id){mutableStateOf<String?>(null)}
            var titleText by remember(original.id){mutableStateOf(candidate.title)}
            ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                Row {Checkbox(checked=selected[original.id]==true,onCheckedChange={selected[original.id]=it},enabled=!saving&&!state.running&&!candidate.needsReview&&candidate.startIso!=null);Text(candidate.title,style=MaterialTheme.typography.titleMedium)}
                Text(candidate.excerpt,style=MaterialTheme.typography.bodySmall,maxLines=7)
                OutlinedTextField(value=titleText,onValueChange={titleText=it.take(300);selected[original.id]=false;edited[original.id]=candidate.copy(title=titleText,needsReview=true,reviewReason="수정한 내용을 확인 완료해주세요.")},label={Text("일정 제목")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!saving)
                if(candidate.needsReview)Text(candidate.reviewReason?:"날짜·시간 확인 필요",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=startText,onValueChange={startText=it.take(30);selected[original.id]=false;edited[original.id]=candidate.copy(needsReview=true,reviewReason="수정한 날짜·시간을 확인 완료해주세요.")},label={Text("시작 · yyyy-MM-dd HH:mm")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!saving)
                OutlinedTextField(value=endText,onValueChange={endText=it.take(30);selected[original.id]=false;edited[original.id]=candidate.copy(needsReview=true,reviewReason="수정한 날짜·시간을 확인 완료해주세요.")},label={Text("종료 · 기본 1시간, 수정 가능")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!saving)
                TextButton(enabled=!saving,onClick={
                    try {require(titleText.isNotBlank());val start=LocalDateTime.parse(startText.trim(),formatter).atZone(ScheduleExtraction.zone).toOffsetDateTime();val end=if(endText.isBlank())start.plusHours(1)else LocalDateTime.parse(endText.trim(),formatter).atZone(ScheduleExtraction.zone).toOffsetDateTime();require(end.isAfter(start));edited[original.id]=candidate.copy(title=titleText,startIso=start.toString(),endIso=end.toString(),needsReview=false,reviewReason=null);startText=display(start.toString());endText=display(end.toString());editError=null}
                    catch(_:Exception){editError="시작·종료 날짜를 예시 형식으로 확인해주세요. 종료는 시작보다 뒤여야 합니다."}
                }){Text("제목·날짜 확인 완료")}
                TextButton(enabled=!saving&&!state.running,onClick={deleteId=original.id}){Text("이 후보 삭제")}
                editError?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            }}
        }
    }
    if(deleteId!=null||clearAll)AlertDialog(onDismissRequest={deleteId=null;clearAll=false},title={Text(if(clearAll)"일정 후보를 모두 삭제할까요?"else"이 일정 후보를 삭제할까요?")},text={Text("앱에 저장한 후보와 문자 발췌만 삭제합니다. 실제 문자와 이미 등록한 캘린더 일정은 유지됩니다.")},confirmButton={TextButton(onClick={val id=deleteId;val all=clearAll;deleteId=null;clearAll=false;scope.launch {saving=true;try{if(all){app.smsAnalysis.clearCandidates();selected.clear();edited.clear()}else if(id!=null){app.smsAnalysis.remove(id);selected.remove(id);edited.remove(id)};onStatus("일정 후보를 삭제했습니다.")}catch(cancel:CancellationException){throw cancel}catch(_:Exception){onStatus("후보를 삭제하지 못했습니다.")}finally{saving=false}}}){Text("삭제")}},dismissButton={TextButton(onClick={deleteId=null;clearAll=false}){Text("취소")}})
    if(confirmSave)AlertDialog(onDismissRequest={confirmSave=false},title={Text("선택한 ${chosen.size}개 일정을 등록할까요?")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text(calendars.firstOrNull {it.id==calendarId}?.let {"${it.displayName} · ${it.accountName}"}.orEmpty())
        chosen.take(8).forEach {Text("${it.title}\n${display(it.startIso)} ~ ${display(it.endIso)}",style=MaterialTheme.typography.bodySmall)}
        if(chosen.size>8)Text("외 ${chosen.size-8}개 · 목록에서 선택한 일정만 저장")
    }},confirmButton={TextButton(onClick={if(saving||saveJob?.isActive==true)return@TextButton;confirmSave=false;val approved=chosen.toList();val target=calendarId?:return@TextButton;saving=true;saveJob=scope.launch {
        try {
            val result=app.calendarGateway.saveApproved(approved,target)
            val inserted=result.events.count {!it.alreadyPresent};val duplicate=result.events.count {it.alreadyPresent}
            savedSummary="실제 저장 ${inserted}개 · 이미 등록 ${duplicate}개 · 실패 ${result.failures.size}개"
            val auditSaved=runCatching {app.repository.recordToolRun("calendar.write_reviewed",result.failures.isEmpty(),"verifiedInserted=$inserted; verifiedExisting=$duplicate; failed=${result.failures.size}")}.isSuccess
            if(!auditSaved)savedSummary+="\n일정 결과는 확인했으나 작업 기록을 저장하지 못했습니다."
            result.events.forEach {selected[it.candidateId]=false}
            if(result.failures.isNotEmpty())savedSummary+="\n"+result.failures.take(3).joinToString("\n"){it.reason}
            val warnings=result.events.mapNotNull {it.warning};if(warnings.isNotEmpty())savedSummary+="\n"+warnings.take(2).joinToString("\n")
            onStatus(savedSummary)
        }
        catch(cancel:CancellationException){onStatus("등록을 중단했습니다. 이미 저장된 일정은 유지되며 다시 등록 시 중복 확인합니다.");throw cancel}
        catch(error:Exception){savedSummary=error.message?.take(400)?:"캘린더에 저장하지 못했습니다.";onStatus(savedSummary)}
        finally{saveJob=null;saving=false}
    }}){Text("이 일정들 등록")}},dismissButton={TextButton(onClick={confirmSave=false}){Text("취소")}})
}
