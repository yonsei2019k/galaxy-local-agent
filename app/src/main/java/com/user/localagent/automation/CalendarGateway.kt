package com.user.localagent.automation

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.user.localagent.core.ScheduleCandidate
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

data class WritableCalendar(val id:Long,val displayName:String,val accountName:String,val accountType:String,val isPrimary:Boolean) {
    val isGoogle:Boolean get()=accountType.equals("com.google",true)
}
data class SavedCalendarEvent(val candidateId:String,val eventId:Long,val eventUri:String,val alreadyPresent:Boolean,val warning:String?=null)
data class CalendarSaveFailure(val candidateId:String,val reason:String)
data class CalendarSaveReport(val events:List<SavedCalendarEvent>,val failures:List<CalendarSaveFailure>,val calendar:WritableCalendar)
data class CalendarEventDraft(val calendarId:Long,val title:String,val startMillis:Long,val endMillis:Long,val description:String,val timezone:String="Asia/Seoul")
data class ProviderCalendarEvent(val id:Long,val calendarId:Long,val title:String,val startMillis:Long,val endMillis:Long)
data class CalendarEventLink(val key:String,val calendarId:Long,val eventId:Long?)

class CalendarPermissionException(val requiredPermissions:List<String>):IllegalStateException(
    "Android 캘린더 ${if(Manifest.permission.WRITE_CALENDAR in requiredPermissions)"읽기·쓰기"else"읽기"} 권한이 필요합니다.")

/** Injection boundary: production uses CalendarContract; synthetic tests never touch real calendars. */
interface CalendarProvider {
    fun hasPermission(permission:String):Boolean
    fun calendars():List<WritableCalendar>
    fun event(calendarId:Long,eventId:Long):ProviderCalendarEvent?
    fun findByMarker(calendarId:Long,marker:String):List<ProviderCalendarEvent>
    fun insert(draft:CalendarEventDraft):Long
}
interface CalendarLinkStore {
    suspend fun read(key:String):CalendarEventLink?
    suspend fun write(link:CalendarEventLink)
}

/** Writes only the candidates the host UI has reviewed and approved for the chosen destination. */
class CalendarGateway(private val provider:CalendarProvider,private val links:CalendarLinkStore) {
    constructor(context:Context,repository:LocalRepository,provider:CalendarProvider=AndroidCalendarProvider(context)):
        this(provider,EncryptedCalendarLinks(repository))

    suspend fun listWritableCalendars():List<WritableCalendar> = withContext(Dispatchers.IO) {
        requirePermissions(Manifest.permission.READ_CALENDAR)
        provider.calendars().filter {it.id>0}.distinctBy {it.id}.sortedWith(
            compareByDescending<WritableCalendar> {it.isGoogle}.thenByDescending {it.isPrimary}
                .thenBy {it.displayName.lowercase(Locale.ROOT)}.thenBy {it.id})
    }

