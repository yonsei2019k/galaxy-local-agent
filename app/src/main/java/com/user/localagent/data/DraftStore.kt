package com.user.localagent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Unsent text gets the same local encryption boundary as other personal content. */
class DraftStore(context:Context) {
    private val file=AtomicFile(File(context.applicationContext.filesDir,"chat-draft.enc"))
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val alias="ziphap-chat-draft-v1"
        if(!store.containsAlias(alias))KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return store.getKey(alias,null) as SecretKey
    }
    fun read():String=synchronized(LOCK){
        if(!file.baseFile.exists())return@synchronized ""
        runCatching {
            require(file.baseFile.length() in 28..64_000)
            val bytes=file.readFully()
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
        }.getOrDefault("")
    }
    fun reserveWrite():Long=REVISION.incrementAndGet()
    fun write(text:String,revision:Long=reserveWrite())=synchronized(LOCK){
        if(revision!=REVISION.get())return@synchronized
        require(text.length<=12_000)
        if(text.isEmpty()){file.delete();return@synchronized}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val encrypted=cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val stream=file.startWrite()
        try {stream.write(cipher.iv);stream.write(encrypted);file.finishWrite(stream)}catch(error:Throwable){file.failWrite(stream);throw error}
    }
    companion object {private val LOCK=Any();private val REVISION=java.util.concurrent.atomic.AtomicLong()}
}
