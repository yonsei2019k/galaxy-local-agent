package com.user.localagent.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import com.user.localagent.integrations.ScreenWindowImage
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
    data class Candidate(val detail:String,val image:String?,val map:Boolean=false,val sites:Set<String> = emptySet())
    data class SitePin(val site:String,val x:Double,val y:Double)
    data class Thumbnail(val data:String,val representative:Boolean,val pins:List<SitePin> = emptyList())

    suspend fun load(candidate:Candidate):Thumbnail? = withContext(Dispatchers.IO) {
        val seen=safe(candidate.detail)?:return@withContext null
        val detail=URI(seen.scheme,seen.authority,seen.path,null,null)
        val direct=candidate.image?.let(::safe)?.takeIf {allowedDirect(detail,it)}
        if(candidate.map)return@withContext direct?.let {readPhoto(it,2048,400_000,exactPath=true,sites=candidate.sites)}
        if(direct!=null){
            val preview=try {readPhoto(direct)}
            catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
            catch(_:Exception){null}
            if(preview!=null)return@withContext preview
        }
        val page=pages.get(detail.toString(),PublicWebClient.MAX_BYTES)
        if(page.statusCode!=200||!page.contentType.substringBefore(';').equals("text/html",true))
            return@withContext null
        val image=ogImage(page.finalUrl,page.body.decodeToString())?:return@withContext null
        readPhoto(image)?.copy(representative=true)
    }

    private suspend fun readPhoto(url:URI,maxEdge:Int=560,maxBytes:Int=180_000,exactPath:Boolean=false,
        sites:Set<String> = emptySet()):Thumbnail? {
        val request=thumbnailUrl(url)
        val result=images.get(request.toString(),PublicWebClient.MAX_BYTES)
        val final=safe(result.finalUrl)?:return null
        if(final.host!=request.host||exactPath&&(final.rawPath!=request.rawPath||final.rawQuery!=request.rawQuery))return null
        if(result.statusCode!=200||result.contentType.substringBefore(';').trim().lowercase() !in mimeTypes)
            return null
        val bytes=result.body
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth !in 60..8192||bounds.outHeight !in 60..8192||bounds.outMimeType !in mimeTypes)
            return null
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxEdge)sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
            ?:return null
        val jpeg=ByteArrayOutputStream()
        try {
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,82,jpeg)||jpeg.size()>maxBytes)return null
            val pins=if(sites.isEmpty())emptyList()else recognizeSites(bitmap,sites)
            val data="data:image/jpeg;base64,"+Base64.encodeToString(jpeg.toByteArray(),Base64.NO_WRAP)
            return Thumbnail(data,false,pins)
        } finally {bitmap.recycle()}
    }

    private suspend fun recognizeSites(bitmap:Bitmap,sites:Set<String>):List<SitePin> = try {
        val bounds=Rect(0,0,bitmap.width,bitmap.height)
        val lines=ScreenWindowImage.lines(bitmap,bounds).filter {bounds.contains(it.bounds)&&!it.bounds.isEmpty}
        matchedSitePins(sites,lines.map {line->
            SitePin(line.text,line.bounds.exactCenterX()*100.0/bitmap.width,line.bounds.exactCenterY()*100.0/bitmap.height)
        })
    } catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
      catch(_:Exception){emptyList()}

    companion object {
        private val mimeTypes=setOf("image/jpeg","image/png","image/webp")
        private val meta=Regex("""(?i)<meta\b[^>]{0,4096}>""")
        private val attribute=Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        private val fence=Regex("^ {0,3}([`]{3,}|~{3,})\\s*([\\w+-]*)\\s*$")
        private val siteCode=Regex("[A-Z]{1,3}-[0-9]{1,4}")

        internal fun matchedSitePins(sites:Set<String>,observed:List<SitePin>):List<SitePin> =
            observed.map {it.copy(site=it.site.trim().uppercase(java.util.Locale.ROOT))}
                .filter {it.site in sites&&siteCode.matches(it.site)&&it.x in 0.0..100.0&&it.y in 0.0..100.0}
                .groupBy {it.site}.values.mapNotNull {it.singleOrNull()}

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

        private fun firstBlock(message:String,type:String,kind:String?=null):String? {
            var marker:String?=null
            var matching=false
            val text=StringBuilder()
            message.lineSequence().forEach {line->
                val active=marker
                if(active==null){
                    val parts=fence.matchEntire(line)?.groupValues?:return@forEach
                    marker=parts[1]
                    matching=parts[2]==type
                }else if(line.trim().let {it.length>=active.length&&it.all {symbol->symbol==active.first()}}){
                    if(matching){
                        if(kind==null||runCatching{Json.parseToJsonElement(text.toString()).jsonObject.text("kind")}.getOrNull()==kind)
                            return text.toString()
                        text.clear()
                    }
                    marker=null
                }else if(matching){
                    if(text.length+line.length>=14_000)return null
                    text.appendLine(line)
                }
            }
            return null
        }

        internal fun candidate(snapshot:String,thread:String,branch:String,messageId:String,index:Int,map:Boolean=false):Candidate?=runCatching {
            require(index in 0..5)
            val root=Json.parseToJsonElement(snapshot).jsonObject
            require(root.text("threadId")==thread&&root.text("branchId")==branch)
            val message=root.getValue("messages").jsonArray.single {
                it.jsonObject.text("id")==messageId&&it.jsonObject.text("role")=="assistant"
            }.jsonObject.text("text")?:return null
            val json=(if(map)firstBlock(message,"service-result","camp_sites")else firstBlock(message,"stays"))?:return null
            val data=Json.parseToJsonElement(json).jsonObject
            if(map){
                require(data["version"]==JsonPrimitive(1))
                val items=data.getValue("items").jsonArray
                require(items.size==1&&index==0)
                val row=items.single().jsonObject
                val detail=row.text("mapUrl")?.let(::safe)?:return null
                val image=row.text("mapImageUrl")?.let(::safe)?:return null
                require(detail.host.equals(image.host,true)&&image.rawQuery==null&&image.rawFragment==null)
                val sites=row.text("availableSites").orEmpty().take(240).uppercase(java.util.Locale.ROOT)
                    .split(Regex("[,·\\s]+")).filter(siteCode::matches).toSet()
                return Candidate(detail.toString(),image.toString(),map=true,sites=sites)
            }
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
