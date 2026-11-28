package com.user.localagent.compute

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import com.user.localagent.core.WebAgentPolicy
import com.user.localagent.connections.ConnectionStore
import java.security.SecureRandom

internal object PairedWebsiteSessionKeys {
    private fun store(context:Context)=ConnectionStore(context,"ziphap-website-session-keys-v1","ziphap.website.session.keys.v1")
    @Synchronized fun key(context:Context,origin:String,create:Boolean=false):String? {
        require(PcLoginSite.origin(origin)==origin)
        val store=store(context)
        return store.read(origin) ?: if(create) {
            val key=ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            store.write(origin,key)
            key
        } else null
    }
    @Synchronized fun remove(context:Context,origin:String) { store(context).write(origin,null) }
    suspend fun openFields(context:Context,url:String):Map<String,JsonElement> = withContext(Dispatchers.IO) {
        val origin=PcLoginSite.origin(url) ?: return@withContext emptyMap()
        runCatching { key(context,origin) }.getOrNull()?.let { mapOf("sessionKey" to JsonPrimitive(it)) }.orEmpty()
    }
}

internal object PcLoginSite {
    const val KAIRB = "https://www.kairb.org"
    const val HALLYM = "https://hallymedu.co.kr"

    fun origin(url:String?):String? {
        if(!WebAgentPolicy.urlAllowed(url))return null
        val uri=URI(url!!)
        if(!uri.scheme.equals("https",true)||uri.port !in setOf(-1,443))return null
        return URI("https",null,uri.host.lowercase(),-1,null,null,null).toASCIIString()
    }

    fun entry(origin:String):String = when(origin){
        KAIRB->"$KAIRB/member/"
        HALLYM->"$HALLYM/new_home/My/login/"
        else->"$origin/"
    }
}

internal enum class PcLoginStage {
    SIGNED_IN, CORRECT_INPUT, OWNER_VERIFICATION, OWNER_CODE_ON_SITE, RETRY_LATER, FORM_EXPIRED, DISCONNECTED, UNCONFIRMED
}

