package com.user.localagent.images

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.user.localagent.core.ImageConsistencyRecipe
import com.user.localagent.core.ImageConsistencyLibrary
import com.user.localagent.data.DraftStore
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Explicit Studio preset only. Never attached silently to chats or queued requests. */
class ImageConsistencyStore(context:Context,scopeKey:String="image-studio-consistency-v1") {
    init {require(scopeKey.matches(Regex("[a-zA-Z0-9-]{1,80}")))}
    private val context=context.applicationContext
    private val store=DraftStore(this.context,scopeKey,256_000)
    private val directory=File(this.context.filesDir,"shared/image-series"+
        if(scopeKey=="image-studio-consistency-v1")""else"/scoped/"+hash(scopeKey.toByteArray())).apply {mkdirs()}
    private fun library():ImageConsistencyLibrary {
        val raw=store.readStrict()
        if(raw.isEmpty())return ImageConsistencyLibrary()
        return if("schemaVersion" in Json.parseToJsonElement(raw).jsonObject)
            Json.decodeFromString<ImageConsistencyLibrary>(raw).validate()
        else ImageConsistencyLibrary(recipes=listOf(Json.decodeFromString<ImageConsistencyRecipe>(raw).validate())).validate()
    }
    fun list():List<ImageConsistencyRecipe> = synchronized(LOCK){library().recipes}
    fun read():ImageConsistencyRecipe? = synchronized(LOCK){library().recipes.firstOrNull()?.also{reference(it)}}
    fun save(recipe:ImageConsistencyRecipe) {
        recipe.validate();reference(recipe)
        synchronized(LOCK){store.write(CODEC.encodeToString(library().save(recipe)))}
    }
    /** Forget only a named recipe, never its reference file or generated photographs. */
    fun remove(expected:ImageConsistencyRecipe) = synchronized(LOCK){store.write(CODEC.encodeToString(library().remove(expected)))}
    /** Removes only this scope's preset; source photos and generated outputs remain intact. */
    fun clearPreset() = synchronized(LOCK){store.write("")}
    fun reference(recipe:ImageConsistencyRecipe):Uri? {
        val name=recipe.validate().referenceName?:return null
        val file=File(directory,name)
        require(file.isFile&&file.length() in 1..8*1024*1024)
        require(hash(file.readBytes())==recipe.referenceSha256){"기준 사진 무결성 확인 실패"}
        return FileProvider.getUriForFile(context,"${context.packageName}.files",file)
    }
    fun importReference(uri:Uri):Pair<String,String> {
        require(uri.scheme=="content")
        val bytes=context.contentResolver.openInputStream(uri)?.use {input->
            ByteArrayOutputStream().use {out->
                val buffer=ByteArray(32*1024)
                while(true){val count=input.read(buffer);if(count<0)break
                    require(out.size()+count<=8*1024*1024);out.write(buffer,0,count)}
                out.toByteArray().also {require(it.isNotEmpty())}
            }
        }?:error("기준 사진을 읽을 수 없습니다.")
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..16384&&bounds.outHeight in 1..16384&&bounds.outWidth.toLong()*bounds.outHeight<=100_000_000)
        // Preserve EXIF/orientation for the existing reference preprocessing path.
        val sha=hash(bytes)
        val name="$sha.image"
        val file=File(directory,name)
        if(!file.exists()) {
            val temp=File.createTempFile("reference-",".tmp",directory)
            try {temp.outputStream().use {it.write(bytes)};check(temp.renameTo(file))}finally{temp.delete()}
        }
        require(hash(file.readBytes())==sha)
        return name to sha
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    companion object {private val LOCK=Any();private val CODEC=Json{encodeDefaults=true}}
}
