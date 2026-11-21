package com.user.localagent.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import com.user.localagent.callfollowup.*
import kotlinx.coroutines.*

/** Settings only. Reports and subsequent reviewed actions continue in their original conversation. */
@Composable
fun CallFollowUpPanel(app:AppContainer,onStatus:(String)->Unit,onOpenReport:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var state by remember {mutableStateOf<CallFollowUpState?>(null)}
    var sourceSelected by remember {mutableStateOf(false)}
    var jobs by remember {mutableStateOf<List<CallFollowUpQueueItem>>(emptyList())}
    var busy by remember {mutableStateOf(false)}
    var pendingGeneration by rememberSaveable {mutableStateOf<Long?>(null)}
    var selectedRecordingGeneration by rememberSaveable {mutableStateOf<Long?>(null)}
    var transcriptJob by remember {mutableStateOf<String?>(null)}
    var transcriptStart by remember {mutableIntStateOf(0)}
    var transcriptChunks by remember {mutableStateOf<List<CallRawChunk>>(emptyList())}
    var forgetTarget by remember {mutableStateOf<String?>(null)}
    var jobsOffset by rememberSaveable {mutableIntStateOf(0)}
    var modelDetail by remember {mutableStateOf<String?>(null)}
    suspend fun refresh(){state=app.callFollowUp.state();modelDetail=app.callFollowUpPipeline.modelReadinessDetail();sourceSelected=app.callFollowUp.configuredSource()!=null;jobs=app.callFollowUpPipeline.listJobs(jobsOffset,20)}
    fun launchWork(block:suspend()->Unit){scope.launch {
        busy=true
        try {block();refresh()}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){onStatus("통화 분석 설정 또는 작업 상태를 확인하지 못했습니다. 다시 확인해주세요.")}
        finally {busy=false}
    }}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        launchWork {refresh();app.callFollowUp.requestReconciliation("permissions_checked")}
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        val expected=pendingGeneration;pendingGeneration=null
        if(uri!=null&&expected!=null)launchWork {
            check(app.callFollowUp.state().generation==expected){"폴더를 선택하는 동안 설정이 변경되었습니다."}
            val grant=RecordingSourceGrant(RecordingSourceKind.SAF_TREE,uri.toString(),ownerConfirmedCallRecordingFolder=true).validate()
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.callFollowUp.setEnabled(true,grant)
            onStatus("선택한 통화 녹음 폴더의 새 파일부터 분석합니다. 기존 녹음은 자동으로 읽지 않습니다.")
        }
    }
    val recordingPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){selection->
        val expected=selectedRecordingGeneration;selectedRecordingGeneration=null
        val files=selection.distinct()
        if(files.isNotEmpty()&&expected!=null)launchWork {
            if(files.size>20){
                onStatus("한 번에 녹음 20개까지 선택할 수 있습니다.")
                return@launchWork
            }
            val intake=app.callFollowUp.requestSelectedRecordings(files,expected)
            onStatus("선택 ${files.size}개 · 대기열 요청 ${intake.accepted}개 · 미접수 ${intake.skipped}개")
        }
    }
    LaunchedEffect(app,jobsOffset){while(isActive){try {refresh()}catch(cancel:CancellationException){throw cancel}catch(_:Exception){};delay(10_000)}}
    ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("통화 뒤 정리",style=MaterialTheme.typography.titleMedium)
        Text("삼성 전화가 저장한 새 통화 녹음을 기기 안에서 분석하고, 일정·메모·메일 답장 후보를 전용 대화에 보고합니다. 원본 녹음은 유지합니다.",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("새 통화 녹음 자동 정리")
            Switch(checked=state?.enabled==true,enabled=state!=null&&!busy,onCheckedChange={enabled->
                if(enabled&&!sourceSelected){pendingGeneration=state?.generation;picker.launch(null)}
                else launchWork {app.callFollowUp.setEnabled(enabled)}
            })
        }
        Text(when(state?.readiness){
            null->"상태 확인 중"
            CallFollowUpReadiness.DISABLED->"자동 분석 꺼짐"
            CallFollowUpReadiness.SOURCE_NOT_SELECTED->"삼성 전화의 통화 녹음 폴더를 선택해주세요."
            CallFollowUpReadiness.REQUIRES_SOURCE_ACCESS->"녹음 폴더 접근을 다시 연결해주세요."
            CallFollowUpReadiness.REQUIRES_PHONE_PERMISSION->"통화 종료 감지 권한이 필요합니다."
            CallFollowUpReadiness.MODEL_UNAVAILABLE->if(modelDetail=="TEXT_MODEL_UNAVAILABLE")"모델 관리에서 Gemma E4B 대화 모델을 준비해주세요."else"자동 분석용 Qwen 음성 모델과 인코더를 모델 관리에서 준비해주세요."
            CallFollowUpReadiness.DEFERRED_DEVICE_STATE->"다른 작업·통화·발열·전원 상태가 안정되면 이어갑니다."
            CallFollowUpReadiness.SOURCE_REVIEW_REQUIRED->"녹음 파일 또는 폴더 상태를 확인해주세요."
            CallFollowUpReadiness.READY->"새 녹음 대기 · 저장 완료 확인 후 분석"
        },style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled=!busy&&state!=null,onClick={pendingGeneration=state?.generation;picker.launch(null)}){Text(if(sourceSelected)"통화 녹음 폴더 변경" else "통화 녹음 폴더 연결")}
        Text("전화 앱의 녹음 목록에서 저장 위치를 확인한 뒤 해당 폴더만 선택하세요. 새로 켠 시점 이전 녹음과 다른 폴더는 자동 수집하지 않습니다.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=!busy,onClick={permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.POST_NOTIFICATIONS))}){Text("통화와 알림 권한 확인")}
        TextButton(enabled=!busy&&state?.enabled==true,onClick={launchWork {app.callFollowUp.requestReconciliation("owner_refresh");refresh()}}){Text("새 녹음 확인")}
        OutlinedButton(enabled=!busy&&sourceSelected&&state?.enabled==true,onClick={selectedRecordingGeneration=state?.generation;recordingPicker.launch(arrayOf("audio/*"))}){Text("기존 통화 녹음 선택")}
        Text("파일 저장과 Android의 백그라운드 실행 일정에 따라 지연될 수 있습니다. 전사 구간 시각은 단어 시각이 아니며, 날짜·이름·수신자·부정·정정은 원음과 대조해야 합니다. 실제 일정 등록과 메일 발송은 별도로 확인합니다.",style=MaterialTheme.typography.bodySmall)
        SettingsDisclosure("음성 인식 세부 정보","통화 정리에서 사용하는 인식 방법을 확인해요."){
            Text("자동 통화 전사는 별도 Qwen 경로를 사용합니다. 기기 내 ML Kit는 화면을 연 상태의 비교 후보이며 일반 음성 인식 선택은 바꾸지 않습니다.",style=MaterialTheme.typography.bodySmall)
        }
        jobs.forEach {job->
            HorizontalDivider()
            Text("${callJobLabel(job.status.name)} · 전사 ${job.completedChunks}구간 / 분석 ${job.analyzedChunks}구간",style=MaterialTheme.typography.bodyMedium)
            callModelProgress(job)?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
            callFailureDetail(job.errorCode)?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
            job.asrModelId?.let {Text("음성 인식: ${if(it=="asr-qwen-17b")"Qwen3-ASR 1.7B"else if(it=="asr-qwen-06b")"Qwen3-ASR 0.6B"else it}",style=MaterialTheme.typography.bodySmall)}
            if(job.reviewRequired)Text("확인 필요한 항목이 있습니다.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(enabled=!busy,onClick={onOpenReport(job.reportThreadId)}){Text("대화에서 보고서 보기")}
                if(job.canRetry)TextButton(enabled=!busy,onClick={launchWork {app.callFollowUpPipeline.retry(job.queueId)}}){Text("이어 분석")}
                if(job.status !in setOf(CallFollowUpQueueStatus.COMPLETE,CallFollowUpQueueStatus.PAUSED,CallFollowUpQueueStatus.REVIEW_REQUIRED,CallFollowUpQueueStatus.INVALIDATED))TextButton(enabled=!busy,onClick={launchWork {app.callFollowUpPipeline.cancel(job.queueId)}}){Text("중지")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(enabled=!busy,onClick={launchWork {
                    val uri=app.callFollowUpPipeline.sourceForReview(job.queueId)
                    val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    check(intent.resolveActivity(context.packageManager)!=null)
                    context.startActivity(intent)
                }}){Text("녹음 열기")}
                TextButton(enabled=!busy&&job.completedChunks>0,onClick={launchWork {
                    transcriptStart=0;transcriptChunks=app.callFollowUpPipeline.transcriptForReview(job.queueId,0,5);transcriptJob=job.queueId
                }}){Text("녹음에서 만든 글 보기")}
            }
            TextButton(enabled=!busy,onClick={forgetTarget=job.queueId}){Text("이 녹음의 분석 기록 삭제")}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TextButton(enabled=!busy&&jobsOffset>0,onClick={jobsOffset=(jobsOffset-20).coerceAtLeast(0)}){Text("더 최근 기록")}
            if(jobs.size==20)TextButton(enabled=!busy,onClick={jobsOffset+=20}){Text("이전 기록")}
        }
    }}
    transcriptJob?.let {id->AlertDialog(onDismissRequest={transcriptJob=null},title={Text("녹음에서 만든 글 · ${transcriptStart+1}구간부터")},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("자동 인식 원문입니다. 교정된 정답이 아니며 표시 시간은 조각 범위입니다.",style=MaterialTheme.typography.bodySmall)
            SelectionContainer {Column {transcriptChunks.forEach {chunk->Text("[${chunk.startMillis/1000}–${chunk.endMillis/1000}초]\n${chunk.text}");HorizontalDivider()}}}
        }
    },confirmButton={TextButton(onClick={transcriptJob=null}){Text("닫기")}},dismissButton={Row {
        TextButton(enabled=!busy&&transcriptStart>0,onClick={launchWork {val next=(transcriptStart-5).coerceAtLeast(0);transcriptChunks=app.callFollowUpPipeline.transcriptForReview(id,next,5);transcriptStart=next}}){Text("이전")}
        TextButton(enabled=!busy&&transcriptStart+transcriptChunks.size<(jobs.firstOrNull {it.queueId==id}?.completedChunks?:0),onClick={launchWork {val next=transcriptStart+5;transcriptChunks=app.callFollowUpPipeline.transcriptForReview(id,next,5);transcriptStart=next}}){Text("다음")}
    }})}
    forgetTarget?.let {id->AlertDialog(onDismissRequest={forgetTarget=null},title={Text("분석 기록을 삭제할까요?")},text={
        Text("이 녹음의 자동 분석을 중지하고 전사·분석 기록과 수정하지 않은 자동 보고서를 삭제합니다. 직접 수정한 보고서와 삼성 전화의 원본 녹음은 유지합니다.")
    },confirmButton={TextButton(enabled=!busy,onClick={launchWork {app.callFollowUpPipeline.forget(id);forgetTarget=null;if(transcriptJob==id){transcriptJob=null;transcriptChunks=emptyList()};onStatus("선택한 녹음의 분석 기록을 삭제했습니다. 원본 녹음은 유지합니다.")}}){Text("분석 기록 삭제")}},dismissButton={TextButton(onClick={forgetTarget=null}){Text("취소")}})}
}

