package com.user.localagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import com.user.localagent.automation.PlanningFeedbackSnapshot
import com.user.localagent.automation.PlanningToolFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bounded aggregate-only controls, safe to embed as one item in the knowledge screen. */
@Composable fun PlanningLearningPanel(app:AppContainer,onStatus:(String)->Unit,onReset:()->Unit={}){
    val scope=rememberCoroutineScope()
    var snapshot by remember {mutableStateOf<PlanningFeedbackSnapshot?>(null)}
    var loading by remember {mutableStateOf(false)}
    var resetDialog by remember {mutableStateOf(false)}
    var deleteTarget by remember {mutableStateOf<PlanningToolFeedback?>(null)}
    fun execute(block:suspend ()->Unit){if(loading)return;loading=true;scope.launch {
        try{block()}catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){onStatus("실행 피드백 변경 결과를 확인하지 못했습니다. 상태를 다시 조회해주세요.")}
        finally{loading=false}
    }}
    fun refresh(){execute {snapshot=app.planningFeedback.snapshot()}}
    LaunchedEffect(app){refresh()}
    if(resetDialog)AlertDialog(onDismissRequest={if(!loading)resetDialog=false},title={Text("실행 피드백을 초기화할까요?")},text={Text("도구 성공·준비·실패 집계와 승인 전 개선 제안을 지웁니다. 개인 기억과 이미 실행한 작업은 유지됩니다.")},confirmButton={TextButton(enabled=!loading,onClick={execute {
        withContext(NonCancellable){
            app.planningFeedback.reset()
            onReset()
            val current=app.planningFeedback.snapshot()
            check(current.stats.isEmpty())
            snapshot=current;resetDialog=false;onStatus("실행 피드백 초기화와 삭제 결과를 확인했습니다.")
        }
    }}){Text("피드백 초기화")}},dismissButton={TextButton(enabled=!loading,onClick={resetDialog=false}){Text("취소")}})
    deleteTarget?.let {target->AlertDialog(onDismissRequest={if(!loading)deleteTarget=null},title={Text("이 도구의 피드백을 지울까요?")},text={Text("${feedbackDomainLabel(target.domain)} · ${feedbackToolLabel(target.tool)}\n이 도구의 집계와 개선 제안만 지웁니다.")},confirmButton={TextButton(enabled=!loading,onClick={execute {
        withContext(NonCancellable){
            app.planningFeedback.delete(target.domain,target.tool)
            onReset()
            val current=app.planningFeedback.snapshot()
            check(current.stats.none {it.domain==target.domain&&it.tool==target.tool})
            snapshot=current;deleteTarget=null;onStatus("선택한 도구의 피드백 삭제를 확인했습니다.")
        }
    }}){Text("기록 삭제")}},dismissButton={TextButton(enabled=!loading,onClick={deleteTarget=null}){Text("취소")}})}
    ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
        Text("실행하며 쌓은 피드백",style=MaterialTheme.typography.titleMedium)
        Text("검증된 결과를 집계해 다음 계획에 참고합니다. 모델 가중치와 실행 권한은 바뀌지 않습니다.",style=MaterialTheme.typography.bodySmall)
        val rows=snapshot?.stats.orEmpty()
        if(snapshot!=null){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text("검증 성공 ${rows.sumOf {it.verifiedSuccess}}",style=MaterialTheme.typography.labelLarge)
                Text("준비 ${rows.sumOf {it.prepared}}",style=MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text("미검증 ${rows.sumOf {it.unverified}}",style=MaterialTheme.typography.labelLarge)
                Text("실패 ${rows.sumOf {it.failed}}",style=MaterialTheme.typography.labelLarge)
            }
            Text("준비는 외부 앱의 저장·발송 완료를 의미하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            val lessons=rows.sumOf {it.proposedLessons.values.sum()}
            Text("승인 전 개선 제안 ${lessons}건 · 자동 적용·권한 부여 없음",style=MaterialTheme.typography.bodySmall)
            if(rows.isEmpty())Text("아직 기록된 실행 피드백이 없습니다.",style=MaterialTheme.typography.bodySmall)
            rows.sortedByDescending {it.updatedAt}.take(8).forEach {row->
                ZipInsetDivider(inset = 0.dp)
                Text("${feedbackDomainLabel(row.domain)} · ${feedbackToolLabel(row.tool)}",style=MaterialTheme.typography.labelLarge)
                Text("검증 ${row.verifiedSuccess} · 준비 ${row.prepared} · 미검증 ${row.unverified} · 실패 ${row.failed}",style=MaterialTheme.typography.bodySmall)
                row.proposedLessons.forEach {(kind,count)->
                    Text("제안: ${feedbackLessonLabel(kind)} · ${count}회",style=MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled=!loading,onClick={execute {app.planningFeedback.decideLesson(row.domain,row.tool,kind,true);snapshot=app.planningFeedback.snapshot()}}){Text("승인")}
                        TextButton(enabled=!loading,onClick={execute {app.planningFeedback.decideLesson(row.domain,row.tool,kind,false);snapshot=app.planningFeedback.snapshot()}}){Text("무시")}
                    }
                }
                row.approvedLessons.forEach {kind->Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                    Text("승인됨: ${feedbackLessonLabel(kind)}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!loading,onClick={execute {app.planningFeedback.decideLesson(row.domain,row.tool,kind,false);snapshot=app.planningFeedback.snapshot()}}){Text("해제")}
                }}
                TextButton(enabled=!loading,onClick={deleteTarget=row}){Text("이 도구 기록 지우기")}
            }
            if(rows.size>8)Text("최근 갱신된 8개 도구를 표시합니다. 전체 ${rows.size}개 집계가 위 합계에 포함됩니다.",style=MaterialTheme.typography.labelSmall)
        }
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ZipOutlinedButton(enabled=!loading,onClick={refresh()}){Text("상태 새로고침")}
            TextButton(enabled=!loading&&snapshot!=null,onClick={resetDialog=true}){Text("전체 피드백 초기화")}
        }
    }}
}

