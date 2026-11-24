package com.user.localagent.integrations

import android.content.Context
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.user.localagent.compute.BrowserAgentSettings
import kotlinx.coroutines.launch

/** Settings for the in-app browser agent plus a full-screen view where the owner logs in or watches the agent. */
@Composable fun AppBrowserPanel(context:Context){
    val settings=remember(context){BrowserAgentSettings.app(context)}
    var enabled by remember{mutableStateOf(settings.enabled())}
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("앱 브라우저 에이전트",style=MaterialTheme.typography.titleMedium)
        Text("에이전트가 ZipHap 앱 안의 브라우저에서 웹페이지를 열고 읽고 누를 수 있습니다(예약·장바구니·조회). 사이트 로그인은 아래 '앱 브라우저 열기'에서 직접 해 두세요. 로그인 정보와 쿠키는 이 휴대폰에만 남습니다. 비밀번호·카드·인증번호 칸은 에이전트가 입력하지 않고, 결제·주문·예약 확정·전송 버튼과 검색칸 밖 입력은 승인해야 실행됩니다. 결제 앱으로 넘어가는 단계는 직접 진행하세요.",style=MaterialTheme.typography.bodySmall)
        Row{Text("앱 브라우저 에이전트 사용",Modifier.weight(1f));Switch(enabled,onCheckedChange={value->settings.setEnabled(value);enabled=settings.enabled()},modifier=Modifier.testTag("app_browser_agent"))}
        OutlinedButton(onClick={AppWebAgent.get(context).showBrowser()}){Text("앱 브라우저 열기 · 로그인")}
    }
}

@Composable fun AppBrowserSurface(context:Context){
    val agent=remember(context){AppWebAgent.get(context)}
    val card by agent.ownerLoginCard.collectAsState()
    if(card!=null)return
    val opened by agent.presentedAddress.collectAsState()
    val initialAddress=opened?:return
    val busy by agent.active.collectAsState()
    val navigation by agent.navigationStatus.collectAsState()
    val ownerLogin by agent.waitingForOwnerLogin.collectAsState()
    val credentialPage by agent.showingCredentialPage.collectAsState()
    var printError by remember(initialAddress){mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    val scope=rememberCoroutineScope()
    var address by remember(initialAddress){mutableStateOf(initialAddress)}
    fun openAddress(){
        if(busy||address.isBlank())return
        keyboard?.hide()
        scope.launch {agent.openFromAddress(address)}
    }
    Dialog(onDismissRequest=agent::closeBrowser,properties=DialogProperties(
        usePlatformDefaultWidth=false,
        securePolicy=if(credentialPage)SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit)){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.fillMaxSize().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    OutlinedTextField(address,{address=it},Modifier.weight(1f),singleLine=true,label={Text("주소")},
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Go),
                        keyboardActions=KeyboardActions(onGo={openAddress()}))
                    Button(enabled=!busy&&address.isNotBlank(),onClick={openAddress()}){Text("열기")}
                }
                if(navigation.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                navigation.error?.let { code->
                    Text(when(code){
                        "URL_NOT_ALLOWED"->"웹사이트 주소를 확인해 주세요."
                        "HTTPS_REQUIRED"->"이 브라우저는 보안 연결 주소(https://)를 사용해요."
                        "TLS_ERROR"->"이 사이트의 보안 연결을 확인할 수 없어요."
                        "PAGE_TIMEOUT"->"페이지 응답이 늦어요. 다시 열어 주세요."
                        "NAVIGATION_BLOCKED"->"이 페이지에서 여는 외부 앱은 휴대폰에서 직접 열어 주세요."
                        "HTTP_ERROR"->"사이트에서 페이지를 열어 주지 않았어요."
                        else->"이 사이트에 연결할 수 없어요. 주소나 인터넷 연결을 확인해 주세요."
                    },color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    OutlinedButton(enabled=!busy,onClick={agent.view().let{if(it.canGoBack())it.goBack()}}){Text("뒤로")}
                    TextButton(onClick=agent::closeBrowser){Text("닫기")}
                    if(!busy && !credentialPage && ownerLogin==null && !navigation.loading && navigation.error==null)
                        TextButton(onClick={ printError=!agent.openPrintPreview(context) }){Text("화면 PDF 저장")}
                    val loginId=ownerLogin
                    if(loginId!=null)Button(onClick={agent.ownerFinishedBrowserLogin(loginId)}){Text("로그인했어요")}
                    else if(busy)Text("에이전트 작업 중 · 보기만 가능",style=MaterialTheme.typography.labelMedium)
                }
                if(printError)Text("현재 페이지의 PDF 저장 창을 열지 못했어요.",color=MaterialTheme.colorScheme.error)
                if(ownerLogin!=null)Text("사이트에서 직접 로그인한 다음 ‘로그인했어요’를 눌러 주세요.",
                    style=MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f).fillMaxWidth()){
                    AndroidView(factory={_->agent.view().also{(it.parent as? ViewGroup)?.removeView(it)}},Modifier.fillMaxSize(),
                        onRelease={agent.detach()})
                }
            }
        }
    }
}


