package com.user.localagent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Unsent text gets the same local encryption boundary as other personal content. */
class DraftStore(context:Context,private val scopeKey:String?=null,private val maxChars:Int=12_000) {
    init {require(scopeKey==null||scopeKey.length in 1..512);require(maxChars in 1..4_000_000)}
    private val identity=scopeKey?.let{key->"chat-draft-"+MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}+".enc"}?:"chat-draft.enc"
    private val file=AtomicFile(File(context.applicationContext.filesDir,identity))
    private val revisionCounter=DraftRevisions.forFile(file.baseFile)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val alias="ziphap-chat-draft-v1"
        if(!store.containsAlias(alias))KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return store.getKey(alias,null) as SecretKey
    }
    fun read():String=runCatching {readStrict()}.getOrDefault("")
    /** A corrupt scoped record must never be treated as proof that no other owner references it. */
    fun readStrict():String=synchronized(LOCK){
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return@synchronized ""
        val bytes=file.openRead().use {input->
            require(input.channel.size() in 28L..(maxChars.toLong()*4+28))
            input.readBytes()
        }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        scopeKey?.let{cipher.updateAAD(it.toByteArray(Charsets.UTF_8))}
        String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8).also{require(it.length<=maxChars)}
    }
    fun reserveWrite():Long=revisionCounter.incrementAndGet()
    fun write(text:String,revision:Long=reserveWrite())=synchronized(LOCK){
        if(revision!=revisionCounter.get())return@synchronized
        require(text.length<=maxChars)
        if(text.isEmpty()){file.delete();return@synchronized}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        scopeKey?.let{cipher.updateAAD(it.toByteArray(Charsets.UTF_8))}
        val encrypted=cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val stream=file.startWrite()
        try {stream.write(cipher.iv);stream.write(encrypted);file.finishWrite(stream)}catch(error:Throwable){file.failWrite(stream);throw error}
    }
    companion object {private val LOCK=Any()}
}
