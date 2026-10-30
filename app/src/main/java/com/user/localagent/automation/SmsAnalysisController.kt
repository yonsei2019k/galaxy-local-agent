package com.user.localagent.automation

import android.Manifest
import android.content.Context
import android.provider.Telephony
import com.user.localagent.core.ScheduleCandidate
import com.user.localagent.core.ScheduleExtraction
import com.user.localagent.data.LocalRepository
import com.user.localagent.imports.AndroidPhoneRecordSource
import com.user.localagent.imports.PhoneRecordSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class SmsAnalysisState(val running:Boolean=false,val status:String="저장된 일정 후보를 확인합니다",val scannedCount:Int=0,val totalCount:Int=0,
    val candidates:List<ScheduleCandidate> = emptyList(),val firstAtMillis:Long?=null,val lastAtMillis:Long?=null,val truncated:Boolean=false,val error:String?=null,
    val incomingCount:Int=0,val outgoingCount:Int=0,val categories:Map<String,Int> = emptyMap(),val bytesRead:Long=0,val year:Int?=null)
data class SmsAnalysisResult(val scannedCount:Int,val totalCount:Int,val firstAtMillis:Long?,val lastAtMillis:Long?,val truncated:Boolean,
    val candidates:List<ScheduleCandidate>,val incomingCount:Int,val outgoingCount:Int,val categories:Map<String,Int>,val bytesRead:Long,val year:Int?,val status:String,val error:String?=null)

