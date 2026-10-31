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

class PcUnavailable:java.io.IOException("PC 연결을 사용할 수 없어 휴대폰에서 처리합니다.")

/** No public endpoint, redirect, cleartext credential or system-wide trust override. */
class PcTransport(context:Context) {
    private val context=context.applicationContext
    private val settings=PcComputeSettings.get(context)
    @Volatile private var lastDiscovery=0L
    @Volatile private var current:HttpsURLConnection?=null
    fun open(config:PcConnection,path:String,method:String="GET",body:JsonObject?=null,timeout:Int=30_000,computeOwner:String?=null):HttpsURLConnection {
        if(!PcLocalNetwork.allowed(context))throw PcUnavailable()
        require(Regex("/v1/[a-z0-9/-]+").matches(path))
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
        connection.connectTimeout=minOf(timeout,1500);connection.readTimeout=timeout;connection.requestMethod=method
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
            if(connection.responseCode !in 200..299)throw PcUnavailable()
            return Json.parseToJsonElement(String(read(connection,1024*1024),Charsets.UTF_8)).jsonObject
        }finally{connection.disconnect();if(current===connection)current=null}
    }
    suspend fun available(computeOwner:String?=null):PcConnection?=withContext(Dispatchers.IO){
        var config=settings.snapshot()?:return@withContext null
        if(!PcLocalNetwork.allowed(context)){
            settings.report("PC 사용에 로컬 네트워크 권한이 필요합니다. 지금은 휴대폰에서 처리합니다.")
            return@withContext null
        }
        suspend fun check(candidate:PcConnection):Boolean=try {
            val result=json(candidate,"/v1/health",timeout=1200,computeOwner=computeOwner)
            result["service"]?.jsonPrimitive?.content=="ZipHapCompute"&&result["serverId"]?.jsonPrimitive?.content==candidate.serverId&&
                (computeOwner==null||result["compute_owner_binding"]?.jsonPrimitive?.intOrNull==1)
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){false}
        if(check(config)){settings.report("PC 사용 가능 · 작업과 결과는 스마트폰에서 관리");return@withContext config}
        if(System.currentTimeMillis()-lastDiscovery>15_000){
            lastDiscovery=System.currentTimeMillis()
            val found=discover(config)
            if(found!=null&&check(found)){settings.updateEndpoint(found.endpoint);settings.report("PC 연결 확인됨");return@withContext found}
        }
        settings.report("PC 응답 없음 · 휴대폰 단독 처리")
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
