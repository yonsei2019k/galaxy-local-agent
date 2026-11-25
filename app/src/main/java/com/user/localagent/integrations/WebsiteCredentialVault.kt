package com.user.localagent.integrations

import android.content.Context
import com.user.localagent.connections.ConnectionStore
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Phone-native website passwords. Callers outside the protected browser have no secret-reading API. */
internal class WebsiteCredentialVault(context: Context) {
    private val store = ConnectionStore(context, "ziphap-website-vault-v1", "ziphap.website.vault.aes.v1")

    class Login(val account: String, val password: String) {
        override fun toString() = "WebsiteCredentialVault.Login([protected])"
    }

    private fun entries() = store.read("sites")?.let { Json.parseToJsonElement(it).jsonArray }
        ?.map { URI(it.jsonPrimitive.content) }.orEmpty()

    private fun writeEntries(sites: List<URI>) = store.write("sites", buildJsonArray {
        sites.forEach { add(it.toASCIIString()) }
    }.toString())

    private fun slot(origin: URI): String {
        require(origin.scheme == "https" && origin.host != null && origin.rawUserInfo == null &&
            origin.port == -1 && origin.path.isEmpty() && origin.rawQuery == null && origin.rawFragment == null)
        val hash = MessageDigest.getInstance("SHA-256").digest(origin.toASCIIString().toByteArray(Charsets.UTF_8))
        return "login." + hash.joinToString("") { "%02x".format(it) }
    }

    fun has(origin: URI) = synchronized(lock) { store.contains(slot(origin)) }

    fun sites(): List<URI> = synchronized(lock) { entries().filter { store.contains(slot(it)) } }

    fun save(origin: URI, account: String, password: String) = synchronized(lock) {
        require(account.isNotBlank() && account.length <= 320 && password.length in 1..1024)
        val id = slot(origin)
        val active = sites()
        require(origin in active || active.size < 32) { "Website vault is full" }
        if (origin !in active) writeEntries(active + origin)
        store.write(id, buildJsonObject { put("account", account); put("password", password) }.toString())
    }

    fun remove(origin: URI) = synchronized(lock) {
        store.write(slot(origin), null)
        writeEntries(sites())
    }

    fun recentAccess():List<Pair<String,Long>> = synchronized(lock) {
        val records=store.read("access")?.let{Json.parseToJsonElement(it).jsonArray}.orEmpty()
        records.takeLast(8).asReversed().map {
            val event=it.jsonObject
            event.getValue("site").jsonPrimitive.content to event.getValue("at").jsonPrimitive.long
        }
    }

    /** Commit a local access attempt before decrypting. Audit records carry no credentials. */
    fun readForOwner(origin: URI): Login? = synchronized(lock) {
        val id = slot(origin)
        if (!store.contains(id)) return null
        val previous = store.read("access")?.let { Json.parseToJsonElement(it).jsonArray }.orEmpty()
        store.write("access", JsonArray(previous.takeLast(127) + buildJsonObject {
            put("site", origin.host)
            put("at", System.currentTimeMillis())
            put("operation", "owner_login_attempt")
        }).toString())
        val record = store.read(id)?.let { Json.parseToJsonElement(it).jsonObject } ?: return null
        Login(record.getValue("account").jsonPrimitive.content, record.getValue("password").jsonPrimitive.content)
    }

    companion object { private val lock = Any() }
}
