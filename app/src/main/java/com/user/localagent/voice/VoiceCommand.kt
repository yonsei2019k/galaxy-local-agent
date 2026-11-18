package com.user.localagent.voice

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.TileService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.user.localagent.LocalAgentApplication
import com.user.localagent.MainActivity
import com.user.localagent.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID

/** Entry points share a lightweight voice/text request sheet, without opening the main workspace. */
object VoiceCommand {
    const val THREAD_TITLE="음성 명령"
    fun launch(context:Context){
        context.startActivity(Intent(context,VoiceCommandActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
}

/**
 * Small sheet over whatever app is in front: listens with the phone's on-device speech recognizer only
 * (audio never leaves the phone for recognition), shows the text, then hands it to the agent as an ordinary
 * chat request in a "음성 명령" thread. The agent keeps running in [AgentRunService] after the sheet closes;
 * tool policy, approvals and consent are exactly those of a typed request.
 */
class VoiceCommandActivity:ComponentActivity() {
    private var recognizer:SpeechRecognizer?=null
    private val phase=mutableStateOf("준비 중")
    private val heard=mutableStateOf("")
    private val finalText=mutableStateOf<String?>(null)
    private val countdown=mutableStateOf<Int?>(null)
    private val textMode=mutableStateOf(false)
    private val typedText=mutableStateOf("")
    private var autoSend:Job?=null
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted)listen() else phase.value="마이크 권한이 필요합니다."
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        textMode.value=intent?.getBooleanExtra("text_input",false)==true
        setContent{MaterialTheme(colorScheme=darkColorScheme()){Sheet()}}
        if(textMode.value){phase.value="요청을 입력하고 보내기를 눌러주세요.";return}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)listen()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Composable private fun Sheet(){
        Box(Modifier.fillMaxSize().imePadding(),contentAlignment=Alignment.BottomCenter){
            Surface(Modifier.fillMaxWidth().padding(12.dp),shape=MaterialTheme.shapes.large,tonalElevation=6.dp){
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("ZipHap 빠른 요청",style=MaterialTheme.typography.titleMedium)
                    Text(phase.value,style=MaterialTheme.typography.bodyMedium)
                    val shown=finalText.value?:heard.value
                    if(textMode.value)OutlinedTextField(value=typedText.value,onValueChange={if(it.length<=2000)typedText.value=it},
                        modifier=Modifier.fillMaxWidth(),label={Text("어떤 작업을 할까요?")},minLines=2,maxLines=5,enabled=!sent)
                    else if(shown.isNotBlank())Text(shown,style=MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        if(textMode.value){
                            Button(onClick={send()},enabled=typedText.value.isNotBlank()&&!sent){Text("보내기")}
                            OutlinedButton(onClick={startVoice()}){Text("말로 요청")}
                        }else if(finalText.value!=null){
                            Button(onClick={send()}){Text(countdown.value?.let{"보내기 ($it)"}?:"보내기")}
                            OutlinedButton(onClick={autoSend?.cancel();countdown.value=null;listen()}){Text("다시 말하기")}
                        }else if(recognizer!=null) OutlinedButton(onClick={recognizer?.stopListening()}){Text("말 끝")}
                        TextButton(onClick={autoSend?.cancel();finish()}){Text("취소")}
                    }
                    if(!textMode.value)TextButton(onClick={switchToText()}){Text("글로 입력")}
                }
            }
        }
    }

