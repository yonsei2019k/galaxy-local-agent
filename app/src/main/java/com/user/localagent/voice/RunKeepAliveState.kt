package com.user.localagent.voice

import kotlinx.coroutines.delay
import java.util.UUID

enum class RunKeepAlivePhase { STARTING, ACTIVE, FAILED }
data class RunKeepAliveReceipt(val runId:String,val phase:RunKeepAlivePhase,val reason:String?=null) {
    val active:Boolean get()=phase==RunKeepAlivePhase.ACTIVE
}

/** Process-local acknowledgement of a real foreground service, not a battery/liveness guarantee. */
internal class RunKeepAliveRegistry(private val clock:()->Long={System.nanoTime()/1_000_000}) {
    private data class Entry(val token:String,var receipt:RunKeepAliveReceipt,val started:Long)
    private val entries=linkedMapOf<String,Entry>()
    @Synchronized fun begin(runId:String):String? {
        require(runId.isNotBlank()&&runId.length<=128)
        entries.values.filter{it.receipt.phase==RunKeepAlivePhase.STARTING&&clock()-it.started>=5000}.forEach{
            it.receipt=RunKeepAliveReceipt(it.receipt.runId,RunKeepAlivePhase.FAILED,"FOREGROUND_START_TIMEOUT")
        }
        entries[runId]?.takeIf{it.receipt.phase!=RunKeepAlivePhase.FAILED}?.let{return it.token}
        if(entries.size>=64)entries.entries.firstOrNull{it.value.receipt.phase==RunKeepAlivePhase.FAILED}?.let{entries.remove(it.key)}
        if(entries.size>=64)return null
        return UUID.randomUUID().toString().also{entries[runId]=Entry(it,RunKeepAliveReceipt(runId,RunKeepAlivePhase.STARTING),clock())}
    }
    @Synchronized fun acknowledge(runId:String,token:String,success:Boolean,reason:String="FOREGROUND_START_FAILED") {
        val entry=entries[runId]?.takeIf{it.token==token}?:return
        if(entry.receipt.phase==RunKeepAlivePhase.FAILED)return
        if(success&&entry.receipt.phase==RunKeepAlivePhase.STARTING&&clock()-entry.started>=5000){
            entry.receipt=RunKeepAliveReceipt(runId,RunKeepAlivePhase.FAILED,"FOREGROUND_START_TIMEOUT");return
        }
        entry.receipt=RunKeepAliveReceipt(runId,if(success)RunKeepAlivePhase.ACTIVE else RunKeepAlivePhase.FAILED,
            if(success)null else reason.takeIf{it.matches(Regex("[A-Z_]{1,64}"))}?:"FOREGROUND_START_FAILED")
    }
    @Synchronized fun snapshot(runId:String):RunKeepAliveReceipt {
        val entry=entries[runId]?:return RunKeepAliveReceipt(runId,RunKeepAlivePhase.FAILED,"FOREGROUND_NOT_REQUESTED")
        if(entry.receipt.phase==RunKeepAlivePhase.STARTING&&clock()-entry.started>=5000)
            entry.receipt=RunKeepAliveReceipt(runId,RunKeepAlivePhase.FAILED,"FOREGROUND_START_TIMEOUT")
        return entry.receipt
    }
}

object RunKeepAlive {
    private val registry=RunKeepAliveRegistry()
    internal fun begin(runId:String)=registry.begin(runId)
    internal fun acknowledge(runId:String,token:String,success:Boolean,reason:String="FOREGROUND_START_FAILED")=registry.acknowledge(runId,token,success,reason)
    fun snapshot(runId:String)=registry.snapshot(runId)
    suspend fun await(runId:String,timeoutMillis:Long=5000):RunKeepAliveReceipt {
        require(timeoutMillis in 0..5000)
        val start=System.nanoTime()
        while(true){
            val value=snapshot(runId)
            if(value.phase!=RunKeepAlivePhase.STARTING)return value
            if((System.nanoTime()-start)/1_000_000>=timeoutMillis)return RunKeepAliveReceipt(runId,RunKeepAlivePhase.FAILED,"FOREGROUND_START_TIMEOUT")
            delay(50)
        }
    }
}
