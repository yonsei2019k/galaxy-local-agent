package com.user.localagent.images

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.user.localagent.AppContainer
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.ImageGenerationEngine
import com.user.localagent.inference.InferenceOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

data class ImageStudioState(val phase:String="만들고 싶은 장면을 적어주세요",val running:Boolean=false,val progress:Float=0f,
    val step:Int=0,val totalSteps:Int=0,val outputPath:String?=null,val seed:Long?=null,val error:String?=null,val prompt:String="")
data class GeneratedImage(val file:File,val seed:Long,val width:Int,val height:Int,val elapsedMillis:Long) {
    fun toolResult():String=buildJsonObject {put("success",true);put("fileName",file.name);put("seed",seed);put("width",width);put("height",height);put("elapsedMillis",elapsedMillis);put("location","app_private_generated_images")}.toString()
}

/** One compute lease covers local prompt translation, LLM release, diffusion and image release. */
class ImageStudioController(context:Context,private val app:AppContainer) {
    private val context=context.applicationContext
    val models=ImageModelManager(this.context)
    private val engine=ImageGenerationEngine(this.context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutableState=MutableStateFlow(ImageStudioState())
    val state:StateFlow<ImageStudioState> = mutableState.asStateFlow()
    @Volatile private var activeJob:Job?=null
    init {scope.launch {
        runCatching {app.repository.latestNote("generated-images")}.getOrNull()?.let {note->
            runCatching {
                val info=Json.parseToJsonElement(note.body).jsonObject
                val name=info.getValue("fileName").jsonPrimitive.content
                require(Regex("image-[a-f0-9-]{36}\\.png").matches(name))
                val directory=File(this@ImageStudioController.context.filesDir,"generated-images").canonicalFile
                val file=File(directory,name).canonicalFile
                require(file.parentFile==directory&&file.isFile)
                mutableState.update {state->if(state.running||state.outputPath!=null)state else state.copy(outputPath=file.path,seed=info["seed"]?.jsonPrimitive?.longOrNull,prompt=info["prompt"]?.jsonPrimitive?.content.orEmpty(),phase="최근 생성 이미지")}
            }
        }
    }}

    fun generate(prompt:String,steps:Int=4,seed:Long?=null,negativePrompt:String=""):Boolean {
        if(prompt.isBlank()||prompt.length>2000||negativePrompt.length>1000||steps !in 1..8||seed?.let {it<0}==true)return false
        if(!RuntimeGate.inference.compareAndSet(false,true)){mutableState.update {it.copy(error="다른 모델 작업이 끝난 뒤 생성해주세요.")};return false}
        mutableState.update {it.copy(phase="준비 중",running=true,prompt=prompt,error=null,progress=0f,step=0,totalSteps=steps)}
        activeJob=scope.launch {
            try {perform(prompt,negativePrompt,steps,seed?:-1)}
            catch(cancel:CancellationException){mutableState.update {it.copy(phase="중단했습니다",error=null)};throw cancel}
            catch(error:Exception){mutableState.update {it.copy(phase="이미지를 만들지 못했습니다",error=error.message?.take(400))}}
            finally {RuntimeGate.inference.set(false);activeJob=null;app.scheduleIdleUnload()}
        }
        return true
    }
    /** MainActivity already holds the request's compute lease. No second lease is acquired. */
    suspend fun generateInCurrentRequest(prompt:String,negativePrompt:String="",steps:Int=4,seed:Long=-1):GeneratedImage {
        check(RuntimeGate.inference.get())
        require(prompt.isNotBlank()&&prompt.length<=2000&&negativePrompt.length<=1000&&steps in 1..8&&seed>=-1)
        activeJob=currentCoroutineContext()[Job]
        mutableState.update {it.copy(phase="준비 중",running=true,prompt=prompt,error=null,progress=0f,step=0,totalSteps=steps)}
        try{return perform(prompt,negativePrompt,steps,seed)}finally{activeJob=null}
    }
    private suspend fun perform(prompt:String,negative:String,steps:Int,seed:Long):GeneratedImage {
        app.cancelIdleUnload();app.updates.setBusy(true)
        var output:File?=null;var metadataId:String?=null;var committed=false;var completed=false
        try {
            models.refresh()
            val model=models.verifiedModelFile()?:error("이미지 작업실에서 모델을 한 번 내려받아주세요. 이후에는 인터넷 없이 만들 수 있습니다.")
            if(context.getSystemService(PowerManager::class.java).currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE)error("휴대전화가 뜨거워 잠시 식힌 뒤 생성해주세요.")
            if(RuntimeGate.foreground.get())ContextCompat.startForegroundService(context,Intent(context,ImageGenerationService::class.java))
            val effectivePrompt=translateIfNeeded(prompt)
            app.engine.unload()
            currentCoroutineContext().ensureActive()
            val memory=ActivityManager.MemoryInfo().also {context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
            // S26 measurements reached 2.46M KiB PSS. Reserve about 3 GiB before loading.
            check(!memory.lowMemory&&memory.availMem>=3L*1024*1024*1024){"이미지 생성에 필요한 메모리가 부족합니다. 사용하지 않는 앱을 정리한 뒤 다시 시도해주세요."}
            mutableState.update {it.copy(phase="이미지 모델 준비 중")}
            engine.load(model.path,threads=4)
            val result=withTimeout(10*60_000L){engine.generate(effectivePrompt,negative,seed,steps,onProgress={progress->
                mutableState.update {it.copy(phase=progress.phase,progress=progress.fraction,step=progress.step,totalSteps=progress.totalSteps)}
            })}
            try {
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
                val artifact=GeneratedImage(output!!,result.seed,result.width,result.height,result.elapsedMillis)
                metadataId=UUID.randomUUID().toString()
                app.repository.saveNote("생성 이미지",buildJsonObject {put("type","generated_image_v1");put("fileName",artifact.file.name);put("prompt",prompt);put("effectivePrompt",effectivePrompt);put("negativePrompt",negative);put("seed",artifact.seed);put("steps",steps);put("modelFile",model.name)}.toString(),"generated-images",metadataId)
                currentCoroutineContext().ensureActive();committed=true;completed=true
                mutableState.update {it.copy(outputPath=artifact.file.path,seed=artifact.seed,progress=1f,step=steps,totalSteps=steps,error=null)}
                return artifact
            }finally {result.bitmap.recycle()}
        }catch(cancel:CancellationException){mutableState.update {it.copy(phase="중단했습니다",error=null)};throw cancel}
        catch(error:Exception){mutableState.update {it.copy(phase="이미지를 만들지 못했습니다",error=error.message?.take(400))};throw error}
        finally {
            withContext(NonCancellable+Dispatchers.IO){
                try {engine.unload()}finally {
                    try {app.engine.unload()}finally {
                        if(!committed){output?.delete();metadataId?.let {runCatching {app.repository.deleteNote(it)}}}
                        mutableState.update {it.copy(running=false,phase=if(completed)"완료 · 기기에서 생성" else it.phase)}
                        context.stopService(Intent(context,ImageGenerationService::class.java));app.updates.setBusy(false)
                    }
                }
            }
        }
    }
    private suspend fun translateIfNeeded(prompt:String):String {
        if(!prompt.any {it in '\uAC00'..'\uD7A3'})return prompt
        mutableState.update {it.copy(phase="한국어 장면 설명 정리 중")}
        app.models.refresh()
        val translator=app.models.models.value.firstOrNull {it.id=="gemma-e2b"&&it.verified}
            ?:error("한국어 설명을 정리할 E2B 모델이 필요합니다. 영어 설명으로도 바로 생성할 수 있습니다.")
        app.engine.load(translator.path,InferenceOptions(maxOutputTokens=256))
        val text=StringBuilder()
        withTimeout(90_000){app.engine.generateChat(prompt,"Translate the user's visual scene description into a concise English image prompt. Preserve the requested subject, style and details. This is translation only; another local tool renders the image. Output only the translated prompt, without commentary or questions.").collect {text.append(it);require(text.length<=3000)}}
        return text.toString().trim().takeIf {it.isNotBlank()}?:error("장면 설명을 정리하지 못했습니다.")
    }
    fun cancel(){engine.cancel();app.engine.cancel();activeJob?.cancel()}
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
