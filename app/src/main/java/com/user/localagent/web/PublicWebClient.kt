package com.user.localagent.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.time.Instant
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class WebSearchResult(val title:String,val url:String,val snippet:String,val source:String,val fetchedAt:String,val offer:WebOffer?=null,val merchant:String?=null)
data class WebSearchReport(val query:String,val items:List<WebSearchResult>,val fetchedAt:String,val errors:List<String>,val partial:Boolean,val requestCount:Int=0)
data class WebOffer(val price:String?,val currency:String?,val availability:String?,val shipping:String?,val validThrough:String?,val sourceUrl:String,val fetchedAt:String,val priceSource:String="JSON-LD")
data class WebPage(val url:String,val title:String,val text:String,val offers:List<WebOffer>,val fetchedAt:String,val untrusted:Boolean=true,val publicExcerpt:String?=null,val campMap:PublicCampMap?=null)

data class PublicCampMap(val pageUrl:String,val imageUrl:String) {
    internal fun json()=buildJsonObject {put("mapUrl",pageUrl);put("mapImageUrl",imageUrl)}

    companion object {
        private fun detail(raw:String):URI?=runCatching {
            val url=PublicWebSafety.requireHttps(raw)
            require(url.host.equals("m.thankqcamping.com",true)&&url.path=="/resv/view.hbb"&&url.port in setOf(-1,443))
            val campsite=url.rawQuery.orEmpty().split('&').filter {it.startsWith("cseq=")}.single()
            require(campsite.matches(Regex("cseq=[1-9][0-9]{0,8}")))
            URI("https://m.thankqcamping.com/resv/view.hbb?$campsite")
        }.getOrNull()

        private fun original(page:URI,source:String):URI?=runCatching {
            val image=PublicWebSafety.requireHttps(page.resolve(source).toString())
            require(image.host.equals(page.host,true)&&image.port in setOf(-1,443)&&image.rawQuery==null&&image.rawFragment==null)
            require(image.rawPath.matches(Regex("/file/[0-9]{4}/[0-9]{2}/[0-9]{2}/[A-Za-z0-9._-]+\\.(?:jpe?g|png|webp)",RegexOption.IGNORE_CASE)))
            image
        }.getOrNull()

        internal fun observe(url:String,document:org.jsoup.nodes.Document):PublicCampMap? {
            val page=detail(url)?:return null
            val section=document.select("section#view_map").singleOrNull()?:return null
            if(section.selectFirst(".sec_title .tit")?.text()!="배치도")return null
            val image=section.select(".pic img[src]").mapNotNull {original(page,it.attr("src"))}.distinct().singleOrNull()?:return null
            return PublicCampMap(page.toASCIIString(),image.toASCIIString())
        }

        internal fun projected(url:String,observation:JsonObject):PublicCampMap? {
            val page=detail(url)?:return null
            if(observation["mapUrl"]!=JsonPrimitive(page.toASCIIString()))return null
            val source=(observation["mapImageUrl"] as? JsonPrimitive)?.takeIf {it.isString}?.content?:return null
            val image=original(page,source)?:return null
            return PublicCampMap(page.toASCIIString(),image.toASCIIString())
        }
    }
}
data class PublicWebResponse(val statusCode:Int,val finalUrl:String,val contentType:String,val body:ByteArray)
fun interface PublicWebTransport {suspend fun get(url:String,maxBytes:Int):PublicWebResponse}
class UnsafePublicWebUrlException:IllegalArgumentException("공개 HTTPS 주소만 허용됩니다.")

internal enum class PublicPageReadReason(val userMessage: String) {
    URL_REJECTED("공개 HTTPS 주소만 허용됩니다."),
    HTTP_STATUS("사이트가 페이지 읽기 요청에 정상적으로 응답하지 않았습니다."),
    TIMEOUT("사이트 응답을 기다리는 시간이 초과됐습니다."),
    DNS("사이트 주소를 확인하지 못했습니다."),
    TLS("사이트에 안전하게 연결하지 못했습니다."),
    CONNECTION("사이트와 연결하는 중 문제가 생겼습니다."),
    UNSUPPORTED_CONTENT("읽을 수 있는 공개 텍스트 페이지가 아닙니다."),
    ACCESS_CHALLENGE("이 사이트는 접근 확인이 필요합니다. 브라우저에서 확인해주세요."),
    BODY_LIMIT("페이지 용량 상한 초과"),
    NO_READABLE_CONTENT("사이트에서 읽을 수 있는 본문을 찾지 못했습니다."),
    RESPONSE_ERROR("사이트 응답을 읽지 못했습니다.");

    companion object {
        fun from(error: Throwable): PublicPageReadReason {
            if (error is CancellationException) throw error
            if (error is PublicPageReadException) return error.reason
            return when (error) {
                is UnsafePublicWebUrlException -> URL_REJECTED
                is SocketTimeoutException -> TIMEOUT
                is UnknownHostException -> DNS
                is SSLException -> TLS
                is ZipException -> RESPONSE_ERROR
                is IOException -> CONNECTION
                else -> RESPONSE_ERROR
            }
        }
    }
}

internal class PublicPageReadException(
    val reason: PublicPageReadReason,
    status: Int? = null
) : IllegalStateException(reason.userMessage) {
    val auditCode: String = status?.takeIf { reason == PublicPageReadReason.HTTP_STATUS && it in 100..599 }
        ?.let { "${reason.name}_$it" } ?: reason.name
}