    private fun switchToText(){
        textMode.value=true;autoSend?.cancel();countdown.value=null
        typedText.value=(finalText.value?:heard.value).take(2000)
        recognizer?.destroy();recognizer=null
        phase.value="요청을 입력하고 보내기를 눌러주세요."
    }
    private fun startVoice(){
        textMode.value=false
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)listen()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun listen(){
        if(textMode.value)return
        recognizer?.destroy();recognizer=null
        finalText.value=null;heard.value=""
        if(Build.VERSION.SDK_INT<31||!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)){
            phase.value="기기 내 음성 인식을 쓸 수 없습니다. 휴대폰 설정에서 한국어 오프라인 음성 인식을 설치해주세요.";return
        }
        val created=SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer=created
        created.setRecognitionListener(object:RecognitionListener{
            override fun onReadyForSpeech(params:Bundle?){if(!textMode.value)phase.value="듣고 있어요. 말씀하세요."}
            override fun onBeginningOfSpeech(){}
            override fun onRmsChanged(rmsdB:Float){}
            override fun onBufferReceived(buffer:ByteArray?){}
            override fun onEndOfSpeech(){if(!textMode.value)phase.value="인식 중"}
            override fun onError(error:Int){
                if(textMode.value)return
                phase.value=when(error){
                    SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"말씀을 알아듣지 못했습니다. 다시 말하기를 눌러주세요."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"마이크 권한이 필요합니다."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE->"한국어 오프라인 음성 인식 언어 팩이 필요합니다."
                    else->"음성 인식 오류($error). 다시 시도해주세요."
                }
                finalText.value=heard.value.takeIf{it.isNotBlank()}
            }
            override fun onPartialResults(partialResults:Bundle?){
                if(textMode.value)return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let{heard.value=it}
            }
            override fun onResults(results:Bundle?){
                if(textMode.value)return
                val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if(text.isBlank()){phase.value="말씀을 알아듣지 못했습니다. 다시 말하기를 눌러주세요.";return}
                finalText.value=text.take(2000)
                phase.value="이대로 보낼까요?"
                autoSend?.cancel()
                autoSend=lifecycleScope.launch{for(i in 3 downTo 1){countdown.value=i;delay(1000)};countdown.value=null;send()}
            }
            override fun onEvent(eventType:Int,params:Bundle?){}
        })
        created.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
        phase.value="마이크 준비 중"
    }

    private var sent by mutableStateOf(false)
    private fun send(){
        val text=(if(textMode.value)typedText.value else finalText.value)?.takeIf{it.isNotBlank()}?:return
        if(sent)return;sent=true
        autoSend?.cancel()
        // A spoken "/command" is never a slash command; the agent treats it as text.
        val request=text.trimStart().removePrefix("/").trim()
        val app=(application as LocalAgentApplication).container
        val selectedModel=app.runs.preferredModelId.value
        lifecycleScope.launch{
            phase.value="에이전트에게 전달 중"
            val thread=withContext(Dispatchers.IO){app.repository.chat.createThread(null,VoiceCommand.THREAD_TITLE)}
            val runId=UUID.randomUUID().toString()
            val accepted=app.runs.submitVoiceCommand(request,thread.id,selectedModel,runId)
            if(!accepted){phase.value="지금은 요청을 받을 수 없습니다. 다른 작업이 끝난 뒤 다시 시도해주세요.";sent=false;return@launch}
            if(!RunKeepAlive.await(runId).active){
                phase.value="요청은 접수했지만 백그라운드 실행 유지를 확인하지 못했습니다. 자동으로 다시 보내지 않았습니다. 실행 상태를 확인해주세요."
                return@launch
            }
            finish()
        }
    }

    override fun onDestroy(){recognizer?.destroy();recognizer=null;super.onDestroy()}
}

/**
 * Holds a foreground service while any agent run is active, so the run keeps working when ZipHap goes behind
 * another app (screen control opening an app, a voice request, or the owner switching away). Without it,
 * Samsung's background freezer suspends the process within seconds and open PC/cloud connections abort.
 * Approvals and final answers become notifications only while ZipHap is not on screen; the approval itself
 * is still made in the app, as for typed requests.
 */
