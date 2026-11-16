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

class RemoteImageClient(context:Context,private val computeOwner:String?=null,private val recoveryScope:String?=computeOwner,private val allowRecovery:Boolean=false) {
    init{require(computeOwner==null||runCatching{UUID.fromString(computeOwner).toString()==computeOwner}.getOrDefault(false))}
    private val context=context.applicationContext
    private val transport=PcTransport(this.context)
    private val settings=PcComputeSettings.get(this.context)
    private val recovery=recoveryScope?.let{PcImageRecoveryStore(this.context,it)}
    @Volatile private var pendingDelivery:Pair<PcConnection,String>?=null
    @Volatile private var cleanupTarget:Pair<PcConnection,String>?=null
    @Volatile private var discardId:String?=null
    @Volatile private var cleanupInstance:String?=null
    fun recoveryTicket():PcImageRecoveryTicket?=recovery?.read()
    private fun checkTicket(ticket:PcImageRecoveryTicket,config:PcConnection){
        require(ticket.owner==computeOwner&&ticket.validAt(System.currentTimeMillis())&&
            ticket.pair==PcImageRecoveryTicket.pair(config,settings.authorizationRevision)){"복구 작업의 PC 연결·소유권이 변경됐거나 보관 시간이 지났습니다."}
    }
    suspend fun acknowledgeSavedFile(file:java.io.File) = withContext(Dispatchers.IO) {
        val pending=pendingDelivery?:return@withContext
        require(file.isFile&&file.length() in 44..32L*1024*1024)
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
        recovery?.read()?.takeIf{it.id==pending.second}?.let {ticket->
            recovery.save(ticket.copy(context=JsonObject(ticket.context+mapOf("deliveredFile" to JsonPrimitive(file.name),"deliveredSha256" to JsonPrimitive(digest.digest().joinToString(""){"%02x".format(it)})))))
        }
        val result=transport.json(pending.first,"/v1/images/${pending.second}/ack","POST",buildJsonObject{},3000,computeOwner=computeOwner)
        check(result["cleanup_complete"]?.jsonPrimitive?.boolean==true)
        recovery?.clear(pending.second);pendingDelivery=null
    }
    suspend fun discardRecovery(ticket:PcImageRecoveryTicket)=withContext(Dispatchers.IO){
        require(ticket.owner==computeOwner)
        val current=settings.snapshot()
        val originalConnection=current!=null&&(ticket.context["connectionPin"]?.jsonPrimitive?.contentOrNull==PcImageRecoveryTicket.pair(current,0)||
            ticket.pair==PcImageRecoveryTicket.pair(current,settings.authorizationRevision))
        if(!originalConnection){recovery?.clear(ticket.id);return@withContext false}
        val config=transport.available()?:throw PcUnavailable()
        require(ticket.context["connectionPin"]?.jsonPrimitive?.contentOrNull==PcImageRecoveryTicket.pair(config,0)||ticket.pair==PcImageRecoveryTicket.pair(config,settings.authorizationRevision))
        val result=transport.json(config,"/v1/images/${ticket.id}","DELETE",timeout=10_000,computeOwner=computeOwner)
        check(result["cleanup_complete"]?.jsonPrimitive?.boolean==true){"PC 작업 정리가 끝난 뒤 다시 확인해주세요."};recovery?.clear(ticket.id);true
    }
    suspend fun recoveryReference(ticket:PcImageRecoveryTicket):Bitmap=withContext(Dispatchers.IO){
        check(allowRecovery){"이 요청은 PC 중간 저장을 허용하지 않습니다."}
        val config=transport.available()?:throw PcUnavailable();requireOwnerAccess(config);checkTicket(ticket,config)
        val connection=transport.open(config,"/v1/images/${ticket.id}/reference",timeout=30_000,computeOwner=computeOwner)
        val bytes=try{transport.cancellable(connection){requireOwnerAccess(config);require(connection.responseCode==200);transport.read(connection,8*1024*1024)}}finally{connection.disconnect()}
        requireOwnerAccess(config)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth==1024&&bounds.outHeight==1024&&bounds.outMimeType=="image/png")
        checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
    }
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
    suspend fun generate(config:PcConnection,prompt:String,negative:String,seed:Long,steps:Int,reference:Bitmap?,onProgress:(ImageGenerationProgress)->Unit,
        requestedResume:PcImageRecoveryTicket?=null,recoveryContext:JsonObject=buildJsonObject{}):ImageGenerationResult=withContext(Dispatchers.IO){
        check(requestedResume==null||allowRecovery){"이 요청은 PC 중간 저장을 허용하지 않습니다."}
        val resume=requestedResume?:if(allowRecovery)recovery?.read()?.also{check(it.owner==computeOwner){"다른 실행의 복구 작업을 새 작업으로 덮어쓰지 않습니다."}}else null
        requireOwnerAccess(config)
        if(reference!=null&&(resume?.model?:selectedModel.id)==PcImageModelCatalog.SDXL_ID)throw PcImageComputationFailure.fromReport("failed","REFERENCE_MODEL_NOT_SUPPORTED")
        val health=transport.json(config,"/v1/health",timeout=3000,computeOwner=computeOwner)
        val selectedId=resume?.model?:selectedModel.id
        val modelPin=PcImageModelCatalog.effectiveSelection(selectedId,reference!=null)
        com.user.localagent.core.PcComputeContract.steps(true,steps,pcModelId=selectedId)
        require(modelPin.id!=PcImageModelCatalog.FAST_ID||negative.isBlank()){"빠른 모델은 제외 문장을 지원하지 않습니다. 비우거나 Qwen을 선택해주세요."}
        require((health["image_models"] as? JsonObject)?.get(modelPin.id)?.jsonPrimitive?.content==modelPin.sha256){"선택한 PC 이미지 모델이 준비되지 않았습니다."}
        requireOwnerAccess(config)
        val revision=cancelRevision.get()
        fun checkCancellation(){if(cancelRevision.get()!=revision)throw CancellationException("PC image cancelled")}
        if(resume!=null){checkTicket(resume,config);require(steps==resume.steps&&(seed<0||seed==resume.seed))}
        cleanupInstance=health["image_recovery_instance"]?.jsonPrimitive?.contentOrNull?.takeIf{runCatching{UUID.fromString(it).toString()==it}.getOrDefault(false)}
        val recoverable=allowRecovery&&health["image_recovery"]?.jsonPrimitive?.intOrNull==1&&cleanupInstance!=null&&computeOwner!=null&&recovery!=null
        check(resume==null||recoverable){"PC 복구 작업기 업데이트가 필요합니다."}
        val id=resume?.id?:UUID.randomUUID().toString();jobId=id;currentConfig=config
        val actualSeed=resume?.seed?:if(seed<0)SecureRandom().nextLong().ushr(1)else seed
        val body=buildJsonObject {
            put("requestId",id);put("modelId",modelPin.id);put("ioMode",if(recoverable)"encrypted_checkpoint"else"ram_only");put("prompt",prompt);put("negativePrompt",negative);put("seed",actualSeed);put("steps",steps)
            reference?.let {bitmap->
                require(bitmap.width==1024&&bitmap.height==1024)
                val bytes=ByteArrayOutputStream().use {out->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray()}
                require(bytes.size<=8*1024*1024);put("referencePng",Base64.encodeToString(bytes,Base64.NO_WRAP))
            }
        }
        val requestDigest=MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray()).joinToString(""){"%02x".format(it)}
        if(resume!=null&&requestedResume==null)check(resume.context["requestDigest"]?.jsonPrimitive?.contentOrNull==requestDigest){"원래 복구 작업과 입력 조건이 다릅니다. 기존 작업을 덮어쓰지 않습니다."}
        if(recoverable&&resume==null)recovery!!.save(PcImageRecoveryTicket(id,checkNotNull(computeOwner),
            PcImageRecoveryTicket.pair(config,settings.authorizationRevision),selectedId,actualSeed,steps,System.currentTimeMillis(),
            JsonObject(recoveryContext+mapOf("connectionPin" to JsonPrimitive(PcImageRecoveryTicket.pair(config,0)),"requestDigest" to JsonPrimitive(requestDigest)))))
        var delivered=false
        var discard=false
        var route=config
        cleanupTarget=config to id
        try {
            var accepted=false
            repeat(2){
                if(!accepted){
                    requireOwnerAccess(config)
                    try {transport.json(config,if(resume==null)"/v1/images"else"/v1/images/$id/resume","POST",if(resume==null)body else buildJsonObject{if(health["image_resume_guard"]?.jsonPrimitive?.intOrNull==1)put("explicit",requestedResume!=null)},15_000,computeOwner=computeOwner);accepted=true}
                    catch(cancel:CancellationException){throw cancel}catch(error:Exception){if(it==1)throw error;delay(1000)}
                }
            }
            var networkFailures=0
            var routeRecoveryTried=false
            var resumeAttempts=0
            var unchangedPolls=0
            var lastProgressKey=""
            withTimeout(4*60*60_000L){
                while(true){
                    currentCoroutineContext().ensureActive()
                    checkCancellation()
                    requireOwnerAccess(config)
                    val report=try{transport.json(route,"/v1/images/$id",timeout=10_000,computeOwner=computeOwner).also {networkFailures=0;routeRecoveryTried=false}}
                    catch(cancel:CancellationException){throw cancel}
                    catch(error:Exception){
                        if(!routeRecoveryTried){
                            routeRecoveryTried=true
                            val found=transport.recoverOriginalRoute(config,route.endpoint,computeOwner)
                            currentCoroutineContext().ensureActive();checkCancellation();requireOwnerAccess(config)
                            if(found!=null){route=found;currentConfig=found;cleanupTarget=found to id}
                        }
                        if(++networkFailures>=3)throw PcUnavailable()
                        onProgress(ImageGenerationProgress("원래 PC의 LAN·Tailscale 연결 다시 확인 중"));delay(2000);continue
                    }
                    requireOwnerAccess(config)
                    val phase=report.getValue("phase").jsonPrimitive.content
                    val step=report.getValue("step").jsonPrimitive.int
                    val total=report.getValue("steps").jsonPrimitive.int
                    require(step in 0..total&&total==steps)
                    if(phase in setOf("resumable","suspended")){
                        val saved=report["checkpointStep"]?.jsonPrimitive?.intOrNull?:0
                        PcImageSuspended.fromReport(phase,report["paused"]?.jsonPrimitive?.boolean,saved,steps)?.let{throw it}
                        check(recoverable&&resumeAttempts++<2){"PC 복구 정보를 보관했습니다. 자원 상태를 확인한 뒤 ‘PC 작업 이어받기’를 눌러주세요."}
                        check(saved in 1..steps){"저장된 계산 지점이 없습니다. PC 상태를 확인한 뒤 새로 시작해야 합니다."}
                        onProgress(ImageGenerationProgress("PC ${saved}단계 저장 확인 · 원래 설정으로 이어서 계산 준비",saved,total,saved.toFloat()/total))
                        delay(5000L*resumeAttempts)
                        requireOwnerAccess(config)
                        val latest=transport.json(route,"/v1/images/$id",timeout=10_000,computeOwner=computeOwner)
                        requireOwnerAccess(config)
                        val latestPhase=latest.getValue("phase").jsonPrimitive.content
                        if(latestPhase !in setOf("resumable","suspended"))continue
                        val latestSaved=latest["checkpointStep"]?.jsonPrimitive?.intOrNull?:0
                        require(latest.getValue("steps").jsonPrimitive.int==steps)
                        PcImageSuspended.fromReport(latestPhase,latest["paused"]?.jsonPrimitive?.boolean,latestSaved,steps)?.let{throw it}
                        require(latestSaved in 1..steps)
                        try {
                            transport.json(route,"/v1/images/$id/resume","POST",buildJsonObject{if(health["image_resume_guard"]?.jsonPrimitive?.intOrNull==1)put("automatic",true)},15_000,computeOwner=computeOwner)
                        }catch(cancel:CancellationException){throw cancel}catch(error:Exception){
                            val stopped=try{
                                val current=transport.json(route,"/v1/images/$id",timeout=10_000,computeOwner=computeOwner)
                                requireOwnerAccess(config)
                                require(current.getValue("steps").jsonPrimitive.int==steps)
                                PcImageSuspended.fromReport(current.getValue("phase").jsonPrimitive.content,current["paused"]?.jsonPrimitive?.boolean,
                                    current["checkpointStep"]?.jsonPrimitive?.intOrNull?:0,steps)
                            }catch(cancel:CancellationException){throw cancel}catch(_:Exception){null}
                            if(stopped!=null)throw stopped
                            throw error
                        }
                        continue
                    }
                    if(phase in setOf("failed","cancelled","acknowledged"))throw PcImageComputationFailure.fromReport(phase,report["error"]?.jsonPrimitive?.contentOrNull)
                    val label=com.user.localagent.core.PcImageProgressPolicy.label(phase,report["stage"]?.jsonPrimitive?.contentOrNull)
                    onProgress(ImageGenerationProgress("${modelPin.id} · $label",step,total,if(total>0)step.toFloat()/total else 0f))
                    if(phase=="completed"){
                        PcImageModelCatalog.verifyResult(modelPin,report["modelId"]?.jsonPrimitive?.content,report["modelSha256"]?.jsonPrimitive?.content)
                        require(report["width"]?.jsonPrimitive?.int==1024&&report["height"]?.jsonPrimitive?.int==1024)
                        requireOwnerAccess(config)
                        val connection=transport.open(route,"/v1/images/$id/png",timeout=30_000,computeOwner=computeOwner)
                        val bytes=try{transport.cancellable(connection){requireOwnerAccess(config);require(connection.responseCode==200);transport.read(connection,32*1024*1024)}}finally{connection.disconnect()}
                        requireOwnerAccess(config)
                        val actual=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(actual==report["sha256"]?.jsonPrimitive?.content&&bytes.size==report["bytes"]?.jsonPrimitive?.int)
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                        require(bounds.outWidth==1024&&bounds.outHeight==1024&&bounds.outMimeType=="image/png")
                        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("PC 생성 이미지를 읽지 못했습니다.")
                        try{currentCoroutineContext().ensureActive();checkCancellation();requireOwnerAccess(config)}catch(error:Throwable){bitmap.recycle();throw error}
                        delivered=true
                        pendingDelivery=route to id
                        val result=ImageGenerationResult(bitmap,report.getValue("seed").jsonPrimitive.long,1024,1024,report.getValue("elapsedMillis").jsonPrimitive.long,steps,computeLocation="pc",sourceModelId=modelPin.id,sourceModelSha256=modelPin.sha256)
                        return@withTimeout result
                    }
                    val progressKey="$phase:$step:${report["stage"]?.jsonPrimitive?.contentOrNull}"
                    unchangedPolls=if(progressKey==lastProgressKey)unchangedPolls+1 else 0
                    lastProgressKey=progressKey
                    delay(com.user.localagent.core.PcImageProgressPolicy.pollDelayMillis(unchangedPolls))
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        }catch(error:Exception){discard=error is CancellationException&&error !is TimeoutCancellationException;if(discard)discardId=id;checkCancellation();throw error}finally{
            withContext(NonCancellable+Dispatchers.IO){
                // Cleanup is allowed against this exact original target even after local permission revocation.
                if(!delivered)runCatching{
                    val retain=recoverable&&!discard&&cancelRevision.get()==revision
                    val result=transport.json(route,"/v1/images/$id"+if(retain)"/suspend"else"",if(retain)"POST"else"DELETE",if(retain)buildJsonObject{}else null,10_000,computeOwner=computeOwner)
                    if(result["cleanup_complete"]?.jsonPrimitive?.boolean==true){cleanupTarget=null;if(!retain)recovery?.clear(id)}
                }
            }
            jobId=null;currentConfig=null
        }
    }
    fun cancel(){
        cancelRevision.incrementAndGet()
        transport.cancel()
        val id=jobId?:pendingDelivery?.second;val config=currentConfig?:pendingDelivery?.first
        discardId=id
        // Never replace the original cleanup target with settings.snapshot() after a re-pairing.
        if(id!=null&&config!=null)scope.launch {runCatching{val result=PcTransport(context).json(config,"/v1/images/$id","DELETE",timeout=10_000,computeOwner=computeOwner);if(result["cleanup_complete"]?.jsonPrimitive?.boolean==true){recovery?.clear(id);cleanupTarget=null;pendingDelivery=null}}}
    }
    /** A broker lease ending after download is not owner cancellation or delivery acknowledgement. */
    suspend fun releaseCompute(){
        val target=cleanupTarget?:return
        var route=target.first
        var routeRecoveryTried=false
        repeat(5){
            val verified=runCatching{
                val checker=PcTransport(context);val connection=checker.open(route,"/v1/images/${target.second}",timeout=3000,computeOwner=computeOwner)
                val status=try{checker.cancellable(connection){
                    val code=connection.responseCode
                    code to if(code==200)Json.parseToJsonElement(checker.read(connection,32768).toString(Charsets.UTF_8)).jsonObject else null
                }}finally{connection.disconnect()}
                if(status.first==404&&cleanupInstance!=null){
                    // Same live worker: an absent ID was never started, or was removed only after native exit.
                    val health=checker.json(route,"/v1/health",timeout=3000,computeOwner=computeOwner)
                    health["image_recovery_instance"]?.jsonPrimitive?.contentOrNull==cleanupInstance
                }else status.second?.get("cleanup_complete")?.jsonPrimitive?.booleanOrNull==true
            }.getOrElse { error->
                if(error is CancellationException)throw error
                if(!routeRecoveryTried){
                    routeRecoveryTried=true
                    val recovered=try{PcTransport(context).recoverOriginalRoute(target.first,route.endpoint,computeOwner)}
                        catch(cancel:CancellationException){throw cancel}catch(_:Exception){null}
                    if(recovered!=null){route=recovered;cleanupTarget=recovered to target.second}
                }
                false
            }
            if(verified){
                if(discardId==target.second)recovery?.clear(target.second)
                cleanupTarget=null;return
            }
            delay(500)
        }
        error("PC 계산 종료를 확인하지 못해 계산 자원을 유지했습니다. PC 연결 후 다시 확인해주세요.")
    }
    companion object {
        const val MODEL_ID=PcImageModelCatalog.QUALITY_ID
        const val MODEL_SHA=PcImageModelCatalog.QUALITY_SHA
    }
}
