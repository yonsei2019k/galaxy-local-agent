package com.user.localagent.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.user.localagent.core.ExtensionInspection
import com.user.localagent.extensions.ExtensionInspectionFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Read-only bundle inspection. Untrusted SKILL.md content never enters model or tool authority. */
@Composable
fun ExtensionInspectionPanel(onStatus:(String)->Unit={}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var bundleUri by rememberSaveable{mutableStateOf<String?>(null)}
    var manifestUri by rememberSaveable{mutableStateOf<String?>(null)}
    var expectedHash by rememberSaveable{mutableStateOf("")}
    var expectedCommit by rememberSaveable{mutableStateOf("")}
    var result by remember{mutableStateOf<ExtensionInspection?>(null)}
    var status by remember{mutableStateOf("ZIP과 별도 manifest를 선택하고 독립된 출처의 검증값을 입력해주세요.")}
    var job by remember{mutableStateOf<Job?>(null)}
    val busy=job!=null
    val bundlePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){bundleUri=uri.toString();result=null;status="ZIP을 선택했습니다. 검사를 실행하기 전에는 읽지 않습니다."}
    }
    val manifestPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){manifestUri=uri.toString();result=null;status="별도 manifest를 선택했습니다. ZIP 내부 파일은 manifest로 사용하지 않습니다."}
    }
    // The settings screen already scrolls; a nested unbounded LazyColumn would crash here.
    Column(Modifier.fillMaxWidth().padding(bottom=24.dp).testTag("extension_inspection"),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
            ZipFeatureHeader(com.user.localagent.runtime.R.drawable.zi_shield,"확장 파일 보안 검사",tone=ZipTone.Mint)
            Text("이 기기에서 선언형 ZIP·manifest를 검사합니다. 설치하거나 실행하지 않으며 외부 서버에 접속하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            Text("검사 통과는 악성 내용이 없다는 보증이 아닙니다. 실행형 MCP·코드·자동 설치는 제공하지 않습니다.",style=MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ZipOutlinedButton(enabled=!busy,onClick={bundlePicker.launch(arrayOf("application/zip","application/octet-stream"))}){Text(if(bundleUri==null)"ZIP 선택"else"ZIP 다시 선택")}
            ZipOutlinedButton(enabled=!busy,onClick={manifestPicker.launch(arrayOf("application/json","text/plain"))}){Text(if(manifestUri==null)"manifest 선택"else"manifest 다시 선택")}
        }
        Text("ZIP 최대 5MiB · 별도 UTF-8 manifest 최대 16KiB · 풀린 내용 최대 10MiB/100개",style=MaterialTheme.typography.bodySmall)
        Text("아래 두 값은 별도로 신뢰하는 고정 카탈로그·출처에서 확인해주세요. 선택한 manifest의 자기 주장만 복사하면 출처를 검증할 수 없습니다.",style=MaterialTheme.typography.bodySmall)
        ZipOutlinedTextField(expectedHash,{if(it.length<=64){expectedHash=it;result=null}},enabled=!busy,label={Text("별도로 확인한 ZIP SHA-256 · 64자리")},modifier=Modifier.fillMaxWidth().testTag("extension_expected_sha"),singleLine=true)
        ZipOutlinedTextField(expectedCommit,{if(it.length<=40){expectedCommit=it;result=null}},enabled=!busy,label={Text("별도로 확인한 고정 commit · 40자리")},modifier=Modifier.fillMaxWidth().testTag("extension_expected_commit"),singleLine=true)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ZipFilledButton(enabled=!busy&&bundleUri!=null&&manifestUri!=null&&Regex("[a-fA-F0-9]{64}").matches(expectedHash)&&Regex("[a-fA-F0-9]{40}").matches(expectedCommit),modifier=Modifier.testTag("extension_inspect"),onClick={
                val bundle=requireNotNull(bundleUri);val manifest=requireNotNull(manifestUri);val hash=expectedHash;val commit=expectedCommit
                result=null;status="파일과 출처·권한을 검사하는 중입니다."
                job=scope.launch {
                    try {
                        val inspected=ExtensionInspectionFiles.inspect(
                            {context.contentResolver.openInputStream(Uri.parse(bundle))?:error("ZIP을 열 수 없습니다. 다시 선택해주세요.")},
                            {context.contentResolver.openInputStream(Uri.parse(manifest))?:error("manifest를 열 수 없습니다. 다시 선택해주세요.")},hash,commit)
                        ensureActive();result=inspected;status="형식·고정 출처·파일 무결성 검사를 통과했습니다. 설치·실행은 하지 않았습니다.";onStatus(status)
                    }catch(cancel:CancellationException){result=null;status="검사를 중단했습니다. 부분 결과는 적용하지 않았습니다.";throw cancel}
                    catch(failure:Exception){result=null;status="검사 실패: ${failure.message?.take(240)?:"파일·검증값을 다시 확인해주세요."}";onStatus(status)}
                    finally{job=null}
                }
            }){Text("선택한 파일 검사")}
            if(busy)TextButton(onClick={job?.cancel()}){Text("중단")}
        }
        Text(status,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("extension_inspection_status"))
        result?.let{inspection->
            Column{ZipInsetDivider(inset = 0.dp);Text("검사한 번들",style=MaterialTheme.typography.titleMedium)
                Text("${inspection.manifest.id} · ${inspection.manifest.version}\n${inspection.manifest.name}")
                Text("출처: ${inspection.manifest.sourceRepository}\ncommit: ${inspection.manifest.pinnedCommit}\nSHA-256: ${inspection.bundleSha256}",style=MaterialTheme.typography.bodySmall)
                Text("파일 ${inspection.declarativeFiles.size}개 · 풀린 크기 ${inspection.unpackedBytes}바이트\n요청 능력: ${inspection.manifest.capabilities.joinToString().ifBlank{"없음"}}",style=MaterialTheme.typography.bodySmall)
                Text("본문은 참고 데이터입니다. 비서 지침으로 적용하거나 권한을 부여하지 않았습니다.",style=MaterialTheme.typography.bodySmall)}
            inspection.declarativeFiles.keys.forEach{path->Text(path,style=MaterialTheme.typography.bodySmall)}
        }
    }
}