/** Public, unauthenticated reading only. Returned content is untrusted data, never agent instructions.
 * Search endpoints are public website interfaces with no availability/coverage/lowest-price guarantee.
 */
class PublicWebClient(private val transport:PublicWebTransport=PinnedPublicHttpsTransport(),private val now:()->Instant={Instant.now()}) {
    /** Observed search candidates. Their relevance and research conclusions require reading and assessment. */
    suspend fun search(query:String,limit:Int=5,maxRequests:Int=3):WebSearchReport =
        searchInternal(query,limit,maxRequests,strictProductIdentity=false)

    private suspend fun searchInternal(query:String,limit:Int,maxRequests:Int,strictProductIdentity:Boolean):WebSearchReport {
        val clean=validateQuery(query)
        require(limit in 1..10){"검색 결과 개수는 1~10개입니다."}
        require(maxRequests in 1..3)
        val stamp=now().toString();val errors=mutableListOf<String>();var requests=0
        val encoded=URLEncoder.encode(clean,"UTF-8")
        val endpoints=listOf("DuckDuckGo Lite" to "https://lite.duckduckgo.com/lite/?q=$encoded",
            "Yahoo Search" to "https://search.yahoo.com/search?p=$encoded",
            "Bing RSS" to "https://www.bing.com/search?format=rss&q=$encoded")
        for((source,url)in endpoints.take(maxRequests)){
            currentCoroutineContext().ensureActive()
            try {
                requests++;val response=transport.get(url,MAX_BYTES)
                PublicWebSafety.requireHttps(response.finalUrl)
                check(response.statusCode==200){"HTTP ${response.statusCode}"}
                check(response.body.size<=MAX_BYTES){"응답 용량 상한 초과"}
                val body=decode(response)
                check(!isChallenge(body)){"접근 확인 또는 CAPTCHA가 필요합니다."}
                val items=(when(source){"Bing RSS"->parseRss(body,stamp);"Yahoo Search"->parseYahoo(body,stamp);else->parseDuck(body,stamp)}).filter {
                    siteScopeMatches(clean,it.url)&&(!strictProductIdentity||(relevant(clean,it.title+" "+it.snippet,it.url)&&!isAccessory(clean,it.title)))
                }
                if(items.isNotEmpty())return WebSearchReport(clean,items.distinctBy {it.url}.take(limit),stamp,errors.toList(),errors.isNotEmpty(),requests)
                errors+="$source: 공개 검색 결과가 없거나 응답 형식이 변경됐습니다."
            }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
            catch(error:Exception){errors+="$source: ${publicFailure(error)}"}
        }
        return WebSearchReport(clean,emptyList(),stamp,errors,true,requests)
    }

