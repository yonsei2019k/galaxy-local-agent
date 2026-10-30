package com.user.localagent.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** On-device image workspace. The primary action remains above the software keyboard. */
@Composable
fun ImageStudioPanel(app:AppContainer,onStatus:(String)->Unit,initialPrompt:String="",onInitialPromptConsumed:()->Unit={}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val state by app.imageStudio.state.collectAsState()
    val model by app.imageStudio.models.state.collectAsState()
    var prompt by rememberSaveable{mutableStateOf(initialPrompt.ifBlank{state.prompt})}
    var negative by rememberSaveable{mutableStateOf("")}
    var steps by rememberSaveable{mutableIntStateOf(4)}
    var seedText by rememberSaveable{mutableStateOf("")}
    var advanced by rememberSaveable{mutableStateOf(false)}
    var sourceHash by rememberSaveable{mutableStateOf("")}
    var importing by remember{mutableStateOf(false)}
    var saving by remember{mutableStateOf(false)}
    var downloadJob by remember{mutableStateOf<Job?>(null)}
    var output by remember{mutableStateOf<ImageBitmap?>(null)}
    var previewFailed by remember{mutableStateOf(false)}
    var deleteModel by remember{mutableStateOf(false)}

    LaunchedEffect(initialPrompt){if(initialPrompt.isNotBlank()){prompt=initialPrompt;onInitialPromptConsumed()}}
    LaunchedEffect(app){try{app.imageStudio.models.refresh()}catch(cancel:CancellationException){throw cancel}catch(_:Exception){onStatus("이미지 모델 상태를 확인하지 못했습니다.")}}
    LaunchedEffect(state.outputPath){
        output=null;previewFailed=false
        val path=state.outputPath?:return@LaunchedEffect
        output=withContext(Dispatchers.IO){runCatching {
            val file=File(path);require(file.isFile)
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(path,bounds)
            require(bounds.outWidth in 1..4096&&bounds.outHeight in 1..4096)
            var sample=1
            while(bounds.outWidth/sample>1024||bounds.outHeight/sample>1024)sample*=2
            BitmapFactory.decodeFile(path,BitmapFactory.Options().apply{inSampleSize=sample})?.asImageBitmap()
        }.getOrNull()}
        previewFailed=output==null
    }
    val importPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)scope.launch {
            importing=true
            try {app.imageStudio.models.importModel(uri,sourceHash.trim());onStatus("이미지 모델 파일과 SHA-256을 확인했습니다.")}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){onStatus("모델을 가져오지 못했습니다. 지원 형식과 배포자의 SHA-256을 확인해주세요.")}
            finally{importing=false}
        }
    }
    fun generate(){
        val seed=seedText.trim().takeIf{it.isNotEmpty()}?.toLongOrNull()
        if(seedText.isNotBlank()&&(seed==null||seed<0)){onStatus("시드는 0 이상의 정수로 입력하거나 비워주세요.");return}
        if(!app.imageStudio.generate(prompt.trim(),steps=steps,seed=seed,negativePrompt=negative.trim()))onStatus("이미지 생성을 시작하지 못했습니다. 현재 작업과 모델 상태를 확인해주세요.")
    }
    Column(Modifier.fillMaxSize().imePadding()){
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=16.dp)){
            item {Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text("Image Studio",style=MaterialTheme.typography.headlineMedium)
                Text("장면을 적고, 이 기기에서 이미지로 만드세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            item {Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center){
                if(output!=null)Image(bitmap=output!!,contentDescription="기기에서 생성한 이미지",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                else Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                    StudioPlaceholder(Modifier.size(70.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(previewFailed)"미리보기를 열지 못했습니다." else if(state.running)state.phase else "새로운 장면을 만들어 보세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }}
            if(!model.available||model.downloading)item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
                Text(if(model.downloading)"이미지 모델 준비 중" else "첫 사용에 모델이 필요해요",style=MaterialTheme.typography.titleMedium)
                Text("DreamShaper 8 LCM · 약 2.13GB\n한 번 받은 뒤 기본 생성에 API 키나 서버 연결이 필요하지 않습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(model.downloading){
                    val fraction=if(model.totalBytes>0)(model.downloadedBytes.toDouble()/model.totalBytes).toFloat().coerceIn(0f,1f)else 0f
                    LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())
                    Text("${model.downloadedBytes/1_000_000} / ${model.totalBytes/1_000_000} MB",style=MaterialTheme.typography.labelMedium)
                    if(downloadJob?.isActive==true)TextButton(onClick={downloadJob?.cancel()}){Text("다운로드 중단")}
                }else Button(enabled=!state.running&&!importing,onClick={downloadJob=scope.launch {
                    try {app.imageStudio.models.downloadDefault();onStatus("이미지 모델 다운로드와 무결성 확인을 마쳤습니다.")}
                    catch(cancel:CancellationException){onStatus("다운로드를 중단했습니다. 다시 받기를 눌러 이어받을 수 있습니다.");throw cancel}
                    catch(_:Exception){onStatus("이미지 모델을 받지 못했습니다. 연결과 저장 공간을 확인한 뒤 다시 시도해주세요.")}
                }}){Text("기본 모델 다운로드")}
                Text(model.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}}
            item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("어떤 장면을 만들까요?",style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(value=prompt,onValueChange={if(it.length<=2000)prompt=it},label={Text("이미지 설명")},placeholder={Text("예: a cinematic photo of a quiet forest at sunrise, soft golden light")},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=7,enabled=!state.running)
                Text("짧은 설명으로 시작해도 됩니다. 세부 표현은 결과를 보며 바꿀 수 있습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    listOf(2,4,8).forEach {value->FilterChip(selected=steps==value,onClick={steps=value},enabled=!state.running,label={Text("${value}단계")})}
                }
                Text("512×512 · 단계가 늘면 계산 시간이 길어집니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={advanced=!advanced}){Text(if(advanced)"상세 설정 접기" else "시드·제외할 표현·모델 파일")}
                if(advanced){
                    OutlinedTextField(value=seedText,onValueChange={if(it.length<=19)seedText=it},label={Text("시드 · 비우면 매번 새롭게")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!state.running)
                    OutlinedTextField(value=negative,onValueChange={if(it.length<=1000)negative=it},label={Text("제외할 표현 · 선택 사항")},modifier=Modifier.fillMaxWidth(),maxLines=3,enabled=!state.running)
                    HorizontalDivider()
                    Text("다른 호환 모델 가져오기",style=MaterialTheme.typography.titleSmall)
                    Text("배포자의 SHA-256을 확인한 호환 safetensors·GGUF 파일을 가져올 수 있습니다. 모델 구성과 생성 엔진이 맞아야 실행할 수 있습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value=sourceHash,onValueChange={if(it.length<=64)sourceHash=it.trim()},label={Text("배포자 SHA-256 · 64자리")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                    OutlinedButton(enabled=!state.running&&!model.downloading&&!importing&&sourceHash.matches(Regex("[a-fA-F0-9]{64}")),onClick={importPicker.launch(arrayOf("application/octet-stream","*/*"))}){Text(if(importing)"모델 확인 중" else "모델 파일 선택")}
                    if(model.available)TextButton(enabled=!state.running&&!model.downloading&&!importing,onClick={deleteModel=true}){Text("이미지 모델 파일 삭제")}
                }
            }}}
            state.error?.takeIf{it.isNotBlank()}?.let {error->item {ZipHapCard {Text(error.take(700),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}}}
            if(state.outputPath!=null)item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("완성한 이미지",style=MaterialTheme.typography.titleMedium)
                state.seed?.let {seed->Text("시드 $seed",style=MaterialTheme.typography.labelMedium)}
                Button(enabled=!state.running&&!saving,onClick={scope.launch {
                    saving=true
                    try{app.imageStudio.exportToGallery();onStatus("생성한 이미지를 갤러리에 저장했습니다.")}
                    catch(cancel:CancellationException){throw cancel}
                    catch(_:Exception){onStatus("갤러리에 저장하지 못했습니다. 생성 이미지는 앱에 유지됩니다.")}
                    finally{saving=false}
                }}){Text(if(saving)"저장 중" else "갤러리에 저장")}
            }}}
            item {Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text("기본 모델: Lykon / DreamShaper 8 LCM\n라이선스: CreativeML OpenRAIL-M",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://huggingface.co/Lykon/dreamshaper-8-lcm")))}.onFailure{onStatus("모델 출처 페이지를 열 앱을 찾지 못했습니다.")}}){Text("모델 출처·라이선스 보기")}
            }}
        }
        Surface(color=MaterialTheme.colorScheme.background,shadowElevation=0.dp){Column(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(state.running){
                Text(state.phase,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.step<=0&&(!state.progress.isFinite()||state.progress<=0f))LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress={state.progress.takeIf{it.isFinite()}?.coerceIn(0f,1f)?:0f},modifier=Modifier.fillMaxWidth())
                if(state.totalSteps>0)Text("${state.step} / ${state.totalSteps}단계",style=MaterialTheme.typography.labelSmall)
            }
            Button(onClick={if(state.running)app.imageStudio.cancel()else generate()},enabled=state.running||(model.available&&!model.downloading&&!importing&&prompt.isNotBlank()),modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)){
                Text(if(state.running)"생성 중지" else "이미지 만들기",style=MaterialTheme.typography.titleMedium)
            }
        }}
    }
    if(deleteModel)AlertDialog(onDismissRequest={deleteModel=false},title={Text("이미지 모델 파일을 삭제할까요?")},text={Text("생성한 이미지는 유지하고 이미지 생성용 모델 파일만 삭제합니다. 다시 사용하려면 모델을 받아야 합니다.")},confirmButton={TextButton(onClick={deleteModel=false;scope.launch {
        importing=true
        try{app.imageStudio.models.deleteModel();onStatus("이미지 모델 파일을 삭제했습니다.")}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){onStatus("이미지 모델 파일을 삭제하지 못했습니다.")}
        finally{importing=false}
    }}){Text("모델 삭제")}},dismissButton={TextButton(onClick={deleteModel=false}){Text("취소")}})
}

@Composable private fun StudioPlaceholder(modifier:Modifier,color:Color){
    Canvas(modifier){
        drawRoundRect(color.copy(alpha=.18f),cornerRadius=CornerRadius(size.width*.18f),size=Size(size.width,size.height))
        drawCircle(color.copy(alpha=.6f),size.width*.075f,Offset(size.width*.68f,size.height*.31f))
        drawLine(color.copy(alpha=.6f),Offset(size.width*.19f,size.height*.72f),Offset(size.width*.43f,size.height*.43f),strokeWidth=size.width*.055f)
        drawLine(color.copy(alpha=.6f),Offset(size.width*.43f,size.height*.43f),Offset(size.width*.77f,size.height*.72f),strokeWidth=size.width*.055f)
    }
}
