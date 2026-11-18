package com.user.localagent.collab

import android.content.Context
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.compute.PcConnection
import com.user.localagent.compute.PcTransport
import com.user.localagent.compute.PcUnavailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

/** Data-only cooperation feed. Receiving a message never starts a model, changes permission or runs a tool. */
internal class CollaborationRoom private constructor(context:Context) {
    private class CursorReset(val first:Long):Exception()
    private class StalePairing:Exception()
    private class Capture(val epoch:Long,val config:PcConnection)
    companion object {
        @Volatile private var instance:CollaborationRoom?=null
        fun get(context:Context)=instance?:synchronized(this){instance?:CollaborationRoom(context.applicationContext).also{instance=it}}
    }
    private val settings=PcComputeSettings.get(context)
    private val transport=PcTransport(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutex=Mutex()
    private var cursor=0L
    private var receiptEpoch=0L
    private var openRequest=0L
    private var receipt:JsonObject?=null
    private var pairedEpoch=settings.authorizationRevision
    private val messages=linkedMapOf<Long,JsonObject>()
    private val mutable=MutableStateFlow(snapshot(false,false,"개인 PC에 연결해 협업 기록을 확인하세요."))
    val state=mutable.asStateFlow()
    init {
        scope.launch {
            combine(settings.state,settings.authorityChanges){_,epoch->epoch}.collect {
                if(it!=pairedEpoch||settings.snapshot()==null)invalidatePairing()
            }
        }
    }

    private fun invalidatePairing(){
        transport.cancel();pairedEpoch=settings.authorizationRevision;messages.clear();cursor=0;receipt=null
        mutable.value=snapshot(false,false,"PC 연결이 변경됐습니다. 협업 기록을 다시 확인해주세요.")
    }
    private fun capture(expected:Long):Capture? {
        if(pairedEpoch!=settings.authorizationRevision||settings.snapshot()==null)invalidatePairing()
        if(expected!=pairedEpoch)return null
        return settings.snapshot()?.let{Capture(expected,it)}
    }
    private fun current(captured:Capture):Boolean {
        val live=settings.snapshot()?:return false
        return captured.epoch==settings.authorizationRevision&&live.serverId==captured.config.serverId&&
            live.certificateSha256==captured.config.certificateSha256&&live.token==captured.config.token
    }

    private fun snapshot(available:Boolean,loading:Boolean,status:String)=buildJsonObject {
        put("available",available);put("loading",loading);put("status",status);put("cursor",cursor);put("openRequest",openRequest);put("bindingEpoch",pairedEpoch)
        put("messages",JsonArray(messages.values.toList()));receipt?.let{put("sendReceipt",it)}
    }
    fun open(){openRequest++;mutable.value=JsonObject(mutable.value+mapOf("openRequest" to JsonPrimitive(openRequest)));refresh()}
    fun refresh(expectedEpoch:Long=settings.authorizationRevision){
        val captured=capture(expectedEpoch)?:return
        if(!mutex.isLocked)scope.launch{mutex.withLock{if(current(captured))fetch(captured)}}
    }
    fun post(clientId:String,text:String,recipient:String,expectedEpoch:Long){
        val captured=capture(expectedEpoch)?:return
        scope.launch{mutex.withLock{
        if(!current(captured)){invalidatePairing();return@withLock}
        if(runCatching{UUID.fromString(clientId).toString()==clientId}.getOrDefault(false)!=true||text.isBlank()||text.length>8000||recipient !in setOf("user","codex","claude","all"))return@withLock
        receipt=null;mutable.value=snapshot(mutable.value["available"]?.jsonPrimitive?.boolean==true,true,"협업 기록을 저장하고 있습니다.")
        var accepted=false
        try {
            val result=exchange(captured,"POST","/v1/collab/messages",buildJsonObject{put("clientMessageId",clientId);put("text",text);put("recipient",recipient)})
            if(!current(captured))throw StalePairing()
            require(result["dataOnly"]?.jsonPrimitive?.boolean==true&&result["executionAuthority"]?.jsonPrimitive?.boolean==false)
            val message=checkedMessage(result.getValue("message").jsonObject)
            require(message["clientMessageId"]?.jsonPrimitive?.content==clientId&&message["sender"]?.jsonPrimitive?.content=="user"&&message["text"]?.jsonPrimitive?.content==text&&message["recipient"]?.jsonPrimitive?.content==recipient)
            messages[message.getValue("sequence").jsonPrimitive.long]=message
            while(messages.size>200)messages.remove(messages.keys.min())
            accepted=true
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){/* Only a host acknowledgement is delivery evidence. */}
        if(!current(captured)){invalidatePairing();return@withLock}
        receipt=buildJsonObject{put("clientMessageId",clientId);put("accepted",accepted);put("epoch",++receiptEpoch);put("bindingEpoch",captured.epoch)}
        mutable.value=snapshot(accepted,false,if(accepted)"협업 기록에 저장했습니다. 각 에이전트 세션에서 수신할 수 있습니다."else "전송을 확인하지 못했습니다. 입력을 보존했으며 같은 ID로 재시도할 수 있습니다.")
        if(accepted)fetch(captured)
    }}}

    private suspend fun fetch(captured:Capture){
        mutable.value=snapshot(mutable.value["available"]?.jsonPrimitive?.boolean==true,true,"협업 기록을 확인하고 있습니다.")
        try {
            if(!current(captured))throw StalePairing()
            var more=false
            repeat(4){
                val page=exchange(captured,"GET","/v1/collab/messages?after=$cursor&limit=50")
                if(!current(captured))throw StalePairing()
                require(page["dataOnly"]?.jsonPrimitive?.boolean==true&&page["executionAuthority"]?.jsonPrimitive?.boolean==false)
                val rows=page.getValue("messages").jsonArray;require(rows.size<=50)
                val latest=page.getValue("latestSequence").jsonPrimitive.long;require(latest>=cursor)
                for(row in rows){
                    val message=checkedMessage(row.jsonObject);val sequence=message.getValue("sequence").jsonPrimitive.long
                    require(sequence==cursor+1&&sequence<=latest)
                    val old=messages[sequence];require(old==null||old==message)
                    messages[sequence]=message;cursor=sequence
                }
                more=page.getValue("hasMore").jsonPrimitive.boolean
                require(!more||rows.isNotEmpty())
                while(messages.size>200)messages.remove(messages.keys.first())
                if(!more){mutable.value=snapshot(true,false,"협업 기록을 확인했습니다.");return}
            }
            mutable.value=snapshot(true,false,if(more)"이전 기록을 불러오는 중입니다. 새로고침하면 이어서 확인합니다."else "협업 기록을 확인했습니다.")
        }catch(cancel:CancellationException){throw cancel}
        catch(_:StalePairing){invalidatePairing()}
        catch(reset:CursorReset){if(!current(captured))invalidatePairing()else {messages.clear();cursor=reset.first-1;mutable.value=snapshot(true,false,"이전 기록의 보관 범위가 바뀌었습니다. 새로고침하면 보관된 기록부터 확인합니다.")}}
        catch(_:Exception){if(!current(captured))invalidatePairing()else mutable.value=snapshot(false,false,"협업방에 연결하지 못했습니다. PC 연결과 작업기 버전을 확인하세요.")}
    }
    private fun checkedMessage(raw:JsonObject):JsonObject {
        require(raw.keys==setOf("sequence","messageId","sender","recipient","text","createdAtMs","clientMessageId","bodyDigest","dataOnly","executionAuthority"))
        require(raw.getValue("dataOnly").jsonPrimitive.boolean&&!raw.getValue("executionAuthority").jsonPrimitive.boolean)
        require(raw.getValue("sequence").jsonPrimitive.long>0&&raw.getValue("messageId").jsonPrimitive.content.length<=128)
        require(raw.getValue("sender").jsonPrimitive.content in setOf("user","codex","claude")&&raw.getValue("recipient").jsonPrimitive.content in setOf("user","codex","claude","all"))
        require(raw.getValue("text").jsonPrimitive.content.length<=16000)
        return JsonObject(raw+mapOf("createdAt" to JsonPrimitive(Instant.ofEpochMilli(raw.getValue("createdAtMs").jsonPrimitive.long).toString())))
    }
    private suspend fun exchange(captured:Capture,method:String,path:String,body:JsonObject?=null):JsonObject=withContext(Dispatchers.IO){
        if(!current(captured))throw StalePairing()
        val config=transport.available()?:throw PcUnavailable()
        if(!current(captured)||config.serverId!=captured.config.serverId||config.certificateSha256!=captured.config.certificateSha256||config.token!=captured.config.token)throw StalePairing()
        val connection=transport.open(config,path,method,timeout=15_000)
        try {
            val bytes=body?.toString()?.toByteArray(Charsets.UTF_8)
            if(bytes!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.size)}
            transport.cancellable(connection){
                if(!current(captured))throw StalePairing()
                bytes?.let{connection.outputStream.use{out->out.write(it)}}
                if(connection.responseCode==410){
                    val error=connection.errorStream?.use{stream->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(512)
                        while(out.size()<4097){val count=stream.read(buffer,0,minOf(buffer.size,4097-out.size()));if(count<0)break;out.write(buffer,0,count)}
                        out.toByteArray()
                    }?:throw PcUnavailable()
                    require(error.size<=4096)
                    val value=Json.parseToJsonElement(String(error,Charsets.UTF_8)).jsonObject
                    val first=value["firstRetainedSequence"]?.jsonPrimitive?.longOrNull?:throw PcUnavailable()
                    require(first>0&&value["resetRequired"]?.jsonPrimitive?.boolean==true)
                    throw CursorReset(first)
                }
                if(connection.responseCode !in 200..299)throw PcUnavailable()
                Json.parseToJsonElement(String(transport.read(connection,2*1024*1024),Charsets.UTF_8)).jsonObject
            }
        }finally{connection.disconnect()}
    }
}
