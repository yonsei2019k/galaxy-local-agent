package com.user.localagent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.user.localagent.AppContainer
import com.user.localagent.web.PublicWebSafety
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Explicit public research only; user opens seller links and performs account/purchase actions. */
@Composable fun WebResearchPanel(app:AppContainer,onStatus:(String)->Unit) {
    val context=LocalContext.current
    val state by app.webResearch.state.collectAsState()
    var query by rememberSaveable{mutableStateOf(state.query)}
    var shopping by rememberSaveable{mutableStateOf(state.shopping)}
    var store by rememberSaveable{mutableStateOf(state.store)}
    val formatter=remember{DateTimeFormatter.ofPattern("MM.dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))}
    fun time(value:String?)=value?.let {runCatching {formatter.format(Instant.parse(it))}.getOrDefault(it)}.orEmpty()
    fun open(url:String){
        try {PublicWebSafety.requireHttps(url);context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        catch(_:Exception){onStatus("확인된 HTTPS 링크를 열지 못했습니다. 브라우저 앱을 확인해주세요.")}
    }
    fun searchUrl():String {
        val encoded=URLEncoder.encode(query.trim(),StandardCharsets.UTF_8.name())
        return when(store){"coupang"->"https://www.coupang.com/np/search?q=$encoded";"naver"->"https://search.shopping.naver.com/search/all?query=$encoded";else->"https://www.google.com/search?q=$encoded"}
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("웹 조사와 쇼핑",style=MaterialTheme.typography.titleLarge)
            Text("입력한 검색어로 공개 자료를 확인합니다. 저장된 문자·기억이나 계정 정보는 검색에 자동으로 보내지 않습니다.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=query,onValueChange={if(it.length<=500)query=it},label={Text("무엇을 찾을까요?")},placeholder={Text("예: 샤오미 공기청정기")},modifier=Modifier.fillMaxWidth(),minLines=1,maxLines=4,enabled=!state.running)
            Row {Switch(checked=shopping,onCheckedChange={shopping=it;if(!it)store=null},enabled=!state.running);Text(if(shopping)"상품·가격 자료"else"일반 웹 자료",modifier=Modifier.padding(start=8.dp,top=12.dp))}
            if(shopping)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(null to "전체", "coupang" to "쿠팡", "naver" to "네이버").forEach {(value,label)->FilterChip(selected=store==value,onClick={store=value},enabled=!state.running,label={Text(label)})}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(enabled=!state.running&&query.isNotBlank(),onClick={if(!app.webResearch.startSearch(query.trim(),shopping,store))onStatus("검색을 시작하지 못했습니다. 진행 중인 검색을 확인해주세요.")}){Text("실제 공개 자료 검색")}
                if(state.running)TextButton(onClick={app.webResearch.cancel()}){Text("중단")}
            }
            if(state.running){LinearProgressIndicator(progress={if(state.totalSteps>0)(state.progress.toFloat()/state.totalSteps).coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth())}
            Text(state.status,style=MaterialTheme.typography.bodyMedium)
            state.sourceFetchedAt?.let {Text("자료 확인 ${time(it)}",style=MaterialTheme.typography.labelSmall)}
            state.error?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
        }}}
        if(state.results.isNotEmpty())item {Text("확인한 자료 ${state.results.size}개 · ${state.query}",style=MaterialTheme.typography.titleMedium)}
        itemsIndexed(state.results,key={index,item->"$index:${item.url}"}) {index,item->ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
            if(item.section=="preferred")Text("요청 판매처 링크",style=MaterialTheme.typography.labelMedium)
            if(item.section=="comparison")Text("비교 참고 가격 · 다른 공개 출처",style=MaterialTheme.typography.labelMedium)
            Text("${index+1}. ${item.title}",style=MaterialTheme.typography.titleMedium)
            if(item.price!=null&&item.currency!=null)Text("${item.price} ${item.currency}",style=MaterialTheme.typography.headlineSmall)
            else Text("가격: 판매처에서 확인",style=MaterialTheme.typography.bodyMedium)
            if(item.snippet.isNotBlank())Text(item.snippet,style=MaterialTheme.typography.bodySmall,maxLines=6)
            item.availability?.let {availability->Text("페이지 재고 표기: ${when{availability.endsWith("InStock")->"재고 있음";availability.endsWith("OutOfStock")->"품절";availability.endsWith("PreOrder")->"예약 판매";else->availability}}",style=MaterialTheme.typography.bodySmall)}
            item.shipping?.let {Text("배송 표기: $it",style=MaterialTheme.typography.bodySmall)}
            item.merchant?.let {Text("판매처 자료: $it",style=MaterialTheme.typography.bodySmall)}
            item.priceSource?.let {Text("가격 근거: $it",style=MaterialTheme.typography.labelSmall)}
            Text("${item.source} · 확인 ${time(item.priceFetchedAt?:item.fetchedAt)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("링크: ${runCatching {java.net.URI(item.url).host}.getOrDefault("")}",style=MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick={open(item.url)}){Text(if(shopping)"상품·판매처 링크 열기"else"출처 페이지 열기")}
        }}}
        item {ZipHapCard {Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text("판매처에서 최종 조건 확인",style=MaterialTheme.typography.titleMedium)
            Text("표시 가격은 읽은 페이지·옵션의 자료입니다. 쿠폰, 회원가, 배송비와 재고에 따라 달라질 수 있으며 전체 시장 최저가로 단정하지 않습니다. 로그인·장바구니·구매·결제는 자동 수행하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled=query.isNotBlank(),onClick={open(searchUrl())}){Text(if(shopping)"판매처 검색 화면 직접 열기"else"브라우저에서 검색 열기")}
            if(state.partial)Text("일부 페이지는 자동으로 읽지 못했습니다. 확인된 자료와 링크만 표시합니다.",style=MaterialTheme.typography.bodySmall)
        }}}
    }
}
