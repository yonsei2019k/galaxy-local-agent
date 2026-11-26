package com.user.localagent.integrations

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
    val browser=remember(context){AppWebAgent.get(context)}
    val scope=rememberCoroutineScope()
    var vaultOpen by remember{mutableStateOf(false)}
    var savedSites by remember{mutableStateOf<List<java.net.URI>>(emptyList())}
    var vaultStatus by remember{mutableStateOf<String?>(null)}
    var accessHistory by remember{mutableStateOf<List<Pair<String,Long>>>(emptyList())}
    fun loadVault(){scope.launch {
        try {savedSites=browser.savedWebsiteOrigins();accessHistory=browser.websiteAccessHistory();vaultStatus=null}
        catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
        catch(_:Exception){vaultStatus="저장한 로그인을 확인할 수 없어요."}
    }}
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("앱 브라우저 에이전트",style=MaterialTheme.typography.titleMedium)
        Text("에이전트가 ZipHap 앱 안의 브라우저에서 웹페이지를 열고 읽고 누를 수 있습니다(예약·장바구니·조회). 사이트 로그인은 아래 '앱 브라우저 열기'에서 직접 해 두세요. 로그인 정보와 쿠키는 이 휴대폰에만 남습니다. 비밀번호·카드·인증번호 칸은 에이전트가 입력하지 않고, 결제·주문·예약 확정·전송 버튼과 검색칸 밖 입력은 승인해야 실행됩니다. 결제 앱으로 넘어가는 단계는 직접 진행하세요.",style=MaterialTheme.typography.bodySmall)
        Row{Text("앱 브라우저 에이전트 사용",Modifier.weight(1f));Switch(enabled,onCheckedChange={value->settings.setEnabled(value);enabled=settings.enabled()},modifier=Modifier.testTag("app_browser_agent"))}
        OutlinedButton(onClick={browser.showBrowser()}){Text("앱 브라우저 열기 · 로그인")}
        TextButton(onClick={vaultOpen=!vaultOpen;if(vaultOpen)loadVault()}){Text(if(vaultOpen)"저장한 로그인 닫기" else "저장한 로그인")}
        if(vaultOpen){
            if(savedSites.isEmpty()&&vaultStatus==null)Text("이 휴대폰에 저장한 로그인이 없어요.")
            for(site in savedSites)Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){
                Text(site.host,Modifier.weight(1f))
                TextButton(onClick={scope.launch {
                    try{browser.removeSavedWebsite(site);savedSites=browser.savedWebsiteOrigins();vaultStatus=null}
                    catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
                    catch(_:Exception){vaultStatus="저장한 로그인을 지우지 못했어요."}
                }}){Text("삭제")}
            }
            if(accessHistory.isNotEmpty()){
                Text("최근 로그인 사용 시도",style=MaterialTheme.typography.titleSmall)
                val format=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT)
                for((site,at) in accessHistory)Text("$site · ${format.format(java.util.Date(at))}",style=MaterialTheme.typography.bodySmall)
            }
            vaultStatus?.let{Text(it)}
        }
    }
}

