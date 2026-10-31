package com.user.localagent.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolRegistry
import kotlinx.serialization.json.*
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

// Never sends or prints source arguments, identifiers or fingerprints.
data class WorkTraceHashes(val argsHash:String,val scopeHash:String)
class WorkTraceFingerprinter {
    fun of(binding:WorkTraceBinding,call:ToolCall,workspaceBinding:String?=null):WorkTraceHashes {
        ToolRegistry.validate(call)
        val args=canonical(buildJsonObject{put("tool",call.name);put("arguments",call.arguments)})
        val scope=canonical(buildJsonObject{put("run",binding.runId);put("thread",binding.threadId);put("branch",binding.branchId);put("project",binding.projectId);put("threadRevision",binding.threadRevision);put("projectRevision",binding.projectRevision);put("workspace",workspaceBinding)})
        return WorkTraceHashes(hmac("args\u0000$args"),hmac("scope\u0000$scope"))
    }
    private fun hmac(input:String):String {
        val bytes=input.toByteArray(Charsets.UTF_8)
        return try {Mac.getInstance("HmacSHA256").apply{init(key())}.doFinal(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}}finally{bytes.fill(0)}
    }
    private fun key():SecretKey=synchronized(KEY_LOCK){
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        if(!store.containsAlias(ALIAS))KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
        store.getKey(ALIAS,null) as SecretKey
    }
    private fun canonical(value:JsonElement):String=when(value){
        is JsonObject->value.keys.sorted().joinToString(",","{","}"){key->JsonPrimitive(key).toString()+":"+canonical(value.getValue(key))}
        is JsonArray->value.joinToString(",","[","]"){canonical(it)}
        else->value.toString()
    }
    companion object {private const val ALIAS="ziphap-work-trace-hmac-v1";private val KEY_LOCK=Any()}
}
