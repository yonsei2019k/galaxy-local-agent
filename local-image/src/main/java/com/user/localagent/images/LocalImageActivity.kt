package com.user.localagent.images

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.ui.ImageStudioPanel
import com.user.localagent.ui.ZipHapTheme

/** Image-only launcher. Original image work remains in the primary app's private data. */
class LocalImageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = (application as LocalImageApplication).container
        setContent {
            ZipHapTheme {
                var status by remember { mutableStateOf("휴대폰 또는 연결한 개인 PC에서 이미지를 만듭니다.") }
                val imageState by app.imageStudio.state.collectAsState()
                var helperOpen by rememberSaveable { mutableStateOf(false) }
                var helperPreparing by remember { mutableStateOf(false) }
                var notificationsAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(this@LocalImageActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    notificationsAllowed = it
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text("집합 로컬 이미지", style = MaterialTheme.typography.titleLarge)
                            Text(status, style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !imageState.running && !helperPreparing, onClick = {
                                if (!app.imageStudio.state.value.running) helperOpen = true
                            }) { Text("한국어 보조 모델") }
                            if (!notificationsAllowed) TextButton(onClick = {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }) { Text("진행·중단 알림 허용") }
                        }
                        Box(Modifier.weight(1f)) { ImageStudioPanel(app, onStatus = { status = it }) }
                    }
                }
                if (helperOpen) LocalImageHelperPanel(
                    manager = app.models,
                    imageRunning = imageState.running,
                    isImageRunning = { app.imageStudio.state.value.running },
                    onPreparingChanged = { helperPreparing = it },
                    onStatus = { status = it },
                    onClose = { if (!helperPreparing) helperOpen = false }
                )
            }
        }
    }
    override fun onStart() { super.onStart(); RuntimeGate.foreground.set(true) }
    override fun onStop() { RuntimeGate.foreground.set(false); super.onStop() }
}
