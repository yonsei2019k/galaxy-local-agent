package com.user.localagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.compute.PcTransport
import com.user.localagent.compute.PcLocalNetwork
import com.user.localagent.compute.PcPairingClient
import com.user.localagent.compute.PcCandidate
import com.user.localagent.compute.PcPairingChallenge
import kotlinx.coroutines.*

@Composable fun PcComputePanel(busy:Boolean,onStatus:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val settings=remember{PcComputeSettings.get(context)}
    val state by settings.state.collectAsState()
    var checking by remember{mutableStateOf(false)}
    var networkAllowed by remember{mutableStateOf(PcLocalNetwork.allowed(context))}
    var showPairing by remember{mutableStateOf(false)}
    var pendingDiscovery by remember{mutableStateOf(false)}
    fun checkConnection(){scope.launch {
        checking=true
        try {
            val connected=PcTransport(context).available()!=null
            networkAllowed=PcLocalNetwork.allowed(context)
            onStatus(if(connected)"PC 연결을 확인했습니다. 결과는 스마트폰에 저장합니다."else"PC 연결을 등록했습니다. PC가 응답해야 AI 작업을 실행합니다.")
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){onStatus("PC 연결을 확인하지 못했습니다. 연결 전에는 AI 작업을 실행하지 않습니다.")}
        finally{checking=false}
    }}
    val networkPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        networkAllowed=granted
        if(granted){if(pendingDiscovery)showPairing=true else checkConnection()}else onStatus("로컬 네트워크 권한을 허용해야 AI 작업을 실행할 수 있습니다.")
    }
    fun connect(discover:Boolean=!state.paired){
        pendingDiscovery=discover
        networkAllowed=PcLocalNetwork.allowed(context)
        if(networkAllowed){if(discover)showPairing=true else checkConnection()}else networkPermission.launch(PcLocalNetwork.PERMISSION)
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)scope.launch {
            checking=true
            try{settings.importPairing(uri);connect(false)}
            catch(cancel:CancellationException){throw cancel}catch(_:Exception){onStatus("PC 연결 파일을 확인하지 못했습니다.")}
            finally{checking=false}
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("내 PC의 계산 자원",style=MaterialTheme.typography.titleMedium)
        Text("작업·대화·기억·완성 파일은 스마트폰에서 관리합니다. 이미지에서 PC를 선택하면 연결 실패 시 멈추며 휴대폰으로 자동 전환하지 않습니다.",style=MaterialTheme.typography.bodySmall)
        Text(state.status,style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(enabled=!busy&&!checking,onClick={connect()},modifier=Modifier.testTag("pc_connect")){Text(if(checking)"확인 중"else if(state.paired)"연결 확인"else"내 PC 연결")}
            if(state.paired)TextButton(enabled=!busy&&!checking,onClick={connect(true)}){Text("다른 PC 연결")}
        }
        if(state.paired){
            Text("대화 엔진",style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=!state.claudeText,onClick={settings.claudeText(false)},enabled=!busy,label={Text("오픈소스 · PC")},modifier=Modifier.testTag("text_engine_local"))
                FilterChip(selected=state.claudeText,onClick={settings.claudeText(true)},enabled=!busy,label={Text("Claude")},modifier=Modifier.testTag("text_engine_claude"))
            }
            if(state.claudeText)Text("Claude를 고르면 대화 내용과 도구 결과가 인터넷을 거쳐 Anthropic으로 전송되고 API 요금이 듭니다. 음성과 사진 분석은 계속 PC에서 처리합니다.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=state.preferPcImages,onClick={settings.preferPcImages(true)},enabled=!busy,label={Text("이미지 · PC 정밀")})
                FilterChip(selected=!state.preferPcImages,onClick={settings.preferPcImages(false)},enabled=!busy,label={Text("이미지 · 휴대폰")})
            }
            PcImageModelSelector(state.imageModelId,{settings.imageModel(it)},enabled=!busy)
            Text("휴대폰 단독은 설치된 SDXL을 사용합니다. Qwen은 새 이미지에2512, 참고 사진 편집에Edit2511을 사용합니다. PC 모델 선택은 다음 작업부터 적용됩니다.",style=MaterialTheme.typography.bodySmall)
            TextButton(enabled=!busy&&!checking,onClick={settings.forget();onStatus("PC 연결을 해제했습니다.")}){Text("PC 연결 해제")}
        }
    }
    if(showPairing)PcPairingDialog(onDismiss={showPairing=false},onConnected={showPairing=false;checkConnection()},onImport={showPairing=false;picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))})
}