private fun callJobLabel(value:String)=when(value.uppercase()){
    "QUEUED"->"분석 대기";"TRANSCRIBING"->"음성 인식 중";"ANALYZING"->"후속 사항 정리 중"
    "COMPLETE"->"보고서 저장됨";"CANCELLED","PAUSED"->"중지됨";"INVALIDATED"->"분석 권한 또는 원본 변경"
    "FAILED","NEEDS_REVIEW","REVIEW_REQUIRED"->"확인 필요";"DEFERRED"->"기기 상태가 안정되면 재개";"PUBLISHING"->"보고서 저장 중";else->"처리 상태 확인"
}

private fun callModelProgress(job:CallFollowUpQueueItem):String? {
    if(job.status!=CallFollowUpQueueStatus.ANALYZING)return null
    val label=when(job.modelPhase){
        "LOAD"->"로컬 정리 모델 불러오는 중"
        "COUNT"->"원문 전체의 입력 길이 확인 중"
        "GENERATE"->when(job.nativePhase){
            "PREPARE"->"통화 문맥 준비 중"
            "SAMPLER_INIT"->"제안 출력 형식 준비 중"
            "PREFILL"->"통화 내용 읽는 중"+if(job.promptTotal!=null&&job.promptTotal>0)" (${job.promptDone?:0}/${job.promptTotal} 토큰)"else""
            "DECODE"->"일정·메모·메일 후속 후보 작성 중"
            else->"일정·메모·메일 후속 후보 작성 중"
        }
        "VALIDATE"->"원문 인용과 제안 형식 대조 중"
        "SUCCEEDED"->"이번 구간의 근거 확인 완료 · 다음 구간 준비 중"
        else->return null
    }
    val seconds=(job.attemptElapsedMs?:0L).coerceAtLeast(0L)/1000
    return if(seconds==0L)label else "$label · ${seconds/60}분 ${seconds%60}초 경과"
}