@Composable fun AppBrowserSurface(context:Context){
    val agent=remember(context){AppWebAgent.get(context)}
    val scope=rememberCoroutineScope()
    var printError by remember {mutableStateOf(false)}
    var pdfTicket by rememberSaveable {mutableStateOf<String?>(null)}
    var exportingPdf by remember {mutableStateOf(false)}
    val pdfWrite by agent.browserPdfWrite.collectAsState()
    val savePdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){destination->
        val ticket=pdfTicket
        if(destination==null){
            ticket?.let(agent::cancelBrowserPdf)
            pdfTicket=null
            exportingPdf=false
        }else if(ticket==null||!agent.writeBrowserPdf(ticket,destination)){
            pdfTicket=null
            exportingPdf=false
            printError=true
            android.widget.Toast.makeText(context,"저장할 화면 내용을 다시 준비해 주세요.",android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(pdfTicket,pdfWrite){
        val result=pdfWrite?.takeIf{it.ticket==pdfTicket&&it.phase!=AppWebAgent.PdfWritePhase.WRITING}
            ?:return@LaunchedEffect
        printError=result.phase==AppWebAgent.PdfWritePhase.FAILED
        pdfTicket=null
        exportingPdf=false
        agent.acknowledgeBrowserPdf(result.ticket)
        val message=if(printError)"PDF 저장을 완료하지 못했어요." else "화면 PDF를 저장했어요."
        android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show()
    }
    val card by agent.ownerLoginCard.collectAsState()
    if(card!=null)return
    val opened by agent.presentedAddress.collectAsState()
    val initialAddress=opened?:return
    val busy by agent.active.collectAsState()
    val navigation by agent.navigationStatus.collectAsState()
    val ownerLogin by agent.waitingForOwnerLogin.collectAsState()
    val credentialPage by agent.showingCredentialPage.collectAsState()
    val paymentPage by agent.showingPaymentPage.collectAsState()
    var paymentError by remember { mutableStateOf(false) }
    val keyboard=LocalSoftwareKeyboardController.current
    var address by remember(initialAddress){mutableStateOf(initialAddress)}
    fun openAddress(){
        if(busy||address.isBlank())return
        keyboard?.hide()
        scope.launch {agent.openFromAddress(address)}
    }
    Dialog(onDismissRequest=agent::closeBrowser,properties=DialogProperties(
        usePlatformDefaultWidth=false,
        securePolicy=SecureFlagPolicy.SecureOn)){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.fillMaxSize().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                if(!paymentPage)Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
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
                    if(!busy && !credentialPage && !paymentPage && ownerLogin==null && !navigation.loading && navigation.error==null)
                        TextButton(enabled=!exportingPdf&&pdfTicket==null&&pdfWrite?.phase!=AppWebAgent.PdfWritePhase.WRITING,onClick={
                            exportingPdf=true
                            printError=false
                            scope.launch {
                                val ticket=agent.prepareBrowserPdf()
                                if(ticket==null){printError=true;exportingPdf=false}
                                else{
                                    pdfTicket=ticket
                                    try{savePdf.launch("ZipHap 화면 텍스트.pdf")}
                                    catch(_:android.content.ActivityNotFoundException){
                                        agent.cancelBrowserPdf(ticket)
                                        pdfTicket=null
                                        printError=true
                                        exportingPdf=false
                                    }
                                }
                            }
                        }){Text("화면 텍스트 PDF 저장")}
                    val loginId=ownerLogin
                    if(loginId!=null)Button(onClick={agent.ownerFinishedBrowserLogin(loginId)}){Text("로그인했어요")}
                    else if(busy)Text("에이전트 작업 중 · 보기만 가능",style=MaterialTheme.typography.labelMedium)
                }
                if(paymentPage&&!busy){
                    val activity=context as? android.app.Activity
                    if(activity!=null)OutlinedButton(onClick={paymentError=!agent.openOwnerPayment(activity,null,null,null)}){
                        Text("공식 결제 앱 열기")
                    }
                    if(paymentError)Text("결제 화면에서 카드사를 선택한 뒤 다시 눌러 주세요. 모니모 앱이 설치되어 있어야 해요.")
                }
                if(printError)Text("현재 페이지에서 확인한 내용을 PDF로 저장하지 못했어요.",color=MaterialTheme.colorScheme.error)
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
internal fun InlineBrowserLoginCard(agent:AppWebAgent,login:AppWebAgent.LoginCard,window:Window,
    modifier:Modifier=Modifier,keyboardOpen:Boolean=false){
    SecureBrowserWindow(window)
    androidx.activity.compose.BackHandler(enabled=true){agent.foldOwnerLogin(login.id)}
    val scope=rememberCoroutineScope()
    val keyboard=LocalSoftwareKeyboardController.current
    val loginStatus by agent.ownerLoginStatus.collectAsState()
    var account by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var submitting by remember {mutableStateOf(false)}
    var saveOnPhone by remember {mutableStateOf(false)}
    var hasSaved by remember(login.id){mutableStateOf(false)}
    var authPending by remember(login.id){mutableStateOf(false)}
    val keyguard=LocalContext.current.getSystemService(KeyguardManager::class.java)
    LaunchedEffect(login.id){hasSaved=agent.hasSavedWebsiteLogin(login.id)}
    val ownerAuth=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val wasPending=authPending
        authPending=false
        if(wasPending&&result.resultCode==Activity.RESULT_OK){
            submitting=true
            account="";password=""
            scope.launch {try{agent.submitSavedWebsiteLogin(login.id)}finally{submitting=false}}
        }
    }
    @Suppress("DEPRECATION")
    fun useSaved(){
        val confirmation=keyguard?.createConfirmDeviceCredentialIntent("저장된 로그인 사용",login.host)?:return
        authPending=true
        keyboard?.hide()
        try{ownerAuth.launch(confirmation)}catch(_:android.content.ActivityNotFoundException){authPending=false}
    }
    val accountTarget=remember {BringIntoViewRequester()}
    val passwordTarget=remember {BringIntoViewRequester()}
    var focusedField by remember {mutableIntStateOf(0)}
    var cardHeight by remember {mutableIntStateOf(0)}
    LaunchedEffect(keyboardOpen,focusedField,cardHeight){
        if(keyboardOpen&&focusedField!=0){
            withFrameNanos { }
            if(focusedField==1)accountTarget.bringIntoView() else passwordTarget.bringIntoView()
        }
    }
    val ready=account.isNotBlank()&&password.isNotEmpty()&&!submitting&&!authPending
    fun submit(){
        if(!ready)return
        val enteredAccount=account
        val enteredPassword=password
        val keep=saveOnPhone
        account=""
        password=""
        submitting=true
        keyboard?.hide()
        scope.launch {
            if(!agent.submitLoginCard(login.id,enteredAccount,enteredPassword,keep)){
                submitting=false
            }
        }
    }
    Surface(modifier.onSizeChanged {cardHeight=it.height},shape=MaterialTheme.shapes.large,
        color=MaterialTheme.colorScheme.surfaceContainerHigh){
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(if(login.pairedPc)"전용 PC 브라우저 로그인" else "사이트 로그인",style=MaterialTheme.typography.titleMedium)
                Text(login.host,style=MaterialTheme.typography.bodySmall)
                if(login.pairedPc)Text("연결한 PC를 거쳐 이 사이트에 제출해요.",style=MaterialTheme.typography.bodySmall)
                loginStatus?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                if(hasSaved&&keyguard?.isDeviceSecure==true)OutlinedButton(onClick={useSaved()},enabled=!submitting&&!authPending){
                    Text("저장된 로그인 사용")
                }
                OutlinedTextField(account,{if(it.length<=320)account=it},Modifier.fillMaxWidth()
                    .bringIntoViewRequester(accountTarget).onFocusChanged { if(it.isFocused)focusedField=1 },singleLine=true,
                    enabled=!submitting,label={Text("아이디")},
                    keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Next))
                OutlinedTextField(password,{if(it.length<=1024)password=it},Modifier.fillMaxWidth()
                    .bringIntoViewRequester(passwordTarget).onFocusChanged { if(it.isFocused)focusedField=2 },singleLine=true,
                    enabled=!submitting,label={Text("비밀번호")},visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Go),
                    keyboardActions=KeyboardActions(onGo={submit()}))
                if(!login.pairedPc&&keyguard?.isDeviceSecure==true)Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                    Checkbox(checked=saveOnPhone,onCheckedChange={saveOnPhone=it},enabled=!submitting&&!authPending)
                    Text("이 휴대폰에 로그인 저장")
                }
                Button(onClick={submit()},modifier=Modifier.fillMaxWidth(),enabled=ready){
                    Text(if(submitting)"로그인 확인 중" else "로그인하고 계속")
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    if(!login.pairedPc)TextButton(onClick={keyboard?.hide();agent.switchToWebsiteLogin(login.id)},enabled=!submitting){Text("사이트에서 로그인")}
                    TextButton(onClick={keyboard?.hide();agent.foldOwnerLogin(login.id)},enabled=!submitting){Text("나중에")}
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
    var naverGuide by remember(request.card.id){mutableStateOf<NolConnectionGuide.Status?>(null)}
    LaunchedEffect(request.card.id,reload){
        visible=false
        visible=agent.loadWebsiteLogin(request.card.id,retry=reload!=0)
        if(!visible)return@LaunchedEffect
        var sawLoginForm=false
        var readbacks=0
        while(!returned){
            if(!checking){
                naverGuide=agent.observeNaverLoginGuide(request.card.id)
                if(naverGuide!=null){
                    readbacks=0
                    kotlinx.coroutines.delay(500)
                    continue
                }
                when(agent.websiteLoginFormPresent(request.card.id)){
                    true->{sawLoginForm=true;readbacks=0}
                    false->if((sawLoginForm||request.card.host=="nid.naver.com")&&++readbacks>=2){
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
    androidx.activity.compose.BackHandler {agent.foldOwnerLogin(request.card.id)}
    Surface(modifier,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh){
        Column(Modifier.fillMaxSize().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(request.card.host,style=MaterialTheme.typography.titleSmall)
            naverGuide?.let{NaverLoginHelp(it)}?:loginStatus?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
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
                TextButton(onClick={agent.foldOwnerLogin(request.card.id)},enabled=!checking&&!returned){Text("나중에")}
                if(!request.card.websiteOnly)TextButton(onClick={goBack()},enabled=!checking&&!returned){Text("입력창")}
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
internal fun InlineNolConnection(
    agent:AppWebAgent, request:AppWebAgent.WebsiteLogin, window:Window,
    modifier:Modifier=Modifier, onVerified:()->Unit
){
    SecureBrowserWindow(window)
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val navigation by agent.navigationStatus.collectAsState()
    var reload by remember(request.card.id){mutableIntStateOf(0)}
    var guide by remember(request.card.id){mutableStateOf<NolConnectionGuide.Status?>(null)}
    var externalMessage by remember {mutableStateOf<String?>(null)}
    var resetting by remember {mutableStateOf(false)}
    fun fold()=agent.foldOwnerLogin(request.card.id)
    androidx.activity.compose.BackHandler {fold()}
    LaunchedEffect(request.card.id,reload){
        guide=null
        resetting=true
        try{agent.loadWebsiteLogin(request.card.id,retry=reload!=0)}
        finally{resetting=false}
        while(true){
            val next=agent.observeNolGuide(request.card.id)
            if(next!=null)guide=next
            if(next?.connected==true&&agent.finishNolConnection(request.card.id)){
                onVerified()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(900)
        }
    }
    fun openOfficial(){
        try{
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(NolConnectionGuide.entry)).addCategory(android.content.Intent.CATEGORY_BROWSABLE))
            externalMessage="휴대폰 브라우저에서 NOL을 열었어요. 집순이는 이 채팅 카드 안에서 확인한 로그인 상태를 연결 결과로 표시해요."
        }catch(_:android.content.ActivityNotFoundException){
            externalMessage="휴대폰 브라우저를 열 수 없어요. 이 카드에서 다시 열어 주세요."
        }
    }
    val failed=navigation.error!=null||guide?.key=="error"
    Surface(modifier,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh){
        Column(Modifier.fillMaxSize().padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text("NOL 계정 연결",style=MaterialTheme.typography.titleMedium)
            val showingNaver=!failed&&!resetting&&!navigation.loading&&NolConnectionGuide.isNaverApproval(guide?.key)
            if(showingNaver)guide?.let{NaverLoginHelp(it)}
            else Text(when {
                navigation.error=="NAVIGATION_BLOCKED"->"인증 화면이 다른 주소로 이동했어요. 휴대폰 브라우저에서 공식 로그인 화면을 확인해 주세요."
                navigation.error=="TLS_ERROR"->"사이트의 보안 연결을 확인하지 못했어요. 연결을 확인한 뒤 다시 열어 주세요."
                navigation.error=="PAGE_TIMEOUT"->"NOL 화면이 늦게 열리고 있어요. 잠시 뒤 다시 열어 주세요."
                failed->"NOL이 지금 이 화면을 열어 주지 않았어요. 잠시 후 다시 열거나 휴대폰 브라우저를 사용해 주세요."
                resetting||navigation.loading->"NOL에서 다음 화면을 보내 주기를 기다리고 있어요."
                else->guide?.text?: "NOL 화면에서 ‘로그인’을 눌러 주세요. 공식 페이지 안에서 진행할 수 있어요."
            },style=MaterialTheme.typography.bodySmall)
            if(!failed)guide?.takeUnless{NolConnectionGuide.isNaverApproval(it.key)}?.let{observed->
                if(observed.notices.isNotEmpty()||observed.siteNotices.isNotEmpty())
                    Column(Modifier.heightIn(max=102.dp).verticalScroll(rememberScrollState())){
                        observed.notices.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
                        observed.siteNotices.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
                    }
            }
            if(!failed&&guide?.key=="login")TextButton(onClick={scope.launch {
                if(!agent.openNolVaultForm(request.card.id))
                    externalMessage="이 페이지는 보안 입력칸에 연결할 수 없어요. 아래 NOL 화면에서 로그인해 주세요."
            }}){Text("이메일 로그인 · 금고 사용") }
            if(request.vaultSaveFailed)Text("이번 비밀번호는 금고에 저장되지 않았어요. 공식 화면에서 인증을 계속할 수 있어요.",
                style=MaterialTheme.typography.bodySmall)
            externalMessage?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
            if(resetting||navigation.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()){
                if(!failed||resetting)AndroidView(factory={_->agent.view().also{(it.parent as? ViewGroup)?.removeView(it)}},
                    modifier=Modifier.fillMaxSize(),onRelease={agent.detach()})
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                TextButton(onClick={fold()}){Text("나중에")}
                TextButton(onClick={reload++;externalMessage=null},enabled=!resetting&&!navigation.loading){Text("다시 열기")}
                if(failed||guide?.key=="provider")TextButton(onClick={openOfficial()}){Text("브라우저에서 확인")}
            }
        }
    }
}

@Composable
private fun NaverLoginHelp(guide:NolConnectionGuide.Status){
    val context=LocalContext.current
    var appUnavailable by remember{mutableStateOf(false)}
    val appIntent=remember(context){context.packageManager.getLaunchIntentForPackage("com.nhn.android.search")}
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
        Text(guide.text,style=MaterialTheme.typography.bodySmall)
        if(guide.notices.isNotEmpty()||guide.siteNotices.isNotEmpty())
            Column(Modifier.heightIn(max=66.dp).verticalScroll(rememberScrollState())){
                (guide.notices+guide.siteNotices).forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            }
        if(guide.key!="naver_challenge"&&appIntent!=null)TextButton(onClick={
            appUnavailable=runCatching{context.startActivity(appIntent)}.isFailure
        }){Text("네이버 앱 열기")}
        if(guide.key!="naver_challenge"&&(appIntent==null||appUnavailable))Text("인증에 등록한 휴대폰에서 네이버 앱을 열어 주세요.",
            style=MaterialTheme.typography.bodySmall)
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
                Text(if(work.host=="www.kairb.org")"KAIRB 온라인 교육" else work.host,
                    style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                TextButton(onClick=agent::dismissWebsiteWork){Text("닫기")}
            }
            Text(work.status,style=MaterialTheme.typography.bodyMedium)
            if(work.working)LinearProgressIndicator(Modifier.fillMaxWidth())
            work.steps.filter {it!=work.status}.takeLast(2).forEach {step->
                Text("• "+step,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
