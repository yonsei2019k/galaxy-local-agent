package com.user.localagent.web

import android.content.Context
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Serializable data class WebResearchItem(val title:String,val url:String,val snippet:String,val source:String,val fetchedAt:String,
    val price:String?=null,val currency:String?=null,val availability:String?=null,val shipping:String?=null,val validThrough:String?=null,val offerUrl:String?=null,val priceFetchedAt:String?=null,val priceSource:String?=null,val merchant:String?=null,val section:String="public")
@Serializable data class WebResearchResult(val query:String,val shopping:Boolean,val store:String?,val fetchedAt:String,val items:List<WebResearchItem>,
    val errors:List<String>,val partial:Boolean,val noteId:String?=null) {
    val results:List<WebResearchItem> get()=items
    val success:Boolean get()=items.isNotEmpty()&&(!shopping||store==null||items.any {it.section=="preferred"})
    val error:String? get()=if(items.isEmpty())errors.firstOrNull()?:"읽을 수 있는 공개 검색 결과가 없습니다."else if(!success)"요청 판매처 링크는 확인하지 못했습니다. 다른 출처의 자료는 아래에서 확인할 수 있습니다."else null
    fun toolResult():String=toToolJson()
    fun toToolJson():String {
        val rows=mutableListOf<JsonElement>();var trimmed=partial
        items.take(5).forEach {item->
            val row=buildJsonObject {put("title",item.title);put("url",item.url);put("snippet",item.snippet.take(300));put("source",item.source);put("section",item.section);put("fetchedAt",item.fetchedAt);put("price",item.price);put("currency",item.currency);put("priceSource",item.priceSource);put("priceFetchedAt",item.priceFetchedAt);put("offerUrl",item.offerUrl);put("merchant",item.merchant);put("availability",item.availability);put("shipping",item.shipping)}
            if(rows.sumOf {it.toString().length}+row.toString().length<=9000)rows+=row else trimmed=true
        }
        return buildJsonObject {put("success",success);put("query",query);put("shopping",shopping);put("store",store);put("fetchedAt",fetchedAt);put("partial",trimmed);put("items",JsonArray(rows));put("errors",buildJsonArray {errors.take(4).forEach {add(it.take(200))}});put("untrustedSourceData",true);put("privateSourcesRead",false);put("purchaseExecuted",false)}.toString()
    }
    fun humanSummary():String=buildString {
        appendLine("공개 웹 검색: $query");appendLine("확인 시각: $fetchedAt · 자료 ${items.size}개${if(partial)" · 일부 페이지 확인 제한"else""}")
        if(!success&&items.isNotEmpty())appendLine(error)
        var section=""
        items.forEachIndexed {index,item->
            val itemSection=if(shopping)item.section else "public"
            if(itemSection!=section){section=itemSection;appendLine(when(section){"preferred"->"요청 판매처 링크";"comparison"->"비교 참고 가격 · 다른 공개 출처";else->"확인한 공개 자료"})}
            appendLine("${index+1}. ${item.title}\n${item.url}")
            if(shopping){
                if(item.price!=null&&item.currency!=null){appendLine("${item.source} 표시 가격: ${item.price} ${item.currency} · ${item.priceFetchedAt?:item.fetchedAt}");item.priceSource?.let {appendLine("가격 출처: $it")}}else appendLine("가격: 판매처에서 확인")
                item.availability?.let {appendLine("재고 페이지 표기: $it")}
            }
            if(item.snippet.isNotBlank())appendLine(item.snippet.take(300))
        }
        if(items.isEmpty())appendLine(if(shopping)"자동으로 읽을 수 있는 검색 자료를 받지 못했습니다. 공개 검색이나 판매처 검색 화면을 직접 열어 확인할 수 있습니다."else"자동으로 읽을 수 있는 검색 자료를 받지 못했습니다. 공개 검색 화면을 직접 열어 확인할 수 있습니다.")
        if(shopping)appendLine("가격은 해당 페이지·옵션의 표시이며 전체 시장 최저가나 할인·배송비 포함 가격을 확정하지 않습니다. 구매·결제는 수행하지 않았습니다.")
        if(errors.isNotEmpty())appendLine("확인 제한: ${errors.take(3).joinToString(" / ")}")
    }.take(7000)
}
data class WebResearchState(val running:Boolean=false,val status:String="공개 웹에서 자료를 찾아보세요",val query:String="",val shopping:Boolean=true,val store:String?=null,
    val progress:Int=0,val totalSteps:Int=6,val results:List<WebResearchItem> = emptyList(),val sourceFetchedAt:String?=null,val partial:Boolean=false,val error:String?=null)

