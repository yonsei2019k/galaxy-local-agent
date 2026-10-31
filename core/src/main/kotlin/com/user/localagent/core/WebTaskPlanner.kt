package com.user.localagent.core

import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Owner-requested public research only; this does not authorize purchasing or private-data upload. */
sealed interface WebTaskPlan {
    data class WebResearch(val query:String,val shopping:Boolean,val preferredStore:String?=null):WebTaskPlan
    data object NoPlan:WebTaskPlan
}

object WebTaskPlanner {
    const val MAX_WEB_READS=6
    private val stores=linkedMapOf("coupang" to listOf("쿠팡","coupang"),"naver" to listOf("네이버쇼핑","네이버 쇼핑","네이버","naver"),"11st" to listOf("11번가","11st"),"gmarket" to listOf("지마켓","g마켓","gmarket"),"amazon" to listOf("아마존","amazon"))
    private val quotes=Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|“[^”]*”|‘[^’]*’|`[^`]*`")
    private val actions=Regex("검색|찾아|알아봐|조사|비교|추천|살펴|확인|search|find|research|investigate|lookup|compare|recommend")
    private val buying=Regex("최저가|가성비|가격|구매|상품|제품|할인|재고|배송|쇼핑|shopping|price|cheapest|buy")
    private val publicWeb=Regex("인터넷|웹에서|웹검색|온라인|사이트에서|실시간|최신|현재|오늘|지금|뉴스|공식사이트|공식 사이트|web|online|latest|https://")
    private val stop=Regex("(?:검색|조회|조사|찾아|알아보)(?:은|는)?(?:하지마|하지말|보지마|보지말|지마|지말)|인터넷(?:은|는)?(?:쓰지마|사용하지마)|오프라인으로|검색없이|검색하지않고|(?:donot|don't)(?:search|research|investigate|find)|withoutsearch|offline")
    private val explanation=Regex("무슨뜻|작동원리|검색방법|사용법|검색기능설명")
    private val capability=Regex("^(?:너는?|네가|당신은?|이앱은?)?(?:웹|인터넷|쿠팡|쇼핑)?(?:에서|으로)?(?:상품|제품)?(?:을|를)?(?:검색|쇼핑)(?:기능)?(?:을|를|은|는|이|가)?(?:가능해|가능한가|지원해|할수있어|할수있나|할수없어|할줄알아|되나요|되나)[?!?.]*$")
    private val privateDatasets=Regex("일정|스케줄|스케쥴|캘린더|메모(?!리)|기억|문자(?!열|코드)|sms|메시지|메세지|메일|받은편지함|카카오톡|카톡|대화|연락처|통화(?:내역|기록|녹음|내용|목록)|전화(?:내역|기록|번호)|녹음(?!기)|전사문|음성파일|사진|앨범|동영상|파일|앱사용(?:시간|기록|패턴)?|사용기록|사용시간")
    private val ownerScope=Regex("(?:내|나의|우리|개인|사용자|이기기|휴대폰|스마트폰)(?:의|에있는|에저장된|에서|속|안)?(?:일정|스케줄|스케쥴|캘린더|메모|기억|문자|sms|메시지|메세지|메일|대화|연락처|통화|전화|녹음|전사|음성|사진|앨범|영상|동영상|앱사용|사용기록|사용시간)|(?:받은|보낸|저장한|저장된|수신한|발신한)(?:모든|전체|최근)?(?:문자|sms|메시지|메일|사진|영상|메모|녹음|파일)|개인정보")
    private val removeActions=Regex("링크(?:를|도)?|찾아주고|찾아주세요|찾아줘|찾아봐|검색해주고|검색해주세요|검색해줘|검색해봐|비교해주고|비교해주세요|비교해줘|추천해주세요|추천해줘|알아봐줘|알아봐|조사해줘|확인해줘|보여줘|알려줘|해줘요|해주세요|해줘|주세요")
    private val followupWords=Regex(listOf("그대로","그럼","그러면","그거","그걸","이거","이걸","같은","제품","상품","다시","계속","추가로","가성비","저렴한","최저가","가격","비교","추천","검색","찾아","알아봐","링크","주고","해줘","해주세요","해봐","좋은","걸로","으로","에서","더","싼","줘","만","로","도","것","좀","요","에","는","중","응","그래","좋아").sortedByDescending {it.length}.joinToString("|"){Regex.escape(it)})
    private val documentationFollowupWords=Regex(listOf("파일가져오기","파일내보내기","대화내보내기","가져오기","내보내기","그안내","그방법","그방식","그거","그것","상태","말고","아니라","제외하고","빼고","직접","다른","또다른","대체","연결","연동","설정","접근","방법","방식","수단","대안","찾아봐","찾아줘","찾아","알아봐","조사해줘","조사해","조사","검색해줘","검색해","검색","확인해줘","그럼","그러면","그대로","다시","추가로","좀","더","을","를","이","가","은","는","으로","로","해줘","줘","봐","해","wayto","howto","another","alternative","connect","connection","method","research","search","find","directly","direct","please").sortedByDescending {it.length}.joinToString("|"){Regex.escape(it)})

    fun plan(userText:String,previousUserRequests:List<String> = emptyList()):WebTaskPlan {
        val direct=parseOwner(userText)?.let { documentationTopic(it,previousUserRequests) }?:return WebTaskPlan.NoPlan
        val current=resolve(direct)
        if(current is WebTaskPlan.WebResearch)return current
        if(!isFollowup(direct))return WebTaskPlan.NoPlan
        // The latest new topic or document is a barrier, not an invitation to search older history.
        var inherited:WebTaskPlan.WebResearch?=null
        previousUserRequests.takeLast(2).forEach {request->
            val previous=parseOwner(request)
            inherited=if(previous==null)null else {
                val resolved=resolve(previous)
                if(resolved is WebTaskPlan.WebResearch)resolved
                else if(isFollowup(previous))inherited?.copy(preferredStore=preferredStore(previous.authority)?:inherited?.preferredStore)
                else null
            }
        }
        val prior=inherited?:return WebTaskPlan.NoPlan
        val store=preferredStore(direct.authority)?:prior.preferredStore
        return WebTaskPlan.WebResearch(prior.query,prior.shopping||preferredStore(direct.authority)!=null||buying.containsMatchIn(direct.authority),store)
    }

    private data class OwnerText(val text:String,val authority:String)
    private fun parseOwner(raw:String):OwnerText? {
        if(RequestActionSemantics.explicitLocalWriteTool(raw)!=null)return null
        val text=RequestActionSemantics.ownerInput(raw)
        if(text.isBlank())return null
        val authority=text.replace(quotes," ").lowercase(Locale.ROOT).replace(Regex("\\s+"),"")
        if(authority.isBlank()||stop.containsMatchIn(authority)||capability.matches(authority)||RequestActionSemantics.usesPrivateContent(authority))return null
        if(RequestActionSemantics.isConnectionManagement(authority)&&!RequestActionSemantics.hasDocumentation(authority))return null
        // Time words are not permission to send local records to an external search engine.
        // A requested method investigation is public research without a magic "web" keyword.
        val publicDocs=RequestActionSemantics.hasDocumentation(authority)&&actions.containsMatchIn(authority)
        if(privateDatasets.containsMatchIn(authority)){
            if(ownerScope.containsMatchIn(authority))return null
            val publicProduct=preferredStore(authority)!=null&&buying.containsMatchIn(authority)
            if(!publicDocs&&!publicProduct)return null
        }
        if(explanation.containsMatchIn(authority)&&!publicDocs)return null
        return OwnerText(text,authority)
    }
    private fun resolve(owner:OwnerText):WebTaskPlan {
        val publicDocs=RequestActionSemantics.hasDocumentation(owner.authority)
        val store=if(publicDocs)null else preferredStore(owner.authority)
        val shopping=store!=null||buying.containsMatchIn(owner.authority)
        val action=actions.containsMatchIn(owner.authority)
        if(!action||(!shopping&&!publicWeb.containsMatchIn(owner.authority)&&!publicDocs))return WebTaskPlan.NoPlan
        val query=(if(shopping)shoppingQuery(owner.text) else if(publicDocs)documentationQuery(owner) else removeStores(owner.text).replace(removeActions," "))
            .replace(Regex("^(?:인터넷|웹|온라인)(?:에서|으로)?\\s*"),"")
            .replace(Regex("\\s+")," ").trim(' ','.','!','?').take(500)
        // A store/action by itself is a follow-up, not a complete invented product query.
        val target=query.lowercase(Locale.ROOT).replace(Regex("\\s+"),"").replace(followupWords,"")
        if(target.none {it.isLetterOrDigit()}||target in setOf("최신","현재","오늘","지금"))return WebTaskPlan.NoPlan
        return WebTaskPlan.WebResearch(query,shopping,store)
    }
    private fun documentationTopic(owner:OwnerText,previousUserRequests:List<String>):OwnerText {
        if(!RequestActionSemantics.hasDocumentation(owner.authority)||!actions.containsMatchIn(owner.authority)||RequestActionSemantics.publicSubject(owner.authority)!=null)return owner
        if(owner.authority.replace(documentationFollowupWords,"").any {it.isLetterOrDigit()})return owner
        // Inherit only a public product name, never the previous request's personal content.
        for(raw in previousUserRequests.takeLast(2).asReversed()){
            val authority=RequestActionSemantics.ownerText(raw)
            if(authority.isBlank())break
            val subject=RequestActionSemantics.publicSubject(authority)
            if(subject!=null){
                if(RequestActionSemantics.excludesSubject(owner.authority,subject))break
                return owner.copy(text="$subject ${owner.text}",authority=subject.lowercase(Locale.ROOT).replace(" ","")+owner.authority)
            }
            if(!RequestActionSemantics.hasDocumentation(authority)&&parseOwner(raw)?.let(::isFollowup)!=true)break
        }
        return owner
    }
    private fun documentationQuery(owner:OwnerText):String {
        val subject=RequestActionSemantics.publicSubject(owner.authority)
        // Rejected suggestions and quoted source data do not become search terms.
        var query=owner.text.replace(quotes," ").substringAfterLast("말고").trim()
        if(subject!=null&&!query.lowercase(Locale.ROOT).replace(" ","").contains(subject.lowercase(Locale.ROOT).replace(" ","")))query="$subject $query"
        return query.replace(removeActions," ")
    }
    private fun shoppingQuery(text:String):String {
        var query=removeStores(text)
        // A second requested comparison is not part of the first product's search keywords.
        Regex("찾아주고|검색해주고|비교해주고|추천해주고|알아봐주고|그리고|다른\\s*(?:상품|제품)|대체\\s*상품").find(query)?.range?.first?.let {query=query.substring(0,it)}
        query=query.replace(Regex("(?:제품\\s*)?링크\\s*(?:하나|한\\s*개|1개)?")," ")
            .replace(Regex("(?:찾아줄|검색해줄|비교해줄)\\s*수\\s*(?:있어|있나요|있니|있을까|있나)")," ")
            .replace(removeActions," ")
            .replace(Regex("최저\\s*가격|최저가|가성비(?:가)?|가격\\s*비교|가격(?:이|을|은|도)?|재고\\s*확인|할인\\s*정보")," ")
            .replace(Regex("^(?:네가|니가|알아서|직접|온라인에서|인터넷에서)\\s*"),"")
            .replace(Regex("\\s+")," ").trim(' ','.','!','?')
        return query.replace(Regex("(?:을|를)$"),"").trim()
    }
    private fun preferredStore(authority:String)=stores.entries.firstOrNull {(_,names)->names.any {authority.contains(it.replace(" ",""))}}?.key
    private fun removeStores(text:String):String {
        var result=text
        stores.values.flatten().sortedByDescending {it.length}.forEach {name->result=result.replace(Regex(Regex.escape(name)+"(?:에서는|에서만|에선|에서|으로)?",RegexOption.IGNORE_CASE)," ")}
        return result
    }
    private fun isFollowup(owner:OwnerText):Boolean {
        if(owner.authority.length>100)return false
        val remaining=removeStores(owner.authority).replace(followupWords,"").replace(Regex("[\\s!?.,]"),"")
        return remaining.isEmpty()&&owner.authority.isNotEmpty()
    }
}

/** Values must come from an actually fetched product page, not a snippet or model guess. */
data class PriceOfferEvidence(
    val sourceUrl:String,
    val amount:BigDecimal?,
    val currency:String?,
    val checkedAt:Instant?,
    val directProductPage:Boolean=false,
    val sameProductAndOptions:Boolean=false,
    val priceIncludesShipping:Boolean=false,
    val inStock:Boolean?=null
)
enum class PriceVerificationIssue { SOURCE_INVALID, PRICE_MISSING_OR_INVALID, CURRENCY_UNKNOWN, CHECK_TIME_INVALID, NOT_PRODUCT_PAGE, OPTIONS_UNCONFIRMED, SHIPPING_UNKNOWN, STOCK_UNCONFIRMED }
data class PriceComparisonResult(val verifiedOffers:List<PriceOfferEvidence>,val lowestComparedOffer:PriceOfferEvidence?) {
    /** A bounded comparison cannot establish the lowest price across the entire internet. */
    val canClaimGlobalLowest:Boolean get()=false
}
object PriceEvidenceValidator {
    fun issues(offer:PriceOfferEvidence,now:Instant,maxAge:Duration=Duration.ofHours(24)):Set<PriceVerificationIssue> {
        require(!maxAge.isZero&&!maxAge.isNegative)
        val issues=linkedSetOf<PriceVerificationIssue>()
        val source=runCatching {URI(offer.sourceUrl)}.getOrNull()
        if(source==null||source.scheme!="https"||source.host.isNullOrBlank()||source.userInfo!=null)issues+=PriceVerificationIssue.SOURCE_INVALID
        if(offer.amount==null||offer.amount<=BigDecimal.ZERO||offer.amount.precision()>18)issues+=PriceVerificationIssue.PRICE_MISSING_OR_INVALID
        if(offer.currency !in setOf("KRW","USD","EUR","JPY","GBP","CNY"))issues+=PriceVerificationIssue.CURRENCY_UNKNOWN
        if(offer.checkedAt==null||offer.checkedAt.isAfter(now.plusSeconds(300))||offer.checkedAt.isBefore(now.minus(maxAge)))issues+=PriceVerificationIssue.CHECK_TIME_INVALID
        if(!offer.directProductPage)issues+=PriceVerificationIssue.NOT_PRODUCT_PAGE
        if(!offer.sameProductAndOptions)issues+=PriceVerificationIssue.OPTIONS_UNCONFIRMED
        if(!offer.priceIncludesShipping)issues+=PriceVerificationIssue.SHIPPING_UNKNOWN
        if(offer.inStock!=true)issues+=PriceVerificationIssue.STOCK_UNCONFIRMED
        return issues
    }
    fun compare(offers:List<PriceOfferEvidence>,now:Instant,maxAge:Duration=Duration.ofHours(24)):PriceComparisonResult {
        val verified=offers.filter {issues(it,now,maxAge).isEmpty()}.distinctBy {it.sourceUrl}
        val lowest=if(verified.size>=2&&verified.map {it.currency}.distinct().size==1)verified.minByOrNull {it.amount!!} else null
        return PriceComparisonResult(verified,lowest)
    }
}
