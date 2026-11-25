package com.user.localagent.connections

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.user.localagent.core.connections.ConnectedService
import com.user.localagent.core.connections.MutationRecord
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

data class LocalConnection(val principal:String,val accountName:String?,val writeAllowed:Boolean,val generation:Long,val verifiedAt:Long=0)

/** Separate Keystore envelope: no plaintext tokens/account names in preferences or model prompts. */
class ConnectionStore internal constructor(context:Context, preferenceName:String, private val alias:String) {
    constructor(context:Context):this(context,"ziphap-connections-v1","ziphap.connections.aes.v1")
    private val prefs=context.applicationContext.getSharedPreferences(preferenceName,Context.MODE_PRIVATE)
    internal fun contains(name:String):Boolean=prefs.contains(name)
    private fun key():SecretKey=synchronized(KEY_LOCK) {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(name:String):String? {
        val encoded=prefs.getString(name,null)?:return null
        val bytes=Base64.decode(encoded,Base64.NO_WRAP);require(bytes.size>=29)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        cipher.updateAAD(name.toByteArray());return cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
    }
    @Synchronized fun write(name:String,value:String?) {
        if(value==null){check(prefs.edit().remove(name).commit());return}
        require(value.toByteArray().size<=4*1024*1024)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(name.toByteArray())
        val encoded=Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)
        check(prefs.edit().putString(name,encoded).commit()){"연결 설정 저장 실패"}
    }
    fun connection(service:ConnectedService):LocalConnection?=synchronized(CONNECTION_LOCK){read("connection.${service.id}")?.let {raw->val value=Json.parseToJsonElement(raw).jsonObject
        LocalConnection(value.getValue("principal").jsonPrimitive.content,value["account"]?.jsonPrimitive?.contentOrNull,value.getValue("write").jsonPrimitive.boolean,value.getValue("generation").jsonPrimitive.long,value["verifiedAt"]?.jsonPrimitive?.long?:0)}}
    private fun saveConnection(service:ConnectedService,value:LocalConnection)=write("connection.${service.id}",buildJsonObject {put("principal",value.principal);put("account",value.accountName);put("write",value.writeAllowed);put("generation",value.generation);put("verifiedAt",value.verifiedAt)}.toString())
    fun connect(service:ConnectedService,value:LocalConnection)=synchronized(CONNECTION_LOCK){
        val prior=connection(service)
        val epoch=maxOf(prior?.generation?:0,read("epoch.${service.id}")?.toLong()?:0)
        require(epoch in 0 until Long.MAX_VALUE&&value.generation in 0 until Long.MAX_VALUE)
        val next=value.copy(generation=maxOf(epoch+1,value.generation))
        write("epoch.${service.id}",next.generation.toString());saveConnection(service,next)
    }
    fun markVerified(service:ConnectedService,expected:com.user.localagent.core.connections.ConnectionAccess):Boolean=synchronized(CONNECTION_LOCK){
        val value=connection(service)?:return@synchronized false
        if(com.user.localagent.core.connections.ConnectionAccess(value.principal,value.generation,true,value.writeAllowed)!=expected)return@synchronized false
        saveConnection(service,value.copy(verifiedAt=System.currentTimeMillis()));true
    }
    fun disconnect(service:ConnectedService)=synchronized(CONNECTION_LOCK) {
        connection(service)?.let {prior->
            val epoch=maxOf(prior.generation,read("epoch.${service.id}")?.toLong()?:0)
            write("epoch.${service.id}",epoch.toString())
        }
        write("connection.${service.id}",null);write("token.${service.id}",null);write("allowedChat.${service.id}",null)
        // Keep encrypted mutation IDs/hashes so reconnecting cannot replay an uncertain create.
        val names=prefs.all.keys.filter {it.startsWith("picker.${service.id}")}
        val editor=prefs.edit();names.forEach(editor::remove);check(editor.commit())
    }
    fun mutation(service:ConnectedService,key:String)=read("journal.${service.id}.$key")?.let {val value=Json.parseToJsonElement(it).jsonObject
        MutationRecord(value.getValue("hash").jsonPrimitive.content,value["id"]?.jsonPrimitive?.contentOrNull,value.getValue("phase").jsonPrimitive.content,
            value["requestedAtMillis"]?.jsonPrimitive?.longOrNull)}
    fun saveMutation(service:ConnectedService,key:String,value:MutationRecord)=write("journal.${service.id}.$key",buildJsonObject {put("hash",value.requestHash);put("id",value.remoteId);put("phase",value.phase);put("requestedAtMillis",value.requestedAtMillis)}.toString())
    @Synchronized fun reserve(service:ConnectedService):Boolean {
        val today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()
        val key="budget.${service.id}";val data=read(key)?.let {Json.parseToJsonElement(it).jsonObject}
        val old=if(data?.get("date")?.jsonPrimitive?.content==today)data["count"]?.jsonPrimitive?.int?:0 else 0
        val max=if(service==ConnectedService.YOUTUBE)20 else 200
        if(old>=max)return false
        write(key,buildJsonObject {put("date",today);put("count",old+1)}.toString());return true
    }
    companion object {private val KEY_LOCK=Any();private val CONNECTION_LOCK=Any()}
}