internal data class PcLoginResult(val stage: PcLoginStage, val retryAfterSeconds: Int? = null) {
    companion object {
        fun decode(status: Int, body: JsonObject): PcLoginResult {
            val outcome = (body["outcome"] as? JsonPrimitive)?.contentOrNull
            val signed = body["signedIn"]
            if (status == 200 && signed == JsonPrimitive(true) && outcome in setOf(null, "signed_in"))
                return PcLoginResult(PcLoginStage.SIGNED_IN)
            if (status == 200 && signed == JsonPrimitive(false)) {
                when (outcome) {
                    "correct_input" -> return PcLoginResult(PcLoginStage.CORRECT_INPUT)
                    "owner_verification" -> return PcLoginResult(PcLoginStage.OWNER_VERIFICATION)
                    "owner_code_on_site" -> return PcLoginResult(PcLoginStage.OWNER_CODE_ON_SITE)
                    "retry_later" -> return PcLoginResult(PcLoginStage.RETRY_LATER,
                        (body["retryAfterSeconds"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..300 })
                }
            }
            val error = (body["error"] as? JsonPrimitive)?.contentOrNull
            return PcLoginResult(when {
                status == 429 && error == "OWNER_LOGIN_WAIT" -> PcLoginStage.RETRY_LATER
                status == 409 && error in setOf("OWNER_LOGIN_EXPIRED", "OWNER_LOGIN_FORM_CHANGED",
                    "BROWSER_WORK_SESSION_GONE") -> PcLoginStage.FORM_EXPIRED
                else -> PcLoginStage.UNCONFIRMED
            })
        }
    }
}

/** A one-use, native-owner capability. Its contents never reach the model or conversation database. */
internal class PairedWebsiteLogin private constructor(
    private val connection: PcConnection, private val token: String, private val work: String, val origin:String,
    private val sessionVault:Boolean
) {
    val host:String get()=URI(origin).host
    companion object {
        suspend fun observeSignedIn(context:Context,connection:PcConnection,work:String,origin:String,taskUrl:String?):Boolean {
            fun current()=BrowserAgentSettings.pc(context).enabled()&&
                PcComputeSettings.get(context).snapshot()?.samePair(connection)==true
            if(PcLoginSite.origin(origin)!=origin||!current())return false
            return withContext(Dispatchers.IO) {
                val transport=PcTransport(context)
                val body=buildJsonObject {put("work",work);put("origin",origin)}
                suspend fun observe()=transport.jsonWithStatus(connection,"/v1/browser/owner-login-observe",body,20_000)
                var result=observe()
                val gone=(result.second["error"] as? JsonPrimitive)?.contentOrNull in
                    setOf("BROWSER_WORK_SESSION_GONE","BROWSER_WORK_PAGE_GONE")
                if(result.first==409&&gone&&current()){
                    val target=com.user.localagent.integrations.BrowserLoginRoutes.pairedTaskPage(taskUrl,origin)?:return@withContext false
                    val saved=PairedWebsiteSessionKeys.openFields(context,target)
                    if(saved.isEmpty()||!current())return@withContext false
                    val opened=transport.jsonWithStatus(connection,"/v1/browser/open",
                        JsonObject(mapOf("work" to JsonPrimitive(work),"url" to JsonPrimitive(target))+saved),25_000)
                    if(opened.first!=200||opened.second["loginRequired"]!=JsonPrimitive(false)||!current())return@withContext false
                    result=observe()
                }
                current()&&result.first==200&&result.second["origin"]==JsonPrimitive(origin)&&result.second["signedIn"]==JsonPrimitive(true)
            }
        }

        suspend fun reopen(context:Context,connection:PcConnection,work:String,origin:String,taskUrl:String?=null):PairedWebsiteLogin? {
            if(PcLoginSite.origin(origin)!=origin)return null
            fun stillPaired()=BrowserAgentSettings.pc(context).enabled()&&
                PcComputeSettings.get(context).snapshot()?.samePair(connection)==true
            if(!stillPaired())return null
            prepare(context,connection,work,origin)?.let {return it.takeIf {stillPaired()} }
            if(!stillPaired())return null
            val (status,page)=PcTransport(context).jsonWithStatus(connection,"/v1/browser/open",buildJsonObject {
                val task=com.user.localagent.integrations.BrowserLoginRoutes.pairedTaskPage(taskUrl,origin)
                put("url",task?:PcLoginSite.entry(origin));put("work",work)
            },25_000)
            if(status!=200||page["loginRequired"]!=JsonPrimitive(true)||!stillPaired())return null
            return prepare(context,connection,work,origin)
        }
        suspend fun prepare(context: Context, connection: PcConnection, work: String, origin:String): PairedWebsiteLogin? = withContext(Dispatchers.IO) {
            if(PcLoginSite.origin(origin)!=origin)return@withContext null
            val (status, result) = PcTransport(context).jsonWithStatus(connection, "/v1/browser/owner-login-form",
                buildJsonObject { put("origin", origin); put("work",work) }, 20_000)
            val token = result["challenge"]?.jsonPrimitive?.contentOrNull
            if (status == 200 && result["origin"] == JsonPrimitive(origin) && token != null &&
                Regex("[a-f0-9]{64}").matches(token)) PairedWebsiteLogin(connection, token, work, origin,
                    result["sessionVault"]==JsonPrimitive(true)) else null
        }
    }
    fun current(context: Context) = BrowserAgentSettings.pc(context).enabled() &&
        PcComputeSettings.get(context).snapshot()?.samePair(connection) == true

    suspend fun freshForm(context: Context) = if (current(context)) reopen(context, connection, work, origin) else null

    suspend fun submit(context: Context, account: String, password: String,remember:Boolean=false):PcLoginResult {
        val key=if(remember&&sessionVault)withContext(Dispatchers.IO) {
            runCatching { PairedWebsiteSessionKeys.key(context,origin,create=true) }.getOrNull()
        } else null
        return request(context,"submit",buildJsonObject {
            put("account",account);put("password",password)
            key?.let { put("sessionKey",it) }
        })
    }

    suspend fun checkResult(context:Context,timeoutMillis:Int=75_000) =
        request(context,"status",buildJsonObject {},timeoutMillis)

    private suspend fun request(context:Context,operation:String,fields:JsonObject,timeoutMillis:Int=75_000):PcLoginResult = withContext(Dispatchers.IO) {
        val disconnected = PcLoginResult(PcLoginStage.DISCONNECTED)
        if (!current(context)) return@withContext disconnected
        val body = buildJsonObject { put("work", work); put("challenge", token); fields.forEach { (k, v) -> put(k,v) } }
        val (status, result) = PcTransport(context).jsonWithStatus(connection, "/v1/browser/owner-login-$operation", body, timeoutMillis)
        if (current(context)) PcLoginResult.decode(status, result) else disconnected
    }
}