    suspend fun saveApproved(candidates:List<ScheduleCandidate>,calendarId:Long):CalendarSaveReport = withContext(Dispatchers.IO) {
        WRITE_LOCK.withLock {
            requirePermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
            require(candidates.isNotEmpty()&&candidates.size<=1000){"저장할 검토 완료 일정을 1~1,000개 선택해주세요."}
            require(candidates.map {it.id}.distinct().size==candidates.size){"같은 일정 후보를 중복 선택했습니다."}
            val calendar=listWritableCalendars().firstOrNull {it.id==calendarId}
                ?:error("선택한 캘린더에 쓸 수 없습니다. Google 계정 동기화 또는 쓸 수 있는 캘린더를 확인해주세요.")
            val saved=mutableListOf<SavedCalendarEvent>()
            val failures=mutableListOf<CalendarSaveFailure>()
            for((index,candidate)in candidates.withIndex()){
                currentCoroutineContext().ensureActive()
                val missing=listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR).filterNot(provider::hasPermission)
                if(missing.isNotEmpty()){
                    candidates.drop(index).forEach {failures+=CalendarSaveFailure(it.id,"캘린더 권한이 해제되어 남은 저장을 중단했습니다.")}
                    break
                }
                try {saved+=saveOne(candidate,calendar.id)}
                catch(cancel:CancellationException){throw cancel}
                catch(error:Exception){failures+=CalendarSaveFailure(candidate.id,error.message?.take(400)?:"저장 완료를 확인하지 못했습니다. 다시 시도하면 중복 여부부터 확인합니다.")}
            }
            CalendarSaveReport(saved,failures,calendar)
        }
    }

    private suspend fun saveOne(candidate:ScheduleCandidate,calendarId:Long):SavedCalendarEvent {
        require(!candidate.needsReview){"날짜·시간 또는 내용을 먼저 검토한 뒤 저장해주세요."}
        require(candidate.id.isNotBlank()&&candidate.id.length<=100&&candidate.sourceId.isNotBlank()&&candidate.sourceId.length<=300)
        require(candidate.sourceTimestampMillis>=0&&HASH.matches(candidate.bodySha256.lowercase(Locale.ROOT))){"일정 출처 정보가 유효하지 않습니다."}
        val title=candidate.title.trim().replace(Regex("\\s+")," ")
        require(title.isNotBlank()&&title.length<=300&&'\u0000' !in title){"일정 제목을 확인해주세요."}
        val start=parseTime(candidate.startIso?:error("시작 날짜와 시각을 확인해주세요."))
        val end=parseTime(candidate.endIso?:error("종료 날짜와 시각을 확인해주세요."))
        require(end>start){"종료 시각은 시작 시각보다 늦어야 합니다."}
        val key=sourceKey(candidate,calendarId,title,start,end)
        val marker="ZipHap.Agent source:$key"
        links.read(key)?.takeIf {it.key==key&&it.calendarId==calendarId}?.eventId?.let {eventId->
            if(provider.event(calendarId,eventId)!=null)return saved(candidate.id,eventId,true)
        }
        currentCoroutineContext().ensureActive()
        // Recover an insert completed just before a crash/cancellation/marker-storage failure.
        val matches=provider.findByMarker(calendarId,marker).filter {it.calendarId==calendarId}
        require(matches.size<=1){"같은 출처로 연결된 일정이 여러 개입니다. 캘린더에서 먼저 확인해주세요."}
        matches.singleOrNull()?.let {event->
            links.write(CalendarEventLink(key,calendarId,event.id))
            val changed=event.title!=title||event.startMillis!=start||event.endMillis!=end
            return saved(candidate.id,event.id,true,if(changed)
                "같은 출처의 일정이 캘린더에서 수정되어 있습니다. 기존 일정을 유지하고 다시 만들지 않았습니다." else null)
        }
        // Persist an encrypted intent before the external side effect. It never triggers an automatic retry.
        links.write(CalendarEventLink(key,calendarId,null))
        currentCoroutineContext().ensureActive()
        val owner=currentCoroutineContext().job
        return withContext(NonCancellable) {
            // Finish recording a committed provider ID if cancellation arrives during insert.
            if(!owner.isActive)throw CancellationException("일정 저장을 취소했습니다.")
            val id=provider.insert(CalendarEventDraft(calendarId,title,start,end,
                "ZipHap.Agent에서 검토하고 승인한 일정\n$marker",TIMEZONE.id))
            require(id>0){"캘린더가 저장된 일정 ID를 반환하지 않았습니다."}
            var warning:String?=null
            try {links.write(CalendarEventLink(key,calendarId,id))}
            catch(_:Exception){warning="일정은 저장했지만 기기 내 연결 기록 저장을 확인하지 못했습니다. 다음 저장에서 캘린더 출처 표시로 중복을 확인합니다."}
            require(provider.event(calendarId,id)!=null){"캘린더 저장 응답은 받았지만 일정 조회로 확인하지 못했습니다. 다시 시도하면 중복 여부부터 확인합니다."}
            saved(candidate.id,id,false,warning)
        }
    }
    private fun saved(candidateId:String,eventId:Long,alreadyPresent:Boolean,warning:String?=null)=SavedCalendarEvent(candidateId,eventId,
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,eventId).toString(),alreadyPresent,warning)
    private fun requirePermissions(vararg permissions:String){
        val missing=permissions.filterNot(provider::hasPermission)
        if(missing.isNotEmpty())throw CalendarPermissionException(missing)
    }
    private fun parseTime(raw:String):Long {
        require(raw.length<=100&&raw.isNotBlank())
        return try {OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli()}
        catch(_:java.time.format.DateTimeParseException){LocalDateTime.parse(raw.trim()).atZone(TIMEZONE).toInstant().toEpochMilli()}
    }
    private fun sourceKey(candidate:ScheduleCandidate,calendarId:Long,title:String,start:Long,end:Long):String {
        val data=buildJsonObject {put("sourceId",candidate.sourceId);put("sourceTimestamp",candidate.sourceTimestampMillis)
            put("bodySha256",candidate.bodySha256.lowercase(Locale.ROOT));put("calendarId",calendarId)
            put("start",start);put("end",end);put("title",title)}.toString()
        return MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    companion object {
        private val WRITE_LOCK=Mutex()
        private val TIMEZONE=ZoneId.of("Asia/Seoul")
        private val HASH=Regex("[a-f0-9]{64}")
    }
}

