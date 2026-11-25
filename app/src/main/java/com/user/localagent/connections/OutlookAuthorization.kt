package com.user.localagent.connections

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.user.localagent.core.connections.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** OAuth device flow for a deployment-owned Microsoft public client. Codes never enter chat history. */
internal class OutlookAuthorization(context: Context, private val store: ConnectionStore) {
    private val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    private val client = metadata.getString("ziphap.outlook.client").orEmpty()
    private val tenant = metadata.getString("ziphap.outlook.tenant").orEmpty()
    private val guid = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    val configured = guid.matches(client) && (tenant in setOf("common","organizations","consumers") || guid.matches(tenant))
    private val renewals = Mutex()
    private val tickets = mutableMapOf<ConnectedService, String>()

    @Synchronized fun begin(service: ConnectedService): String {
        require(service.outlook && configured)
        return UUID.randomUUID().toString().also { tickets[service]=it }
    }
    @Synchronized fun cancel(service: ConnectedService, ticket: String) {
        if(tickets[service]==ticket) tickets.remove(service)
    }
    @Synchronized fun disconnect(service: ConnectedService) {
        tickets.remove(service)
        store.disconnect(service)
    }
    private fun scope(service: ConnectedService) = when(service) {
        ConnectedService.OUTLOOK_MAIL -> "Mail.ReadBasic"
        ConnectedService.OUTLOOK_CALENDAR -> "Calendars.ReadBasic"
        else -> error("Unsupported Outlook scope")
    }
    private fun text(json: JsonObject, field: String) = json[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun form(path: String, vararg fields: Pair<String,String>): JsonObject {
        check(configured)
        val url=URL("https://login.microsoftonline.com/$tenant/oauth2/v2.0/$path")
        val connection=url.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=15000
            connection.readTimeout=20000
            connection.requestMethod="POST"
            connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
            val data=(fields.toList()+("client_id" to client)).joinToString("&"){(k,v)->k+"="+ConnectionRequests.encode(v)}
            connection.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
            val status=connection.responseCode
            require(status in 200..299 || status==400 || status==401){"Microsoft 연결을 다시 확인해 주세요."}
            val input=if(status in 200..299)connection.inputStream else connection.errorStream
            val body=checkNotNull(input).use { read ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(4096)
                while(true){
                    val n=read.read(buffer)
                    if(n<0)break
                    require(output.size()+n<=128*1024)
                    output.write(buffer,0,n)
                }
                output.toString("UTF-8")
            }
            return Json.parseToJsonElement(body).jsonObject.also { if(status !in 200..299)require("error" in it) }
        } finally { connection.disconnect() }
    }

    private fun credential(service: ConnectedService, response: JsonObject, refresh: String?, principal: String): JsonObject {
        require(text(response,"token_type").equals("Bearer",ignoreCase=true))
        val scopes=text(response,"scope").orEmpty().lowercase().split(' ').map { it.removePrefix("https://graph.microsoft.com/") }
        require(scope(service).lowercase() in scopes && "user.read" in scopes)
        val ttl=response.getValue("expires_in").jsonPrimitive.long
        require(ttl in 1..172800)
        return buildJsonObject {
            put("access",requireNotNull(text(response,"access_token")))
            put("refresh",requireNotNull(text(response,"refresh_token")?:refresh))
            put("expires",System.currentTimeMillis()+ttl*1000)
            put("client",client);put("tenant",tenant);put("principal",principal)
        }
    }

    suspend fun login(service: ConnectedService, ticket: String, showCode: suspend (String)->Unit,
        verifyUser: suspend (String)->String) = withContext(Dispatchers.IO) {
        val request=form("devicecode","scope" to "offline_access User.Read ${scope(service)}")
        val result=OutlookDeviceFlow.awaitToken(request,SystemClock::elapsedRealtime,{delay(it)},
            {synchronized(this@OutlookAuthorization){tickets[service]==ticket}},showCode){ device ->
            form("token","grant_type" to "urn:ietf:params:oauth:grant-type:device_code","device_code" to device)
        }
        val account=verifyUser(requireNotNull(text(result,"access_token")))
        val saved=credential(service,result,null,account)
        currentCoroutineContext().ensureActive()
        synchronized(this@OutlookAuthorization){
            check(tickets[service]==ticket)
            // A partial disk write must not associate a new token with old authority.
            store.disconnect(service)
            store.write("token.${service.id}",saved.toString())
            store.connect(service,LocalConnection(account,null,false,System.currentTimeMillis()))
            tickets.remove(service)
        }
        Unit
    }

    suspend fun token(service: ConnectedService, expected: ConnectionAccess, verifyUser: suspend (String)->String): String =
        withContext(Dispatchers.IO) { renewals.withLock {
            fun bound():Boolean = store.connection(service)?.let { it.principal==expected.principal && it.generation==expected.generation }==true
            check(configured && bound())
            val saved=Json.parseToJsonElement(checkNotNull(store.read("token.${service.id}"))).jsonObject
            require(text(saved,"client")==client && text(saved,"tenant")==tenant && text(saved,"principal")==expected.principal)
            if(saved.getValue("expires").jsonPrimitive.long>System.currentTimeMillis()+60000)
                return@withLock requireNotNull(text(saved,"access"))
            val prior=requireNotNull(text(saved,"refresh"))
            val result=form("token","grant_type" to "refresh_token","refresh_token" to prior,"scope" to "offline_access User.Read ${scope(service)}")
            if(text(result,"error") in setOf("invalid_grant","interaction_required","consent_required")){
                synchronized(this@OutlookAuthorization){if(bound())store.disconnect(service)}
                error("Microsoft 재연결 필요")
            }
            val fresh=credential(service,result,prior,expected.principal)
            val access=requireNotNull(text(fresh,"access"))
            check(verifyUser(access)==expected.principal)
            currentCoroutineContext().ensureActive()
            synchronized(this@OutlookAuthorization){ check(bound());store.write("token.${service.id}",fresh.toString()) }
            access
        } }
}
