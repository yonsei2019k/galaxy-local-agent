package com.user.localagent.updates

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Run owners cannot clear each other; legacy callers have a separate compatibility flag. */
internal class RuntimeBusyOwners {
    private val lock=Any()
    private val owners=mutableMapOf<String,String>()
    private var legacy=false
    private var installPending=false
    fun acquire(owner:String,onChanged:()->Unit={}):Closeable {
        require(owner.isNotBlank()&&owner.length<=160)
        val token=UUID.randomUUID().toString();val open=AtomicBoolean(true)
        synchronized(lock){
            check(!installPending){"업데이트 설치가 진행 중입니다. 설치 후 다시 실행해주세요."};owners[token]=owner
            try{onChanged()}catch(error:Throwable){owners.remove(token);throw error}
        }
        return Closeable{if(open.compareAndSet(true,false))synchronized(lock){owners.remove(token);onChanged()}}
    }
    fun setLegacyBusy(value:Boolean,onChanged:()->Unit={})=synchronized(lock){
        check(!value||!installPending){"업데이트 설치가 진행 중입니다."};legacy=value;onChanged()
    }
    fun busy():Boolean=synchronized(lock){legacy||owners.isNotEmpty()||installPending}
    /** An owner cannot appear between the final idle check and submitting the installer session. */
    fun submitInstallIfIdle(block:()->Unit):Boolean=synchronized(lock){
        if(legacy||owners.isNotEmpty()||installPending)return@synchronized false
        installPending=true
        try{block();true}catch(error:Throwable){installPending=false;throw error}
    }
    fun installationFinished(onChanged:()->Unit={})=synchronized(lock){installPending=false;onChanged()}
}
