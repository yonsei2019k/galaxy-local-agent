package com.user.localagent.images

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.user.localagent.AppContainer
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.ImageGenerationEngine
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.ImageSampler
import com.user.localagent.core.PhoneImageQuality
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class GeneratedImage(val file:File,val seed:Long,val width:Int,val height:Int,val elapsedMillis:Long,val computeLocation:String="phone",val sourceModelId:String?=null) {
    fun toolResult():String=buildJsonObject {put("success",true);put("fileName",file.name);put("seed",seed);put("width",width);put("height",height);put("elapsedMillis",elapsedMillis);put("computeLocation",computeLocation);put("modelId",sourceModelId);put("location","app_private_generated_images")}.toString()
}

/** One compute lease covers local prompt translation, LLM release, diffusion and image release. */
class ImageStudioController(context:Context,private val app:AppContainer,
    private val requestEngine:com.user.localagent.inference.InferenceEngine?=null,
    private val ownerRunId:String?=null,
    private val scopedRepository:com.user.localagent.data.LocalRepository=app.repository,
    private val ownerThreadId:String?=null,
    private val verifyAccess:suspend()->Unit={}) {
    private val context=context.applicationContext
    val models=ImageModelManager(this.context)
    private val engine=ImageGenerationEngine(this.context)
    private var standaloneEngine:com.user.localagent.inference.AutomaticComputeEngine?=null
    private val executionEngine get()=requestEngine?:standaloneEngine?:app.engine
    private val resourceAware get()=requestEngine!=null||standaloneEngine!=null
    private val operation=java.util.concurrent.atomic.AtomicBoolean(false)
    private val pcSettings=com.user.localagent.compute.PcComputeSettings.get(this.context)
    private var pcImages=com.user.localagent.compute.RemoteImageClient(this.context,computeOwner=ownerRunId)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutableState=MutableStateFlow(ImageStudioState())
    val state:StateFlow<ImageStudioState> = mutableState.asStateFlow()
    val residentImageModelPath:String? get()=engine.loadedModelPath
    @Volatile private var activeJob:Job?=null
    init {if(ownerRunId==null)scope.launch {
        runCatching {scopedRepository.latestNote("generated-images")}.getOrNull()?.let {note->
            runCatching {
                val info=Json.parseToJsonElement(note.body).jsonObject
                val name=info.getValue("fileName").jsonPrimitive.content
                require(Regex("image-[a-f0-9-]{36}\\.png").matches(name))
                val directory=File(this@ImageStudioController.context.filesDir,"generated-images").canonicalFile
                val file=File(directory,name).canonicalFile
                require(file.parentFile==directory&&file.isFile)
                mutableState.update {state->if(state.running||state.outputPath!=null)state else state.copy(outputPath=file.path,seed=info["seed"]?.jsonPrimitive?.longOrNull,prompt=info["prompt"]?.jsonPrimitive?.content.orEmpty(),phase="최근 생성 이미지",mode=info["mode"]?.jsonPrimitive?.content?:"txt2img",referenceSha256=info["referenceSha256"]?.jsonPrimitive?.contentOrNull,referenceWidth=info["referenceWidth"]?.jsonPrimitive?.intOrNull,referenceHeight=info["referenceHeight"]?.jsonPrimitive?.intOrNull,strength=info["strength"]?.jsonPrimitive?.floatOrNull,computeLocation=info["computeLocation"]?.jsonPrimitive?.content?:"phone",sourceModelId=info["modelFile"]?.jsonPrimitive?.contentOrNull)}
            }
        }
    }}

    fun generate(prompt:String,steps:Int?=null,seed:Long?=null,negativePrompt:String="",referenceUri:Uri?=null,strength:Float=.65f):Boolean {
        if(prompt.isBlank()||prompt.length>2000||negativePrompt.length>1000||steps?.let {it !in 1..50}==true||seed?.let {it<0}==true)return false
        if(referenceUri!=null&&(referenceUri.scheme!="content"||referenceUri.authority.isNullOrBlank()||!strength.isFinite()||strength !in .4f..0.85f))return false
        if(!operation.compareAndSet(false,true)){mutableState.update {it.copy(error="현재 이미지 작업을 마친 뒤 생성해주세요.")};return false}
        val computeOwner=UUID.randomUUID().toString()
        standaloneEngine=com.user.localagent.inference.AutomaticComputeEngine(context,ownerRunId=computeOwner,beforePhoneLoad={app.engine.unloadPhoneResidency()})
        pcImages=com.user.localagent.compute.RemoteImageClient(this.context,computeOwner=computeOwner)
        mutableState.update {it.copy(phase="준비 중",running=true,prompt=prompt,error=null,progress=0f,step=0,totalSteps=steps?:if(pcSettings.state.value.preferPcImages&&pcSettings.state.value.paired)50 else if(models.isQualityModel)PhoneImageQuality.STEPS else 8,mode=if(referenceUri==null)"txt2img"else"img2img",referenceSha256=null,referenceWidth=null,referenceHeight=null,strength=if(referenceUri==null)null else strength)}
        activeJob=scope.launch {
            try {perform(prompt,negativePrompt,steps,seed?:-1,referenceUri,strength)}
            catch(cancel:CancellationException){mutableState.update {it.copy(phase="중단했습니다",error=null)};throw cancel}
            catch(error:Exception){mutableState.update {it.copy(phase="이미지를 만들지 못했습니다",error=error.message?.take(400))}}
            finally {activeJob=null;standaloneEngine=null;operation.set(false);mutableState.update{it.copy(running=false)};app.scheduleIdleUnload()}
        }
        return true
    }
    /** MainActivity already holds the request's compute lease. No second lease is acquired. */
    suspend fun generateInCurrentRequest(prompt:String,negativePrompt:String="",steps:Int?=null,seed:Long=-1,referenceUri:Uri?=null,strength:Float=.65f):GeneratedImage {
        check(resourceAware||RuntimeGate.inference.get())
        require(prompt.isNotBlank()&&prompt.length<=2000&&negativePrompt.length<=1000&&(steps==null||steps in 1..50)&&seed>=-1)
        require(referenceUri==null||referenceUri.scheme=="content"&&!referenceUri.authority.isNullOrBlank()&&strength.isFinite()&&strength in .4f..0.85f)
        check(operation.compareAndSet(false,true)){"현재 이미지 작업을 마친 뒤 생성해주세요."}
        activeJob=currentCoroutineContext()[Job]
        mutableState.update {it.copy(phase="준비 중",running=true,prompt=prompt,error=null,progress=0f,step=0,totalSteps=steps?:if(pcSettings.state.value.preferPcImages&&pcSettings.state.value.paired)50 else if(models.isQualityModel)PhoneImageQuality.STEPS else 8,mode=if(referenceUri==null)"txt2img"else"img2img",referenceSha256=null,referenceWidth=null,referenceHeight=null,strength=if(referenceUri==null)null else strength)}
        try{return perform(prompt,negativePrompt,steps,seed,referenceUri,strength)}finally{activeJob=null;operation.set(false);mutableState.update{it.copy(running=false)}}
    }
    private suspend fun perform(prompt:String,negative:String,requestedSteps:Int?,seed:Long,referenceUri:Uri?=null,strength:Float=.65f):GeneratedImage {
        app.cancelIdleUnload()
        val serviceOwner="image:${ownerRunId?:UUID.randomUUID()}"
        val busyLease=app.updates.acquireBusy(serviceOwner)
        var serviceLease:java.io.Closeable?=null
        var output:File?=null;var metadataId:String?=null;var metadataReceipt:com.user.localagent.data.NoteRecord?=null;var committed=false;var completed=false
        var reference:ReferenceImage?=null
        try {
            verifyAccess()
            serviceLease=ImageSessionRegistry.register(serviceOwner,ownerThreadId,state,::cancel,
                onRegistered={if(RuntimeGate.foreground.get())ContextCompat.startForegroundService(context,Intent(context,ImageGenerationService::class.java))},
                onLastClosed={context.stopService(Intent(context,ImageGenerationService::class.java));Unit})
            val remote=if(pcSettings.state.value.preferPcImages&&pcSettings.state.value.paired)pcImages.available()else null
            models.refresh()
            val quality=models.isQualityModel
            val requested=if(remote==null&&!quality&&pcSettings.state.value.preferPcImages)requestedSteps?.takeIf{it<=8}else requestedSteps
            val steps=com.user.localagent.core.PcComputeContract.steps(remote!=null,requested,quality)
            val size=if(remote!=null||quality)1024 else 512
            val model=if(remote==null)models.verifiedModelFile()?:error("휴대폰에서 생성할 이미지 모델을 먼저 내려받아주세요.")else null
            mutableState.update {it.copy(totalSteps=steps,phase=if(remote!=null)"PC 정밀 계산 준비"else"휴대폰에서 생성 준비")}
            if(remote==null)waitForPhoneResources(3L*1024*1024*1024)
            if(referenceUri!=null){
                mutableState.update {it.copy(phase="참고 사진 준비 중")}
                reference=prepareReference(referenceUri,size)
                mutableState.update {it.copy(referenceSha256=reference!!.sha256,referenceWidth=reference!!.sourceWidth,referenceHeight=reference!!.sourceHeight)}
            }
            val effectivePrompt=translateIfNeeded(prompt)
            executionEngine.unload()
            currentCoroutineContext().ensureActive()
            val progress:(com.user.localagent.inference.ImageGenerationProgress)->Unit={value->
                mutableState.update {it.copy(phase=value.phase,progress=value.fraction,step=value.step,totalSteps=value.totalSteps)}
            }
            val result=if(remote!=null){
                try{if(resourceAware)ownedCompute().withPcResource(cleanup={pcImages.cancel()}) {
                    pcImages.generate(remote,effectivePrompt,negative,seed,steps,reference?.bitmap,progress)
                }else pcImages.generate(remote,effectivePrompt,negative,seed,steps,reference?.bitmap,progress)}
                catch(cancel:CancellationException){throw cancel}
                catch(error:java.io.IOException){
                    pcSettings.report("PC 연결이 끊겨 휴대폰에서 다시 생성합니다.")
                    models.refresh()
                    reference?.bitmap?.recycle();reference=referenceUri?.let{prepareReference(it,if(models.isQualityModel)1024 else 512)}
                    val local=models.verifiedModelFile()?:throw error
                    runPhone(local,effectivePrompt,negative,seed,if(models.isQualityModel)PhoneImageQuality.STEPS else 8,reference?.bitmap,strength,progress)
                }
            }else runPhone(checkNotNull(model),effectivePrompt,negative,seed,steps,reference?.bitmap,strength,progress)
            try {
                val outputPixelHash=pixelHash(result.bitmap)
                val referenceChanged=reference?.let {source->
                    check(outputPixelHash!=source.pixelSha256){"참고 사진과 다른 변환 결과를 확인하지 못했습니다."}
                    changedFraction(source.bitmap,result.bitmap).also {check(it>0.0){"참고 사진의 변환 픽셀을 확인하지 못했습니다."}}
                }
                val directory=File(context.filesDir,"generated-images").apply {check(isDirectory||mkdirs())}
                output=File(directory,"image-${UUID.randomUUID()}.png")
                val temporary=File(directory,output!!.name+".tmp")
                try {
                    java.io.FileOutputStream(temporary).use {stream->check(result.bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));stream.fd.sync()}
                    currentCoroutineContext().ensureActive();check(temporary.renameTo(output!!))
                }finally {temporary.delete()}
                val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeFile(output!!.path,bounds)
                check(output!!.length()>44&&bounds.outWidth==result.width&&bounds.outHeight==result.height){"생성 파일의 이미지 크기를 재조회로 확인하지 못했습니다."}
                val artifact=GeneratedImage(output!!,result.seed,result.width,result.height,result.elapsedMillis,result.computeLocation,result.sourceModelId)
                metadataId=UUID.randomUUID().toString()
                val metadata=buildJsonObject {put("type","generated_image_v2");put("mode",if(reference==null)"txt2img"else"img2img");put("fileName",artifact.file.name);put("prompt",prompt);put("effectivePrompt",effectivePrompt);put("negativePrompt",negative);put("seed",artifact.seed);put("steps",result.samplingSteps);put("requestedSteps",requestedSteps);put("samplingSteps",result.samplingSteps);put("computeLocation",result.computeLocation);put("modelFile",result.sourceModelId?:model?.name?:"phone_fallback");put("modelSha256",result.sourceModelSha256);put("width",result.width);put("height",result.height);put("outputPixelSha256",outputPixelHash)
                    reference?.let {source->put("referenceSha256",source.sha256);put("referenceWidth",source.sourceWidth);put("referenceHeight",source.sourceHeight);put("referencePixelSha256",source.pixelSha256);put("referenceNormalizedWidth",source.bitmap.width);put("referenceNormalizedHeight",source.bitmap.height);put("normalization","EXIF orientation; aspect-fit white canvas; original unchanged");if(result.computeLocation=="phone")put("strength",strength)else put("referenceMode","instruction_conditioned_edit");put("changedPixelFraction",referenceChanged)}
                }.toString()
                verifyAccess()
                metadataReceipt=scopedRepository.saveNote("생성 이미지",metadata,"generated-images",metadataId)
                val saved=scopedRepository.getNote(metadataId!!)
                check(saved!=null&&saved.body==metadata&&saved.collection=="generated-images"){"생성 이미지 메타데이터 저장을 확인하지 못했습니다."}
                currentCoroutineContext().ensureActive();verifyAccess();committed=true;completed=true
                mutableState.update {it.copy(outputPath=artifact.file.path,seed=artifact.seed,progress=1f,step=result.samplingSteps,totalSteps=result.samplingSteps,error=null,computeLocation=result.computeLocation,sourceModelId=result.sourceModelId,strength=if(result.computeLocation=="phone"&&reference!=null)strength else null)}
                return artifact
            }finally {result.bitmap.recycle()}
        }catch(cancel:CancellationException){mutableState.update {it.copy(phase="중단했습니다",error=null)};throw cancel}
        catch(error:Exception){mutableState.update {it.copy(phase="이미지를 만들지 못했습니다",error=error.message?.take(400))};throw error}
        finally {
            withContext(NonCancellable+Dispatchers.IO){
                try {engine.unload()}finally {
                    try {executionEngine.unload()}finally {
                        reference?.bitmap?.recycle()
                        if(!committed){
                            val deleteOutput=when {
                                metadataId==null->true
                                metadataReceipt==null->false
                                else->runCatching {scopedRepository.deleteGeneratedImageIfUnchanged(checkNotNull(metadataReceipt))}.getOrDefault(false)
                            }
                            if(deleteOutput)output?.delete()
                        }
                        mutableState.update {it.copy(running=false,phase=if(completed)"완료 · 스마트폰에 저장" else it.phase)}
                        try{serviceLease?.close()}finally{busyLease.close()}
                    }
                }
            }
        }
    }
    private fun ownedCompute()=checkNotNull(executionEngine as? com.user.localagent.inference.ComputeExecutionEngine){"실행에 연결된 계산 엔진이 필요합니다."}
    private suspend fun runPhone(model:File,prompt:String,negative:String,seed:Long,steps:Int,reference:Bitmap?,strength:Float,progress:(com.user.localagent.inference.ImageGenerationProgress)->Unit):com.user.localagent.inference.ImageGenerationResult =
        if(resourceAware)ownedCompute().withPhoneResource(cleanup={engine.cancel();engine.unload()}) {
            runPhoneOwned(model,prompt,negative,seed,steps,reference,strength,progress)
        }else runPhoneOwned(model,prompt,negative,seed,steps,reference,strength,progress)
    private suspend fun runPhoneOwned(model:File,prompt:String,negative:String,seed:Long,steps:Int,reference:Bitmap?,strength:Float,progress:(com.user.localagent.inference.ImageGenerationProgress)->Unit):com.user.localagent.inference.ImageGenerationResult {
        val quality=models.isQualityModel
        return withTimeout(PhoneImageQuality.TIMEOUT_MILLIS){coroutineScope {
            waitForPhoneResources(if(quality)PhoneImageQuality.START_AVAILABLE_BYTES else 3L*1024*1024*1024)
            mutableState.update{it.copy(phase=if(quality)"휴대폰 SDXL 고품질 모델 준비 중"else"휴대폰 이미지 모델 준비 중",totalSteps=steps)}
            engine.load(model.path,threads=PhoneImageQuality.THREADS)
            engine.setThrottle(phoneBudget().milliseconds)
            val monitor=launch(Dispatchers.IO){
                var previous=-1
                while(isActive){
                    val budget=phoneBudget();engine.setThrottle(budget.milliseconds)
                    if(previous!=budget.milliseconds){
                        mutableState.update{it.copy(phase=budget.reason?:"휴대폰에서 이미지 계산을 계속합니다.")}
                        previous=budget.milliseconds
                    }
                    delay(1000)
                }
            }
            try {
                val result=engine.generate(prompt,negative,seed,steps,width=if(quality)1024 else 512,height=if(quality)1024 else 512,
                    guidanceScale=if(quality)7f else 1f,sampler=if(quality)ImageSampler.DPMPP_2M else ImageSampler.LCM,
                    initImage=reference,strength=strength,onProgress=progress)
                if(quality)result.copy(sourceModelId=PhoneImageQuality.MODEL_ID,sourceModelSha256=PhoneImageQuality.SHA256)else result
            }finally{monitor.cancelAndJoin();engine.setThrottle(0)}
        }}
    }
    private data class PhoneBudget(val milliseconds:Int,val reason:String?)
    private fun phoneBudget():PhoneBudget {
        val memory=ActivityManager.MemoryInfo().also{context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
        val call=context.getSystemService(android.media.AudioManager::class.java).mode!=android.media.AudioManager.MODE_NORMAL
        val power=context.getSystemService(PowerManager::class.java)
        val severe=power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE
        val other=power.isInteractive&&!RuntimeGate.foreground.get()
        return PhoneBudget(PhoneImageQuality.throttleMillis(call,severe,memory.lowMemory,memory.availMem,other),
            PhoneImageQuality.throttleReason(call,severe,memory.lowMemory,memory.availMem,other))
    }
    private suspend fun waitForPhoneResources(minimumBytes:Long){
        while(true){
            currentCoroutineContext().ensureActive()
            val memory=ActivityManager.MemoryInfo().also{context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
            if(!memory.lowMemory&&memory.availMem>=minimumBytes)return
            mutableState.update{it.copy(phase="모델을 올릴 메모리 여유를 기다리는 중")};delay(2000)
        }
    }
    private data class ReferenceImage(val bitmap:Bitmap,val sha256:String,val sourceWidth:Int,val sourceHeight:Int,val pixelSha256:String)
    /** Only a private bounded snapshot is decoded; the granted original URI is read-only. */
    private suspend fun prepareReference(uri:Uri,size:Int=512):ReferenceImage=withContext(Dispatchers.IO){
        require(uri.scheme=="content"&&!uri.authority.isNullOrBlank())
        val source=File.createTempFile("image-reference-",".input",context.cacheDir)
        var decoded:Bitmap?=null;var oriented:Bitmap?=null;var normalized:Bitmap?=null;var success=false
        try {
            val digest=MessageDigest.getInstance("SHA-256");var count=0L
            context.contentResolver.openInputStream(uri)?.use {input->source.outputStream().use {out->
                val buffer=ByteArray(32*1024)
                while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;count+=n;require(count<=32L*1024*1024){"참고 사진은 32MiB 이하여야 합니다."};out.write(buffer,0,n);digest.update(buffer,0,n)}
            }}?:error("선택한 참고 사진을 열 수 없습니다.")
            require(count>0){"빈 사진은 변환할 수 없습니다."}
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(source.path,bounds)
            require(bounds.outWidth in 1..16384&&bounds.outHeight in 1..16384&&bounds.outWidth.toLong()*bounds.outHeight<=100_000_000){"사진 크기를 읽지 못했거나 디코딩 한도를 넘었습니다."}
            var sample=1;while(max(bounds.outWidth,bounds.outHeight)/sample>1024)sample*=2
            val actual=BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})?:error("참고 사진을 디코딩하지 못했습니다.")
            decoded=actual
            val exif=runCatching {ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix=Matrix().apply {when(exif){
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL->setScale(-1f,1f)
                ExifInterface.ORIENTATION_ROTATE_180->setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL->setScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE->{setRotate(90f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_90->setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE->{setRotate(-90f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_270->setRotate(-90f)
            }}
            val image=if(matrix.isIdentity)actual else Bitmap.createBitmap(actual,0,0,actual.width,actual.height,matrix,true)
            oriented=image
            val square=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);normalized=square
            val canvas=Canvas(square);canvas.drawColor(Color.WHITE)
            val scale=min(size.toFloat()/image.width,size.toFloat()/image.height);val width=image.width*scale;val height=image.height*scale
            canvas.drawBitmap(image,null,RectF((size-width)/2,(size-height)/2,(size+width)/2,(size+height)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            currentCoroutineContext().ensureActive()
            val result=ReferenceImage(square,hex(digest.digest()),bounds.outWidth,bounds.outHeight,pixelHash(square));success=true;result
        }finally {source.delete();if(oriented!==decoded)oriented?.recycle();decoded?.recycle();if(!success)normalized?.recycle()}
    }
    private fun pixelHash(bitmap:Bitmap):String {
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val digest=MessageDigest.getInstance("SHA-256");val rgb=ByteArray(pixels.size*3)
        pixels.forEachIndexed {index,pixel->rgb[index*3]=(pixel ushr 16).toByte();rgb[index*3+1]=(pixel ushr 8).toByte();rgb[index*3+2]=pixel.toByte()}
        return hex(digest.digest(rgb))
    }
    private fun changedFraction(reference:Bitmap,result:Bitmap):Double {
        require(reference.width==result.width&&reference.height==result.height)
        val a=IntArray(reference.width*reference.height);val b=IntArray(a.size)
        reference.getPixels(a,0,reference.width,0,0,reference.width,reference.height);result.getPixels(b,0,result.width,0,0,result.width,result.height)
        return a.indices.count {index->
            abs(((a[index] ushr 16)and 255)-((b[index] ushr 16)and 255))>2||
                abs(((a[index] ushr 8)and 255)-((b[index] ushr 8)and 255))>2||abs((a[index]and 255)-(b[index]and 255))>2
        }.toDouble()/a.size
    }
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt()and 255)}
    private suspend fun translateIfNeeded(prompt:String):String {
        if(!prompt.any {it in '\uAC00'..'\uD7A3'})return prompt
        mutableState.update {it.copy(phase="한국어 장면 설명 정리 중")}
        app.models.refresh()
        val translator=app.models.models.value.firstOrNull {it.id=="gemma-e2b"&&it.verified}
            ?:error("한국어 설명을 정리할 E2B 모델이 필요합니다. 영어 설명으로도 바로 생성할 수 있습니다.")
        executionEngine.load(translator.path,InferenceOptions(maxOutputTokens=256))
        val text=StringBuilder()
        withTimeout(90_000){executionEngine.generateChat(prompt,com.user.localagent.core.ClinicalImagePrompt.translationInstruction(prompt)).collect {text.append(it);require(text.length<=3000)}}
        return com.user.localagent.core.ClinicalImagePrompt.validateTranslation(prompt,text.toString())
    }
    fun cancel(){pcImages.cancel();engine.cancel();executionEngine.cancel();activeJob?.cancel()}
    suspend fun exportToGallery():Uri=withContext(Dispatchers.IO){
        val file=outputFile()?:error("먼저 이미지를 생성해주세요.")
        val values=ContentValues().apply {put(MediaStore.Images.Media.DISPLAY_NAME,file.name);put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/ZipHap.Agent");put(MediaStore.Images.Media.IS_PENDING,1)}
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("갤러리 저장 공간을 열지 못했습니다.")
        try {
            context.contentResolver.openOutputStream(uri,"w")?.use {out->file.inputStream().use {it.copyTo(out)}}?:error("갤러리 파일을 쓸 수 없습니다.")
            context.contentResolver.update(uri,ContentValues().apply {put(MediaStore.Images.Media.IS_PENDING,0)},null,null);uri
        }catch(error:Throwable){context.contentResolver.delete(uri,null,null);throw error}
    }
    private fun outputFile():File?=mutableState.value.outputPath?.let {File(it).canonicalFile}?.takeIf {
        it.isFile&&it.parentFile==File(context.filesDir,"generated-images").canonicalFile&&Regex("image-[a-f0-9-]{36}\\.png").matches(it.name)
    }
}
