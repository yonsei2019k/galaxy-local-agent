package com.user.localagent.images

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ImageSession(val ownerId:String,val token:String,val threadId:String?,val notificationId:Int,
    val state:StateFlow<ImageStudioState>,val cancel:()->Unit)

/** Image-service lifetime and stop targets are separate from the foreground chat and its engine. */
internal object ImageSessionRegistry {
    private val lock=Any()
    private val ids=AtomicInteger(20_000)
    private val mutable=MutableStateFlow<Map<String,ImageSession>>(emptyMap())
    val sessions:StateFlow<Map<String,ImageSession>> = mutable.asStateFlow()
    fun register(ownerId:String,threadId:String?,state:StateFlow<ImageStudioState>,cancel:()->Unit,
        onRegistered:()->Unit={},onLastClosed:()->Unit={}):Closeable {
        require(ownerId.isNotBlank()&&ownerId.length<=160)
        val token=UUID.randomUUID().toString();val open=AtomicBoolean(true)
        synchronized(lock){
            check(ownerId !in mutable.value)
            mutable.value=mutable.value+(ownerId to ImageSession(ownerId,token,threadId,ids.incrementAndGet(),state,cancel))
            try{onRegistered()}catch(error:Throwable){mutable.value=mutable.value-ownerId;throw error}
        }
        return Closeable{if(open.compareAndSet(true,false))synchronized(lock){
            if(mutable.value[ownerId]?.token==token){mutable.value=mutable.value-ownerId;if(mutable.value.isEmpty())onLastClosed()}
        }}
    }
    fun cancel(ownerId:String,expectedToken:String)=synchronized(lock){
        mutable.value[ownerId]?.takeIf{it.token==expectedToken}?.cancel?.invoke()
    }
}
