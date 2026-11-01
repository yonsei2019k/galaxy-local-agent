package com.user.localagent.compute

import android.content.Context
import com.user.localagent.data.DraftStore
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

data class PcImageRecoveryTicket(val id:String,val owner:String,val pair:String,val model:String,
    val seed:Long,val steps:Int,val createdAt:Long,val context:JsonObject) {
    init {
        require(listOf(id,owner).all {runCatching{UUID.fromString(it).toString()==it}.getOrDefault(false)})
        require(pair.matches(Regex("[a-f0-9]{64}"))&&seed>=0&&steps in 12..50&&createdAt>0)
        com.user.localagent.core.PcImageModelCatalog.selection(model)
        require(context.toString().length<=12000)
    }
    fun json()=buildJsonObject {
        put("id",id);put("owner",owner);put("pair",pair);put("model",model);put("seed",seed);put("steps",steps)
        put("createdAt",createdAt);put("context",context)
    }.toString()
    fun validAt(now:Long)=now>=createdAt-300_000&&now-createdAt<24*60*60_000L
    companion object {
        fun parse(raw:String):PcImageRecoveryTicket {
            require(raw.length<=16000);val o=Json.parseToJsonElement(raw).jsonObject
            fun s(k:String)=o.getValue(k).jsonPrimitive.content
            return PcImageRecoveryTicket(s("id"),s("owner"),s("pair"),s("model"),o.getValue("seed").jsonPrimitive.long,
                o.getValue("steps").jsonPrimitive.int,o.getValue("createdAt").jsonPrimitive.long,o.getValue("context").jsonObject)
        }
        fun pair(config:PcConnection,revision:Long):String {
            val data=buildJsonArray {add(config.serverId);add(config.certificateSha256);add(config.token);add(revision)}.toString()
            return MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString(""){"%02x".format(it)}
        }
    }
}

class PcImageRecoveryStore(context:Context,scope:String) {
    private val store=DraftStore(context,"pc-image-recovery-v1:$scope",16000)
    fun read():PcImageRecoveryTicket?=synchronized(LOCK) {
        store.readStrict().takeIf{it.isNotBlank()}?.let(PcImageRecoveryTicket::parse)?.takeIf{it.validAt(System.currentTimeMillis())}
    }
    fun save(ticket:PcImageRecoveryTicket)=synchronized(LOCK) {
        val current=read();check(current==null||current.id==ticket.id){"기존 PC 이미지 복구 작업을 이어받거나 먼저 삭제해주세요."}
        store.write(ticket.json())
    }
    fun clear(id:String)=synchronized(LOCK){if(read()?.id==id)store.write("")}
    companion object {private val LOCK=Any()}
}
