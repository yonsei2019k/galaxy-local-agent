package com.user.localagent.connections

import android.app.Activity
import android.content.Context
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.core.connections.ConnectedService
import com.user.localagent.core.connections.ConnectionCatalog
import kotlinx.coroutines.*

@Composable
internal fun ChatConnectionCard(context:Context,window:Window,onClose:()->Unit,onStatus:(String)->Unit,
    onConnectNol:suspend ()->Boolean,modifier:Modifier=Modifier){
    val hub=remember(context){ConnectionHub.get(context)}
    val scope=rememberCoroutineScope()
    var serviceId by rememberSaveable {mutableStateOf(ConnectedService.entries.first().id)}
    val service=ConnectedService.entries.first {it.id==serviceId}
    var choosing by remember {mutableStateOf(false)}
    var nolDetails by rememberSaveable {mutableStateOf(false)}
    var pendingToken by rememberSaveable {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("")}
    var token by remember(service){mutableStateOf("")}
    var telegramChat by remember(service){mutableStateOf("")}
    var naverAccount by remember(service){mutableStateOf("")}
    var knownConnection by remember(service){mutableStateOf<LocalConnection?>(null)}
    var replaceConnection by remember(service){mutableStateOf(false)}
    val naverMail=service==ConnectedService.NAVER_MAIL
    fun publish(message:String){status=message;onStatus(message)}
    fun run(progress:String,action:suspend ()->String){
        if(busy)return
        busy=true
        publish(progress)
        scope.launch {
            try{
                val message=action()
                knownConnection=withContext(Dispatchers.IO){hub.store.connection(service)}
                if(knownConnection!=null)replaceConnection=false
                publish(message)
            }
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){publish("연결하지 못했어요. 계정 또는 연결 정보를 확인해 주세요.")}
            finally{busy=false}
        }
    }
    LaunchedEffect(service){
        status="연결 상태를 확인하고 있어요."
        val state=withContext(Dispatchers.IO){runCatching{hub.store.connection(service)}.getOrNull()}
        knownConnection=state
        status=when {
            state==null->"연결 전"
            state.verifiedAt==0L->"계정 권한이 연결됐어요. 자료 조회는 확인 전이에요."
            else->"이 계정으로 자료를 조회한 기록이 있어요."
        }
    }
    if(!service.google)DisposableEffect(window){
        val flag=WindowManager.LayoutParams.FLAG_SECURE
        val alreadySecure=(window.attributes.flags and flag)!=0
        if(!alreadySecure)window.addFlags(flag)
        onDispose{if(!alreadySecure)window.clearFlags(flag)}
    }
    val google=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->
        val saved=pendingToken
        pendingToken=null
        val data=result.data
        val request=if(result.resultCode==Activity.RESULT_OK&&data!=null)saved?.let{GoogleConsentRequests.store.claim(it)}
            else {GoogleConsentRequests.store.cancel(saved);null}
        val confirmedService=ConnectedService.entries.firstOrNull{it.id==request?.serviceId&&it.google}
        if(result.resultCode!=Activity.RESULT_OK||data==null)publish("Google 계정 연결을 취소했어요.")
        else if(request==null||confirmedService==null)publish("연결 요청이 만료됐어요. 다시 선택해 주세요.")
        else run("Google 권한을 확인하고 있어요."){
            val authorization=hub.google.fromIntent(data)
            withContext(Dispatchers.IO){hub.google.accept(confirmedService,request.write,authorization);hub.clearCache()}
            "계정 권한이 연결됐어요. 채팅에서 자료 조회를 요청할 수 있어요."
        }
    }
    val pair=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)run("PC 연결 파일을 확인하고 있어요."){
            withContext(Dispatchers.IO){PcComputeSettings.get(context).importPairing(uri)}
            "PC 연결 파일을 저장했어요. 연결 확인을 눌러 상태를 확인할 수 있어요."
        }
    }
    fun authorize(write:Boolean)=run("Google 권한을 확인하고 있어요."){
        val result=hub.google.authorize(service,write)
        if(result.hasResolution()){
            val request=GoogleConsentRequests.store.begin(service.id,write)
            pendingToken=request.token
            try{google.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent)).build())}
            catch(error:Exception){GoogleConsentRequests.store.cancel(request.token);pendingToken=null;throw error}
            "Google 권한 화면에서 선택한 뒤 채팅으로 돌아와 주세요."
        }else{
            withContext(Dispatchers.IO){hub.google.accept(service,write,result);hub.clearCache()}
            "계정 권한이 연결됐어요. 채팅에서 자료 조회를 요청할 수 있어요."
        }
    }
    val available=!busy&&pendingToken==null
    if(nolDetails){
        Surface(modifier,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh){
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)){
                Text("야놀자(NOL)",style=MaterialTheme.typography.titleLarge)
                Text("숙소와 여행 상품을 찾을 때는 바로 검색해요. 내 예약을 보거나 회원 혜택을 확인할 때 계정을 연결해요.")
                Text("로그인은 NOL의 공식 화면에서 진행해요. 가입 방식이 기억나지 않으면 계정 찾기를 같이 볼 수 있어요.")
                Text("예약을 확정하기 전에 일정, 최종 금액과 취소 조건을 확인해요.")
                status.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                Button(enabled=available,onClick={run("채팅에 로그인 화면을 준비하고 있어요."){
                    if(onConnectNol())"" else "다른 로그인 작업이 열려 있어요. 그 작업을 마치거나 대기를 끝낸 뒤 다시 눌러 주세요."
                }}){Text("채팅에서 연결")}
                TextButton(enabled=available,onClick={nolDetails=false;status=""}){Text("연결 목록으로")}
            }
        }
        return
    }
    Surface(modifier,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh){
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("앱과 컴퓨터 연결",style=MaterialTheme.typography.titleLarge)
            OutlinedButton(enabled=available,onClick={nolDetails=true;status=""}){Text("야놀자(NOL)") }
            Box {
                OutlinedButton(onClick={choosing=true},enabled=available){Text(service.label)}
                DropdownMenu(expanded=choosing,onDismissRequest={choosing=false}){
                    ConnectedService.entries.forEach { item ->
                        DropdownMenuItem(text={Text(item.label)},onClick={serviceId=item.id;choosing=false})
                    }
                }
            }
            Text(status,style=MaterialTheme.typography.bodyMedium)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(knownConnection!=null&&!replaceConnection){
                Text("저장된 계정을 다음 작업에도 사용해요.",style=MaterialTheme.typography.bodyMedium)
                if(service.google&&knownConnection?.writeAllowed==false&&ConnectionCatalog.tools.values.any{it.service==service&&it.writes})
                    OutlinedButton(onClick={authorize(true)},enabled=available){Text("작성 권한 추가")}
                TextButton(onClick={replaceConnection=true},enabled=available){Text("연결 다시 설정")}
            }else if(service.google){
                Button(onClick={authorize(false)},enabled=available){Text("계정 연결 · 읽기")}
                if(ConnectionCatalog.tools.values.any{it.service==service&&it.writes})
                    OutlinedButton(onClick={authorize(true)},enabled=available){Text("계정 연결 · 읽기와 작성")}
            }else if(service.outlook){
                key(service){OutlookConnectCard(hub,service){publish("Microsoft 권한이 연결됐어요. 채팅에서 자료 조회를 요청해 주세요.")}}
            }else if(naverMail){
                Text("메일을 읽을 때만 연결해요. 네이버 검색은 연결 없이 쓸 수 있어요.")
                OutlinedTextField(naverAccount,{if(it.length<=74)naverAccount=it},Modifier.fillMaxWidth(),singleLine=true,
                    enabled=available,label={Text("네이버 메일 아이디")},
                    keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Email))
                OutlinedTextField(token,{if(it.length<=128)token=it},Modifier.fillMaxWidth(),singleLine=true,
                    enabled=available,label={Text("네이버 앱 비밀번호")},visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                Button(enabled=available&&naverAccount.isNotBlank()&&token.isNotBlank(),onClick={
                    val account=naverAccount.trim()
                    val secret=token
                    token=""
                    run("네이버 메일 연결을 확인하고 있어요."){
                        hub.privateReads.connectNaver(account,secret)
                        hub.clearCache()
                        "네이버 메일을 연결했어요. 다음에도 이 연결을 사용할게요."
                    }
                }){Text("네이버 메일 연결")}
            }else{
                if(service==ConnectedService.GITHUB)Text("조회할 저장소에 이슈 읽기 권한을 준 GitHub 토큰을 입력해 주세요.")
                if(service==ConnectedService.SLACK)Text("채널 기록 읽기 권한이 있는 Slack 토큰을 입력해 주세요. 봇을 쓰면 먼저 그 채널에 초대해 주세요.")
                OutlinedTextField(token,{token=it},Modifier.fillMaxWidth(),singleLine=true,enabled=available,
                    label={Text("연결 토큰")},visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                if(service==ConnectedService.TELEGRAM)
                    OutlinedTextField(telegramChat,{telegramChat=it},Modifier.fillMaxWidth(),singleLine=true,
                        enabled=available,label={Text("허용할 대화 ID")})
                Button(enabled=available&&token.isNotBlank(),onClick={
                    val secret=token
                    token=""
                    run("서비스에서 연결 정보를 확인하고 있어요."){
                        hub.configureToken(service,secret,telegramChat.takeIf{it.isNotBlank()})
                        "연결 정보를 서비스에서 확인하고 저장했어요."
                    }
                }){Text("연결 확인하고 저장")}
            }
            OutlinedButton(enabled=available,onClick={pair.launch(arrayOf("application/json"))}){Text("PC 연결 파일 선택")}
            OutlinedButton(enabled=available,onClick={run("PC에 연결하는 중이에요."){hub.pc.verify()}}){Text("PC 연결 확인")}
            TextButton(enabled=available,onClick=onClose){Text("채팅 입력으로 돌아가기")}
        }
    }
}
