package com.user.localagent.connections

import com.user.localagent.core.connections.ApiRequest
import com.user.localagent.core.connections.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** No redirect, authentication retry, reconnect retry or replayable request body for actual sends. */
internal object GmailSendTransport {
    private val client=OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).fastFallback(false)
        .protocols(listOf(Protocol.HTTP_1_1)).connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS)
        .addNetworkInterceptor {chain->
            val gate=checkNotNull(chain.request().tag(AtomicBoolean::class.java))
            if(!gate.compareAndSet(false,true))throw java.io.IOException("메일 전송 요청을 자동으로 반복하지 않습니다.")
            chain.proceed(chain.request())
        }.build()
    suspend fun send(request:ApiRequest,token:String):ApiResponse=withContext(Dispatchers.IO){
        require(request.method=="POST"&&request.url=="https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
        require(token.isNotBlank()&&token.none {it=='\r'||it=='\n'})
        val bytes=checkNotNull(request.body).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..1_000_000)
        val body=object:RequestBody(){
            override fun contentType()="application/json; charset=utf-8".toMediaType()
            override fun contentLength()=bytes.size.toLong()
            override fun isOneShot()=true
            override fun writeTo(sink:BufferedSink){sink.write(bytes)}
        }
        currentCoroutineContext().ensureActive()
        val call=client.newCall(Request.Builder().url(request.url).header("Authorization","Bearer $token")
            .header("Accept","application/json").tag(AtomicBoolean::class.java,AtomicBoolean(false)).post(body).build())
        suspendCancellableCoroutine {continuation->
            continuation.invokeOnCancellation {call.cancel()}
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,error:java.io.IOException){if(continuation.isActive)continuation.resumeWithException(error)}
                override fun onResponse(call:Call,response:Response){
                    try {response.use {
                        if(!continuation.isActive)return
                        if(response.code !in 200..299){continuation.resume(ApiResponse(response.code,"{}"));return}
                        val output=ByteArrayOutputStream()
                        response.body.byteStream().use {input->val buffer=ByteArray(8192);while(true){
                            if(!continuation.isActive){call.cancel();return}
                            val count=input.read(buffer);if(count<0)break
                            require(output.size()+count<=2*1024*1024);output.write(buffer,0,count)
                        }}
                        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        val result=ApiResponse(response.code,decoder.decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString())
                        if(continuation.isActive)continuation.resume(result)
                    }}catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}
                }
            })
        }
    }
}
