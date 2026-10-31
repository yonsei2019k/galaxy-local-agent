package com.user.localagent.compute

import com.user.localagent.core.PcImageModelCatalog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.user.localagent.inference.ImageGenerationProgress
import com.user.localagent.inference.ImageGenerationResult
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class RemoteImageClient(context:Context,private val computeOwner:String?=null) {
    init{require(computeOwner==null||runCatching{UUID.fromString(computeOwner).toString()==computeOwner}.getOrDefault(false))}
    private val context=context.applicationContext
    private val transport=PcTransport(this.context)
    private val settings=PcComputeSettings.get(this.context)
    private val selectedModel=PcImageModelCatalog.selection(settings.state.value.imageModelId)
    private val originalPair=settings.authorizationRevision.let {revision->
        val config=settings.snapshot()
        val permission=settings.state.value
        ImageComputePairPin(config?.serverId,config?.certificateSha256,config?.token,
            permission.paired&&permission.preferPcImages&&revision==settings.authorizationRevision,revision)
    }
    @Volatile private var jobId:String?=null
    @Volatile private var currentConfig:PcConnection?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val cancelRevision=AtomicLong()
    private fun requireOwnerAccess(config:PcConnection?=null) {
        if(computeOwner==null)return // Compatibility for an explicitly legacy, caller-owned session.
        val current=settings.snapshot();val state=settings.state.value
        val allowed=state.paired&&state.preferPcImages
        val revision=settings.authorizationRevision
        if(!originalPair.permits(current?.serverId,current?.certificateSha256,current?.token,allowed,revision)||
            config!=null&&!originalPair.permits(config.serverId,config.certificateSha256,config.token,allowed,revision))throw PcUnavailable()
    }
    suspend fun available():PcConnection?=withContext(Dispatchers.IO){
        try {
            requireOwnerAccess()
            val config=transport.available()?:return@withContext null
            requireOwnerAccess(config)
            val health=transport.json(config,"/v1/health",timeout=1500,computeOwner=computeOwner)
            requireOwnerAccess(config)
            if(health["image_io"]?.jsonPrimitive?.content=="ram_only"&&
                (computeOwner==null||health["compute_owner_binding"]?.jsonPrimitive?.intOrNull==1))config else {
                settings.report("PC 이미지 작업기 업데이트가 필요합니다. 휴대폰에서 처리합니다.");null
            }
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){null}
    }
    suspend fun generate(config:PcConnection,prompt:String,negative:String,seed:Long,steps:Int,reference:Bitmap?,onProgress:(ImageGenerationProgress)->Unit):ImageGenerationResult=withContext(Dispatchers.IO){
        requireOwnerAccess(config)
        val health=transport.json(config,"/v1/health",timeout=3000,computeOwner=computeOwner)
        val modelPin=selectedModel
        require((health["image_models"] as? JsonObject)?.get(modelPin.id)?.jsonPrimitive?.content==modelPin.sha256){"선택한 PC 이미지 모델이 준비되지 않았습니다."}
        requireOwnerAccess(config)
        val revision=cancelRevision.get()
        fun checkCancellation(){if(cancelRevision.get()!=revision)throw CancellationException("PC image cancelled")}
        val id=UUID.randomUUID().toString();jobId=id;currentConfig=config
        val actualSeed=if(seed<0)SecureRandom().nextLong().ushr(1)else seed
        val body=buildJsonObject {
            put("requestId",id);put("modelId",modelPin.id);put("ioMode","ram_only");put("prompt",prompt);put("negativePrompt",negative);put("seed",actualSeed);put("steps",steps)
            reference?.let {bitmap->
                require(bitmap.width==1024&&bitmap.height==1024)
                val bytes=ByteArrayOutputStream().use {out->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray()}
                require(bytes.size<=8*1024*1024);put("referencePng",Base64.encodeToString(bytes,Base64.NO_WRAP))
            }
        }
        var delivered=false
        try {
            var accepted=false
            repeat(2){
                if(!accepted){
                    requireOwnerAccess(config)
                    try {transport.json(config,"/v1/images","POST",body,15_000,computeOwner=computeOwner);accepted=true}
                    catch(cancel:CancellationException){throw cancel}catch(error:Exception){if(it==1)throw error;delay(1000)}
                }
            }
            var networkFailures=0
            withTimeout(4*60*60_000L){
                while(true){
                    currentCoroutineContext().ensureActive()
                    checkCancellation()
                    requireOwnerAccess(config)
                    val report=try{transport.json(config,"/v1/images/$id",timeout=10_000,computeOwner=computeOwner).also {networkFailures=0}}
                    catch(cancel:CancellationException){throw cancel}
                    catch(error:Exception){if(++networkFailures>=3)throw PcUnavailable();onProgress(ImageGenerationProgress("PC 연결 다시 확인 중"));delay(2000);continue}
                    requireOwnerAccess(config)
                    val phase=report.getValue("phase").jsonPrimitive.content
                    val step=report.getValue("step").jsonPrimitive.int
                    val total=report.getValue("steps").jsonPrimitive.int
                    require(step in 0..total&&total==steps)
                    val label=when(phase){"queued"->"PC 계산 대기";"waiting_resources"->"PC의 다른 작업이 끝나길 기다리는 중";"loading"->"PC 이미지 모델 준비 중";"sampling"->"PC에서 장면을 정교하게 만드는 중";"decoding"->"PC에서 이미지 디코딩 중";"completed"->"완성 이미지를 스마트폰으로 받는 중";"failed","cancelled","acknowledged"->throw PcUnavailable();else->error("알 수 없는 PC 작업 상태")}
                    onProgress(ImageGenerationProgress("${modelPin.id} · $label",step,total,if(total>0)step.toFloat()/total else 0f))
                    if(phase=="completed"){
                        PcImageModelCatalog.verifyResult(modelPin,report["modelId"]?.jsonPrimitive?.content,report["modelSha256"]?.jsonPrimitive?.content)
                        require(report["width"]?.jsonPrimitive?.int==1024&&report["height"]?.jsonPrimitive?.int==1024)
                        requireOwnerAccess(config)
                        val connection=transport.open(config,"/v1/images/$id/png",timeout=30_000,computeOwner=computeOwner)
                        val bytes=try{transport.cancellable(connection){requireOwnerAccess(config);require(connection.responseCode==200);transport.read(connection,32*1024*1024)}}finally{connection.disconnect()}
                        requireOwnerAccess(config)
                        val actual=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(actual==report["sha256"]?.jsonPrimitive?.content&&bytes.size==report["bytes"]?.jsonPrimitive?.int)
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                        require(bounds.outWidth==1024&&bounds.outHeight==1024&&bounds.outMimeType=="image/png")
                        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("PC 생성 이미지를 읽지 못했습니다.")
                        try{currentCoroutineContext().ensureActive();checkCancellation();requireOwnerAccess(config)}catch(error:Throwable){bitmap.recycle();throw error}
                        delivered=true
                        val result=ImageGenerationResult(bitmap,report.getValue("seed").jsonPrimitive.long,1024,1024,report.getValue("elapsedMillis").jsonPrimitive.long,steps,computeLocation="pc",sourceModelId=modelPin.id,sourceModelSha256=modelPin.sha256)
                        return@withTimeout result
                    }
                    delay(1000)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        }catch(error:Exception){checkCancellation();throw error}finally{
            withContext(NonCancellable+Dispatchers.IO){
                // Cleanup is allowed against this exact original target even after local permission revocation.
                runCatching{transport.json(config,"/v1/images/$id"+if(delivered)"/ack"else"",if(delivered)"POST"else"DELETE",if(delivered)buildJsonObject{}else null,3000,computeOwner=computeOwner)}
            }
            jobId=null;currentConfig=null
        }
    }
    fun cancel(){
        cancelRevision.incrementAndGet()
        transport.cancel()
        val id=jobId;val config=currentConfig
        // Never replace the original cleanup target with settings.snapshot() after a re-pairing.
        if(id!=null&&config!=null)scope.launch {runCatching{PcTransport(context).json(config,"/v1/images/$id","DELETE",timeout=2000,computeOwner=computeOwner)}}
    }
    companion object {
        const val MODEL_ID=PcImageModelCatalog.QUALITY_ID
        const val MODEL_SHA=PcImageModelCatalog.QUALITY_SHA
    }
}