    /** Observed comparison-site listing prices only; never a guarantee of stock or market-wide lowest price. */
    suspend fun searchShopping(query:String,store:String?=null,limit:Int=5,maxRequests:Int=3):WebSearchReport {
        val clean=validateQuery(query);require(limit in 1..10);require(maxRequests in 1..3)
        val stamp=now().toString();val wanted=store?.trim()?.lowercase(Locale.ROOT)
        require(wanted==null||wanted in setOf("coupang","쿠팡","naver","네이버","danawa","다나와","11st","11번가","gmarket","g마켓","지마켓","amazon","아마존")){"지원하는 공개 쇼핑 검색처를 선택해주세요."}
        val searchTerms=productTokens(clean).joinToString(" ")
        require(searchTerms.isNotBlank()){"검색할 상품명과 모델명을 입력해주세요."}
        val url="https://search.danawa.com/dsearch.php?query="+URLEncoder.encode(searchTerms,"UTF-8")
        val comparison=try {
            val response=transport.get(url,MAX_BYTES)
            PublicWebSafety.requireHttps(response.finalUrl)
            check(response.statusCode==200){"HTTP ${response.statusCode}"}
            check(response.body.size<=MAX_BYTES)
            val body=decode(response);check(!isChallenge(body)){"접근 확인 또는 CAPTCHA가 필요합니다."}
            val products=danawaProducts(body).mapNotNull {product->
                fun value(key:String)=(product[key]as? JsonPrimitive)?.contentOrNull.orEmpty()
                val name=plain(value("productName"),300)
                val summary=((product["summary"]as? JsonObject)?.get("desc")as? JsonArray)?.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.let {entry->plain(entry,200)}
                }?.filter {it.isNotBlank()}?.joinToString(" · ")?.take(500).orEmpty()
                val category=plain(product["categoryNameList"].toString(),1000)
                val merchant=plain(value("shopName"),80).ifBlank {null}
                if(value("prodType") !in setOf("1","3"))return@mapNotNull null
                if(name.isBlank()||!relevant(searchTerms,"$name $summary $category",null)||isAccessory(clean,name))return@mapNotNull null
                if(product.any {(key,item)->key.lowercase(Locale.ROOT) in setOf("isad","adyn","isadvertisement","advertisement")&&(item as? JsonPrimitive)?.content?.lowercase(Locale.ROOT)in setOf("true","y","1")})return@mapNotNull null
                val code=(value("productSeq").ifBlank {value("productCode")}).takeIf {it.matches(Regex("[0-9]{1,20}"))}?:return@mapNotNull null
                val link=value("linkUrl").ifBlank {value("productLinkUrl")}.let {if(it.startsWith("//"))"https:$it"else it}
                val safeLink=runCatching {PublicWebSafety.requireHttps(link)}.getOrNull()
                val catalog="https://prod.danawa.com/info/?pcode=$code"
                val direct=if(safeLink!=null&&(safeLink.host.equals("prod.danawa.com",true)||listOf("coupang","naver","11st","gmarket","amazon").any {storeHostMatches(it,safeLink.host)}))safeLink.toString()else catalog
                val storeMatches=when(wanted){
                    "coupang","쿠팡"->merchant=="쿠팡"||storeHostMatches(wanted,URI(direct).host)
                    "naver","네이버"->merchant?.contains("네이버")==true||storeHostMatches(wanted,URI(direct).host)
                    "11st","11번가"->merchant=="11번가"||storeHostMatches(wanted,URI(direct).host)
                    "gmarket","g마켓","지마켓"->merchant?.equals("G마켓",true)==true||storeHostMatches(wanted,URI(direct).host)
                    "amazon","아마존"->merchant?.contains("amazon",true)==true||merchant?.contains("아마존")==true||storeHostMatches(wanted,URI(direct).host)
                    else->true
                }
                if(!storeMatches)return@mapNotNull null
                val amount=value("minPrice").takeIf {it.matches(Regex("[1-9][0-9]{0,11}"))}?:return@mapNotNull null
                val shipping=plain(value("postPriceHtml"),200).ifBlank {value("postPrice").takeIf {it.matches(Regex("[0-9]{1,10}"))}?.let {"배송비 $it KRW"}?:"배송비·회원가·쿠폰·결제 총액은 판매 페이지에서 확인"}
                val offer=WebOffer(amount,"KRW",null,shipping,null,url,stamp,"Danawa public listing minPrice; not checkout or global minimum")
                WebSearchResult(name,direct,(if(summary.isBlank())""else "다나와 표시 사양: $summary\n")+
                    "조회 시점 목록 가격. 재고·쿠폰·배송 포함 결제액·판매처별 최저가는 판매 페이지에서 확인.","Danawa public products",stamp,offer,merchant)
            }.distinctBy {it.url}
            val errors=if(products.isEmpty())listOf("요청한 상품·판매처와 일치하는 공개 가격 자료를 확인하지 못했습니다. 광고·액세서리·다른 판매처 가격을 대신 제시하지 않습니다.")else emptyList()
            WebSearchReport(clean,products.take(limit),stamp,errors,true,1)
        }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
        catch(error:Exception){WebSearchReport(clean,emptyList(),stamp,listOf("Danawa: ${publicFailure(error)}"),true,1)}
        val storeTerm=when(wanted){"coupang","쿠팡"->"쿠팡";"naver","네이버"->"네이버";"11st","11번가"->"11번가";"gmarket","g마켓","지마켓"->"G마켓";"amazon","아마존"->"Amazon";else->""}
        if(maxRequests>1&&(storeTerm.isNotEmpty()||comparison.items.isEmpty())){
            val discovered=searchInternal("$searchTerms $storeTerm".trim(),limit,maxRequests-1,strictProductIdentity=true)
            val links=discovered.items.filter {item->
                val host=URI(item.url).host.lowercase(Locale.ROOT)
                if(storeTerm.isEmpty())true else storeHostMatches(wanted,host)
            }.map {it.copy(merchant=storeTerm.ifBlank {null})}
            return comparison.copy(items=(comparison.items+links).distinctBy {it.url}.take(limit),errors=(comparison.errors+discovered.errors).distinct(),partial=true,requestCount=comparison.requestCount+discovered.requestCount)
        }
        return comparison
    }

    private fun storeHostMatches(store:String?,rawHost:String):Boolean {
        val host=rawHost.lowercase(Locale.ROOT)
        fun domain(root:String)=host==root||host.endsWith(".$root")
        return when(store){
            "coupang","쿠팡"->domain("coupang.com")
            "naver","네이버"->domain("naver.com")
            "11st","11번가"->domain("11st.co.kr")
            "gmarket","g마켓","지마켓"->domain("gmarket.co.kr")
            "amazon","아마존"->AMAZON_DOMAINS.any(::domain)
            else->false
        }
    }

    private fun danawaProducts(body:String):List<JsonObject> {
        val decoded=mutableListOf(body)
        Regex("(?s)self\\.__next_f\\.push\\((\\[.*?])\\)\\s*</script>").findAll(body).take(60).forEach {match->
            if(match.groupValues[1].length<=MAX_BYTES)runCatching {Json.parseToJsonElement(match.groupValues[1])as? JsonArray}.getOrNull()
                ?.filterIsInstance<JsonPrimitive>()?.filter {it.isString}?.forEach {decoded+=it.content}
        }
        val products=mutableListOf<JsonObject>()
        for(text in decoded){
            Regex("\"products\"\\s*:\\s*\\[").findAll(text).take(20).forEach {match->
                val start=match.range.last;var depth=0;var quoted=false;var escaped=false;var end=-1
                for(index in start until minOf(text.length,start+1_500_000)){
                    val c=text[index]
                    if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='\"')quoted=false}
                    else when(c){'\"'->quoted=true;'[','{'->depth++;']','}'->{depth--;if(depth==0){end=index+1;break}}}
                    if(depth>50)break
                }
                if(end>start)runCatching {Json.parseToJsonElement(text.substring(start,end))as? JsonArray}.getOrNull()
                    ?.filterIsInstance<JsonObject>()?.take(300)?.forEach {products+=it}
            }
        }
        return products.take(1000)
    }

    private fun productTokens(query:String):List<String> {
        val clean=query.lowercase(Locale.ROOT).replace(Regex("site:[a-z0-9.-]+")," ")
            .replace(Regex("제품링크(?:하나)?|상품중에|가성비가|최저가|가장\\s*좋은|다른\\s*상품|것도|찾아주고|찾아줘|추천해줘")," ")
            .replace(Regex("(샤오미|xiaomi|삼성|samsung|엘지|애플|apple)(?=[가-힣])"),"$1 ")
        return Regex("[가-힣a-z0-9][가-힣a-z0-9.-]{1,}").findAll(clean).map {it.value}
            .filterNot {it in QUERY_STOPWORDS||it.matches(Regex("(?:쿠팡|네이버|다나와|11번가|11st|g마켓|지마켓|gmarket|amazon|아마존)(?:에서|으로|에)?|(?:찾아|추천|비교|알려|검색|구매|보여|해주세요|해줘).*"))}.distinct().take(8).toList()
    }
    private fun relevant(query:String,text:String,url:String?):Boolean {
        if(url!=null&&!siteScopeMatches(query,url))return false
        fun normalize(value:String)=value.lowercase(Locale.ROOT).replace("xiaomi","샤오미").replace("samsung","삼성").replace(Regex("[^가-힣a-z0-9]"),"")
        val haystack=normalize(entities(text));val tokens=productTokens(query)
        return tokens.isNotEmpty()&&tokens.all {normalize(it)in haystack}
    }
    private fun siteScopeMatches(query:String,url:String):Boolean {
        val site=Regex("(?i)site:([a-z0-9.-]+)").find(query)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?:return true
        val host=runCatching {URI(url).host?.lowercase(Locale.ROOT)}.getOrNull()?:return false
        return host==site||host.endsWith(".$site")
    }
    private fun isAccessory(query:String,title:String):Boolean {
        val terms=listOf("필터","거치대","받침대","케이스","커버","부품","호환","중고","렌탈","대여","구독")
        return terms.any {it in title&&it !in query}
    }

    suspend fun fetchPage(url:String):WebPage {
        PublicWebSafety.requireHttps(url)
        val response=transport.get(url,MAX_BYTES)
        PublicWebSafety.requireHttps(response.finalUrl)
        if (response.statusCode != 200) throw PublicPageReadException(PublicPageReadReason.HTTP_STATUS, response.statusCode)
        if (response.body.size > MAX_BYTES) throw PublicPageReadException(PublicPageReadReason.BODY_LIMIT)
        val type=response.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (!type.startsWith("text/") && type !in setOf("application/xhtml+xml", "application/json", "application/ld+json")) {
            throw PublicPageReadException(PublicPageReadReason.UNSUPPORTED_CONTENT)
        }
        val html=decode(response)
        if (isChallenge(html)) throw PublicPageReadException(PublicPageReadReason.ACCESS_CHALLENGE)
        val stamp=now().toString()
        val title=Regex("(?is)<title\\b[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let {plain(it,300)}.orEmpty()
        val pageText=plain(html,MAX_TEXT)
        if (uniffi.ziphap_android.nativePublicPageInterstitial(title, pageText.take(2048))) {
            throw PublicPageReadException(PublicPageReadReason.ACCESS_CHALLENGE)
        }
        var publicExcerpt:String?=null
        var campMap:PublicCampMap?=null
        val text=if (type == "text/html" || type == "application/xhtml+xml") {
            val document = org.jsoup.Jsoup.parse(html)
            document.select("script, style, noscript, template, form, textarea, input, select, nav, footer, [hidden], [aria-hidden=true]").remove()
            campMap=PublicCampMap.observe(response.finalUrl,document)
            val content = document.selectFirst("main, [role=main], #content, #contents, .subContents")
            val body = content?.takeIf { it.hasText() } ?: document.body()
            val bodyText = body.text()
            // Keep related labels and values together. A partial public read never exports a
            // credential-bearing document, form values, attributes, or substrings of a rejected block.
            if (bodyText.length <= MAX_TEXT && com.user.localagent.core.ConversationPrivacy.sensitiveText(bodyText) &&
                com.user.localagent.core.CredentialRedaction.redact(bodyText).count == 0) {
                val groups = "dl, ul, ol, table, p"
                val excerpt = body.select(groups).asSequence()
                    .filter { block -> block.parents().none { it.`is`(groups) } }
                    .map { it.text() }.filter { it.length in 2..3000 }
                    .filterNot(com.user.localagent.core.ConversationPrivacy::sensitiveText)
                    .distinct().take(32).joinToString("\n").take(4000)
                publicExcerpt = excerpt.takeIf { it.isNotBlank() &&
                    !com.user.localagent.core.ConversationPrivacy.sensitiveText(it) &&
                    com.user.localagent.core.CredentialRedaction.redact(it).count == 0 }
            }
            bodyText.take(MAX_TEXT)
        } else pageText
        return WebPage(response.finalUrl, title, text, extractOffers(html, response.finalUrl, stamp), stamp, publicExcerpt=publicExcerpt,campMap=campMap)
    }

    private fun parseRss(body:String,stamp:String):List<WebSearchResult> {
        if(!body.contains(Regex("(?i)<rss\\b"))||body.contains(Regex("(?i)<!DOCTYPE|<!ENTITY")))return emptyList()
        return Regex("(?is)<item\\b[^>]*>(.*?)</item>").findAll(body).take(40).mapNotNull {match->
            val item=match.groupValues[1]
            fun field(name:String)=Regex("(?is)<$name\\b[^>]*>(.*?)</$name>").find(item)?.groupValues?.get(1).orEmpty()
            val url=entities(field("link").trim()).take(4096)
            if(runCatching {PublicWebSafety.requireHttps(url)}.isFailure)null
            else WebSearchResult(plain(field("title"),300),url,plain(field("description"),1000),"Bing RSS",stamp)
        }.filter {it.title.isNotBlank()}.toList()
    }
    private fun parseDuck(body:String,stamp:String):List<WebSearchResult> {
        val links=Regex("(?is)<a\\b([^>]*class\\s*=\\s*['\"][^'\"]*\\b(?:result__a|result-link)\\b[^'\"]*['\"][^>]*)>(.*?)</a>").findAll(body).take(40).toList()
        return links.mapIndexedNotNull {index,match->
            val href=Regex("(?is)\\bhref\\s*=\\s*(['\"])(.*?)\\1").find(match.groupValues[1])?.groupValues?.get(2)?:return@mapIndexedNotNull null
            val url=duckTarget(entities(href))?:return@mapIndexedNotNull null
            val end=links.getOrNull(index+1)?.range?.first?:minOf(body.length,match.range.last+6000)
            val tail=body.substring(match.range.last+1,end)
            val snippet=Regex("(?is)<(?:a|div|td)\\b[^>]*class\\s*=\\s*['\"][^'\"]*\\b(?:result__snippet|result-snippet)\\b[^'\"]*['\"][^>]*>(.*?)</(?:a|div|td)>").find(tail)?.groupValues?.get(1).orEmpty()
            WebSearchResult(plain(match.groupValues[2],300),url,plain(snippet,1000),"DuckDuckGo public search",stamp)
        }
    }
    private fun parseYahoo(body:String,stamp:String):List<WebSearchResult> {
        return Regex("(?is)<a\\b([^>]*\\bhref\\s*=\\s*(['\"])(.*?)\\2[^>]*)>(.*?)</a>").findAll(body).take(1500).mapNotNull {match->
            val title=Regex("(?is)<h3\\b[^>]*>(.*?)</h3>").find(match.groupValues[4])?.groupValues?.get(1)?:return@mapNotNull null
            val url=entities(match.groupValues[3])
            val safe=runCatching {PublicWebSafety.requireHttps(url)}.getOrNull()?:return@mapNotNull null
            val next=body.substring(match.range.last+1,minOf(body.length,match.range.last+2000))
            val snippet=Regex("(?is)<p\\b[^>]*>(.*?)</p>").find(next)?.groupValues?.get(1).orEmpty()
            WebSearchResult(plain(title,300),safe.toString(),plain(snippet,1000),"Yahoo Search",stamp)
        }.toList()
    }
    private fun duckTarget(raw:String):String?=runCatching {
        val normalized=if(raw.startsWith("//"))"https:$raw"else raw
        val uri=URI(normalized)
        val target=if(uri.host?.lowercase(Locale.ROOT) in setOf("duckduckgo.com","www.duckduckgo.com","html.duckduckgo.com")&&uri.path.startsWith("/l/")) {
            uri.rawQuery.orEmpty().split('&').firstOrNull {it.substringBefore('=')=="uddg"}?.substringAfter('=')?.let {URLDecoder.decode(it,"UTF-8")}?:error("검색 링크 없음")
        }else normalized
        PublicWebSafety.requireHttps(target).toString()
    }.getOrNull()

    private fun extractOffers(html:String,url:String,stamp:String):List<WebOffer> {
        val offers=mutableListOf<WebOffer>();var visited=0
        fun value(objectValue:JsonObject,key:String)=objectValue[key]?.let {it as? JsonPrimitive}?.contentOrNull?.trim()?.take(500)
        fun walk(node:JsonElement,depth:Int) {
            if(depth>20||visited++>10_000||offers.size>=30)return
            when(node){
                is JsonArray->node.forEach {walk(it,depth+1)}
                is JsonObject->{
                    val types=when(val t=node["@type"]){is JsonArray->t.mapNotNull {(it as? JsonPrimitive)?.contentOrNull};is JsonPrimitive->listOf(t.content);else->emptyList()}
                    if(types.any {it.substringAfterLast('/').substringAfterLast(':') in setOf("Offer","AggregateOffer")}){
                        val amount=(value(node,"price")?:value(node,"lowPrice"))?.takeIf {Regex("\\d{1,12}(?:\\.\\d{1,4})?").matches(it)}
                        val currency=value(node,"priceCurrency")?.uppercase(Locale.ROOT)?.takeIf {Regex("[A-Z]{3}").matches(it)}
                        if(amount!=null&&currency!=null)offers+=WebOffer(amount,currency,value(node,"availability")?.substringAfterLast('/'),
                            node["shippingDetails"]?.toString()?.take(1500),value(node,"priceValidUntil")?:value(node,"validThrough"),url,stamp,
                            if(types.any {it.substringAfterLast('/').substringAfterLast(':')=="AggregateOffer"})"JSON-LD AggregateOffer"else"JSON-LD Offer")
                    }
                    node.values.forEach {walk(it,depth+1)}
                }
                else->Unit
            }
        }
        Regex("(?is)<script\\b([^>]*)>(.*?)</script>").findAll(html).take(100).forEach {match->
            if(Regex("(?i)\\btype\\s*=\\s*['\"]application/ld\\+json['\"]").containsMatchIn(match.groupValues[1])){
                val body=match.groupValues[2].trim()
                if(body.length<=250_000)runCatching {Json.parseToJsonElement(body)}.getOrNull()?.let {walk(it,0)}
            }
        }
        return offers.distinct()
    }
    private fun decode(response:PublicWebResponse):String {
        val charset=Regex("(?i)charset\\s*=\\s*['\"]?([a-z0-9._-]+)").find(response.contentType)?.groupValues?.get(1)
        return response.body.toString(runCatching {Charset.forName(charset?:"UTF-8")}.getOrDefault(Charsets.UTF_8))
    }
    private fun plain(raw:String,limit:Int):String=entities(raw.replace(Regex("(?is)<!\\[CDATA\\[(.*?)]]>"),"$1")
        .replace(Regex("(?is)<(script|style|noscript)\\b[^>]*>.*?</\\1>")," ")
        .replace(Regex("(?s)<!--.*?-->")," ").replace(Regex("(?s)<[^>]{0,8192}>")," "))
        .replace(Regex("\\s+")," ").trim().take(limit)
    private fun entities(raw:String):String=Regex("&(#x[0-9a-fA-F]{1,6}|#\\d{1,7}|amp|lt|gt|quot|apos|nbsp);").replace(raw){match->
        when(val key=match.groupValues[1]){"amp"->"&";"lt"->"<";"gt"->">";"quot"->"\"";"apos"->"'";"nbsp"->" "
            else->runCatching {val point=if(key.startsWith("#x"))key.substring(2).toInt(16)else key.substring(1).toInt();require(Character.isValidCodePoint(point)&&point !in 0xD800..0xDFFF&&point>0);String(Character.toChars(point))}.getOrDefault(" ")}
    }
    private fun isChallenge(body:String)=Regex("(?i)captcha|verify (?:you are|that you are) (?:a )?human|unusual traffic|challenge-platform").containsMatchIn(body.take(100_000))
    private fun publicFailure(error:Exception)=when(error){is IllegalArgumentException->"공개 HTTPS 주소·입력 검증 실패";is java.net.SocketTimeoutException->"연결 시간 초과";else->error.message?.takeIf {it.startsWith("HTTP ")||it.contains("CAPTCHA") }?:"공개 페이지에 연결하지 못했습니다 (${error.javaClass.simpleName})"}
    companion object {
        const val MAX_BYTES=2*1024*1024
        const val MAX_TEXT=20_000
        private val AMAZON_DOMAINS=setOf("amazon.com","amazon.co.jp","amazon.co.uk","amazon.de","amazon.fr","amazon.it","amazon.es","amazon.ca","amazon.com.au","amazon.in","amazon.com.br","amazon.com.mx","amazon.sg","amazon.nl","amazon.se","amazon.pl","amazon.com.tr","amazon.ae","amazon.sa","amazon.eg","amazon.com.be","amazon.ie","amazon.co.za")
        private val QUERY_STOPWORDS=setOf("쿠팡","네이버","다나와","가장","최저가","최저","저렴한","저렴하게","가성비","상품","제품","제품링크","상품중에","상품중","링크","하나","하나만","다른","좋은","좋은것","것도","좀","찾아줘","찾아주고","추천해줘","가격","구매","그리고","추천","해줘","해주세요","구입","중에","중에서","저렴","최신","순위","비교")
        fun validateQuery(query:String,maxChars:Int=300):String { require(maxChars in 2..500)
            val clean=query.trim().replace(Regex("\\s+")," ")
            require(clean.length in 2..maxChars&&clean.none {it=='\u0000'||it=='\r'||it=='\n'}){"공개 검색어를 2~${maxChars}자로 입력해주세요."}
            require(!Regex("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?:01[016789]|0\\d{1,2})[- ]?\\d{3,4}[- ]?\\d{4}|bearer\\s|(?:password|api[_ -]?key|비밀번호|인증번호|카톡대화|문자본문|녹음전사)\\s*[:=]?").containsMatchIn(clean)){
                "개인 대화·인증정보·전화번호·이메일은 공개 검색어로 보낼 수 없습니다. 상품명 등 공개 정보만 입력해주세요."}
            require(!Regex("(?:카카오톡|카톡|메디통|텔레그램|문자|이메일|메일|녹음|통화)\\s*(?:대화|본문|내용|전사|기록)").containsMatchIn(clean)){
                "개인 자료의 본문은 공개 검색으로 보내지 않습니다. 공개 상품명·주제만 입력해주세요."}
            return clean
        }
    }
}

