package com.user.localagent

import android.content.Context
import android.provider.CalendarContract
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.automation.*
import com.user.localagent.core.ScheduleCandidate
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Explicitly gated, reversible provider test. Never reads or edits existing calendar events. */
@RunWith(AndroidJUnit4::class)
class CalendarRoundTripValidation {
    @Test fun approvedSyntheticEventIsReadBackDeduplicatedAndRemoved()=runBlocking {
        assumeTrue("Explicit calendarRoundTripApproved=true is required",
            InstrumentationRegistry.getArguments().getString("calendarRoundTripApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=LocalRepository(context)
        val actual=AndroidCalendarProvider(context)
        val token=UUID.randomUUID().toString()
        val title="ZipHap.Agent 연결 확인 · QA-$token"
        val start=probeStart()
        val journal=File(context.filesDir,"calendar-probe-pending/$token.json")
        val inserted=mutableListOf<Pair<Long,CalendarEventDraft>>()
        val attemptedDrafts=mutableListOf<CalendarEventDraft>()
        val tracking=object:CalendarProvider {
            override fun hasPermission(permission:String)=actual.hasPermission(permission)
            override fun calendars()=actual.calendars()
            override fun event(calendarId:Long,eventId:Long)=actual.event(calendarId,eventId)
            override fun findByMarker(calendarId:Long,marker:String)=actual.findByMarker(calendarId,marker)
            override fun insert(draft:CalendarEventDraft):Long {
                check(attemptedDrafts.isEmpty()){"Only one synthetic insertion is permitted"}
                attemptedDrafts+=draft
                writeJournal(journal,draft,null)
                return actual.insert(draft).also {id->inserted+=id to draft;writeJournal(journal,draft,id)}
            }
        }
        val gateway=CalendarGateway(tracking,EncryptedCalendarLinks(repository))
        var google=false;var readback=false;var dedupe=false
        var providerClean=false;var markersClean=false
        val cleanupErrors=mutableListOf<String>()
        var failure:Throwable?=null
        try {
            val calendar=gateway.listWritableCalendars().let {available->available.firstOrNull {it.isGoogle}?:available.firstOrNull()}
                ?:error("A writable calendar is required for this explicitly approved test")
            google=calendar.isGoogle
            val bodyHash=MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString(""){"%02x".format(it.toInt() and 255)}
            val candidate=ScheduleCandidate("qa-$token","qa-synthetic:$token",0,bodyHash,title,start.toString(),start.plusHours(1).toString(),false,null,"")
            val first=gateway.saveApproved(listOf(candidate),calendar.id)
            assertTrue("Synthetic insert must not report failures",first.failures.isEmpty())
            val saved=first.events.single()
            assertFalse(saved.alreadyPresent)
            assertEquals(1,inserted.size)
            assertEquals(inserted.single().first,saved.eventId)
            val event=actual.event(calendar.id,saved.eventId)
            readback=event!=null&&event.title==title&&event.startMillis==start.toInstant().toEpochMilli()&&
                event.endMillis==start.plusHours(1).toInstant().toEpochMilli()&&event.calendarId==calendar.id
            assertTrue("Synthetic event ID and date must be read back",readback)
            val repeated=gateway.saveApproved(listOf(candidate),calendar.id)
            dedupe=repeated.failures.isEmpty()&&repeated.events.size==1&&repeated.events.single().alreadyPresent&&
                repeated.events.single().eventId==saved.eventId&&inserted.size==1
            assertTrue("Repeated approval must not insert a second event",dedupe)
        }catch(error:Throwable){failure=error}
        finally {
            withContext(NonCancellable+Dispatchers.IO) {
                providerClean=true;markersClean=true
                for((id,draft)in inserted){
                    try {
                        // All predicates are our own invocation's values; never delete by time range or account.
                        check(draft.title==title)
                        deleteExactProbe(context,id,draft)
                        if(actual.event(draft.calendarId,id)!=null)providerClean=false
                    }catch(error:Throwable){providerClean=false;cleanupErrors+=error.javaClass.simpleName}
                }
                for(draft in attemptedDrafts){
                    try {
                        val key=Regex("(?m)^ZipHap\\.Agent source:([a-f0-9]{64})$").find(draft.description)?.groupValues?.get(1)
                            ?:error("Synthetic source marker missing")
                        val noteId="calendar-event-link:$key"
                        val note=repository.getNote(noteId)
                        if(note!=null){
                            check(note.collection==EncryptedCalendarLinks.COLLECTION)
                            val data=Json.parseToJsonElement(note.body).jsonObject
                            check(data["key"]?.jsonPrimitive?.content==key&&data["calendarId"]?.jsonPrimitive?.longOrNull==draft.calendarId)
                            val linkedId=data["eventId"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.longOrNull
                            check(linkedId==null||inserted.any {it.first==linkedId&&it.second.calendarId==draft.calendarId})
                            repository.deleteNote(noteId)
                        }
                        if(repository.getNote(noteId)!=null)markersClean=false
                    }catch(error:Throwable){markersClean=false;cleanupErrors+=error.javaClass.simpleName}
                }
                if(providerClean&&markersClean)journal.delete()
                val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks")
                check(directory.isDirectory||directory.mkdirs())
                // Intentionally excludes account names, titles, source bodies, calendar/event IDs and errors.
                File(directory,"calendar-roundtrip.json").writeText(buildJsonObject {
                    put("createdCount",inserted.size);put("googleCalendar",google);put("readbackVerified",readback)
                    put("dedupeVerified",dedupe);put("providerCleanupVerified",providerClean)
                    put("localMarkerCleanupVerified",markersClean);put("cleanupVerified",providerClean&&markersClean)
                    put("cleanupErrorClasses",buildJsonArray {cleanupErrors.distinct().forEach {add(it)}})
                }.toString(),Charsets.UTF_8)
            }
        }
        failure?.let {throw it}
        assertTrue("Only this invocation's synthetic event must be removed",providerClean)
        assertTrue("Only this invocation's encrypted link must be removed",markersClean)
    }

    @Test fun cleanupOwnSyntheticCalendarProbe()=runBlocking(Dispatchers.IO) {
        assumeTrue("Explicit calendarRoundTripApproved=true is required",
            InstrumentationRegistry.getArguments().getString("calendarRoundTripApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=LocalRepository(context)
        val actual=AndroidCalendarProvider(context)
        val errors=mutableListOf<String>()
        var matched=0;var deleted=0;var remaining=0
        val start=probeStart().toInstant().toEpochMilli();val end=start+3_600_000
        val columns=arrayOf(CalendarContract.Events._ID,CalendarContract.Events.CALENDAR_ID,CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,CalendarContract.Events.DTSTART,CalendarContract.Events.DTEND)
        try {
            context.contentResolver.query(CalendarContract.Events.CONTENT_URI,columns,
                "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.DTEND}=? AND ${CalendarContract.Events.DELETED}=0",
                arrayOf("$TITLE_PREFIX%",start.toString(),end.toString()),null)?.use {cursor->
                while(cursor.moveToNext()){
                    val id=cursor.getLong(0);val calendar=cursor.getLong(1);val title=cursor.getString(2).orEmpty()
                    val description=cursor.getString(3).orEmpty()
                    if(id<=0||calendar<=0||!isOwnProbe(title,description,calendar,start,end))continue
                    matched++
                    val draft=CalendarEventDraft(calendar,title,start,end,description)
                    try {
                        deleteExactProbe(context,id,draft)
                        check(actual.event(calendar,id)==null){"Synthetic event remains after delete"}
                        deleted++
                        val key=markerKey(description)?:error("Synthetic marker missing")
                        val noteId="calendar-event-link:$key"
                        repository.getNote(noteId)?.let {note->
                            check(note.collection==EncryptedCalendarLinks.COLLECTION)
                            val data=Json.parseToJsonElement(note.body).jsonObject
                            check(data["key"]?.jsonPrimitive?.content==key&&data["calendarId"]?.jsonPrimitive?.longOrNull==calendar)
                            val linkedId=data["eventId"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.longOrNull
                            check(linkedId==null||linkedId==id)
                            repository.deleteNote(noteId)
                            check(repository.getNote(noteId)==null)
                        }
                        if(actual.event(calendar,id)==null)File(context.filesDir,"calendar-probe-pending/${title.removePrefix(TITLE_PREFIX)}.json").delete()
                    }catch(error:Throwable){errors+=error.javaClass.simpleName}
                    finally {
                        try {if(actual.event(calendar,id)!=null)remaining++}
                        catch(error:Throwable){errors+=error.javaClass.simpleName;remaining++}
                    }
                }
            }?:error("Probe cursor unavailable")
        }catch(error:Throwable){errors+=error.javaClass.simpleName}
        val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks")
        check(directory.isDirectory||directory.mkdirs())
        File(directory,"calendar-probe-cleanup.json").writeText(buildJsonObject {
            put("matchedCount",matched);put("deletedCount",deleted);put("remainingCount",remaining)
            put("errorClasses",buildJsonArray {errors.distinct().forEach {add(it)}})
        }.toString(),Charsets.UTF_8)
        assertTrue("Synthetic probe cleanup must have no errors",errors.isEmpty())
        assertEquals("All matched synthetic probes must be absent",0,remaining)
    }

    private fun probeStart()=LocalDateTime.of(2030,1,1,9,0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime()
    private fun markerKey(description:String)=Regex("(?m)^ZipHap\\.Agent source:([a-f0-9]{64})$").find(description)?.groupValues?.get(1)
    private fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun isOwnProbe(title:String,description:String,calendarId:Long,start:Long,end:Long):Boolean {
        if(!title.startsWith(TITLE_PREFIX))return false
        val token=title.removePrefix(TITLE_PREFIX)
        if(!UUID_PATTERN.matches(token))return false
        val key=markerKey(description)?:return false
        if(description!="ZipHap.Agent에서 검토하고 승인한 일정\nZipHap.Agent source:$key")return false
        val expected=sha(buildJsonObject {
            put("sourceId","qa-synthetic:$token");put("sourceTimestamp",0L);put("bodySha256",sha(token))
            put("calendarId",calendarId);put("start",start);put("end",end);put("title",title)
        }.toString())
        return key==expected
    }
    private fun deleteExactProbe(context:Context,id:Long,draft:CalendarEventDraft) {
        check(id>0&&draft.calendarId>0&&isOwnProbe(draft.title,draft.description,draft.calendarId,draft.startMillis,draft.endMillis))
        context.contentResolver.delete(CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.DESCRIPTION}=? AND ${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.DTEND}=?",
            arrayOf(id.toString(),draft.calendarId.toString(),draft.title,draft.description,draft.startMillis.toString(),draft.endMillis.toString()))
    }
    private fun writeJournal(file:File,draft:CalendarEventDraft,id:Long?) {
        check(file.parentFile?.let {it.isDirectory||it.mkdirs()}==true)
        val atomic=AtomicFile(file)
        val output=atomic.startWrite()
        try {
            output.write(buildJsonObject {
                put("calendarId",draft.calendarId);put("eventId",id?.let(::JsonPrimitive)?:JsonNull);put("title",draft.title)
                put("start",draft.startMillis);put("end",draft.endMillis);put("description",draft.description)
            }.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        }catch(error:Throwable){atomic.failWrite(output);throw error}
    }
    companion object {
        private const val TITLE_PREFIX="ZipHap.Agent 연결 확인 · QA-"
        private val UUID_PATTERN=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }
}
