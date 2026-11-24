package com.user.localagent.compute

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A one-use, native-owner capability. Its contents never reach the model or conversation database. */
internal class PairedWebsiteLogin private constructor(
    private val connection: PcConnection, private val token: String
) {
    companion object {
        const val ORIGIN = "https://www.kairb.org"
        suspend fun prepare(context: Context, connection: PcConnection): PairedWebsiteLogin? = withContext(Dispatchers.IO) {
            val (status, result) = PcTransport(context).jsonWithStatus(connection, "/v1/browser/owner-login-form",
                buildJsonObject { put("origin", ORIGIN) }, 20_000)
            val token = result["challenge"]?.jsonPrimitive?.contentOrNull
            if (status == 200 && result["origin"] == JsonPrimitive(ORIGIN) && token != null &&
                Regex("[a-f0-9]{64}").matches(token)) PairedWebsiteLogin(connection, token) else null
        }
    }
    fun current(context: Context) = BrowserAgentSettings.pc(context).enabled() &&
        PcComputeSettings.get(context).snapshot()?.samePair(connection) == true

    suspend fun freshForm(context: Context) = if (current(context)) prepare(context, connection) else null

    suspend fun submit(context: Context, account: String, password: String): Boolean = withContext(Dispatchers.IO) {
        if (!current(context)) return@withContext false
        val (status, result) = PcTransport(context).jsonWithStatus(connection, "/v1/browser/owner-login-submit",
            buildJsonObject { put("challenge", token); put("account", account); put("password", password) }, 25_000)
        status == 200 && result["signedIn"] == JsonPrimitive(true) && current(context)
    }
}
