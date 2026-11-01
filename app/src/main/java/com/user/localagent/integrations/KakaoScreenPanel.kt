package com.user.localagent.integrations

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun KakaoScreenPanel(context:Context,onAsk:((String)->Unit)?,report:(String)->Unit){
    val reader=remember(context){KakaoScreenReader(context)}
    var enabled by remember{mutableStateOf(runCatching{reader.binding()!=null}.getOrDefault(false))}
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("휴대폰 카카오톡 화면 연결",style=MaterialTheme.typography.titleMedium)
        Text("PC·대화 내보내기 없이 현재 보이는 카카오톡 내용을 읽습니다. 새 알림 연결과는 별도이며, 현재 화면의 읽기 허용은 모든 과거 대화 수집 완료를 뜻하지 않습니다. 입력·전송·삭제는 수행하지 않습니다.",style=MaterialTheme.typography.bodySmall)
        Row{Text("카카오톡 본문 조회 허용",Modifier.weight(1f));Switch(enabled,onCheckedChange={value->
            runCatching{reader.allow(value);enabled=value}.onFailure{report(it.message?:"카카오톡 조회 허용을 변경하지 못했습니다.")}
        })}
        OutlinedButton(onClick={context.startActivity(ScreenBridgeController(context).settingsIntent())}){Text("Android 화면 접근 설정")}
        if(onAsk!=null)Button(enabled=enabled,onClick={onAsk("휴대폰 카카오톡의 현재 보이는 화면을 읽고 핵심 내용과 확인할 일을 요약해줘. 화면 밖 대화나 전체 과거 기록까지 읽었다고 판단하지 말고 전송하지 마.")}){Text("현재 화면 읽고 요약")}
    }
}
