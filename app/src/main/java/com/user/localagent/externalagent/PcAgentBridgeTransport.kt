package com.user.localagent.externalagent

import android.content.Context
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.compute.PcConnection
import com.user.localagent.compute.PcTransport
import com.user.localagent.compute.PcUnavailable
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

internal class PcAgentBridgeTimeout(val stageCode:String,cause:SocketTimeoutException):
    java.io.IOException("PC 연결 응답 시간 초과 ($stageCode)",cause)

internal class PcAgentProviderBusy:PcUnavailable("PC가 다른 요청을 정리하고 있습니다. 잠시 뒤 다시 시도해 주세요.")

/** Injection seam for source-only tests; it conveys raw JSON so duplicate keys stay detectable. */
interface AgentBridgeTransport {
    suspend fun exchange(path:String,method:String,body:JsonObject?=null,phoneCapability:String?=null):String
    fun cancelNetwork()
}

/** The existing pin, pairing token, no-redirect policy and fixed parent compute-owner are reused. */
class PcAgentBridgeTransport(context:Context,private val parentRunId:String,private val beforeLaunchUpload:()->Unit={}):AgentBridgeTransport {
    companion object {
        const val PHONE_CAPABILITY_HEADER="X-ZipHap-Phone-Capability"
        internal fun acceptsPath(path:String)=path.matches(Regex("/v1/agent/sessions(?:/[a-f0-9-]{36}(?:/(?:claim|result|cancel|close|plan-ack|launch|provider|provider-cancel|evidence|context-capabilities))?)?"))
        internal fun timeoutStage(path:String,method:String):String {
            val operation=when {
                path=="/v1/agent/sessions" -> "REGISTER"
                path.substringAfterLast('/').length==36 -> "SESSION_STATUS"
                else -> path.substringAfterLast('/').replace('-','_').uppercase(java.util.Locale.ROOT)
            }
            return "PC_BRIDGE_TIMEOUT_${method}_$operation"
        }
    }
    private val settings=PcComputeSettings.get(context.applicationContext)
    private val transport=PcTransport(context.applicationContext)
    private var captured:PcConnection?=null
    init { ExternalAgentContract.uuid(parentRunId) }
    override suspend fun exchange(path:String,method:String,body:JsonObject?,phoneCapability:String?):String=withContext(Dispatchers.IO) {
        require(acceptsPath(path))
        require(method in setOf("GET","POST")&&(method!="GET"||body==null))
        phoneCapability?.let { require(it.matches(Regex("[A-Za-z0-9_-]{32,256}"))) }
        currentCoroutineContext().ensureActive()
        val config=captured?:transport.available(computeOwner=parentRunId)?.also { captured=it }?:throw PcUnavailable()
        val live=settings.snapshot()?:throw PcUnavailable()
        check(live.serverId==config.serverId&&live.certificateSha256==config.certificateSha256&&live.token==config.token){"BRIDGE_PAIRING_CHANGED"}
        val bytes=body?.toString()?.toByteArray(Charsets.UTF_8)
        require(bytes==null||bytes.size<=ExternalAgentContract.MAX_WIRE_BYTES)
        // open(body=null) does not connect yet, so role capability is set before any bytes are sent.
        val connection=transport.open(config,path,method,timeout=10_000,computeOwner=parentRunId)
        try {
            connection.setRequestProperty("X-ZipHap-Provider-Progress","1")
            phoneCapability?.let { connection.setRequestProperty(PHONE_CAPABILITY_HEADER,it) }
            if(bytes!=null){
                connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
            }
            transport.cancellable(connection) {
                if(bytes!=null)connection.outputStream.use {
                    if(path.endsWith("/launch"))beforeLaunchUpload()
                    it.write(bytes)
                }
                val status=connection.responseCode
                if(status==409&&path.endsWith("/launch")){
                    val payload=connection.errorStream?.buffered()?.use{stream->
                        val bytes=ByteArray(129)
                        var size=0
                        while(size<bytes.size){
                            val count=stream.read(bytes,size,bytes.size-size)
                            if(count<=0)break
                            size+=count
                        }
                        bytes.copyOf(size)
                    }
                    if(payload!=null&&payload.size<=128&&
                        runCatching{ExternalAgentContract.parseObject(payload.toString(Charsets.UTF_8))==
                            JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive("PROVIDER_BUSY")))}.getOrDefault(false)){
                        throw PcAgentProviderBusy()
                    }
                }
                if(status !in 200..299)throw PcUnavailable("외부 에이전트 세션이 만료되었거나 현재 요청과 맞지 않습니다.")
                val raw=transport.read(connection,ExternalAgentContract.MAX_WIRE_BYTES)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString()
            }.also { currentCoroutineContext().ensureActive() }
        }catch(error:SocketTimeoutException){
            currentCoroutineContext().ensureActive()
            throw PcAgentBridgeTimeout(timeoutStage(path,method),error)
        }finally { connection.disconnect() }
    }
    override fun cancelNetwork(){ transport.cancel() }
}