/** Validated DNS addresses are used directly for the TLS connection, preventing DNS rebinding. */
class PinnedPublicHttpsTransport(private val pdf:Boolean=false,private val image:Boolean=false,private val resolve:(String)->List<InetAddress> = {InetAddress.getAllByName(it).toList()}):PublicWebTransport {
    private var pdfSession: BoundPdfGet? = null
    internal constructor(session: BoundPdfGet): this(pdf=true) { pdfSession=session }

    override suspend fun get(url:String,maxBytes:Int):PublicWebResponse=withContext(Dispatchers.IO) {
        require(maxBytes in 1..PublicWebClient.MAX_BYTES)
        var target=PublicWebSafety.requireHttps(url)
        repeat(4){redirect->
            currentCoroutineContext().ensureActive()
            val host=target.host.removePrefix("[").removeSuffix("]")
            val addresses=resolve(host)
            if(addresses.isEmpty()||!addresses.all(PublicWebSafety::isPublicAddress))throw UnsafePublicWebUrlException()
            val address=addresses.firstOrNull {it is Inet4Address}?:addresses.first()
            val plain=Socket()
            try {
                plain.connect(InetSocketAddress(address,443),10_000);plain.soTimeout=15_000
                val tls=(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain,host,443,true) as SSLSocket
                tls.use {socket->
                    socket.soTimeout=15_000
                    socket.sslParameters=socket.sslParameters.apply {endpointIdentificationAlgorithm="HTTPS";if(!host.contains(':')&&!host.matches(Regex("[0-9.]+")))serverNames=listOf(SNIHostName(host))}
                    socket.startHandshake()
                    val path=target.rawPath.ifEmpty {"/"}+(target.rawQuery?.let {"?$it"}?:"")
                    val authority=if(host.contains(':'))"[$host]"else host
                    val accept=when {
                        pdf->"application/pdf,application/octet-stream;q=0.8"
                        image->"image/jpeg,image/png,image/webp"
                        else->"text/html,application/rss+xml,application/xhtml+xml,application/json,text/plain"
                    }
                    val cookie = pdfSession?.headerFor(target.toString()).orEmpty()
                    socket.outputStream.write(("GET $path HTTP/1.1\r\nHost: $authority\r\nUser-Agent: ZipHap.Agent/1.0 PublicWebReader\r\nAccept: $accept\r\nAccept-Encoding: identity\r\n${cookie}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                    val input=socket.inputStream.buffered()
                    val status=line(input,8192).split(' ').getOrNull(1)?.toIntOrNull()?:error("잘못된 HTTP 상태")
                    val headers=linkedMapOf<String,String>();var headerBytes=0
                    for(index in 0..100){
                        val line=line(input,8192);headerBytes+=line.length
                        require(headerBytes<=65_536&&index<100){"HTTP 헤더 상한 초과"}
                        if(line.isEmpty())break
                        val at=line.indexOf(':');require(at>0){"잘못된 HTTP 헤더"}
                        val key=line.substring(0,at).lowercase(Locale.ROOT);val value=line.substring(at+1).trim()
                        require(key !in setOf("content-length","transfer-encoding","location")||key !in headers){"중복 HTTP 프레이밍 헤더"}
                        headers[key]=value
                    }
                    if(status in setOf(301,302,303,307,308)){
                        check(pdfSession==null) { "로그인된 문서 다운로드의 주소가 변경됐어요." }
                        check(redirect<3){"웹 이동 횟수 상한 초과"}
                        target=PublicWebSafety.requireHttps(target.resolve(headers["location"]?:error("이동 주소 없음")).toString())
                    }else {
                        val length = headers["content-length"]?.let { rawLength ->
                            val declaredBytes = rawLength.toLongOrNull() ?: error("잘못된 페이지 길이")
                            if (declaredBytes !in 0..maxBytes.toLong()) throw PublicPageReadException(PublicPageReadReason.BODY_LIMIT)
                            declaredBytes
                        }
                        val encoding=headers["transfer-encoding"]?.lowercase(Locale.ROOT)
                        require(encoding==null||encoding=="chunked"){"지원하지 않는 전송 인코딩"}
                        require(encoding==null||length==null){"모호한 HTTP 프레이밍"}
                        val framed=if(encoding=="chunked")ChunkedInput(input,maxBytes)else if(length!=null)LengthInput(input,length)else input
                        val compression=headers["content-encoding"]?.lowercase(Locale.ROOT)
                        require(compression==null||compression in setOf("identity","gzip")){"지원하지 않는 콘텐츠 인코딩"}
                        val bodyInput=if(compression=="gzip")GZIPInputStream(framed)else framed
                        val output=ByteArrayOutputStream();val buffer=ByteArray(16*1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = bodyInput.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > maxBytes) throw PublicPageReadException(PublicPageReadReason.BODY_LIMIT)
                            output.write(buffer, 0, count)
                        }
                        if(length!=null&&compression!="gzip")require(output.size().toLong()==length){"불완전한 HTTP 본문"}
                        return@withContext PublicWebResponse(status,target.toString(),headers["content-type"].orEmpty(),output.toByteArray())
                    }
                }
            }finally {plain.close()}
        }
        error("웹 이동 횟수 상한 초과")
    }
    private fun line(input:InputStream,max:Int):String {
        val output=ByteArrayOutputStream()
        while(true){val byte=input.read();require(byte>=0){"불완전한 HTTP 헤더"};if(byte==10)break;require(output.size()<max){"HTTP 줄 길이 상한 초과"};output.write(byte)}
        return output.toString("ISO-8859-1").removeSuffix("\r")
    }
    private inner class ChunkedInput(private val input:InputStream,private val limit:Int):InputStream(){
        private var remaining=0L;private var total=0L;private var ended=false;private var needsCrLf=false
        override fun read():Int {val bytes=ByteArray(1);return if(read(bytes,0,1)<0)-1 else bytes[0].toInt()and 255}
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            if(length==0)return 0
            if(ended)return -1
            if(remaining==0L){
                if(needsCrLf)require(line(input,2).isEmpty()){"잘못된 청크 경계"}
                val size=line(input,100).substringBefore(';').trim()
                require(size.matches(Regex("[0-9a-fA-F]{1,8}"))){"잘못된 청크 길이"}
                remaining = size.toLong(16)
                total += remaining
                if (total > limit) throw PublicPageReadException(PublicPageReadReason.BODY_LIMIT)
                if(remaining==0L){ended=true;return -1};needsCrLf=true
            }
            val count=input.read(buffer,offset,minOf(length.toLong(),remaining).toInt());require(count>0){"불완전한 청크"};remaining-=count;return count
        }
    }
    private class LengthInput(private val input:InputStream,private var remaining:Long):InputStream(){
        override fun read():Int {if(remaining==0L)return -1;val byte=input.read();require(byte>=0){"불완전한 HTTP 본문"};remaining--;return byte}
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            if(length==0)return 0
            if(remaining==0L)return -1
            val count=input.read(buffer,offset,minOf(remaining,length.toLong()).toInt())
            require(count>0){"불완전한 HTTP 본문"};remaining-=count;return count
        }
    }
}

