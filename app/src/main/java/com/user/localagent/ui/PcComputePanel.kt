package com.user.localagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

@Composable fun PcComputePanel(busy:Boolean,
    loadQaLessons:(suspend ()->Map<com.user.localagent.data.RemoteQaLesson,Long>)?=null,onStatus:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val settings=remember{PcComputeSettings.get(context)}
    val state by settings.state.collectAsState()
    var checking by remember{mutableStateOf(false)}
    var networkAllowed by remember{mutableStateOf(PcLocalNetwork.allowed(context))}
    var showPairing by remember{mutableStateOf(false)}
    var showConnectionOptions by rememberSaveable{mutableStateOf(false)}
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
        Text(if(state.paired)"연결을 등록했어요" else "아직 연결된 컴퓨터가 없어요.",style=MaterialTheme.typography.titleMedium)
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
            OutlinedButton(enabled=!busy&&!checking,onClick={connect()},modifier=Modifier.testTag("pc_connect")){Text(if(checking)"확인 중"else if(state.paired)"연결 확인"else"컴퓨터 연결")}
            if(state.paired)TextButton(enabled=!busy&&!checking,onClick={connect(true)}){Text("다른 컴퓨터 연결")}
        }
        if(state.paired){
            TextButton(onClick={showConnectionOptions=!showConnectionOptions}){
                Text(if(showConnectionOptions)"세부 설정 접기"else"세부 설정 더 보기")
            }
            if(showConnectionOptions){
                Text(state.status,style=MaterialTheme.typography.bodySmall)
                Text("기존 PC 답변 연결",style=MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    FilterChip(selected=!state.claudeText,onClick={settings.claudeText(false)},enabled=!busy,label={Text("내 PC")},modifier=Modifier.testTag("text_engine_local"))
                    FilterChip(selected=state.claudeText,onClick={settings.claudeText(true)},enabled=!busy,label={Text("Claude")},modifier=Modifier.testTag("text_engine_claude"))
                }
                if(state.claudeText)Text("Claude를 쓰면 대화와 작업 결과가 Anthropic으로 전송되고 별도 요금이 듭니다.",style=MaterialTheme.typography.bodySmall)
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                    FilterChip(selected=state.preferPcImages,onClick={settings.preferPcImages(true)},enabled=!busy,label={Text("컴퓨터에서 만들기")})
                    FilterChip(selected=!state.preferPcImages,onClick={settings.preferPcImages(false)},enabled=!busy,label={Text("휴대폰에서 만들기")})
                }
                PcImageModelSelector(state.imageModelId,{settings.imageModel(it)},enabled=!busy)
                SettingsDisclosure("연결 시험 도구","문제가 있을 때 시험 요청과 기록을 확인해요."){
                    val remote=remember{com.user.localagent.compute.PcRemoteTestSettings.get(context)}
                    var remoteOn by remember{mutableStateOf(remote.enabled())}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Column(Modifier.weight(1f)){
                            Text("컴퓨터에서 시험 요청 받기",style=MaterialTheme.typography.labelLarge)
                            Text("집합이 실행 중일 때 연결된 PC가 시험 요청을 보내고 결과를 받아요. 승인할 작업은 휴대폰에서 확인해요.",style=MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked=remoteOn,onCheckedChange={on->
                            remote.setEnabled(on)
                            remoteOn=remote.enabled()
                            onStatus(if(on)"PC 원격 시험 요청을 허용했습니다." else "PC 원격 시험 요청을 껐습니다.")
                        },modifier=Modifier.testTag("pc_remote_test"))
                    }
                    if(loadQaLessons!=null){
                        var lessons by remember { mutableStateOf<Map<com.user.localagent.data.RemoteQaLesson,Long>?>(null) }
                        TextButton(onClick={scope.launch {
                            try {lessons=loadQaLessons()}
                            catch(cancel:CancellationException){throw cancel}
                            catch(_:Exception){onStatus("시험 교훈을 불러오지 못했습니다.")}
                        }}){Text("시험 교훈 보기")}
                        lessons?.let{items->
                            if(items.isEmpty())Text("보관된 시험에서 확인된 교훈이 없습니다.")
                            else items.forEach{(lesson,count)->Text("${lesson.guidance} · 관련 시험 ${count}건",style=MaterialTheme.typography.bodySmall)}
                        }
                    }
                }
                com.user.localagent.voice.BackgroundRunExemptionRow(onStatus)
                val browser=remember{com.user.localagent.compute.BrowserAgentSettings.pc(context)}
                var browserOn by remember{mutableStateOf(browser.enabled())}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Column(Modifier.weight(1f)){
                        Text("PC에서 웹 작업",style=MaterialTheme.typography.labelLarge)
                        Text("PC 화면에 창을 띄우지 않고 웹 작업을 해요. 로그인과 인증은 휴대폰에서 직접 진행해요. 결제·주문·예약 확정·전송이나 검색 외 입력도 휴대폰에서 승인해요.",style=MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked=browserOn,onCheckedChange={on->browser.setEnabled(on);browserOn=browser.enabled()},modifier=Modifier.testTag("pc_browser_agent"))
                }
                TextButton(enabled=!busy&&!checking,onClick={settings.forget();onStatus("PC 연결을 해제했습니다.")}){Text("PC 연결 해제")}
            }
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
            FilterChip(selected=selectedId==com.user.localagent.core.PcImageModelCatalog.FAST_ID,
                onClick={onSelect(com.user.localagent.core.PcImageModelCatalog.FAST_ID)},enabled=enabled,
                label={Text("FLUX · 4B")},modifier=Modifier.testTag("pc_image_model_flux"))
        }
        Text("FLUX는 기본 4단계, Qwen은 기본 50단계입니다. 실제 처리 시간은 PC 자원에 따라 달라집니다.",style=MaterialTheme.typography.bodySmall)
    }
}
