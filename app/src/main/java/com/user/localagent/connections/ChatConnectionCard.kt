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
internal fun ChatConnectionCard(context:Context,window:Window,onClose:()->Unit,onStatus:(String)->Unit,modifier:Modifier=Modifier){
    val hub=remember(context){ConnectionHub.get(context)}
    val scope=rememberCoroutineScope()
    var serviceId by rememberSaveable {mutableStateOf(ConnectedService.entries.first().id)}
    val service=ConnectedService.entries.first {it.id==serviceId}
    var choosing by remember {mutableStateOf(false)}
    var pendingToken by rememberSaveable {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("")}
    var token by remember(service){mutableStateOf("")}
    var telegramChat by remember(service){mutableStateOf("")}
    fun publish(message:String){status=message;onStatus(message)}
    fun run(progress:String,action:suspend ()->String){
        if(busy)return
        busy=true
        publish(progress)
        scope.launch {
            try{publish(action())}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){publish("연결하지 못했어요. 계정 또는 연결 정보를 확인해 주세요.")}
            finally{busy=false}
        }
    }
    LaunchedEffect(service){
        status="연결 상태를 확인하고 있어요."
        val state=withContext(Dispatchers.IO){runCatching{hub.store.connection(service)}.getOrNull()}
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
    Surface(modifier,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh){
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("앱과 컴퓨터 연결",style=MaterialTheme.typography.titleLarge)
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
            if(service.google){
                Button(onClick={authorize(false)},enabled=available){Text("계정 연결 · 읽기")}
                if(ConnectionCatalog.tools.values.any{it.service==service&&it.writes})
                    OutlinedButton(onClick={authorize(true)},enabled=available){Text("계정 연결 · 읽기와 작성")}
            }else{
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
