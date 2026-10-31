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
import kotlin.math.max
import kotlin.math.roundToInt

/** On-device image workspace. The primary action remains above the software keyboard. */
@Composable
fun ImageStudioPanel(app:AppContainer,onStatus:(String)->Unit,initialPrompt:String="",onInitialPromptConsumed:()->Unit={}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val state by app.imageStudio.state.collectAsState()
    val model by app.imageStudio.models.state.collectAsState()
    val pc by com.user.localagent.compute.PcComputeSettings.get(context).state.collectAsState()
    var prompt by rememberSaveable{mutableStateOf(initialPrompt.ifBlank{state.prompt})}
    var negative by rememberSaveable{mutableStateOf("")}
    var steps by rememberSaveable{mutableIntStateOf(8)}
    var qualitySteps by rememberSaveable{mutableIntStateOf(40)}
    var pcSteps by rememberSaveable{mutableIntStateOf(50)}
    var seedText by rememberSaveable{mutableStateOf("")}
    var advanced by rememberSaveable{mutableStateOf(false)}
    var sourceHash by rememberSaveable{mutableStateOf("")}
    var importing by remember{mutableStateOf(false)}
    var saving by remember{mutableStateOf(false)}
    var downloadJob by remember{mutableStateOf<Job?>(null)}
    var output by remember{mutableStateOf<ImageBitmap?>(null)}
    var previewFailed by remember{mutableStateOf(false)}
    var deleteModel by remember{mutableStateOf(false)}
    var transform by rememberSaveable{mutableStateOf(false)}
    var referenceUri by rememberSaveable{mutableStateOf<String?>(null)}
    var strength by rememberSaveable{mutableFloatStateOf(.65f)}
    var referencePreview by remember{mutableStateOf<ImageBitmap?>(null)}

    val referencePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){referenceUri=uri.toString();transform=true}}
    LaunchedEffect(referenceUri){
        referencePreview=null
        val uri=referenceUri?.let(Uri::parse)?:return@LaunchedEffect
        referencePreview=withContext(Dispatchers.IO){runCatching {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,bounds)}
            require(bounds.outWidth in 1..16384&&bounds.outHeight in 1..16384&&bounds.outWidth.toLong()*bounds.outHeight<=100_000_000)
            var sample=1;while(max(bounds.outWidth,bounds.outHeight)/sample>256)sample*=2
            context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply {inSampleSize=sample})}?.asImageBitmap()
        }.getOrNull()}
    }

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
        if(transform&&referenceUri==null){onStatus("변환할 참고 사진을 먼저 선택해주세요.");return}
        if(!app.imageStudio.generate(prompt.trim(),steps=if(pc.paired&&pc.preferPcImages)pcSteps else if(model.qualityModel)qualitySteps else steps,seed=seed,negativePrompt=negative.trim(),referenceUri=if(transform)referenceUri?.let(Uri::parse)else null,strength=strength))onStatus("이미지 생성을 시작하지 못했습니다. 현재 작업과 모델 상태를 확인해주세요.")
    }
    Column(Modifier.fillMaxSize().imePadding()){
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=16.dp)){
            item {Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text("Image Studio",style=MaterialTheme.typography.headlineMedium)
                Text("장면을 적고, 스마트폰에서 결과를 확인하고 저장하세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            item {ZipHapCard {PcComputePanel(state.running,onStatus)}}
            item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    FilterChip(selected=!transform,onClick={transform=false},enabled=!state.running,label={Text("새 이미지")})
                    FilterChip(selected=transform,onClick={transform=true},enabled=!state.running,label={Text("사진 변환")})
                }
                if(transform){
                    Text("참고 사진을 직접 입력으로 사용해 장면과 스타일을 바꿉니다. 원본 파일은 수정하지 않습니다.",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(enabled=!state.running,onClick={referencePicker.launch(arrayOf("image/*"))}){Text(if(referenceUri==null)"참고 사진 선택"else"다른 사진 선택")}
                        if(referenceUri!=null)TextButton(enabled=!state.running,onClick={referenceUri=null}){Text("사진 제외")}
                    }
                    referencePreview?.let {preview->Image(preview,"사용자가 선택한 참고 사진",Modifier.fillMaxWidth().height(150.dp),contentScale=ContentScale.Fit)}
                    if(referenceUri!=null&&referencePreview==null)Text("선택한 참고 사진 1장",style=MaterialTheme.typography.labelSmall)
                    if(pc.paired&&pc.preferPcImages){
                        Text("PC 사진 편집은 장면 설명으로 변경할 내용을 지정합니다. 참고 사진은 원본 비율을 유지해 1024px로 준비합니다.",style=MaterialTheme.typography.bodySmall)
                    }else{
                        Text("변환 강도 ${(strength*100).roundToInt()}%",style=MaterialTheme.typography.labelLarge)
                        Slider(value=strength,onValueChange={strength=it.coerceIn(.4f,.85f)},valueRange=.4f..0.85f,steps=8,enabled=!state.running)
                        Text("낮을수록 원래 구도 유지, 높을수록 변화가 커집니다. 원본 비율을 유지해 생성 크기의 정사각 캔버스에 맞춥니다.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }}}
            item {Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center){
                if(output!=null)Image(bitmap=output!!,contentDescription="생성한 이미지",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                else Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                    StudioPlaceholder(Modifier.size(70.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(previewFailed)"미리보기를 열지 못했습니다." else if(state.running)state.phase else "새로운 장면을 만들어 보세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }}
            if(!model.available||!model.qualityModel||model.downloading)item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
                Text(if(model.downloading)"고품질 이미지 모델 준비 중" else "휴대폰 고품질 모델",style=MaterialTheme.typography.titleMedium)
                Text("SDXL 1.0 Q4_K · 약 2.80GB\n1024px 정밀 생성 · 한 번 받은 뒤 PC나 API 키 없이 휴대폰에서 계산합니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(model.downloading){
                    val fraction=if(model.totalBytes>0)(model.downloadedBytes.toDouble()/model.totalBytes).toFloat().coerceIn(0f,1f)else 0f
                    LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())
                    Text("${model.downloadedBytes/1_000_000} / ${model.totalBytes/1_000_000} MB",style=MaterialTheme.typography.labelMedium)
                    if(downloadJob?.isActive==true)TextButton(onClick={downloadJob?.cancel()}){Text("다운로드 중단")}
                }else Button(enabled=!state.running&&!importing,onClick={downloadJob=scope.launch {
                    try {app.imageStudio.models.downloadDefault();onStatus("이미지 모델 다운로드와 무결성 확인을 마쳤습니다.")}
                    catch(cancel:CancellationException){onStatus("다운로드를 중단했습니다. 다시 받기를 눌러 이어받을 수 있습니다.");throw cancel}
                    catch(_:Exception){onStatus("이미지 모델을 받지 못했습니다. 연결과 저장 공간을 확인한 뒤 다시 시도해주세요.")}
                }}){Text(if(model.available)"고품질 모델로 교체"else"고품질 모델 다운로드")}
                Text(model.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}}
            item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("어떤 장면을 만들까요?",style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(value=prompt,onValueChange={if(it.length<=2000)prompt=it},label={Text("이미지 설명")},placeholder={Text("예: a cinematic photo of a quiet forest at sunrise, soft golden light")},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=7,enabled=!state.running)
                Text("짧은 설명으로 시작해도 됩니다. 세부 표현은 결과를 보며 바꿀 수 있습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    (if(pc.paired&&pc.preferPcImages)listOf(20,32,50)else if(model.qualityModel||!model.available)listOf(30,40,50)else listOf(2,4,8)).forEach {value->FilterChip(selected=(if(pc.paired&&pc.preferPcImages)pcSteps else if(model.qualityModel||!model.available)qualitySteps else steps)==value,onClick={if(pc.paired&&pc.preferPcImages)pcSteps=value else if(model.qualityModel||!model.available)qualitySteps=value else steps=value},enabled=!state.running,label={Text("${value}단계")})}
                }
                Text((if(pc.paired&&pc.preferPcImages)"PC 1024×1024 · 연결이 끊기면 설치한 휴대폰 모델로 계산합니다."else if(model.qualityModel||!model.available)"휴대폰 SDXL 1024×1024 · 기본40단계 · 계산이 오래 걸릴 수 있습니다. 전화·다른 앱 사용 중에는 품질을 유지하며 속도를 낮춰 계속합니다."else"기존 휴대폰 LCM 512×512 · 고품질 모델로 교체할 수 있습니다.")+if(transform&&!(pc.paired&&pc.preferPcImages))" 사진 변환의 실제 샘플링 횟수는 기준 단계와 변환 강도에 따라 달라집니다."else"",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(if(state.computeLocation=="pc")"PC에서 계산 · 스마트폰에 저장"else"휴대폰에서 생성·저장",style=MaterialTheme.typography.labelMedium)
                if(state.mode=="img2img"&&state.referenceWidth!=null&&state.referenceHeight!=null)Text("사진 변환 · 원본 ${state.referenceWidth}×${state.referenceHeight}"+(state.strength?.let{" · 강도 ${(it*100).roundToInt()}%"}?:" · 지시문에 따라 변환"),style=MaterialTheme.typography.labelMedium)
                Button(enabled=!state.running&&!saving,onClick={scope.launch {
                    saving=true
                    try{app.imageStudio.exportToGallery();onStatus("생성한 이미지를 갤러리에 저장했습니다.")}
                    catch(cancel:CancellationException){throw cancel}
                    catch(_:Exception){onStatus("갤러리에 저장하지 못했습니다. 생성 이미지는 앱에 유지됩니다.")}
                    finally{saving=false}
                }}){Text(if(saving)"저장 중" else "갤러리에 저장")}
            }}}
            item {Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(if(pc.paired&&pc.preferPcImages)"선택 모델: FLUX.2 klein base 4B · Q4\n라이선스: Apache 2.0"else if(model.qualityModel||!model.available)"선택 모델: SDXL 1.0 · Q4_K\n라이선스: CreativeML Open RAIL++-M"else"선택 모델: 기존 DreamShaper 8 LCM\n라이선스: CreativeML OpenRAIL-M",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(if(pc.paired&&pc.preferPcImages)"https://huggingface.co/black-forest-labs/FLUX.2-klein-base-4B"else if(model.qualityModel||!model.available)"https://huggingface.co/kostakoff/stable-diffusion-xl-base-1.0-GGUF"else"https://huggingface.co/Lykon/dreamshaper-8-lcm")))}.onFailure{onStatus("모델 출처 페이지를 열 앱을 찾지 못했습니다.")}}){Text("모델 출처·라이선스 보기")}
            }}
        }
        Surface(color=MaterialTheme.colorScheme.background,shadowElevation=0.dp){Column(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(state.running){
                Text(state.phase,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.step<=0&&(!state.progress.isFinite()||state.progress<=0f))LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress={state.progress.takeIf{it.isFinite()}?.coerceIn(0f,1f)?:0f},modifier=Modifier.fillMaxWidth())
                if(state.totalSteps>0)Text("${state.step} / ${state.totalSteps}단계",style=MaterialTheme.typography.labelSmall)
            }
            Button(onClick={if(state.running)app.imageStudio.cancel()else generate()},enabled=state.running||((model.available||pc.paired&&pc.preferPcImages)&&!model.downloading&&!importing&&prompt.isNotBlank()&&(!transform||referenceUri!=null)),modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)){
                Text(if(state.running)"생성 중지" else if(transform)"참고 사진 변환하기"else"이미지 만들기",style=MaterialTheme.typography.titleMedium)
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
