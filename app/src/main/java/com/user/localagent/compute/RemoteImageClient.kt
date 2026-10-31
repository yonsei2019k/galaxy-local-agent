package com.user.localagent.compute

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

class RemoteImageClient(context:Context) {
    private val context=context.applicationContext
    private val transport=PcTransport(this.context)
    private val settings=PcComputeSettings.get(this.context)
    @Volatile private var jobId:String?=null
    @Volatile private var currentConfig:PcConnection?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val cancelRevision=AtomicLong()
    suspend fun available():PcConnection?=withContext(Dispatchers.IO){
        val config=transport.available()?:return@withContext null
        try {
            val health=transport.json(config,"/v1/health",timeout=1500)
            if(health["image_io"]?.jsonPrimitive?.content=="ram_only")config else {
                settings.report("PC 이미지 작업기 업데이트가 필요합니다. 휴대폰에서 처리합니다.");null
            }
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){null}
    }
    suspend fun generate(config:PcConnection,prompt:String,negative:String,seed:Long,steps:Int,reference:Bitmap?,onProgress:(ImageGenerationProgress)->Unit):ImageGenerationResult=withContext(Dispatchers.IO){
        val revision=cancelRevision.get()
        fun checkCancellation(){if(cancelRevision.get()!=revision)throw CancellationException("PC image cancelled")}
        val id=UUID.randomUUID().toString();jobId=id;currentConfig=config
        val actualSeed=if(seed<0)SecureRandom().nextLong().ushr(1)else seed
        val body=buildJsonObject {
            put("requestId",id);put("ioMode","ram_only");put("prompt",prompt);put("negativePrompt",negative);put("seed",actualSeed);put("steps",steps)
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
                if(!accepted)try {transport.json(config,"/v1/images","POST",body,15_000);accepted=true}
                catch(cancel:CancellationException){throw cancel}catch(error:Exception){if(it==1)throw error;delay(1000)}
            }
            var networkFailures=0
            withTimeout(60*60_000L){
                while(true){
                    currentCoroutineContext().ensureActive()
                    checkCancellation()
                    val report=try{transport.json(config,"/v1/images/$id",timeout=10_000).also {networkFailures=0}}
                    catch(cancel:CancellationException){throw cancel}
                    catch(error:Exception){if(++networkFailures>=3)throw PcUnavailable();onProgress(ImageGenerationProgress("PC 연결 다시 확인 중"));delay(2000);continue}
                    val phase=report.getValue("phase").jsonPrimitive.content
                    val step=report.getValue("step").jsonPrimitive.int
                    val total=report.getValue("steps").jsonPrimitive.int
                    require(step in 0..total&&total==steps)
                    val label=when(phase){"queued"->"PC 계산 대기";"waiting_resources"->"PC의 다른 작업이 끝나길 기다리는 중";"loading"->"PC 이미지 모델 준비 중";"sampling"->"PC에서 장면을 정교하게 만드는 중";"decoding"->"PC에서 이미지 디코딩 중";"completed"->"완성 이미지를 스마트폰으로 받는 중";"failed","cancelled","acknowledged"->throw PcUnavailable();else->error("알 수 없는 PC 작업 상태")}
                    onProgress(ImageGenerationProgress(label,step,total,if(total>0)step.toFloat()/total else 0f))
                    if(phase=="completed"){
                        require(report["modelId"]?.jsonPrimitive?.content==MODEL_ID&&report["modelSha256"]?.jsonPrimitive?.content==MODEL_SHA)
                        require(report["width"]?.jsonPrimitive?.int==1024&&report["height"]?.jsonPrimitive?.int==1024)
                        val connection=transport.open(config,"/v1/images/$id/png",timeout=30_000)
                        val bytes=try{transport.cancellable(connection){require(connection.responseCode==200);transport.read(connection,32*1024*1024)}}finally{connection.disconnect()}
                        val actual=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(actual==report["sha256"]?.jsonPrimitive?.content&&bytes.size==report["bytes"]?.jsonPrimitive?.int)
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                        require(bounds.outWidth==1024&&bounds.outHeight==1024&&bounds.outMimeType=="image/png")
                        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("PC 생성 이미지를 읽지 못했습니다.")
                        try{currentCoroutineContext().ensureActive();checkCancellation()}catch(cancel:CancellationException){bitmap.recycle();throw cancel}
                        delivered=true
                        val result=ImageGenerationResult(bitmap,report.getValue("seed").jsonPrimitive.long,1024,1024,report.getValue("elapsedMillis").jsonPrimitive.long,steps,computeLocation="pc",sourceModelId=MODEL_ID,sourceModelSha256=MODEL_SHA)
                        return@withTimeout result
                    }
                    delay(1000)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        }catch(error:Exception){checkCancellation();throw error}finally{
            withContext(NonCancellable+Dispatchers.IO){
                runCatching{transport.json(config,"/v1/images/$id"+if(delivered)"/ack"else"",if(delivered)"POST"else"DELETE",if(delivered)buildJsonObject{}else null,3000)}
            }
            jobId=null;currentConfig=null
        }
    }
    fun cancel(){
        cancelRevision.incrementAndGet()
        transport.cancel()
        val id=jobId;val config=currentConfig
        if(id!=null&&config!=null)scope.launch {runCatching{PcTransport(context).json(config,"/v1/images/$id","DELETE",timeout=2000)}}
    }
    companion object {
        const val MODEL_ID="FLUX.2-klein-base-4B-Q4_0"
        const val MODEL_SHA="c3a2854510677b7aa37dd7547d908c54889a76c6d6aa3ffe902fcaa092d1328b"
    }
}