@Composable private fun PcPairingDialog(onDismiss:()->Unit,onConnected:()->Unit,onImport:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val client=remember{PcPairingClient(context)}
    var candidates by remember{mutableStateOf<List<PcCandidate>>(emptyList())}
    var challenge by remember{mutableStateOf<PcPairingChallenge?>(null)}
    var code by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("같은 와이파이의 PC를 찾습니다.")}
    fun scan(){scope.launch{
        busy=true;challenge=null;candidates=emptyList()
        try{candidates=client.discover();status=if(candidates.isEmpty())"PC를 찾지 못했습니다. PC 작업기가 켜져 있고 같은 공유기에 연결돼 있는지 확인해주세요."else"연결할 PC를 선택해주세요."}
        catch(cancel:CancellationException){throw cancel}catch(_:Exception){status="PC를 검색하지 못했습니다. 로컬 네트워크 권한과 PC 방화벽을 확인해주세요."}
        finally{busy=false}
    }}
    LaunchedEffect(Unit){scan()}
    AlertDialog(onDismissRequest=onDismiss,title={Text("내 PC 연결")},text={Column(Modifier.verticalScroll(rememberScrollState()).testTag("pc_pairing_dialog"),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("같은 공유기의 PC를 찾은 뒤, 그 PC에 표시되는 일회용 코드를 입력합니다. 한 번 연결하면 다음부터 자동으로 확인합니다.")
        Text(status)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(challenge==null)candidates.forEach{candidate->OutlinedButton(enabled=!busy,onClick={scope.launch{
            busy=true
            try{challenge=client.start(candidate);code="";status="PC에 뜬 연결 창의 12자리 코드를 입력해주세요. 코드는 5분 동안 유효합니다."}
            catch(cancel:CancellationException){throw cancel}catch(_:Exception){status="PC에서 연결 코드를 준비하지 못했습니다. 잠시 후 다시 시도해주세요."}
            finally{busy=false}
        }},modifier=Modifier.fillMaxWidth()){Text(candidate.title)}}
        challenge?.let{pending->
            OutlinedTextField(value=code,onValueChange={if(it.length<=18)code=it},label={Text("PC 연결 코드")},placeholder={Text("XXXX-XXXX-XXXX")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("pc_pairing_code"))
            Button(enabled=!busy&&code.replace("-","").count{!it.isWhitespace()}==12,onClick={scope.launch{
                busy=true
                try{client.finish(pending,code);onConnected()}
                catch(cancel:CancellationException){throw cancel}catch(_:Exception){status="연결 코드를 확인하지 못했습니다. PC의 코드와 유효 시간을 확인해주세요."}
                finally{busy=false}
            }},modifier=Modifier.fillMaxWidth().testTag("pc_pair_confirm")){Text("이 PC 연결")}
        }
        TextButton(enabled=!busy,onClick={scan()}){Text("PC 다시 찾기")}
        TextButton(enabled=!busy,onClick=onImport){Text("연결 파일 가져오기 · 고급")}
    }},confirmButton={TextButton(onClick=onDismiss){Text("닫기")}})
}

@Composable
fun PcImageModelSelector(selectedId:String,onSelect:(String)->Unit,enabled:Boolean=true){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("PC 이미지 모델",style=MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=selectedId==com.user.localagent.core.PcImageModelCatalog.SDXL_ID,
                onClick={onSelect(com.user.localagent.core.PcImageModelCatalog.SDXL_ID)},enabled=enabled,
                label={Text("SDXL")},modifier=Modifier.testTag("pc_image_model_sdxl"))
            FilterChip(selected=selectedId==com.user.localagent.core.PcImageModelCatalog.QUALITY_ID,
                onClick={onSelect(com.user.localagent.core.PcImageModelCatalog.QUALITY_ID)},enabled=enabled,
                label={Text("Qwen · 20B")},modifier=Modifier.testTag("pc_image_model_qwen"))
        }
    }
}