private fun callFailureDetail(code:String?):String?=when(code){
    "STAGE_TIME_LIMIT"->"정리 단계가 시간 한도에 걸렸습니다. 전사는 보존되어 이어 분석할 수 있습니다."
    "MODEL_EVIDENCE_INVALID"->"후속 후보가 원문 근거 검사를 통과하지 못했습니다. 전사와 원음을 확인해주세요."
    "MODEL_OUTPUT_INCOMPLETE"->"모델 출력이 끝나지 않아 제안으로 확정하지 않았습니다. 전사는 보존했습니다."
    "ANALYSIS_CONTRACT_CHANGED"->"분석 방식이 변경되어 기존 결과에 이어 붙이지 않았습니다. 별도의 새 분석이 필요합니다."
    else->null
}

/** A background call report never impersonates the foreground chat run. */
@Composable
fun CallFollowUpConversationStatus(app:AppContainer,threadId:String){
    var job by remember(threadId){mutableStateOf<CallFollowUpQueueItem?>(null)}
    var changing by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(app,threadId){while(isActive){
        try {job=app.callFollowUpPipeline.listJobs().firstOrNull {it.reportThreadId==threadId}}
        catch(cancel:CancellationException){throw cancel}catch(_:Exception){}
        delay(2_000)
    }}
    val current=job?:return
    if(current.status in setOf(CallFollowUpQueueStatus.COMPLETE,CallFollowUpQueueStatus.INVALIDATED))return
    Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),color=MaterialTheme.colorScheme.surfaceVariant){
        Row(Modifier.padding(horizontal=12.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Column(Modifier.weight(1f)){
                Text("통화 후속 · ${callJobLabel(current.status.name)}",style=MaterialTheme.typography.labelMedium)
                Text("음성 인식 ${current.completedChunks}구간 · 근거 분석 ${current.analyzedChunks}구간 · 아직 확인 전",style=MaterialTheme.typography.bodySmall)
                callModelProgress(current)?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                callFailureDetail(current.errorCode)?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
            }
            if(current.status!=CallFollowUpQueueStatus.REVIEW_REQUIRED||current.canRetry)TextButton(enabled=!changing,onClick={scope.launch {
                changing=true
                try {if(current.canRetry)app.callFollowUpPipeline.retry(current.queueId) else app.callFollowUpPipeline.cancel(current.queueId)
                    job=app.callFollowUpPipeline.listJobs().firstOrNull {it.queueId==current.queueId}}
                catch(cancel:CancellationException){throw cancel}catch(_:Exception){}finally {changing=false}
            }}){Text(if(current.canRetry)"이어 분석"else"중지")}
        }
    }
}
