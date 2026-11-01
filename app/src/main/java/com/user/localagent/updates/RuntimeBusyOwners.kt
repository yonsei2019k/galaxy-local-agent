package com.user.localagent.updates

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Run owners cannot clear each other; legacy callers have a separate compatibility flag. */
internal class RuntimeBusyOwners(private val nowMillis:()->Long={System.nanoTime()/1_000_000}) {
    private val lock=Any()
    private val owners=mutableMapOf<String,String>()
    private var legacy=false
    private var installSubmittedAt:Long?=null
    /** A lost installer callback must not block new work for the rest of the process. */
    private fun installHeld():Boolean {
        val submitted=installSubmittedAt?:return false
        if(nowMillis()-submitted<INSTALL_HOLD_MILLIS)return true
        installSubmittedAt=null
        return false
    }
    fun acquire(owner:String,onChanged:()->Unit={}):Closeable {
        require(owner.isNotBlank()&&owner.length<=160)
        val token=UUID.randomUUID().toString();val open=AtomicBoolean(true)
        synchronized(lock){
            check(!installHeld()){"업데이트 설치가 진행 중입니다. 설치 후 다시 실행해주세요."};owners[token]=owner
            try{onChanged()}catch(error:Throwable){owners.remove(token);throw error}
        }
        return Closeable{if(open.compareAndSet(true,false))synchronized(lock){owners.remove(token);onChanged()}}
    }
    fun setLegacyBusy(value:Boolean,onChanged:()->Unit={})=synchronized(lock){
        check(!value||!installHeld()){"업데이트 설치가 진행 중입니다."};legacy=value;onChanged()
    }
    fun busy():Boolean=synchronized(lock){legacy||owners.isNotEmpty()||installHeld()}
    /** An owner cannot appear between the final idle check and submitting the installer session. */
    fun submitInstallIfIdle(block:()->Unit):Boolean=synchronized(lock){
        if(legacy||owners.isNotEmpty()||installHeld())return@synchronized false
        installSubmittedAt=nowMillis()
        try{block();true}catch(error:Throwable){installSubmittedAt=null;throw error}
    }
    fun installationFinished(onChanged:()->Unit={})=synchronized(lock){installSubmittedAt=null;onChanged()}
    companion object {const val INSTALL_HOLD_MILLIS=10*60*1000L}
}