object PublicWebSafety {

    /** Known public lodging routes may carry catalog identifiers or a bounded trip selection. */
    internal fun isPublicLodgingLocator(uri: URI): Boolean {
        val query = uri.rawQuery ?: return false
        if (query.length > 128 || uri.rawFragment != null || uri.rawUserInfo != null) return false
        val fields = linkedMapOf<String, String>()
        for (part in query.split('&')) {
            val pair = part.split('=', limit = 2)
            if (pair.size != 2 || fields.put(pair[0], pair[1]) != null) return false
        }
        val path = uri.rawPath
        val id = Regex("[1-9][0-9]{0,11}")
        return when (uri.host?.lowercase(Locale.ROOT)) {
            "m.thankqcamping.com" -> path == "/resv/view.hbb" &&
                fields.keys.all {it=="cseq"||it=="path"} && fields["cseq"]?.matches(id)==true &&
                (fields["path"]==null||fields["path"]=="RP")
            "rev.yapen.co.kr" -> path == "/external/" && fields.keys == setOf("ypIdx") &&
                id.matches(fields.getValue("ypIdx"))
            "nol.yanolja.com" -> Regex("/stay/domestic/[1-9][0-9]{0,11}").matches(path) &&
                fields.keys.all { it == "ratePlanId" || it == "roomTypeId" } &&
                fields.containsKey("roomTypeId") && fields.values.all(id::matches)
            "place-site.yanolja.com" -> Regex("/places/[1-9][0-9]{0,11}").matches(path) &&
                isPublicStaySelection(fields)
            else -> false
        }
    }

