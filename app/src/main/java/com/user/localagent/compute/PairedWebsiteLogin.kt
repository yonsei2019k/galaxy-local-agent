package com.user.localagent.compute

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal enum class PcLoginStage {
    SIGNED_IN, CORRECT_INPUT, OWNER_VERIFICATION, RETRY_LATER, FORM_EXPIRED, DISCONNECTED, UNCONFIRMED
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
    private val connection: PcConnection, private val token: String, private val work: String
) {
    companion object {
        const val ORIGIN = "https://www.kairb.org"

        suspend fun reopen(context:Context,connection:PcConnection,work:String):PairedWebsiteLogin? {
            fun stillPaired()=BrowserAgentSettings.pc(context).enabled()&&
                PcComputeSettings.get(context).snapshot()?.samePair(connection)==true
            if(!stillPaired())return null
            prepare(context,connection,work)?.let {return it.takeIf {stillPaired()} }
            if(!stillPaired())return null
            val (status,page)=PcTransport(context).jsonWithStatus(connection,"/v1/browser/open",buildJsonObject {
                put("url","$ORIGIN/member/");put("work",work)
            },25_000)
            if(status!=200||page["loginRequired"]!=JsonPrimitive(true)||!stillPaired())return null
            return prepare(context,connection,work)
        }
        suspend fun prepare(context: Context, connection: PcConnection, work: String): PairedWebsiteLogin? = withContext(Dispatchers.IO) {
            val (status, result) = PcTransport(context).jsonWithStatus(connection, "/v1/browser/owner-login-form",
                buildJsonObject { put("origin", ORIGIN); put("work",work) }, 20_000)
            val token = result["challenge"]?.jsonPrimitive?.contentOrNull
            if (status == 200 && result["origin"] == JsonPrimitive(ORIGIN) && token != null &&
                Regex("[a-f0-9]{64}").matches(token)) PairedWebsiteLogin(connection, token, work) else null
        }
    }
    fun current(context: Context) = BrowserAgentSettings.pc(context).enabled() &&
        PcComputeSettings.get(context).snapshot()?.samePair(connection) == true

    suspend fun freshForm(context: Context) = if (current(context)) reopen(context, connection, work) else null

    suspend fun submit(context: Context, account: String, password: String) = request(context, "submit",
        buildJsonObject { put("account", account); put("password", password) })

    suspend fun checkResult(context: Context) = request(context, "status", buildJsonObject {})

    private suspend fun request(context: Context, operation: String, fields: JsonObject): PcLoginResult = withContext(Dispatchers.IO) {
        val disconnected = PcLoginResult(PcLoginStage.DISCONNECTED)
        if (!current(context)) return@withContext disconnected
        val body = buildJsonObject { put("work", work); put("challenge", token); fields.forEach { (k, v) -> put(k,v) } }
        val (status, result) = PcTransport(context).jsonWithStatus(connection, "/v1/browser/owner-login-$operation", body, 75_000)
        if (current(context)) PcLoginResult.decode(status, result) else disconnected
    }
}
