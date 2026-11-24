package com.user.localagent.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.user.localagent.web.PinnedPublicHttpsTransport
import com.user.localagent.web.PublicWebClient
import com.user.localagent.web.PublicWebSafety
import com.user.localagent.web.PublicWebTransport
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** User-triggered public thumbnails. No browser cookies, credentials, or WebView network access. */
internal class StayPhotoLoader(
    private val pages:PublicWebTransport=PinnedPublicHttpsTransport(),
    private val images:PublicWebTransport=PinnedPublicHttpsTransport(image=true),
) {
    data class Candidate(val detail:String,val image:String?)
    data class Thumbnail(val data:String,val representative:Boolean)

    suspend fun load(candidate:Candidate):Thumbnail? = withContext(Dispatchers.IO) {
        val seen=safe(candidate.detail)?:return@withContext null
        val detail=URI(seen.scheme,seen.authority,seen.path,null,null)
        val direct=candidate.image?.let(::safe)?.takeIf {allowedDirect(detail,it)}
        if(direct!=null){
            val preview=try {readPhoto(direct)}
            catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
            catch(_:Exception){null}
            if(preview!=null)return@withContext Thumbnail(preview,false)
        }
        val page=pages.get(detail.toString(),PublicWebClient.MAX_BYTES)
        if(page.statusCode!=200||!page.contentType.substringBefore(';').equals("text/html",true))
            return@withContext null
        val image=ogImage(page.finalUrl,page.body.decodeToString())?:return@withContext null
        readPhoto(image)?.let {Thumbnail(it,true)}
    }

    private suspend fun readPhoto(url:URI):String? {
        val request=thumbnailUrl(url)
        val result=images.get(request.toString(),PublicWebClient.MAX_BYTES)
        if(safe(result.finalUrl)?.host!=request.host)return null
        if(result.statusCode!=200||result.contentType.substringBefore(';').trim().lowercase() !in mimeTypes)
            return null
        val bytes=result.body
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth !in 60..8192||bounds.outHeight !in 60..8192||bounds.outMimeType !in mimeTypes)
            return null
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>560)sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
            ?:return null
        val jpeg=ByteArrayOutputStream()
        try {
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,82,jpeg)||jpeg.size()>180_000)return null
        } finally {bitmap.recycle()}
        return "data:image/jpeg;base64,"+Base64.encodeToString(jpeg.toByteArray(),Base64.NO_WRAP)
    }

    companion object {
        private val mimeTypes=setOf("image/jpeg","image/png","image/webp")
        private val meta=Regex("""(?i)<meta\b[^>]{0,4096}>""")
        private val attribute=Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        private val fence=Regex("^ {0,3}([`]{3,}|~{3,})\\s*([\\w+-]*)\\s*$")

        private fun JsonObject.text(key:String):String?=(get(key) as? JsonPrimitive)?.takeIf {it.isString}?.content
        private fun safe(value:String):URI?=runCatching {
            require(value.length<=2048)
            PublicWebSafety.requireHttps(value)
        }.getOrNull()

        internal fun allowedDirect(detail:URI,image:URI):Boolean {
            val property=detail.host.lowercase().removePrefix("www.")
            val host=image.host.lowercase().removePrefix("www.")
            return property==host||(property=="yeogi.com"&&host=="image.withstatic.com")
        }

        internal fun thumbnailUrl(source:URI):URI =
            if(source.host=="image.withstatic.com")
                URI(source.scheme,source.authority,source.path,"format=webp&height=360&width=560",null)
            else URI(source.scheme,source.authority,source.path,null,null)

        private fun firstStayBlock(message:String):String? {
            var marker:String?=null
            var lodging=false
            val text=StringBuilder()
            message.lineSequence().forEach {line->
                val active=marker
                if(active==null){
                    val parts=fence.matchEntire(line)?.groupValues?:return@forEach
                    marker=parts[1]
                    lodging=parts[2]=="stays"
                }else if(line.trim().let {it.length>=active.length&&it.all {symbol->symbol==active.first()}}){
                    if(lodging)return text.toString()
                    marker=null
                }else if(lodging){
                    if(text.length+line.length>=14_000)return null
                    text.appendLine(line)
                }
            }
            return null
        }

        internal fun candidate(snapshot:String,thread:String,branch:String,messageId:String,index:Int):Candidate?=runCatching {
            require(index in 0..5)
            val root=Json.parseToJsonElement(snapshot).jsonObject
            require(root.text("threadId")==thread&&root.text("branchId")==branch)
            val message=root.getValue("messages").jsonArray.single {
                it.jsonObject.text("id")==messageId&&it.jsonObject.text("role")=="assistant"
            }.jsonObject.text("text")?:return null
            val json=firstStayBlock(message)?:return null
            val data=Json.parseToJsonElement(json).jsonObject
            require(data["version"]==JsonPrimitive(1)&&data.text("currency")=="KRW")
            val stays=data.getValue("stays").jsonArray
            require(stays.size in 1..6)
            val row=stays[index].jsonObject
            val detail=row.text("url")?.takeIf {safe(it)!=null}?:return null
            Candidate(detail,row.text("photoUrl")?.takeIf {safe(it)!=null})
        }.getOrNull()

        internal fun ogImage(page:String,html:String):URI? {
            val source=safe(page)?:return null
            val head=html.substringBefore("</head>",html).take(180_000)
            return meta.findAll(head).take(80).mapNotNull { tag ->
                val attrs=attribute.findAll(tag.value).associate {
                    it.groupValues[1].lowercase() to (it.groups[2]?.value?:it.groupValues[3])
                }
                if(!attrs["property"].equals("og:image",true)&&!attrs["property"].equals("og:image:secure_url",true))
                    return@mapNotNull null
                val value=attrs["content"]?.replace("&amp;","&")?:return@mapNotNull null
                runCatching {source.resolve(value)}.getOrNull()?.toString()?.let(::safe)
            }.firstOrNull()
        }
    }
}
