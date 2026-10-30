package com.user.localagent

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.user.localagent.automation.*
import com.user.localagent.core.ScheduleCandidate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/** Injected providers and in-memory markers only. No real calendar or personal SMS is accessed. */
@RunWith(AndroidJUnit4::class)
class CalendarGatewayValidation {
    @Test fun approvedLocalTimeWritesRealProviderIdAndRepeatedSaveDeduplicates()=runBlocking {
        val provider=FakeProvider();val links=FakeLinks();val gateway=CalendarGateway(provider,links)
        val first=gateway.saveApproved(listOf(candidate("first")),7)
        assertTrue(first.failures.isEmpty())
        assertEquals(1,first.events.size)
        val saved=first.events.single()
        assertEquals(101L,saved.eventId)
        assertEquals("content://com.android.calendar/events/101",saved.eventUri)
        assertFalse(saved.alreadyPresent)
        assertEquals(LocalDateTime.parse("2027-05-12T09:30:00").atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(),provider.drafts.single().startMillis)
        assertEquals("Asia/Seoul",provider.drafts.single().timezone)
        assertFalse(provider.drafts.single().description.contains("synthetic private excerpt"))
        assertFalse(provider.drafts.single().description.contains("qa-sms"))
        val repeated=gateway.saveApproved(listOf(candidate("re-extracted-id")),7)
        assertTrue(repeated.events.single().alreadyPresent)
        assertEquals(saved.eventId,repeated.events.single().eventId)
        assertEquals(1,provider.drafts.size)
    }

    @Test fun providerMarkerRecoversAnInsertWhoseLocalLinkWasLost()=runBlocking {
        val provider=FakeProvider();val links=FakeLinks();val gateway=CalendarGateway(provider,links)
        val original=gateway.saveApproved(listOf(candidate("original")),7).events.single()
        links.values.clear()
        val recovered=gateway.saveApproved(listOf(candidate("repeated")),7).events.single()
        assertTrue(recovered.alreadyPresent)
        assertEquals(original.eventId,recovered.eventId)
        assertEquals(1,provider.drafts.size)
        assertEquals(original.eventId,links.values.values.single().eventId)
    }

    @Test fun lostLocalLinkStillDeduplicatesAnEventEditedInTheCalendar()=runBlocking {
        val provider=FakeProvider();val links=FakeLinks();val gateway=CalendarGateway(provider,links)
        val original=gateway.saveApproved(listOf(candidate("original")),7).events.single()
        links.values.clear()
        val event=provider.events.getValue(original.eventId)
        provider.events[original.eventId]=event.copy(title="Synthetic revised meeting",startMillis=event.startMillis+3_600_000,endMillis=event.endMillis+3_600_000)
        val report=gateway.saveApproved(listOf(candidate("repeated")),7)
        assertTrue(report.failures.isEmpty())
        val recovered=report.events.single()
        assertTrue(recovered.alreadyPresent)
        assertEquals(original.eventId,recovered.eventId)
        assertFalse(recovered.warning.isNullOrBlank())
        assertEquals(1,provider.drafts.size)
        assertEquals(original.eventId,links.values.values.single().eventId)
        assertEquals("Synthetic revised meeting",provider.events.getValue(original.eventId).title)
    }

    @Test fun multipleSourceMarkerMatchesStopWithoutAnotherInsert()=runBlocking {
        val provider=FakeProvider();val links=FakeLinks();val gateway=CalendarGateway(provider,links)
        val original=gateway.saveApproved(listOf(candidate("original")),7).events.single()
        links.values.clear()
        provider.events[202L]=provider.events.getValue(original.eventId).copy(id=202,title="Synthetic duplicate")
        provider.descriptions[202L]=provider.descriptions.getValue(original.eventId)
        val report=gateway.saveApproved(listOf(candidate("repeated")),7)
        assertTrue(report.events.isEmpty())
        assertEquals(1,report.failures.size)
        assertEquals(1,provider.drafts.size)
        assertTrue(links.values.isEmpty())
    }

    @Test fun deletedLinkedEventMayBeCreatedAgainOnlyAfterAnotherExplicitSave()=runBlocking {
        val provider=FakeProvider();val links=FakeLinks();val gateway=CalendarGateway(provider,links)
        val original=gateway.saveApproved(listOf(candidate("original")),7).events.single()
        provider.events.remove(original.eventId)
        val recreated=gateway.saveApproved(listOf(candidate("repeat")),7).events.single()
        assertFalse(recreated.alreadyPresent)
        assertNotEquals(original.eventId,recreated.eventId)
        assertEquals(2,provider.drafts.size)
    }

