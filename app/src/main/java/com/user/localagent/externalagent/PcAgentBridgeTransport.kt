package com.user.localagent.externalagent

import android.content.Context
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.compute.PcConnection
import com.user.localagent.compute.PcTransport
import com.user.localagent.compute.PcUnavailable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/** Injection seam for source-only tests; it conveys raw JSON so duplicate keys stay detectable. */
interface AgentBridgeTransport {
    suspend fun exchange(path:String,method:String,body:JsonObject?=null,phoneCapability:String?=null):String
    fun cancelNetwork()
}

/** The existing pin, pairing token, no-redirect policy and fixed parent compute-owner are reused. */
class PcAgentBridgeTransport(context:Context,private val parentRunId:String):AgentBridgeTransport {
    companion object { const val PHONE_CAPABILITY_HEADER="X-ZipHap-Phone-Capability" }
    private val settings=PcComputeSettings.get(context.applicationContext)
    private val transport=PcTransport(context.applicationContext)
    private var captured:PcConnection?=null
    init { ExternalAgentContract.uuid(parentRunId) }
    override suspend fun exchange(path:String,method:String,body:JsonObject?,phoneCapability:String?):String=withContext(Dispatchers.IO) {
        require(path.matches(Regex("/v1/agent/sessions(?:/[a-f0-9-]{36}(?:/(?:claim|result|cancel|close|plan-ack|launch|provider|provider-cancel|evidence))?)?")))
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
            phoneCapability?.let { connection.setRequestProperty(PHONE_CAPABILITY_HEADER,it) }
            if(bytes!=null){
                connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
            }
            transport.cancellable(connection) {
                if(bytes!=null)connection.outputStream.use { it.write(bytes) }
                if(connection.responseCode !in 200..299)throw PcUnavailable("외부 에이전트 세션이 만료되었거나 현재 요청과 맞지 않습니다.")
                val raw=transport.read(connection,ExternalAgentContract.MAX_WIRE_BYTES)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString()
            }.also { currentCoroutineContext().ensureActive() }
        }finally { connection.disconnect() }
    }
    override fun cancelNetwork(){ transport.cancel() }
}