    private fun isPublicStaySelection(fields: Map<String, String>): Boolean {
        if (fields.keys != setOf("rooms", "checkinDate", "checkoutDate", "capacityAdult", "capacityChild")) return false
        fun numeric(key: String): Int? = fields.getValue(key).takeIf { it.matches(Regex("[0-9]{1,2}")) }?.toInt()
        val adults = numeric("capacityAdult") ?: return false
        val minors = numeric("capacityChild") ?: return false
        val rooms = numeric("rooms") ?: return false
        if (adults !in 1..30 || adults + minors > 30 || rooms !in 1..adults) return false
        fun day(key: String) = fields.getValue(key).takeIf { it.matches(Regex("20[0-9]{2}-[0-9]{2}-[0-9]{2}")) }
            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val start = day("checkinDate") ?: return false
        val end = day("checkoutDate") ?: return false
        return end.isAfter(start) && !end.isAfter(start.plusDays(90))
    }

    fun requireHttps(raw:String):URI=try { validateHttps(raw) }catch(_:Exception){throw UnsafePublicWebUrlException()}
    private fun validateHttps(raw:String):URI {
        require(raw.length in 1..4096&&raw.none {it.code<32||it.code==127||it=='\\'}){"잘못된 웹 주소"}
        val uri=URI(raw)
        require(uri.scheme.equals("https",true)&&uri.rawUserInfo==null&&uri.host!=null&&(uri.port==-1||uri.port==443)){"인증 없는 표준 HTTPS 주소만 허용됩니다."}
        val host=uri.host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        require(host.isNotBlank()&&!host.endsWith('.')&&host!="localhost"&&!host.endsWith(".localhost")&&!host.endsWith(".local")&&!host.endsWith(".internal")&&!host.endsWith(".lan")&&!host.endsWith(".onion")&&!host.contains('%')){"로컬 주소는 허용되지 않습니다."}
        if(host.contains(':')||host.matches(Regex("[0-9.]+")))require(isPublicAddress(InetAddress.getByName(host))){"비공개 IP 주소는 허용되지 않습니다."}
        else require(IDN.toASCII(host)==host&&host.contains('.')&&host.split('.').all {it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))}){"잘못된 공개 호스트 이름"}
        return URI(uri.toASCIIString().substringBefore('#'))
    }
    fun isPublicAddress(address:InetAddress):Boolean {
        if(address.isAnyLocalAddress||address.isLoopbackAddress||address.isLinkLocalAddress||address.isSiteLocalAddress||address.isMulticastAddress)return false
        val b=address.address.map {it.toInt()and 255}
        if(b.size==4){val a=b[0];val c=b[1]
            return !(a==0||a==10||a==127||a>=224||a==100&&c in 64..127||a==169&&c==254||a==172&&c in 16..31||a==192&&(c==168||c==0||c==2)||a==198&&(c in 18..19||c==51&&b[2]==100)||a==203&&c==0&&b[2]==113)
        }
        // Global unicast only; exclude transition networks and documentation space.
        return b.size==16&&b[0]in 0x20..0x3f&&!(b[0]==0x20&&b[1]==0x02)&&
            !(b[0]==0x20&&b[1]==0x01&&(b[2]==0&&b[3]==0||b[2]==0x0d&&b[3]==0xb8))
    }
}
