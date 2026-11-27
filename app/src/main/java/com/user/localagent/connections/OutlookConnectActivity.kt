package com.user.localagent.connections

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.user.localagent.core.connections.ConnectedService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.user.localagent.ui.ZipFilledButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import com.user.localagent.ui.ZipHapTheme
import com.user.localagent.ui.ZipHapColors
import com.user.localagent.ui.ZipScreenTitle

class OutlookConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val service=ConnectedService.entries.firstOrNull { it.outlook && it.id==intent.getStringExtra("service") }
        if(service==null){finish();return}
        setContent {
            ZipHapTheme { Column(Modifier.fillMaxSize().background(ZipHapColors.Background).safeDrawingPadding()
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ZipScreenTitle(service.label)
                OutlookConnectCard(ConnectionHub.get(this@OutlookConnectActivity),service){setResult(RESULT_OK);finish()}
                TextButton(onClick={finish()}){Text("닫기")}
            } }
        }
    }
}

@Composable internal fun OutlookConnectCard(hub:ConnectionHub, service:ConnectedService, connected:()->Unit) {
    val context= LocalContext.current
    val scope=rememberCoroutineScope()
    var code by remember(service){ mutableStateOf<String?>(null) }
    var ticket by remember(service){ mutableStateOf<String?>(null) }
    var job by remember(service){ mutableStateOf<Job?>(null) }
    var error by remember(service){ mutableStateOf(false) }
    DisposableEffect(service){ onDispose {ticket?.let { hub.outlook.cancel(service,it) };job?.cancel()} }
    if(!hub.outlook.configured){
        Text("이 버전에는 Microsoft 계정 연결 설정이 아직 없어요.")
        return
    }
    if(job==null){
        ZipFilledButton(onClick={
            val current=hub.outlook.begin(service)
            ticket=current;error=false
            job=scope.launch {
                try {
                    hub.outlook.login(service,current,{ shown -> withContext(Dispatchers.Main){code=shown} }){
                        hub.microsoftAccountId(service,it)
                    }
                    hub.clearCache()
                    connected()
                }catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){error=true}
                finally {hub.outlook.cancel(service,current);ticket=null;code=null;job=null}
            }
        }) {Text("Microsoft 계정으로 연결")}
    }else{
        if(code==null)Text("로그인 코드를 준비하고 있어요.")
        code?.let { seen ->
            Text("Microsoft 로그인 화면에 다음 코드를 입력해 주세요.")
            Text(seen,style=MaterialTheme.typography.headlineMedium)
            ZipFilledButton(onClick={
                try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://microsoft.com/devicelogin")))}
                catch(_:android.content.ActivityNotFoundException){error=true}
            }){Text("휴대폰에서 Microsoft 로그인")}
        }
        TextButton(onClick={ticket?.let {hub.outlook.cancel(service,it)};job?.cancel()}){Text("로그인 취소")}
    }
    if(error)Text("연결을 마치지 못했어요. 휴대폰 브라우저와 계정 권한을 확인한 뒤 다시 시도해 주세요.")
}
