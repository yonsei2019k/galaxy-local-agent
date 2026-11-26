package com.user.localagent.updates

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.user.localagent.runtime.R
import com.user.localagent.ui.ZipBulletText
import com.user.localagent.ui.ZipHapColors
import com.user.localagent.ui.ZipIconTile
import com.user.localagent.ui.ZipSection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.user.localagent.ui.ZipOutlinedButton
import com.user.localagent.ui.ZipFilledButton
import com.user.localagent.ui.ZipCard

/** Update review panel. The approval click is bound to the displayed hash, version, and manifest digest. */
@Composable
fun UpdatePanel(
    manager:UpdateManager,
    open:(Intent)->Unit,
    report:(String)->Unit,
    modifier:Modifier=Modifier
) {
    val state by manager.state.collectAsState()
    val scope=rememberCoroutineScope()
    var confirmApproval by remember {mutableStateOf(false)}
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
      ZipSection(contentPadding=PaddingValues(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ZipIconTile(R.drawable.zi_download)
            Text("ZipHap.Agent 업데이트",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text(state.message,style=MaterialTheme.typography.bodyMedium,color=ZipHapColors.TextSecondary)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ZipFilledButton(modifier=Modifier.weight(1f),onClick={scope.launch {runCatching {manager.checkAndApply()}.onSuccess(report).onFailure {report(it.message?:"업데이트 확인에 실패했습니다.")}}}) {
                Text("업데이트 확인")
            }
            ZipOutlinedButton(modifier=Modifier.weight(1f),onClick={open(manager.sourcePermissionIntent())}) {Text("설치 허용 설정")}
        }
      }
        if(state.pendingApkSha256!=null&&state.version!=null&&state.pendingManifestDigest!=null) {
            ZipCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("설치 전 변경사항 확인",style=MaterialTheme.typography.titleMedium)
                    Text("버전 ${state.version} · SHA-256 ${state.pendingApkSha256}",style=MaterialTheme.typography.bodySmall)
                    if(state.pendingCapabilities.isNotEmpty()) {
                        Text("새 기능 범위",style=MaterialTheme.typography.titleSmall)
                        state.pendingCapabilities.forEach {ZipBulletText(it,style=MaterialTheme.typography.bodyMedium)}
                    }
                    if(state.pendingPermissions.isNotEmpty()) {
                        Text("현재 APK보다 추가되는 Android 권한",style=MaterialTheme.typography.titleSmall)
                        state.pendingPermissions.forEach {ZipBulletText(permissionLabel(it),style=MaterialTheme.typography.bodyMedium)}
                    }
                    Text("승인은 이 서명된 APK와 표시된 권한·기능 범위 한 번에만 적용됩니다. 다른 APK나 다음 버전은 다시 검토해야 합니다.",style=MaterialTheme.typography.bodySmall)
                    ZipFilledButton(modifier=Modifier.fillMaxWidth(),onClick={confirmApproval=true}) {Text("변경사항을 확인하고 설치 승인")}
                }
            }
        }
    }
    if(confirmApproval) {
        val apkSha=state.pendingApkSha256
        val version=state.version
        val digest=state.pendingManifestDigest
        AlertDialog(
            onDismissRequest={confirmApproval=false},
            title={Text("이 업데이트를 승인할까요?")},
            text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("버전 ${version?:"—"}")
                Text("APK SHA-256",style=MaterialTheme.typography.titleSmall)
                Text(apkSha?:"—",style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max=320.dp)) {
                    items(state.pendingCapabilities) {Text("새 기능 · $it")}
                    items(state.pendingPermissions) {Text("추가 권한 · ${permissionLabel(it)}")}
                }
                Text("설치 전에 서명과 APK 내용을 다시 확인합니다.",style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(enabled=apkSha!=null&&version!=null&&digest!=null,onClick={
                confirmApproval=false
                if(apkSha!=null&&version!=null&&digest!=null)scope.launch {
                    runCatching {manager.approvePendingUpdate(apkSha,version,digest)}.onSuccess(report).onFailure {report(it.message?:"업데이트 승인을 완료하지 못했습니다.")}
                }
            }) {Text("승인하고 설치")}},
            dismissButton={TextButton(onClick={confirmApproval=false}) {Text("취소")}}
        )
    }
}

private fun permissionLabel(permission:String):String=when(permission) {
    "android.permission.READ_CALENDAR" -> "일정 읽기 ($permission)"
    "android.permission.WRITE_CALENDAR" -> "일정 쓰기 ($permission)"
    "android.permission.READ_CONTACTS" -> "연락처 읽기 ($permission)"
    "android.permission.WRITE_CONTACTS" -> "연락처 쓰기 ($permission)"
    "android.permission.POST_NOTIFICATIONS" -> "알림 표시 ($permission)"
    "android.permission.READ_SMS" -> "문자 읽기 ($permission)"
    "android.permission.SEND_SMS" -> "문자 보내기 ($permission)"
    "android.permission.REQUEST_INSTALL_PACKAGES" -> "앱 설치 요청 ($permission)"
    else -> permission
}
