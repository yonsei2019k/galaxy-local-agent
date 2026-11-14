package com.user.localagent.briefing

import android.content.Context
import com.user.localagent.core.BriefingLedger
import com.user.localagent.data.DraftStore
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Separate opt-in archive. Notification expiry never deletes unanalysed records. No send capability. */
class BriefingArchive(context:Context,private val storageKey:String="codex-personal-briefing-v1") {
    private val store=DraftStore(context,storageKey,4_000_000)
    private val cursors=DraftStore(context,if(storageKey=="codex-personal-briefing-v1")"codex-briefing-source-cursors-v1" else "$storageKey-cursors",2000)
    private fun load():JsonObject=store.readStrict().takeIf {it.isNotBlank()}?.let {Json.parseToJsonElement(it).jsonObject}
        ?:buildJsonObject {put("enabled",false);put("records",JsonArray(emptyList()));put("completed",JsonArray(emptyList()))}
    private fun save(state:JsonObject){store.write(state.toString());check(load()==state)}
    fun enabled():Boolean=synchronized(LOCK){load()["enabled"]?.jsonPrimitive?.boolean==true}
    fun setEnabled(value:Boolean)=synchronized(LOCK){val state=load();if(state["enabled"]?.jsonPrimitive?.boolean!=value)
        save(JsonObject(state+mapOf("enabled" to JsonPrimitive(value),"authorization" to JsonPrimitive(java.util.UUID.randomUUID().toString()),
            "startEpochMillis" to (state["startEpochMillis"]?:JsonPrimitive(System.currentTimeMillis()-30L*24*60*60*1000))))) }
    fun authorization():String=synchronized(LOCK){load()["authorization"]?.jsonPrimitive?.content.orEmpty()}
    fun startEpochMillis():Long=synchronized(LOCK){load()["startEpochMillis"]?.jsonPrimitive?.long?:System.currentTimeMillis()}
    fun clear()=synchronized(LOCK){store.write("");cursors.write("")}
    fun status():JsonObject=synchronized(LOCK){val state=load();buildJsonObject {
        put("enabled",state["enabled"]?:JsonPrimitive(false));put("pending",records(state).size)
        put("completed",done(state).size);put("lastSavedAt",state["lastSavedAt"]?:JsonNull)
        put("error",state["error"]?:JsonNull)
    }}
    fun capture(source:String,id:String,at:Long,body:JsonObject)=synchronized(LOCK){
        val state=load();if(state["enabled"]?.jsonPrimitive?.boolean!=true)return@synchronized
        require(at>=0&&body.toString().length<=16_000)
        val sha=sha("$at\n$body");val version=BriefingLedger.version(source,id,sha)
        if(!BriefingLedger.shouldRead(version,done(state))||records(state).any {it["version"]?.jsonPrimitive?.content==version})return@synchronized
        val rows=records(state)
        if(rows.size>=1000||state.toString().length>3_800_000){
            save(JsonObject(state+mapOf("error" to JsonPrimitive("archive_full_source_not_acknowledged"))))
            error("미분석 보관함이 가득 찼습니다. 자료를 처리하거나 보관함을 지워주세요.")
        }
        val next=rows+buildJsonObject {put("source",source);put("id",id);put("at",at);put("sha256",sha);put("version",version);put("body",body)}
        save(JsonObject(state+mapOf("records" to JsonArray(next))))
    }
    fun pending():List<JsonObject> = synchronized(LOCK){records(load())}
    fun completed(version:String):Boolean=synchronized(LOCK){version in done(load())}
    fun acknowledge(version:String,resultSavedAndReadBack:Boolean)=synchronized(LOCK){
        val state=load();val rows=records(state);val item=rows.singleOrNull {it["version"]?.jsonPrimitive?.content==version}
            ?:error("미분석 자료 버전이 없거나 이미 처리됐습니다.")
        val accepted=BriefingLedger.acknowledge(version,item.getValue("version").jsonPrimitive.content,resultSavedAndReadBack)
        val complete=done(state);check(complete.size<20_000){"처리 이력 보관 한도입니다. 이력을 확인해주세요."}
        save(JsonObject(state+mapOf("records" to JsonArray(rows-item),"completed" to JsonArray((complete+accepted).map(::JsonPrimitive)),
            "lastSavedAt" to JsonPrimitive(System.currentTimeMillis()),"error" to JsonNull)))
    }
    private fun records(s:JsonObject)=(s["records"] as? JsonArray).orEmpty().map {it.jsonObject}
    private fun done(s:JsonObject)=(s["completed"] as? JsonArray).orEmpty().map {it.jsonPrimitive.content}.toSet()
    companion object {
        internal val LOCK=Any()
        fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