    @Test fun ambiguousDatesOrUnreviewedCandidatesDoNotWrite()=runBlocking {
        val provider=FakeProvider();val links=FakeLinks();val gateway=CalendarGateway(provider,links)
        val report=gateway.saveApproved(listOf(candidate("review").copy(needsReview=true),
            candidate("missing").copy(startIso=null),candidate("reversed").copy(endIso="2027-05-12T08:00:00+09:00")),7)
        assertEquals(3,report.failures.size)
        assertTrue(report.events.isEmpty())
        assertTrue(provider.drafts.isEmpty())
        assertTrue(links.values.isEmpty())
    }

    @Test fun permissionsAndWritableDestinationAreRequiredAndGoogleIsOnlyAPreference()=runBlocking {
        val provider=FakeProvider();val gateway=CalendarGateway(provider,FakeLinks())
        assertEquals(7L,gateway.listWritableCalendars().first().id)
        provider.writeAllowed=false
        var denied=false
        try {gateway.saveApproved(listOf(candidate("denied")),7)}catch(error:CalendarPermissionException){denied=true;assertTrue(Manifest.permission.WRITE_CALENDAR in error.requiredPermissions)}
        assertTrue(denied)
        assertTrue(provider.drafts.isEmpty())
        provider.writeAllowed=true;provider.availableCalendars=emptyList()
        var unavailable=false
        try {gateway.saveApproved(listOf(candidate("none")),7)}catch(_:IllegalStateException){unavailable=true}
        assertTrue(unavailable)
        assertTrue(provider.drafts.isEmpty())
    }

    @Test fun permissionRevocationStopsRemainingWritesAndKeepsCompletedIds()=runBlocking {
        val provider=FakeProvider();val gateway=CalendarGateway(provider,FakeLinks())
        provider.afterInsert={provider.writeAllowed=false}
        val second=candidate("second").copy(sourceId="qa-sms:20",bodySha256="c".repeat(64))
        val report=gateway.saveApproved(listOf(candidate("first"),second),7)
        assertEquals(1,report.events.size)
        assertEquals(1,report.failures.size)
        assertEquals("second",report.failures.single().candidateId)
        assertEquals(1,provider.drafts.size)
    }

    @Test fun cancellationAfterACommitRecordsItsIdAndDoesNotStartNextInsert()=runBlocking {
        val provider=FakeProvider();val links=FakeLinks();val gateway=CalendarGateway(provider,links)
        lateinit var work:Job
        provider.afterInsert={work.cancel()}
        work=launch(start=CoroutineStart.LAZY){gateway.saveApproved(listOf(candidate("first"),candidate("second").copy(sourceId="qa-sms:20")),7)}
        work.start();work.join()
        assertTrue(work.isCancelled)
        assertEquals(1,provider.drafts.size)
        assertEquals(101L,links.values.values.single().eventId)
    }

    private fun candidate(id:String)=ScheduleCandidate(id=id,sourceId="qa-sms:10",sourceTimestampMillis=1_800_000_000_000,
        bodySha256="b".repeat(64),title="Synthetic team meeting",startIso="2027-05-12T09:30:00",endIso="2027-05-12T10:30:00+09:00",
        needsReview=false,reviewReason=null,excerpt="synthetic private excerpt")
    private class FakeLinks:CalendarLinkStore {
        val values=linkedMapOf<String,CalendarEventLink>()
        override suspend fun read(key:String)=values[key]
        override suspend fun write(link:CalendarEventLink){values[link.key]=link}
    }
    private class FakeProvider:CalendarProvider {
        var readAllowed=true;var writeAllowed=true
        var availableCalendars=listOf(WritableCalendar(3,"Local","local","LOCAL",true),WritableCalendar(7,"Primary","calendar@example.invalid","com.google",true))
        val drafts=mutableListOf<CalendarEventDraft>()
        val events=linkedMapOf<Long,ProviderCalendarEvent>()
        val descriptions=mutableMapOf<Long,String>()
        var afterInsert:(()->Unit)?=null
        override fun hasPermission(permission:String)=when(permission){Manifest.permission.READ_CALENDAR->readAllowed;Manifest.permission.WRITE_CALENDAR->writeAllowed;else->false}
        override fun calendars()=availableCalendars
        override fun event(calendarId:Long,eventId:Long)=events[eventId]?.takeIf {it.calendarId==calendarId}
        override fun findByMarker(calendarId:Long,marker:String)=events.values.filter {it.calendarId==calendarId&&descriptions[it.id]?.contains(marker)==true}
        override fun insert(draft:CalendarEventDraft):Long {
            check(writeAllowed)
            drafts+=draft;val id=100L+drafts.size
            events[id]=ProviderCalendarEvent(id,draft.calendarId,draft.title,draft.startMillis,draft.endMillis)
            descriptions[id]=draft.description
            afterInsert?.invoke()
            return id
        }
    }
}