private fun feedbackDomainLabel(value:String):String=when(value){
    "messages"->"메시지";"calendar"->"일정";"documents"->"문서";"projects"->"프로젝트";"images"->"이미지"
    "notes"->"메모";"memory"->"기억";"audio"->"음성";"web"->"웹·쇼핑";else->"일반"
}
private fun feedbackToolLabel(value:String):String=when(value){
    "planner"->"요청 계획";"get_current_datetime"->"현재 시각";"list_project_files"->"프로젝트 목록";"read_project_file"->"프로젝트 읽기";"write_project_file"->"프로젝트 저장"
    "create_document"->"문서 만들기";"search_web"->"웹 검색";"search_shopping"->"상품 검색";"read_webpage"->"웹페이지 읽기";"generate_image"->"이미지 생성"
    "search_conversation_history"->"비서 대화 검색";"list_imported_conversations"->"가져온 대화 목록";"search_conversation_imports"->"가져온 대화 검색"
    "get_sms_summary"->"문자 조회";"get_call_log_summary"->"통화 기록 조회";"list_audio_recordings"->"녹음 목록";"search_audio_transcripts"->"전사문 검색"
    "list_calendar_events"->"일정 조회";"prepare_calendar_event"->"일정 준비";"create_reminder"->"리마인더 생성";"create_alarm"->"알람 준비"
    "save_note"->"메모 저장";"search_notes"->"메모 검색";"update_note"->"메모 수정";"delete_note"->"메모 삭제";"search_contacts"->"연락처 검색"
    "prepare_sms"->"문자 초안";"prepare_email"->"메일 초안";"open_map"->"지도 열기";"open_url"->"웹주소 열기"
    "save_memory"->"기억 저장";"search_memories"->"기억 검색";"delete_memory"->"기억 삭제";"update_reminder"->"리마인더 수정";"delete_reminder"->"리마인더 삭제"
    "list_messenger_notifications"->"메신저 알림 조회";"reply_messenger_notification"->"메신저 답장";else->value.replace('_',' ')
}
private fun feedbackLessonLabel(value:String):String=when(value){
    "read_before_write"->"수정 전 현재 상태 조회";"verify_result"->"실행 결과 확인";"avoid_repeat"->"동일 실패 반복 줄이기";"keep_user_goal"->"요청 목표 유지"
    "ask_one_essential"->"필수 확인 하나로 줄이기";"prefer_native_tool"->"연결된 기기 도구 우선";"prefer_existing_data"->"이미 연결된 자료 우선";else->"개선 검토"
}