/** Full bounded provider traversal, deterministic statistics/extraction; never sends SMS or writes calendar. */
class SmsAnalysisController(context:Context,private val repository:LocalRepository,private val source:PhoneRecordSource=AndroidPhoneRecordSource(context)) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val busy=AtomicBoolean(false)
    private val revision=AtomicLong(0)
    @Volatile private var activeJob:Job?=null
    private val mutableState=MutableStateFlow(SmsAnalysisState())
    val state:StateFlow<SmsAnalysisState> = mutableState.asStateFlow()
    private val json=Json {ignoreUnknownKeys=true}
    init {scope.launch {
        val generation=revision.get()
        try {val saved=storedCandidates();if(revision.get()==generation&&!busy.get())mutableState.update {it.copy(candidates=saved,status="저장된 일정 후보 ${saved.size}개")}}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){if(revision.get()==generation&&!busy.get())mutableState.update {it.copy(status="저장된 후보를 불러오지 못했습니다.")}}
    }}
    fun startScan(year:Int?=null):Boolean {
        if(year!=null&&year !in 1900..2200)return false
        if(!busy.compareAndSet(false,true))return false
        revision.incrementAndGet();mutableState.value=SmsAnalysisState(running=true,status="문자 제공자 연결 중",year=year)
        scope.launch(start=CoroutineStart.UNDISPATCHED) {try{runReserved(year,{_,_->})}catch(_:CancellationException){}catch(_:Exception){}}
        return true
    }
    suspend fun scan(year:Int?=null,onProgress:(Int,Int)->Unit={_,_->}):SmsAnalysisResult {
        currentCoroutineContext().ensureActive()
        require(year==null||year in 1900..2200)
        check(busy.compareAndSet(false,true)){"이미 문자 분석이 진행 중입니다."}
        revision.incrementAndGet();mutableState.value=SmsAnalysisState(running=true,status="문자 제공자 연결 중",year=year)
        return runReserved(year,onProgress)
    }
    fun cancel(){activeJob?.cancel()}
    suspend fun remove(id:String):Boolean {
        require(Regex("[a-f0-9]{64}").matches(id));check(busy.compareAndSet(false,true)){"분석이 끝난 뒤 후보를 삭제해주세요."};revision.incrementAndGet()
        return try {val note=repository.getNote("sms-schedule-$id");if(note!=null){check(note.collection==COLLECTION);repository.deleteNote(note.id);check(repository.getNote(note.id)==null){"후보 삭제를 확인하지 못했습니다."}};val remaining=storedCandidates().map {it.id}.toSet();mutableState.update {it.copy(candidates=it.candidates.filter {candidate->candidate.id in remaining},status="선택한 일정 후보를 삭제했습니다.")};note!=null}
        finally {busy.set(false)}
    }
    suspend fun clearCandidates():Int {
        check(busy.compareAndSet(false,true)){"분석이 끝난 뒤 후보를 삭제해주세요."};revision.incrementAndGet()
        return try {val notes=repository.notes.first().filter {it.collection==COLLECTION};notes.forEach {currentCoroutineContext().ensureActive();repository.deleteNote(it.id)};check(repository.notes.first().none {it.collection==COLLECTION}){"후보 전체 삭제를 확인하지 못했습니다."};mutableState.value=SmsAnalysisState(status="일정 후보 ${notes.size}개 삭제 완료");notes.size}
        finally {busy.set(false)}
    }
    private suspend fun runReserved(year:Int?,onProgress:(Int,Int)->Unit):SmsAnalysisResult {
        activeJob=currentCoroutineContext()[Job]
        try{return readReserved(year,onProgress)}
        catch(cancel:CancellationException){mutableState.update {it.copy(running=false,status="분석 중단 · 지금까지 저장한 후보는 유지합니다.")};throw cancel}
        finally{activeJob=null;mutableState.update {it.copy(running=false)};busy.set(false)}
    }
    private suspend fun readReserved(year:Int?,onProgress:(Int,Int)->Unit):SmsAnalysisResult=withContext(Dispatchers.IO){
        var scanned=0;var total=0;var incoming=0;var outgoing=0;var bytes=0L;var first:Long?=null;var last:Long?=null;var partial=false
        val categories=linkedMapOf("예약" to 0,"인증" to 0,"금융" to 0,"배송" to 0,"광고" to 0,"기타" to 0)
        val candidates=mutableListOf<ScheduleCandidate>();val foundIds=mutableSetOf<String>();var newlySaved=0
        fun update(status:String){mutableState.value=SmsAnalysisState(true,status,scanned,total,candidates.toList(),first,last,partial,null,incoming,outgoing,categories.toMap(),bytes,year);onProgress(scanned,total)}
        try {
            check(source.hasPermission(Manifest.permission.READ_SMS)){"Android 문자 읽기 권한이 필요합니다. 자료 연결 화면에서 문자 권한을 확인해주세요."}
            val known=storedCandidates().associateBy {it.id}
            val beginning=year?.let {LocalDate.of(it,1,1).atStartOfDay(ScheduleExtraction.zone).toInstant().toEpochMilli()}
            val ending=year?.let {LocalDate.of(it+1,1,1).atStartOfDay(ScheduleExtraction.zone).toInstant().toEpochMilli()}
            val selection=if(year!=null)"${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?"else null
            val args=if(year!=null)arrayOf(beginning.toString(),ending.toString())else null
            val cursor=source.query(Telephony.Sms.CONTENT_URI,COLUMNS,selection,args,"${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC")?:error("문자 제공자가 조회 결과를 반환하지 않았습니다.")
            cursor.use {c->
                total=c.count
                val idColumn=c.getColumnIndexOrThrow(Telephony.Sms._ID);val dateColumn=c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeColumn=c.getColumnIndexOrThrow(Telephony.Sms.TYPE);val bodyColumn=c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                update("문자 ${total}개를 실제로 읽는 중")
                while(c.moveToNext()){
                    currentCoroutineContext().ensureActive()
                    if(scanned>=MAX_ROWS){partial=true;break}
                    if(scanned%64==0)check(source.hasPermission(Manifest.permission.READ_SMS)){"분석 중 문자 읽기 권한이 해제됐습니다."}
                    val body=if(c.isNull(bodyColumn))""else c.getString(bodyColumn)
                    val bodyBytes=body.toByteArray(Charsets.UTF_8)
                    if(bytes+bodyBytes.size>MAX_BYTES||body.length>200_000){partial=true;break}
                    val at=if(c.isNull(dateColumn))null else c.getLong(dateColumn)
                    val sourceId=if(c.isNull(idColumn))""else c.getLong(idColumn).toString()
                    val type=if(c.isNull(typeColumn))0 else c.getInt(typeColumn)
                    bytes+=bodyBytes.size;scanned++
                    if(type==Telephony.Sms.MESSAGE_TYPE_INBOX)incoming++
                    if(type==Telephony.Sms.MESSAGE_TYPE_SENT)outgoing++
                    if(at!=null){first=first?.let {minOf(it,at)}?:at;last=last?.let {maxOf(it,at)}?:at}
                    val category=category(body);categories[category]=categories.getValue(category)+1
                    // Unsent drafts/outbox records are counted but do not become appointments.
                    if(at!=null&&sourceId.isNotBlank()&&type in setOf(Telephony.Sms.MESSAGE_TYPE_INBOX,Telephony.Sms.MESSAGE_TYPE_SENT)){
                        val extracted=ScheduleExtraction.extract(body,at)
                        val hash=if(extracted.isNotEmpty())sha(bodyBytes)else ""
                        for(event in extracted){
                            if(candidates.size>=MAX_CANDIDATES){partial=true;break}
                            val candidateId=sha("$sourceId|$at|$hash|${event.startIso}|${event.endIso}|${event.title}".toByteArray(Charsets.UTF_8))
                            val candidate=ScheduleCandidate(candidateId,sourceId,at,hash,event.title,event.startIso,event.endIso,event.needsReview,event.reviewReason,clip(body,1000))
                            if(foundIds.add(candidateId)){
                                if(candidateId !in known){
                                    currentCoroutineContext().ensureActive()
                                    val noteId="sms-schedule-$candidateId";val payload=json.encodeToString(candidate)
                                    repository.saveNote("문자 일정 후보",payload,COLLECTION,noteId)
                                    val confirmed=repository.getNote(noteId)
                                    check(confirmed?.id==noteId&&confirmed.collection==COLLECTION&&confirmed.body==payload){"일정 후보를 저장한 뒤 다시 확인하지 못했습니다."}
                                    newlySaved++
                                }
                                candidates+=candidate
                            }
                        }
                    }
                    if(scanned%50==0)update("${total}개 중 ${scanned}개 읽음 · 일정 후보 ${candidates.size}개")
                }
            }
            partial=partial||scanned<total
            val status="SMS ${total}개 중 ${scanned}개 본문 확인 · 일정 후보 ${candidates.size}개 · 새 후보 ${newlySaved}개 저장"+if(partial)" · 상한으로 일부만 분석"else " · 조회 범위 전체 분석"
            mutableState.value=SmsAnalysisState(false,status,scanned,total,candidates.toList(),first,last,partial,null,incoming,outgoing,categories.toMap(),bytes,year)
            SmsAnalysisResult(scanned,total,first,last,partial,candidates.toList(),incoming,outgoing,categories.toMap(),bytes,year,status)
        }catch(cancel:CancellationException){mutableState.update {it.copy(running=false,status="분석 중단 · 지금까지 저장한 후보는 유지합니다.")};throw cancel}
        catch(error:Exception){
            val message=when(error){is SecurityException->"문자 읽기 권한을 사용할 수 없습니다.";else->error.message?.take(300)?:"문자 분석을 완료하지 못했습니다."}
            mutableState.value=SmsAnalysisState(false,message,scanned,total,candidates.toList(),first,last,true,message,incoming,outgoing,categories.toMap(),bytes,year)
            SmsAnalysisResult(scanned,total,first,last,true,candidates.toList(),incoming,outgoing,categories.toMap(),bytes,year,message,message)
        }
    }
    private suspend fun storedCandidates():List<ScheduleCandidate> = repository.notes.first().asSequence().filter {it.collection==COLLECTION}.take(MAX_CANDIDATES)
        .mapNotNull {runCatching {json.decodeFromString<ScheduleCandidate>(it.body)}.getOrNull()}.toList()
    private fun category(body:String):String=when {
        body.contains("(광고)")||body.contains("[광고]")->"광고"
        AUTH.containsMatchIn(body)->"인증"
        FINANCE.containsMatchIn(body)->"금융"
        SHIPPING.containsMatchIn(body)->"배송"
        APPOINTMENT.containsMatchIn(body)->"예약"
        else->"기타"
    }
    private fun clip(text:String,limit:Int):String {var end=minOf(text.length,limit);if(end<text.length&&end>0&&text[end-1].isHighSurrogate()&&text[end].isLowSurrogate())end--;return text.substring(0,end)}
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    companion object {const val COLLECTION="sms-schedule-candidates";const val MAX_ROWS=100_000;const val MAX_BYTES=50L*1024*1024;const val MAX_CANDIDATES=10_000
        private val AUTH=Regex("인증|일회용|OTP|본인확인|보안코드",RegexOption.IGNORE_CASE)
        private val FINANCE=Regex("입금|출금|결제|카드|은행|이체|대출|청구|송금")
        private val SHIPPING=Regex("배송|택배|운송|배달")
        private val APPOINTMENT=Regex("예약|약속|회의|미팅|방문|진료|검진|수업|공연|출발|면접|접종")
        private val COLUMNS=arrayOf(Telephony.Sms._ID,Telephony.Sms.DATE,Telephony.Sms.TYPE,Telephony.Sms.BODY)}
}