@Composable
internal fun InlineBrowserLoginCard(agent:AppWebAgent,login:AppWebAgent.LoginCard,window:Window,modifier:Modifier=Modifier){
    SecureBrowserWindow(window)
    val scope=rememberCoroutineScope()
    val keyboard=LocalSoftwareKeyboardController.current
    val loginStatus by agent.ownerLoginStatus.collectAsState()
    var account by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var submitting by remember {mutableStateOf(false)}
    val ready=account.isNotBlank()&&password.isNotEmpty()&&!submitting
    fun submit(){
        if(!ready)return
        val enteredAccount=account
        val enteredPassword=password
        account=""
        password=""
        submitting=true
        keyboard?.hide()
        scope.launch {
            if(!agent.submitLoginCard(login.id,enteredAccount,enteredPassword)){
                submitting=false
            }
        }
    }
    Surface(modifier,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh){
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(if(login.pairedPc)"전용 PC 브라우저 로그인" else "사이트 로그인",style=MaterialTheme.typography.titleLarge)
                Text(login.host,style=MaterialTheme.typography.titleSmall)
                Text(if(login.pairedPc)"입력한 내용은 연결한 내 PC를 거쳐 이 사이트에 제출돼요. 채팅에는 남지 않아요."
                    else "입력 내용은 이 사이트 로그인에만 사용해요.",style=MaterialTheme.typography.bodySmall)
                loginStatus?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                OutlinedTextField(account,{if(it.length<=320)account=it},Modifier.fillMaxWidth(),singleLine=true,
                    enabled=!submitting,label={Text("아이디")},
                    keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Next))
                OutlinedTextField(password,{if(it.length<=1024)password=it},Modifier.fillMaxWidth(),singleLine=true,
                    enabled=!submitting,label={Text("비밀번호")},visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Go),
                    keyboardActions=KeyboardActions(onGo={submit()}))
                Button(onClick={submit()},modifier=Modifier.fillMaxWidth(),enabled=ready){
                    Text(if(submitting)"로그인 확인 중" else "로그인하고 계속")
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    if(!login.pairedPc)TextButton(onClick={agent.switchToWebsiteLogin(login.id)},enabled=!submitting){Text("사이트에서 로그인")}
                    TextButton(onClick={agent.cancelLoginCard(login.id)},enabled=!submitting){Text("취소")}
                }
            }
    }
}


@Composable
internal fun InlineWebsiteLogin(agent:AppWebAgent,request:AppWebAgent.WebsiteLogin,window:Window,modifier:Modifier=Modifier){
    SecureBrowserWindow(window)
    val scope=rememberCoroutineScope()
    val navigation by agent.navigationStatus.collectAsState()
    val loginStatus by agent.ownerLoginStatus.collectAsState()
    var reload by remember {mutableIntStateOf(0)}
    var visible by remember {mutableStateOf(false)}
    var checking by remember {mutableStateOf(false)}
    var returned by remember {mutableStateOf(false)}
    LaunchedEffect(request.card.id,reload){
        visible=false
        visible=agent.loadWebsiteLogin(request.card.id)
        if(!visible)return@LaunchedEffect
        var sawLoginForm=false
        var readbacks=0
        while(!returned){
            if(!checking){
                when(agent.websiteLoginFormPresent(request.card.id)){
                    true->{sawLoginForm=true;readbacks=0}
                    false->if(sawLoginForm&&++readbacks>=2){
                        checking=true
                        val confirmed=try{agent.finishWebsiteLogin(request.card.id)}finally{checking=false}
                        if(confirmed)return@LaunchedEffect
                        readbacks=0
                    }
                    null->readbacks=0
                }
            }
            kotlinx.coroutines.delay(500)
        }
    }
    fun goBack(){
        if(returned)return
        returned=true
        scope.launch {agent.returnToLoginCard(request.card.id)}
    }
    androidx.activity.compose.BackHandler {goBack()}
    Surface(modifier,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh){
        Column(Modifier.fillMaxSize().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(request.card.host,style=MaterialTheme.typography.titleSmall)
            loginStatus?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
            if(navigation.loading||checking||returned)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(navigation.error!=null)Text(when(navigation.error){
                "HTTPS_REQUIRED"->"사이트가 보안 연결을 제공하지 않는 페이지로 이동했어요."
                "TLS_ERROR"->"사이트의 보안 연결을 확인할 수 없어요."
                "PAGE_TIMEOUT"->"사이트 응답이 늦어요. 다시 열 수 있어요."
                "NAVIGATION_BLOCKED"->"로그인 중 다른 사이트로의 이동을 멈췄어요."
                else->"사이트에 연결할 수 없어요. 다시 열거나 로그인 입력창으로 돌아가세요."
            },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            Box(Modifier.weight(1f).fillMaxWidth()){
                AndroidView(factory={_->agent.view().also{(it.parent as? ViewGroup)?.removeView(it)}},
                    modifier=Modifier.fillMaxSize(),onRelease={agent.detach()})
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                TextButton(onClick={goBack()},enabled=!returned){Text("입력창으로")}
                TextButton(onClick={reload++},enabled=!navigation.loading&&!checking&&!returned){Text("다시 열기")}
                TextButton(onClick={
                    checking=true
                    scope.launch {try{agent.finishWebsiteLogin(request.card.id)}finally{checking=false}}
                },enabled=visible&&!navigation.loading&&!checking&&!returned){Text("로그인 확인")}
            }
        }
    }
}

@Composable
private fun SecureBrowserWindow(window:Window){
    DisposableEffect(window){
        val flag=WindowManager.LayoutParams.FLAG_SECURE
        val alreadySecure=(window.attributes.flags and flag)!=0
        if(!alreadySecure)window.addFlags(flag)
        onDispose {if(!alreadySecure)window.clearFlags(flag)}
    }
}


@Composable
internal fun InlineWebsiteWorkCard(agent:AppWebAgent,work:AppWebAgent.WebsiteWork,modifier:Modifier=Modifier){
    Surface(modifier,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh){
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(4.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                Text(work.host,style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                TextButton(onClick=agent::dismissWebsiteWork){Text("닫기")}
            }
            Text(work.status,style=MaterialTheme.typography.bodyMedium)
            if(work.working)LinearProgressIndicator(Modifier.fillMaxWidth())
            work.steps.takeLast(3).forEach {step->
                Text("• "+step,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
