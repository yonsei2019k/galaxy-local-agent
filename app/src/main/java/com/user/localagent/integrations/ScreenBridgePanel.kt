package com.user.localagent.integrations

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Optional user-controlled setup. This panel never enables Android access or executes screen actions. */
@Composable
fun ScreenBridgePanel(context: Context, open: (Intent) -> Unit, report: (String) -> Unit) {
    val appContext = context.applicationContext
    val controller = remember(appContext) { ScreenBridgeController(appContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var current by remember(controller) { mutableStateOf(controller.state()) }
    var notificationUnavailable by remember { mutableStateOf(false) }
    var localStatus by remember { mutableStateOf("") }
    val names = remember {
        linkedMapOf(
            "com.tionsoft.meettalk.eun" to "메디통 MeetTalk",
            "com.meditong.gw" to "메디통 그룹웨어",
            "com.kakao.talk" to "카카오톡",
            "org.telegram.messenger" to "텔레그램",
            "org.telegram.messenger.web" to "텔레그램 별도 배포판",
            "com.android.chrome" to "Chrome",
        )
    }
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                current = controller.state()
                delay(2_000)
            }
        }
    }
    fun status(value: String) { localStatus = value; report(value) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("선택한 앱 화면 연결", style = MaterialTheme.typography.titleLarge)
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("별도 동의가 필요한 화면 접근", style = MaterialTheme.typography.titleMedium)
                Text("Android 접근성 서비스를 직접 켜고 아래에서 허용한 앱만 검사합니다. 현재 보이는 앱의 패키지·버전, 요소 ID·클래스·동작 가능 여부만 표시합니다.")
                Text("메시지 본문·화면 텍스트·비밀번호를 수집하거나 저장하지 않습니다. 각 클릭은 대상 ID를 확인한 뒤 직접 승인해야 합니다. 자동 계획·자동 입력·연속 실행은 없습니다.")
            }
        }
        Text("Android 서비스 권한: ${if (current.systemEnabled) "허용됨" else "꺼짐"}\n서비스 연결: ${if (current.connected) "연결됨" else "연결 대기 또는 꺼짐"}",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            runCatching { open(controller.settingsIntent()) }
                .onFailure { status("Android 화면 접근 설정을 열지 못했습니다. 시스템 설정에서 직접 확인해주세요.") }
        }, modifier = Modifier.fillMaxWidth()) { Text("시스템 화면 접근 설정") }
        Text("앱별 허용", style = MaterialTheme.typography.titleMedium)
        Text("스위치는 앱 내부 동의입니다. Android 서비스 권한을 대신 켜지 않습니다. 설치한 앱과 실제 화면이 지원되는지는 검사창에서 확인하세요.",
            style = MaterialTheme.typography.bodySmall)
        names.forEach { (packageName, name) ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(packageName, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = packageName in current.allowedPackages, onCheckedChange = { allowed ->
                    controller.setAllowed(packageName, allowed)
                    current = controller.state()
                    status(if (allowed) "$name 화면 검사를 허용했습니다. Android 서비스 권한도 필요합니다." else "$name 화면 검사를 껐습니다. 이전 승인은 무효화됩니다.")
                })
            }
            HorizontalDivider()
        }
        Text("사용 순서", style = MaterialTheme.typography.titleMedium)
        Text("1. Android 화면 접근 설정에서 서비스를 켜고 검사할 앱을 허용하세요.\n2. 아래에서 검사 알림을 준비하세요.\n3. 원하는 앱을 앞에 둔 뒤 알림의 ‘현재 앱 화면 검사’를 누르세요.\n4. 검사창에서 요소 ID를 선택하고 한 번의 클릭을 직접 승인하세요.")
        Text("현재 검사창은 메타데이터 조회와 승인한 클릭을 지원합니다. 텍스트 입력 UI는 아직 없으며, 앱별 실제 작업 흐름은 검증되지 않았습니다.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            val posted = runCatching { controller.postInspectorNotification() }.getOrDefault(false)
            notificationUnavailable = !posted
            status(if (posted) "검사 알림을 준비했습니다. 원하는 앱으로 전환한 뒤 알림의 ‘현재 앱 화면 검사’를 누르세요."
                else "검사 알림을 표시할 수 없습니다. Android 13 이상에서는 POST_NOTIFICATIONS 알림 권한과 앱·채널 알림 설정을 확인해주세요.")
        }, enabled = current.systemEnabled && current.connected && current.allowedPackages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("화면 검사 알림 준비")
        }
        if (notificationUnavailable) {
            OutlinedButton(onClick = {
                runCatching { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)) }
                    .onFailure { status("앱 알림 설정을 열지 못했습니다. Android 설정에서 직접 확인해주세요.") }
            }, modifier = Modifier.fillMaxWidth()) { Text("앱 알림 설정 확인") }
        }
        OutlinedButton(onClick = {
            controller.cancelInspectorNotification()
            controller.closeInspector()
            status("검사 알림과 검사창을 닫았습니다. 앱별 동의와 Android 서비스 권한은 별도로 끌 수 있습니다.")
        }, modifier = Modifier.fillMaxWidth()) { Text("검사 알림·검사창 닫기") }
        if (localStatus.isNotBlank()) Text(localStatus, style = MaterialTheme.typography.bodySmall)
    }
}

