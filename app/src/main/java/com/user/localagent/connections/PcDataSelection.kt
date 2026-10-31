package com.user.localagent.connections

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** One immutable, encrypted source descriptor for one original chat. Never logged as a URI/name. */
internal data class PcDataSelection(val threadId:String,val uri:String,val name:String,val sha256:String,val version:String) {
    init {
        require(threadId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val parsed=URI(uri);require(parsed.scheme=="content"&&!parsed.authority.isNullOrBlank()&&uri.length<=16_000)
        require(name.length in 1..200&&name.none{it=='/'||it=='\\'||it=='\u0000'})
        require(sha256.matches(Regex("[a-f0-9]{64}"))&&version.matches(Regex("[A-Za-z0-9_-]{1,100}")))
    }
    fun encode()=buildJsonObject{put("version",version);put("threadId",threadId);put("uri",uri);put("name",name);put("sha256",sha256)}.toString()
    val binding get()=digest(encode())
    override fun toString()="PcDataSelection(privateSourceOmitted=true)"
    companion object {
        fun storageKey(threadId:String):String {require(threadId.matches(Regex("[A-Za-z0-9_-]{1,100}")));return "pc.data.selection."+digest(threadId)}
        fun decode(raw:String):PcDataSelection {
            require(raw.length<=20_000)
            val value=Json.parseToJsonElement(raw).jsonObject
            require(value.keys==setOf("version","threadId","uri","name","sha256"))
            fun text(key:String)=value.getValue(key).jsonPrimitive.also{require(it.isString)}.content
            return PcDataSelection(text("threadId"),text("uri"),text("name"),text("sha256"),text("version"))
        }
        private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}
