package com.user.localagent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Battery-optimization exemption so a run that starts while ZipHap is behind another app (a PC remote request)
 * may still start [AgentRunService]. Android only shows its own consent dialog; the owner decides there.
 */
object BackgroundRunExemption {
    fun granted(context:Context):Boolean=context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    @SuppressLint("BatteryLife") // Owner-requested: background-started agent runs need an FGS; the system dialog asks the owner.
    fun request(context:Context):Boolean=runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

@Composable
fun BackgroundRunExemptionRow(onStatus:(String)->Unit) {
    val context=LocalContext.current
    var granted by remember{mutableStateOf(BackgroundRunExemption.granted(context))}
    LifecycleResumeEffect(Unit){granted=BackgroundRunExemption.granted(context);onPauseOrDispose{}}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Column(Modifier.weight(1f)){
            Text("백그라운드 실행 허용",style=MaterialTheme.typography.labelLarge)
            Text(if(granted)"허용됨. 앱이 뒤에 있을 때 시작된 요청(PC 원격 요청 등)도 '작업 중' 알림을 띄우고 끝까지 실행합니다."
                else "앱이 뒤에 있을 때 시작된 요청은 Android가 실행 유지 알림을 막아 도중에 멈출 수 있습니다. 허용하면 배터리 최적화에서 이 앱만 제외됩니다.",
                style=MaterialTheme.typography.bodySmall)
        }
        if(!granted)TextButton(onClick={
            if(BackgroundRunExemption.request(context))onStatus("시스템의 전체 앱 목록에서 집순이를 선택해 허용해주세요.")
            else onStatus("시스템 허용 화면을 열지 못했습니다.")
        },modifier=Modifier.testTag("background_run_exemption")){Text("허용")}
    }
}