/** Explicit public-query research. Never reads SMS, memories, accounts, cookies or private files. */
class WebResearchController(@Suppress("UNUSED_PARAMETER") context:Context,private val repository:LocalRepository,private val client:PublicWebClient=PublicWebClient()) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val busy=AtomicBoolean(false)
    @Volatile private var job:Job?=null
    private val mutableState=MutableStateFlow(WebResearchState())
    val state:StateFlow<WebResearchState> = mutableState.asStateFlow()
    fun startSearch(query:String,shopping:Boolean=true,store:String?=null):Boolean {
        if(query.isBlank()||query.length>500||!busy.compareAndSet(false,true))return false
        mutableState.value=WebResearchState(true,"공개 검색에 연결 중",query,shopping,store)
        scope.launch(start=CoroutineStart.UNDISPATCHED){try{runReserved(query,shopping,store)}catch(_:CancellationException){}catch(_:Exception){}}
        return true
    }
    suspend fun research(query:String,shopping:Boolean=true,store:String?=null):WebResearchResult {
        currentCoroutineContext().ensureActive();require(query.isNotBlank()&&query.length<=500&&'\u0000' !in query)
        check(busy.compareAndSet(false,true)){"웹 검색이 이미 진행 중입니다."}
        mutableState.value=WebResearchState(true,"공개 검색에 연결 중",query,shopping,store)
        return runReserved(query,shopping,store)
    }
    fun cancel(){job?.cancel()}
    private suspend fun runReserved(query:String,shopping:Boolean,store:String?):WebResearchResult {
        job=currentCoroutineContext()[Job]
        try{return withTimeout(120_000){withContext(Dispatchers.IO){perform(query.trim(),shopping,canonicalStore(store))}}}
        catch(_:TimeoutCancellationException){
            val partial=persist(WebResearchResult(query,shopping,canonicalStore(store),Instant.now().toString(),mutableState.value.results,listOf("확인 시간 안에 읽은 자료만 유지했습니다. 일부 페이지는 시간 제한으로 확인하지 못했습니다."),true))
            mutableState.update {it.copy(status="시간 내 공개 자료 ${partial.items.size}개 확인",results=partial.items,partial=true,error=partial.error,sourceFetchedAt=partial.fetchedAt)}
            return partial
        }
        catch(cancel:CancellationException){mutableState.update {it.copy(status="검색을 중단했습니다.",partial=true,error=null)};throw cancel}
        catch(error:Exception){
            val result=WebResearchResult(query,shopping,store,Instant.now().toString(),emptyList(),listOf("공개 검색 연결을 완료하지 못했습니다."),true)
            mutableState.update {it.copy(status=result.errors.first(),partial=true,error=result.errors.first())};return result
        }
        finally{job=null;mutableState.update {it.copy(running=false)};busy.set(false)}
    }
    private suspend fun perform(query:String,shopping:Boolean,store:String?):WebResearchResult {
        val searchQuery=when(store){"coupang"->"$query site:coupang.com";"naver"->"$query (site:shopping.naver.com OR site:smartstore.naver.com)";"11st"->"$query site:11st.co.kr";"gmarket"->"$query site:gmarket.co.kr";"amazon"->"$query site:amazon.com";else->query}
        var report=withTimeout(45_000){if(shopping)client.searchShopping(query,store=store,limit=5)else client.search(searchQuery,limit=5)}
        var usedRequests=(report.requestCount.takeIf {it>0}?:if(shopping)3 else 2).coerceAtMost(6)
        val preferredUrls=if(shopping&&store!=null)report.items.take(2).map {it.url}.toSet()else emptySet()
        var comparisonUrls=emptySet<String>()
        if(shopping&&store!=null&&6-usedRequests>=3){
            mutableState.update {it.copy(results=report.items.take(2).map {source->WebResearchItem(source.title,source.url,source.snippet,source.source,source.fetchedAt,section="preferred")},status="요청 판매처 링크와 공개 비교 가격 확인 중")}
            try {
                val catalog=withTimeout(35_000){client.searchShopping(query,store=null,limit=3)}
                usedRequests=(usedRequests+catalog.requestCount.coerceAtLeast(1)).coerceAtMost(6)
                comparisonUrls=catalog.items.map {it.url}.toSet()-preferredUrls
                report=report.copy(items=(report.items.take(2)+catalog.items.take(3)).distinctBy {it.url}.take(5),errors=(report.errors+catalog.errors).distinct(),partial=report.partial||catalog.partial)
            }catch(_:TimeoutCancellationException){usedRequests=(usedRequests+3).coerceAtMost(6);report=report.copy(partial=true,errors=report.errors+"비교 가격 자료 확인 시간이 초과됐습니다.")}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){usedRequests=(usedRequests+3).coerceAtMost(6);report=report.copy(partial=true,errors=report.errors+"비교 가격 자료를 읽지 못해 확인된 판매처 링크를 유지했습니다.")}
        }
        val errors=report.errors.take(6).map {it.take(250)}.toMutableList()
        val items=mutableListOf<WebResearchItem>()
        val sources=report.items.filter {publicHttps(it.url)}.distinctBy {it.url}.take(5)
        val detailBudget=(6-usedRequests).coerceAtLeast(0)
        mutableState.update {it.copy(progress=usedRequests,totalSteps=6,status="검색 자료 ${sources.size}개 확인 중",sourceFetchedAt=report.fetchedAt)}
        var partial=report.partial
        var details=0
        sources.forEach {source->
            currentCoroutineContext().ensureActive()
            var item=WebResearchItem(source.title.take(300),source.url,source.snippet.take(800),source.source.take(100),source.fetchedAt,merchant=source.merchant,section=when(source.url){in preferredUrls->"preferred";in comparisonUrls->"comparison";else->"public"})
            source.offer?.takeIf {shopping&&it.price!=null&&it.currency!=null&&publicHttps(it.sourceUrl)&&!expired(it.validThrough)}?.let {offer->
                item=item.copy(price=offer.price,currency=offer.currency,availability=offer.availability,shipping=offer.shipping,validThrough=offer.validThrough,offerUrl=offer.sourceUrl,priceFetchedAt=offer.fetchedAt,priceSource=offer.priceSource)
            }
            // Count both search sources before allocating detail fetches; the complete
            // operation makes at most six public transport requests.
            if(item.price!=null||details>=detailBudget){if(shopping&&item.price==null)partial=true;items+=item;mutableState.update {it.copy(results=items.toList(),status="공개 자료 ${items.size}개 확인",partial=partial)};return@forEach}
            try {
                details++
                val page=withTimeout(20_000){client.fetchPage(source.url)}
                if(!publicHttps(page.url)){partial=true;return@forEach}
                item=item.copy(title=if(shopping)item.title else page.title.take(300).ifBlank{item.title},url=page.url,fetchedAt=page.fetchedAt,
                    snippet=item.snippet.ifBlank{page.text.take(800)})
                // Multiple variants/offers do not establish one price for the search title.
                val offer=page.offers.singleOrNull()?.takeIf {shopping&&it.price!=null&&it.currency!=null&&publicHttps(it.sourceUrl)&&!expired(it.validThrough)&&
                    !it.priceSource.contains("AggregateOffer")&&sameProductIdentity(source.url,page.url,source.title,page.title)}
                if(offer!=null)item=item.copy(price=offer.price,currency=offer.currency,availability=offer.availability,
                    shipping=offer.shipping,validThrough=offer.validThrough,offerUrl=offer.sourceUrl,priceFetchedAt=offer.fetchedAt,priceSource=offer.priceSource)
            }catch(_:TimeoutCancellationException){partial=true;errors+="${URI(source.url).host}: 페이지 확인 시간 초과"}
            catch(cancel:CancellationException){throw cancel}
            catch(_:UnsafePublicWebUrlException){partial=true;return@forEach}
            catch(_:Exception){partial=true;errors+="${URI(source.url).host}: ${if(shopping)"페이지 가격·본문"else"페이지 본문"} 확인 제한"}
            items+=item
            mutableState.update {it.copy(progress=usedRequests+details,results=items.toList(),status="공개 자료 ${items.size}개 확인",partial=partial)}
        }
        val finished=Instant.now().toString()
        val result=persist(WebResearchResult(query,shopping,store,finished,items.toList(),errors.take(8),partial||items.isEmpty()))
        mutableState.update {it.copy(status="공개 자료 ${result.items.size}개 확인 완료",progress=usedRequests+details,totalSteps=usedRequests+details,results=result.items,sourceFetchedAt=finished,partial=result.partial,error=result.error)}
        return result
    }
    private suspend fun persist(input:WebResearchResult):WebResearchResult {
        val id=UUID.randomUUID().toString()
        var result=input.copy(noteId=id)
        var payload=Json.encodeToString(result)
        while(payload.length>12_000&&result.items.size>1){result=result.copy(items=result.items.dropLast(1),partial=true);payload=Json.encodeToString(result)}
        try {
            require(payload.length<=12_000)
            repository.saveNote("공개 웹 조사",payload,COLLECTION,id)
            val saved=repository.getNote(id)
            check(saved!=null&&saved.collection==COLLECTION&&saved.body==payload)
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){result=result.copy(noteId=null,errors=(result.errors+"검색 자료는 확인했으나 로컬 저장을 확인하지 못했습니다.").take(8),partial=true)}
        return result
    }
    private fun expired(value:String?):Boolean {
        if(value.isNullOrBlank())return false
        val end=runCatching {Instant.parse(value)}.getOrElse {runCatching {LocalDate.parse(value.take(10)).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()}.getOrNull()}
        return end?.isBefore(Instant.now())==true
    }
    private fun sameProductIdentity(originalUrl:String,finalUrl:String,originalTitle:String,finalTitle:String):Boolean {
        fun normalized(text:String)=text.lowercase().filter {it.isLetterOrDigit()}
        val original=URI(originalUrl);val target=URI(finalUrl)
        if(original.host.removePrefix("www.")!=target.host.removePrefix("www.")||original.path!=target.path)return false
        val first=normalized(originalTitle);val second=normalized(finalTitle)
        return first.length>=8&&second.length>=8&&(first.contains(second)||second.contains(first))
    }
    private fun canonicalStore(value:String?):String?=when(value?.lowercase()?.trim()){"coupang","쿠팡"->"coupang";"naver","네이버"->"naver";"11st","11번가"->"11st";"gmarket","지마켓"->"gmarket";"amazon","아마존"->"amazon";else->null}
    private fun publicHttps(url:String):Boolean=runCatching {val uri=URI(url);val host=uri.host?.lowercase().orEmpty();uri.scheme=="https"&&uri.userInfo==null&&uri.port in setOf(-1,443)&&host.contains('.')&&host!="localhost"&&!host.endsWith(".local")&&!host.contains(':')&&!Regex("[0-9.]+").matches(host)}.getOrDefault(false)
    companion object {const val COLLECTION="web-research"}
}
