package com.user.localagent.connections

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.user.localagent.core.connections.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal class PrivateAccountReads(context: Context, private val store: ConnectionStore) {
    private val x = ConnectedService.X_BOOKMARKS
    private val naver = ConnectedService.NAVER_MAIL
    private val client = context.packageManager.getApplicationInfo(context.packageName,PackageManager.GET_META_DATA)
        .metaData.getString("ziphap.x.client").orEmpty()
    val xConfigured get() = client.matches(Regex("[A-Za-z0-9_-]{8,200}"))
    private val refreshLock=Mutex()
    private val redirect="com.user.localagent.x://oauth/callback"
    private fun random()=ByteArray(32).also(SecureRandom()::nextBytes).let(::base64)
    private fun base64(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun fingerprint(value:String)=base64(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)))
    @Synchronized fun disconnect(service:ConnectedService) {
        store.write("pending."+service.id,null)
        store.disconnect(service)
    }
    private fun validTicket(service:ConnectedService,ticket:String)=store.read("pending."+service.id)==ticket

    suspend fun connectNaver(login:String, password:String)=withContext(Dispatchers.IO){
        val ticket=random()
        synchronized(this@PrivateAccountReads){store.write("pending."+naver.id,ticket)}
        try {
            imap(login,password,true)
            ensureActive()
            synchronized(this@PrivateAccountReads){
                check(validTicket(naver,ticket))
                val principal=fingerprint(login.lowercase())
                store.write("token."+naver.id,buildJsonObject {put("login",login);put("password",password);put("principal",principal)}.toString())
                store.connect(naver,LocalConnection(principal,login,false,0))
            }
        }finally{synchronized(this@PrivateAccountReads){if(validTicket(naver,ticket))store.write("pending."+naver.id,null)}}
    }
    private fun imap(login:String,password:String,probe:Boolean):JsonArray {
        return java.net.Socket().use { tcp ->
            tcp.connect(InetSocketAddress("imap.naver.com",993),10000)
            val socket=(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(tcp,"imap.naver.com",993,true) as SSLSocket
            socket.use {
            NaverMailInbox.secure(socket)
            socket.startHandshake()
            NaverMailInbox(socket.inputStream.buffered(),socket.outputStream).inbox(login,password,probe)
            }
        }
    }
    @Synchronized fun startX():Intent {
        check(xConfigured)
        val verifier=random()
        val state=random()
        store.write("pending."+x.id,buildJsonObject {put("state",state);put("verifier",verifier);put("at",System.currentTimeMillis());put("client",client)}.toString())
        val uri=Uri.parse("https://x.com/i/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type","code").appendQueryParameter("client_id",client)
            .appendQueryParameter("redirect_uri",redirect).appendQueryParameter("scope","tweet.read users.read bookmark.read offline.access")
            .appendQueryParameter("state",state).appendQueryParameter("code_challenge",fingerprint(verifier))
            .appendQueryParameter("code_challenge_method","S256").build()
        return Intent(Intent.ACTION_VIEW,uri)
    }
    suspend fun finishX(uri:Uri)=withContext(Dispatchers.IO){
        require(xConfigured && uri.toString().substringBefore('?')==redirect && uri.fragment==null)
        val raw=checkNotNull(store.read("pending."+x.id))
        val pending=Json.parseToJsonElement(raw).jsonObject
        val state=pending.getValue("state").jsonPrimitive.content
        require(uri.getQueryParameters("state")==listOf(state))
        val ticket=random()
        synchronized(this@PrivateAccountReads){check(validTicket(x,raw));store.write("pending."+x.id,ticket)}
        try {
            require("error" !in uri.queryParameterNames && pending.getValue("client").jsonPrimitive.content==client)
            require(System.currentTimeMillis()-pending.getValue("at").jsonPrimitive.long in 0..600000)
            val code=uri.getQueryParameters("code").single()
            require(code.length in 1..2048)
            val form=mapOf("code" to code,"grant_type" to "authorization_code","client_id" to client,
                "redirect_uri" to redirect,"code_verifier" to pending.getValue("verifier").jsonPrimitive.content)
                .entries.joinToString("&"){it.key+"="+ConnectionRequests.encode(it.value)}
            val grant=grant(xRequest("https://api.x.com/2/oauth2/token",null,form))
            val id=grant.getValue("principal").jsonPrimitive.content
            ensureActive()
            synchronized(this@PrivateAccountReads){
                check(validTicket(x,ticket))
                store.write("token."+x.id,grant.toString())
                store.connect(x,LocalConnection(id,grant.getValue("username").jsonPrimitive.content,false,0))
            }
        }finally{synchronized(this@PrivateAccountReads){if(validTicket(x,ticket))store.write("pending."+x.id,null)}}
    }
    private fun grant(token:JsonObject, priorRefresh:String?=null):JsonObject {
        require(token.getValue("token_type").jsonPrimitive.content.equals("bearer",true))
        val scopes=token.getValue("scope").jsonPrimitive.content.split(' ')
        require(scopes.containsAll(listOf("tweet.read","users.read","bookmark.read","offline.access")))
        val secret=token.getValue("access_token").jsonPrimitive.content
        val refresh=token["refresh_token"]?.jsonPrimitive?.contentOrNull?:requireNotNull(priorRefresh)
        require(secret.isNotBlank()&&refresh.isNotBlank())
        val ttl=token.getValue("expires_in").jsonPrimitive.long.also {require(it in 1..86400)}
        val me=xRequest("https://api.x.com/2/users/me",secret).getValue("data").jsonObject
        val id=me.getValue("id").jsonPrimitive.content.also{require(it.matches(Regex("[0-9]{1,22}")))}
        return buildJsonObject {
            put("access",secret);put("refresh",refresh);put("principal",id);put("client",client)
            put("username",me.getValue("username"));put("expires",System.currentTimeMillis()+ttl*1000)
        }
    }
    private suspend fun xToken(expected:ConnectionAccess):String?=refreshLock.withLock {
        fun authorized()=store.connection(x)?.let { it.principal==expected.principal && it.generation==expected.generation }==true
        if(!authorized() || !xConfigured)return@withLock null
        val record=store.read("token."+x.id)?:return@withLock null
        val data=Json.parseToJsonElement(record).jsonObject
        if(data.getValue("client").jsonPrimitive.content!=client || data.getValue("principal").jsonPrimitive.content!=expected.principal)return@withLock null
        if(data.getValue("expires").jsonPrimitive.long>System.currentTimeMillis()+60000)return@withLock data.getValue("access").jsonPrimitive.content
        val refresh=data.getValue("refresh").jsonPrimitive.content
        val form="grant_type=refresh_token&client_id="+ConnectionRequests.encode(client)+"&refresh_token="+ConnectionRequests.encode(refresh)
        val updated=grant(xRequest("https://api.x.com/2/oauth2/token",null,form),refresh)
        currentCoroutineContext().ensureActive()
        synchronized(this@PrivateAccountReads){
            if(!authorized() || store.read("token."+x.id)!=record || updated.getValue("principal").jsonPrimitive.content!=expected.principal)return@withLock null
            store.write("token."+x.id,updated.toString())
        }
        updated.getValue("access").jsonPrimitive.content
    }
    private class ServiceResponse(val status:Int):Exception()
    private fun xRequest(url:String,token:String?,form:String?=null):JsonObject {
        val c=URL(url).openConnection() as HttpsURLConnection
        try {
            c.instanceFollowRedirects=false;c.connectTimeout=10000;c.readTimeout=12000
            if(token!=null)c.setRequestProperty("Authorization","Bearer "+token)
            if(form!=null){
                c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
                c.outputStream.use {it.write(form.toByteArray(Charsets.UTF_8))}
            }
            val status=c.responseCode
            if(status !in 200..299)throw ServiceResponse(status)
            val body=c.inputStream.use {input->
                val bytes=java.io.ByteArrayOutputStream();val chunk=ByteArray(4096)
                while(true){val n=input.read(chunk);if(n<0)break;require(bytes.size()+n<=256*1024);bytes.write(chunk,0,n)}
                bytes.toString("UTF-8")
            }
            return Json.parseToJsonElement(body).jsonObject
        }finally{c.disconnect()}
    }
    suspend fun read(service:ConnectedService,request:ApiRequest,expected:ConnectionAccess):ApiResponse=withContext(Dispatchers.IO){
        val stored=store.read("token."+service.id) ?: return@withContext ApiResponse(401,"{}")
        val credential=Json.parseToJsonElement(stored).jsonObject
        val current=store.connection(service)
        if(current?.generation!=expected.generation || current.principal!=expected.principal ||
            credential.getValue("principal").jsonPrimitive.content!=expected.principal)return@withContext ApiResponse(401,"{}")
        ensureActive()
        val data=when(service){
            naver->{
                require(request.url=="imaps://imap.naver.com:993/INBOX")
                imap(credential.getValue("login").jsonPrimitive.content,credential.getValue("password").jsonPrimitive.content,false)
            }
            x->{
                val token=try {xToken(expected)}catch(error:ServiceResponse){
                    return@withContext ApiResponse(if(error.status==400)401 else error.status,"{}")
                }?:return@withContext ApiResponse(401,"{}")
                val prefix="https://api.x.com/2/users/me/bookmarks?"
                require(request.url.startsWith(prefix))
                try {xRequest("https://api.x.com/2/users/"+expected.principal+"/bookmarks?"+request.url.removePrefix(prefix),token)}
                catch(error:ServiceResponse){return@withContext ApiResponse(error.status,"{}")}
            }
            else->error("Unsupported account")
        }
        ensureActive()
        ApiResponse(200,data.toString())
    }
}