/** Minimal dedupe markers remain in the existing encrypted Room database, never plain preferences. */
class EncryptedCalendarLinks(private val repository:LocalRepository):CalendarLinkStore {
    override suspend fun read(key:String):CalendarEventLink? {
        require(KEY.matches(key))
        val note=repository.getNote(noteId(key))?:return null
        require(note.collection==COLLECTION){"일정 연결 기록 범위가 일치하지 않습니다."}
        val data=Json.parseToJsonElement(note.body) as? JsonObject?:error("일정 연결 기록이 손상되었습니다.")
        require(data["type"]?.jsonPrimitive?.content==TYPE&&data["key"]?.jsonPrimitive?.content==key)
        val calendarId=data["calendarId"]?.jsonPrimitive?.longOrNull?:error("일정 연결 캘린더 ID 오류")
        val eventId=data["eventId"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.longOrNull
        require(calendarId>0&&(eventId==null||eventId>0))
        return CalendarEventLink(key,calendarId,eventId)
    }
    override suspend fun write(link:CalendarEventLink){
        require(KEY.matches(link.key)&&link.calendarId>0&&(link.eventId==null||link.eventId>0))
        val data=buildJsonObject {put("type",TYPE);put("key",link.key);put("calendarId",link.calendarId)
            put("eventId",link.eventId?.let(::JsonPrimitive)?:JsonNull)}
        repository.saveNote("일정 연결 기록",data.toString(),COLLECTION,noteId(link.key))
    }
    private fun noteId(key:String)="calendar-event-link:$key"
    companion object {
        const val COLLECTION="calendar-event-links"
        private const val TYPE="ziphap-calendar-link-v1"
        private val KEY=Regex("[a-f0-9]{64}")
    }
}

class AndroidCalendarProvider(context:Context):CalendarProvider {
    private val context=context.applicationContext
    override fun hasPermission(permission:String)=ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED
    override fun calendars():List<WritableCalendar> {
        val projection=arrayOf(CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,CalendarContract.Calendars.ACCOUNT_TYPE,CalendarContract.Calendars.IS_PRIMARY)
        val result=mutableListOf<WritableCalendar>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,projection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),null)?.use {cursor->
            while(cursor.moveToNext()){
                val id=cursor.getLong(0)
                if(id>0)result+=WritableCalendar(id,cursor.getString(1).orEmpty().take(300),cursor.getString(2).orEmpty().take(320),cursor.getString(3).orEmpty().take(200),!cursor.isNull(4)&&cursor.getInt(4)!=0)
                require(result.size<=1000){"캘린더가 너무 많습니다. 사용할 계정과 캘린더를 먼저 정리해주세요."}
            }
        }?:error("Android 캘린더 목록을 읽을 수 없습니다.")
        return result
    }
    override fun event(calendarId:Long,eventId:Long):ProviderCalendarEvent? {
        require(calendarId>0&&eventId>0)
        return query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,eventId),
            "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DELETED}=0",arrayOf(calendarId.toString()),1).singleOrNull()
    }
    override fun findByMarker(calendarId:Long,marker:String):List<ProviderCalendarEvent> {
        require(calendarId>0&&marker.matches(Regex("ZipHap\\.Agent source:[a-f0-9]{64}")))
        return query(CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DELETED}=0 AND ${CalendarContract.Events.DESCRIPTION} LIKE ?",
            arrayOf(calendarId.toString(),"%$marker%"),5)
    }
    override fun insert(draft:CalendarEventDraft):Long {
        val values=ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID,draft.calendarId);put(CalendarContract.Events.TITLE,draft.title)
            put(CalendarContract.Events.DTSTART,draft.startMillis);put(CalendarContract.Events.DTEND,draft.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE,draft.timezone);put(CalendarContract.Events.ALL_DAY,0)
            put(CalendarContract.Events.DESCRIPTION,draft.description)
        }
        val uri=context.contentResolver.insert(CalendarContract.Events.CONTENT_URI,values)?:error("캘린더가 저장 응답을 반환하지 않았습니다.")
        require(uri.scheme=="content"&&uri.authority==CalendarContract.AUTHORITY){"캘린더 저장 응답 주소가 유효하지 않습니다."}
        return ContentUris.parseId(uri).also {require(it>0)}
    }
    private fun query(uri:Uri,selection:String,args:Array<String>,limit:Int):List<ProviderCalendarEvent> {
        val projection=arrayOf(CalendarContract.Events._ID,CalendarContract.Events.CALENDAR_ID,CalendarContract.Events.TITLE,CalendarContract.Events.DTSTART,CalendarContract.Events.DTEND)
        val result=mutableListOf<ProviderCalendarEvent>()
        context.contentResolver.query(uri,projection,selection,args,null)?.use {cursor->
            while(result.size<limit&&cursor.moveToNext()){
                if(!cursor.isNull(0)&&!cursor.isNull(1)&&!cursor.isNull(3)&&!cursor.isNull(4))result+=ProviderCalendarEvent(
                    cursor.getLong(0),cursor.getLong(1),cursor.getString(2).orEmpty(),cursor.getLong(3),cursor.getLong(4))
            }
        }?:error("저장된 캘린더 일정을 확인할 수 없습니다.")
        return result
    }
}