class AgentRunService:Service() {
    companion object {
        private const val CHANNEL="agent-run";private const val ALERTS="agent-run-alerts";private const val ID=7321
        /** Submission success is distinct from RunKeepAlive.await's actual startForeground receipt. */
        fun track(context:Context,runId:String,threadId:String,text:String):Boolean {
            val token=RunKeepAlive.begin(runId)?:return false
            return runCatching {
                ContextCompat.startForegroundService(context,Intent(context,AgentRunService::class.java)
                    .putExtra("runId",runId).putExtra("threadId",threadId).putExtra("text",text.take(200)).putExtra("keepAliveToken",token))
            }.onFailure{RunKeepAlive.acknowledge(runId,token,false,"FOREGROUND_START_REJECTED")}.isSuccess
        }
        internal fun appOnScreen():Boolean {
            val info=ActivityManager.RunningAppProcessInfo();ActivityManager.getMyMemoryState(info)
            return info.importance<=ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
    private class Tracked(val threadId:String,val text:String,val token:String?,val since:Long=System.currentTimeMillis()){var approvalNotified=false}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val runs=LinkedHashMap<String,Tracked>()
    private var job:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"에이전트 작업 진행",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERTS,"에이전트 승인·결과",NotificationManager.IMPORTANCE_HIGH))
        val runId=intent?.getStringExtra("runId");val threadId=intent?.getStringExtra("threadId")
        val token=intent?.getStringExtra("keepAliveToken")
        if(runId!=null&&threadId!=null)runs[runId]=Tracked(threadId,intent.getStringExtra("text").orEmpty(),token)
        val latest=runs.entries.lastOrNull()
        // startForeground must follow every startForegroundService, even one that brought nothing to track.
        try{ServiceCompat.startForeground(this,ID,ongoing(latest?.key,latest?.value),if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)}
        catch(_:RuntimeException){
            if(runId!=null&&token!=null)RunKeepAlive.acknowledge(runId,token,false)
            stopSelf();return START_NOT_STICKY
        }
        if(runId!=null&&token!=null)RunKeepAlive.acknowledge(runId,token,true)
        if(runs.isEmpty()){stopSelf();return START_NOT_STICKY}
        if(job?.isActive!=true)job=scope.launch{watch(manager)}
        return START_NOT_STICKY
    }

    private fun open(runId:String,threadId:String)=PendingIntent.getActivity(this,runId.hashCode(),
        Intent(this,MainActivity::class.java).putExtra("open_thread_id",threadId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun ongoing(runId:String?,run:Tracked?)=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_agent)
        .setContentTitle(if(runs.size>1)"ZipHap 작업 ${runs.size}개 진행 중" else "ZipHap 작업 중").setContentText(run?.text.orEmpty()).setOngoing(true)
        .also{b->if(runId!=null&&run!=null)b.setContentIntent(open(runId,run.threadId))}.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()

    private suspend fun watch(manager:NotificationManager){
        val app=(application as LocalAgentApplication).container
        while(runs.isNotEmpty()){
            delay(2000)
            val pending=app.runs.approval.value
            for((runId,run) in runs.entries.toList()){
                if(pending!=null&&pending.runId==runId){
                    if(!run.approvalNotified&&!appOnScreen()){
                        run.approvalNotified=true
                        alert(manager,runId,run,"승인이 필요합니다","눌러서 ZipHap에서 확인해주세요: "+(pending.reviewText?.lineSequence()?.firstOrNull{it.isNotBlank()}?:pending.call.name))
                    }
                }else run.approvalNotified=false
                val row=withContext(Dispatchers.IO){app.repository.chat.run(runId)}
                val age=System.currentTimeMillis()-run.since
                val done=if(row==null)age>60_000L else row.status !in setOf("queued","running","waiting_approval")
                if(done||age>30*60_000L){
                    runs.remove(runId)
                    run.token?.let{RunKeepAlive.acknowledge(runId,it,false,"FOREGROUND_TRACKING_ENDED")}
                    if(row!=null&&!appOnScreen()){
                        val answer=withContext(Dispatchers.IO){
                            app.repository.chat.messages(run.threadId).first().lastOrNull{it.runId==runId&&it.role=="assistant"}?.text.orEmpty()
                        }
                        alert(manager,runId,run,if(row.status=="completed")"작업 완료" else "작업이 멈췄습니다",answer.ifBlank{row.status}.take(400))
                    }
                    runs.entries.lastOrNull()?.let{manager.notify(ID,ongoing(it.key,it.value))}
                }
            }
        }
        stopSelf()
    }

    private fun alert(manager:NotificationManager,runId:String,run:Tracked,title:String,text:String){
        manager.notify(ID+1+(runId.hashCode() and 0xffff),NotificationCompat.Builder(this,ALERTS).setSmallIcon(R.drawable.ic_agent).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open(runId,run.threadId))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }
    override fun onDestroy(){
        runs.forEach{(id,run)->run.token?.let{RunKeepAlive.acknowledge(id,it,false,"FOREGROUND_SERVICE_STOPPED")}}
        scope.cancel();super.onDestroy()
    }
}

/** Quick-settings tile: pull down the shade from any app and tap to speak. */
class VoiceCommandTileService:TileService() {
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick(){
        val intent=Intent(this,VoiceCommandActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
