package com.user.localagent.compute

import android.content.Context
import com.user.localagent.core.PcComputeContract
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

open class PcUnavailable(message:String="PC에 연결되지 않아 실행하지 않았습니다. PC를 켜고 연결한 뒤 다시 요청해주세요."):java.io.IOException(message)
class PcBusy:PcUnavailable("PC가 다른 작업을 처리 중입니다. 끝난 뒤 다시 요청해주세요.")

/** No public endpoint, redirect, cleartext credential or system-wide trust override. */
class PcTransport(context:Context) {
    private val context=context.applicationContext
    private val settings=PcComputeSettings.get(context)
    @Volatile private var lastDiscovery=0L
    companion object {
        private val reachable = kotlinx.coroutines.flow.MutableStateFlow(false)
        internal val pairedReachable: kotlinx.coroutines.flow.StateFlow<Boolean> get() = reachable
        /** Shared by every transport instance: when, and on which network, this phone last saw the paired PC answer. */
        @Volatile var lastReachableAt=0L
        @Volatile var lastReachableNetwork:Long?=null
    }
    /** Health body of the last successful check by this instance; read right after [available]. */
    @Volatile var lastHealth:JsonObject?=null
        private set
    private fun networkHandle():Long?=runCatching{context.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork?.networkHandle}.getOrNull()
    @Volatile private var current:HttpsURLConnection?=null
    fun open(config:PcConnection,path:String,method:String="GET",body:JsonObject?=null,timeout:Int=30_000,computeOwner:String?=null):HttpsURLConnection {
        if(!PcLocalNetwork.allowed(context))throw PcUnavailable()
        require(Regex("/v1/[a-z0-9/-]+").matches(path)||
            method=="GET"&&Regex("/v1/collab/messages\\?after=[0-9]{1,15}&limit=(?:[1-9]|[1-4][0-9]|50)").matches(path))
        val target=PcComputeContract.endpoint(config.endpoint)
        val expected=config.certificateSha256
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain:Array<X509Certificate>?,authType:String?){throw java.security.cert.CertificateException()}
            override fun checkServerTrusted(chain:Array<X509Certificate>?,authType:String?){
                require(chain!=null&&chain.isNotEmpty()&&chain.size<=3)
                chain[0].checkValidity()
                val actual=MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString(""){"%02x".format(it.toInt()and 255)}
                if(!MessageDigest.isEqual(actual.toByteArray(),expected.toByteArray()))throw java.security.cert.CertificateException("Paired PC certificate mismatch")
            }
        }
        val tls=SSLContext.getInstance("TLS").apply {init(null,arrayOf<TrustManager>(trust),SecureRandom())}
        val connection=URL(config.endpoint+path).openConnection() as HttpsURLConnection
        current=connection
        connection.sslSocketFactory=tls.socketFactory
        connection.hostnameVerifier=HostnameVerifier {host,_->host==target.host}
        connection.instanceFollowRedirects=false;connection.useCaches=false
        connection.connectTimeout=minOf(timeout,4000);connection.readTimeout=timeout;connection.requestMethod=method
        connection.setRequestProperty("Authorization","Bearer "+config.token)
        computeOwner?.let {owner->
            require(runCatching {java.util.UUID.fromString(owner).toString()==owner}.getOrDefault(false)){"PC 작업의 원래 실행 ID가 필요합니다."}
            connection.setRequestProperty("X-ZipHap-Compute-Owner",owner)
        }
        connection.setRequestProperty("Cache-Control","no-store")
        if(body!=null){
            val bytes=body.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=12*1024*1024)
            connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use {it.write(bytes)}
        }
        return connection
    }
    fun json(config:PcConnection,path:String,method:String="GET",body:JsonObject?=null,timeout:Int=30_000,computeOwner:String?=null):JsonObject {
        val connection=open(config,path,method,body,timeout,computeOwner)
        try {
            if(connection.responseCode !in 200..299){
                val status=connection.responseCode
                val code=runCatching{Json.parseToJsonElement(connection.errorStream.bufferedReader().use{it.readText()}).jsonObject["error"]?.jsonPrimitive?.content}.getOrNull()
                com.user.localagent.FailureLog.record(context,"pc_http $path",null,"$status ${code.orEmpty()}")
                throw if(code=="PC_BUSY")PcBusy() else PcUnavailable()
            }
            return Json.parseToJsonElement(String(read(connection,1024*1024),Charsets.UTF_8)).jsonObject
        }finally{connection.disconnect();if(current===connection)current=null}
    }
    /** Like [json] but a 4xx answer with an error code is returned (status, body) for tools that explain refusals to the model. */
    fun jsonWithStatus(config:PcConnection,path:String,body:JsonObject,timeout:Int,
        maxResponseBytes:Int=1024*1024):Pair<Int,JsonObject> {
        val connection=open(config,path,"POST",body,timeout,null)
        try {
            val status=connection.responseCode
            if(status in 200..299)return status to Json.parseToJsonElement(String(read(connection,maxResponseBytes),Charsets.UTF_8)).jsonObject
            val error=runCatching{Json.parseToJsonElement(connection.errorStream.bufferedReader().use{it.readText().take(4096)}).jsonObject}.getOrNull()
            if(status !in 400..499||error?.get("error")==null){com.user.localagent.FailureLog.record(context,"pc_http $path",null,"$status");throw PcUnavailable()}
            return status to error
        }finally{connection.disconnect();if(current===connection)current=null}
    }
    /**
     * [waitForRestart]: when the PC answered recently, a failed check is most likely a worker restart
     * (the supervisor brings it back within ~15 s), so wait and retry instead of failing the request.
     */
    suspend fun available(computeOwner:String?=null,waitForRestart:Boolean=false):PcConnection?{
        probe(computeOwner)?.let{reachable.value=true;return it}
        if (computeOwner == null) reachable.value=false
        // A different network (left home Wi-Fi, switched to mobile data) is not a worker restart: fail fast.
        val sameNetwork=lastReachableNetwork!=null&&lastReachableNetwork==networkHandle()
        val delays=com.user.localagent.core.PcReconnectPolicy.retryDelays(waitForRestart&&sameNetwork,System.currentTimeMillis()-lastReachableAt)
        delays.forEachIndexed {index,wait->
            settings.report("PC 응답 대기 · 작업기 재시작 중일 수 있어 다시 확인합니다 (${index+1}/${delays.size})")
            delay(wait)
            probe(computeOwner)?.let{reachable.value=true;return it}
        }
        if(delays.isNotEmpty())settings.report("PC가 ${delays.sum()/1000}초 동안 응답하지 않았습니다 · PC 전원과 네트워크를 확인해주세요")
        return null
    }
    private suspend fun probe(computeOwner:String?):PcConnection?=withContext(Dispatchers.IO){
        var config=settings.snapshot()?:return@withContext null
        if(!PcLocalNetwork.allowed(context)){
            settings.report("PC 사용에 로컬 네트워크 권한이 필요합니다. 허용 전에는 AI 작업을 실행하지 않습니다.")
            return@withContext null
        }
        suspend fun check(candidate:PcConnection):Boolean=try {
            val result=json(candidate,"/v1/health",timeout=4000,computeOwner=computeOwner)
            result["overlayEndpoint"]?.jsonPrimitive?.contentOrNull?.let {overlay->runCatching{settings.updateOverlay(overlay)}}
            (result["service"]?.jsonPrimitive?.content=="ZipHapCompute"&&result["serverId"]?.jsonPrimitive?.content==candidate.serverId&&
                (computeOwner==null||result["compute_owner_binding"]?.jsonPrimitive?.intOrNull==1)).also{if(it){lastReachableAt=System.currentTimeMillis();lastReachableNetwork=networkHandle();lastHealth=result}}
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){false}
        if(check(config)){settings.report("PC 사용 가능 · 작업과 결과는 스마트폰에서 관리");return@withContext config}
        if(System.currentTimeMillis()-lastDiscovery>15_000){
            lastDiscovery=System.currentTimeMillis()
            val found=discover(config)
            if(found!=null&&check(found)){settings.updateEndpoint(found.endpoint);settings.report("PC 연결 확인됨");return@withContext found}
        }
        settings.snapshot()?.overlayEndpoint?.takeIf{it!=config.endpoint}?.let {overlay->
            val remote=config.copy(endpoint=overlay)
            if(check(remote)){settings.updateEndpoint(overlay);settings.report("PC 연결 확인됨 · Tailscale");return@withContext remote}
        }
        settings.report("PC 응답 없음 · 집 밖이면 휴대폰의 Tailscale을 켜주세요")
        null
    }
    private fun discover(config:PcConnection):PcConnection?=runCatching {
        DatagramSocket().use {socket->
            socket.broadcast=true;socket.soTimeout=700
            val nonce=ByteArray(32).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it.toInt()and 255)}
            val data=buildJsonObject{put("service","ZipHapCompute");put("nonce",nonce)}.toString().toByteArray()
            socket.send(DatagramPacket(data,data.size,InetAddress.getByName("255.255.255.255"),config.discoveryPort))
            repeat(6){
                val packet=DatagramPacket(ByteArray(2048),2048);socket.receive(packet)
                val address=packet.address.hostAddress?:return@repeat
                if(!PcComputeContract.privateIpv4(address))return@repeat
                val reply=Json.parseToJsonElement(String(packet.data,0,packet.length,Charsets.UTF_8)).jsonObject
                if(reply["serverId"]?.jsonPrimitive?.content!=config.serverId||reply["nonce"]?.jsonPrimitive?.content!=nonce)return@repeat
                val port=reply.getValue("port").jsonPrimitive.int;require(port in 1024..65535)
                val proof=Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(config.token.toByteArray(),"HmacSHA256"))}.doFinal("${config.serverId}|$nonce|$port".toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
                if(MessageDigest.isEqual(proof.toByteArray(),reply.getValue("proof").jsonPrimitive.content.toByteArray()))return@runCatching config.copy(endpoint="https://$address:$port")
            }
            null
        }
    }.getOrNull()
    /** Read-only recovery against the original certificate/token/identity, including after revocation.
     * The caller still fences normal work; cleanup may only inspect that original job. */
    internal suspend fun recoverOriginalRoute(original:PcConnection,failedEndpoint:String,computeOwner:String?):PcConnection?=
        withContext(Dispatchers.IO){
            findOriginalPcRoute(original,settings.snapshot(),failedEndpoint){candidate->
                val health=json(candidate,"/v1/health",timeout=3000,computeOwner=computeOwner)
                health["service"]?.jsonPrimitive?.content=="ZipHapCompute"&&
                    health["serverId"]?.jsonPrimitive?.content==original.serverId&&
                    (computeOwner==null||health["compute_owner_binding"]?.jsonPrimitive?.intOrNull==1)
            }?.also{settings.updateEndpointIfSamePair(original,it.endpoint)}
        }
    fun read(connection:HttpsURLConnection,limit:Int):ByteArray {
        val output=ByteArrayOutputStream()
        connection.inputStream.use {input->val bytes=ByteArray(32*1024);while(true){val count=input.read(bytes);if(count<0)break;require(output.size()+count<=limit);output.write(bytes,0,count)}}
        return output.toByteArray()
    }
    suspend fun <T> cancellable(connection:HttpsURLConnection,block:()->T):T=suspendCancellableCoroutine {continuation->
        current=connection
        continuation.invokeOnCancellation{connection.disconnect()}
        try {if(continuation.isActive)continuation.resumeWith(Result.success(block()))}catch(error:Throwable){if(continuation.isActive)continuation.resumeWith(Result.failure(error))}
        finally{current=null}
    }
    fun cancel(){current?.disconnect()}
}
