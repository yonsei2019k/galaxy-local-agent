package com.user.localagent.compute

import android.content.Context
import com.user.localagent.core.PcComputeContract
import java.net.*
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class PcCandidate(val connection:PcConnection){val title:String get()="ZipHap PC · "+connection.serverId.take(6)}
data class PcPairingChallenge(val candidate:PcCandidate,val ticket:String,val serverNonce:String,val clientNonce:String)

/** First enrollment uses a PC-displayed one-time secret, bound to the discovered TLS identity. */
class PcPairingClient(context:Context) {
    private val context=context.applicationContext
    private val transport=PcTransport(this.context)
    private fun nonce()=ByteArray(32).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it.toInt()and 255)}
    suspend fun discover():List<PcCandidate> = withContext(Dispatchers.IO){
        check(PcLocalNetwork.allowed(context)){"PC 검색에 로컬 네트워크 권한이 필요합니다."}
        val found=linkedMapOf<String,PcCandidate>();val requestNonce=nonce()
        val data=buildJsonObject{put("service","ZipHapCompute");put("nonce",requestNonce);put("pairing",true)}.toString().toByteArray(Charsets.UTF_8)
        DatagramSocket().use {socket->
            socket.broadcast=true;socket.soTimeout=250
            val targets=mutableSetOf("255.255.255.255")
            runCatching{NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&!it.isLoopback}.forEach{network->network.interfaceAddresses.mapNotNull{it.broadcast?.hostAddress}.forEach{targets+=it}}}
            val deadline=System.nanoTime()+2_500_000_000L;var nextSend=0L
            while(System.nanoTime()<deadline&&found.size<8){
                currentCoroutineContext().ensureActive()
                if(System.nanoTime()>=nextSend){targets.take(8).forEach{address->runCatching{socket.send(DatagramPacket(data,data.size,InetAddress.getByName(address),18435))}};nextSend=System.nanoTime()+800_000_000L}
                val packet=DatagramPacket(ByteArray(2048),2048)
                try{socket.receive(packet)}catch(_:SocketTimeoutException){continue}
                runCatching {
                    val host=packet.address.hostAddress?:return@runCatching
                    require(PcComputeContract.privateIpv4(host))
                    val json=Json.parseToJsonElement(String(packet.data,0,packet.length,Charsets.UTF_8)).jsonObject
                    require(json["service"]?.jsonPrimitive?.content=="ZipHapCompute"&&json["version"]?.jsonPrimitive?.int==1)
                    require(json["nonce"]?.jsonPrimitive?.content==requestNonce&&json["pairingAvailable"]?.jsonPrimitive?.boolean==true)
                    val id=json.getValue("serverId").jsonPrimitive.content;require(UUID.fromString(id).toString()==id)
                    val certificate=json.getValue("certificateSha256").jsonPrimitive.content;require(certificate.matches(Regex("[a-f0-9]{64}")))
                    val port=json.getValue("port").jsonPrimitive.int;require(port in 1024..65535)
                    val endpoint="https://$host:$port";PcComputeContract.endpoint(endpoint)
                    found[id]=PcCandidate(PcConnection(id,endpoint,"0".repeat(64),certificate))
                }
            }
        }
        found.values.toList()
    }
    suspend fun start(candidate:PcCandidate):PcPairingChallenge=withContext(Dispatchers.IO){
        val client=nonce()
        val result=transport.json(candidate.connection,"/v1/pair/start","POST",buildJsonObject{put("clientNonce",client)},10_000)
        val ticket=result.getValue("ticketId").jsonPrimitive.content;require(UUID.fromString(ticket).toString()==ticket)
        val server=result.getValue("serverNonce").jsonPrimitive.content;require(server.matches(Regex("[a-f0-9]{64}")))
        PcPairingChallenge(candidate,ticket,server,client)
    }
    suspend fun finish(challenge:PcPairingChallenge,enteredCode:String)=withContext(Dispatchers.IO){
        val code=enteredCode.replace("-","").filterNot{it.isWhitespace()}.uppercase(Locale.ROOT)
        require(code.matches(Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}"))){"PC에 표시된 12자리 연결 코드를 입력해주세요."}
        val config=challenge.candidate.connection
        val message=listOf("ZipHapPair-v1",config.serverId,challenge.ticket,challenge.serverNonce,challenge.clientNonce,config.certificateSha256).joinToString("|")
        val proof=Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(code.toByteArray(Charsets.US_ASCII),"HmacSHA256"))}.doFinal(message.toByteArray(Charsets.US_ASCII)).joinToString(""){"%02x".format(it.toInt()and 255)}
        val result=transport.json(config,"/v1/pair/finish","POST",buildJsonObject{put("ticketId",challenge.ticket);put("proof",proof)},10_000)
        require(result["serverId"]?.jsonPrimitive?.content==config.serverId&&result["certificateSha256"]?.jsonPrimitive?.content==config.certificateSha256)
        currentCoroutineContext().ensureActive()
        PcComputeSettings.get(context).importPairing(JsonObject(result+mapOf("endpoint" to JsonPrimitive(config.endpoint))).toString())
    }
}
