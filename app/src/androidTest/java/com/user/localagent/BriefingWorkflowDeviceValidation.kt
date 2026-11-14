package com.user.localagent

import android.content.ContentUris
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.briefing.*
import com.user.localagent.data.DraftStore
import com.user.localagent.automation.AndroidCalendarProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BriefingWorkflowDeviceValidation {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun notificationReviewActionOpensActualPhoneReport() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        val scenario=androidx.test.core.app.ActivityScenario.launch(BriefingReviewActivity::class.java)
        try {
            val app=(context.applicationContext as LocalAgentApplication).container
            val real=BriefingAgenda(context,app).items()
            BriefingNotifications.show(context,real.count {it["status"]?.jsonPrimitive?.content=="registered"},real.count {it["status"]?.jsonPrimitive?.content=="needs_review"})
            val manager=context.getSystemService(android.app.NotificationManager::class.java)
            var posted:android.service.notification.StatusBarNotification?=null
            repeat(30){if(posted==null){Thread.sleep(100);posted=manager.activeNotifications.firstOrNull {it.id==2301}}}
            val notification=checkNotNull(posted){"Posted result notification did not appear"}
            val action=notification.notification.actions.single {it.title.toString()=="확인·수정"}
            assertEquals(context.packageName,action.actionIntent.creatorPackage)
            assertEquals(notification.notification.contentIntent,action.actionIntent)
            // Foreground sender avoids conflating system-shade accessibility with this intent contract.
            action.actionIntent.send()
            var resumed=false
            repeat(30){if(!resumed){Thread.sleep(100);instrumentation.runOnMainSync {
                resumed=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any {it is BriefingReviewActivity&&it.window.decorView.isShown}
            }}}
            assertTrue("Real notification action must resume the phone review Activity",resumed)
            // Keep the genuine current-result notification; no synthetic queue entries are retained.
        }finally{scenario.close()}
    }
    @Test fun encryptedArchiveRestartDedupeReadBackAndConsentRevision() {
        val key="qa-briefing-${UUID.randomUUID()}";val archive=BriefingArchive(context,key)
        try {
            archive.setEnabled(true);val first=archive.authorization()
            val body=buildJsonObject {put("text","합성 일정 확인");put("partial",false)}
            archive.capture("sms","1",1,body);archive.capture("sms","1",1,body)
            val restored=BriefingArchive(context,key);assertEquals(1,restored.pending().size)
            val version=restored.pending().single().getValue("version").jsonPrimitive.content
            assertThrows(IllegalStateException::class.java){restored.acknowledge(version,false)}
            restored.acknowledge(version,true);restored.capture("sms","1",1,body);assertTrue(restored.pending().isEmpty())
            restored.capture("sms","1",1,buildJsonObject {put("text","수정된 합성 일정");put("partial",false)});assertEquals(1,restored.pending().size)
            restored.setEnabled(false);restored.setEnabled(true);assertNotEquals(first,restored.authorization())
        }finally{archive.clear()}
    }
    @Test fun actualCalendarAutomaticAndOwnerReviewedEventsRoundTrip()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("briefingCalendarApproved")=="true")
        val app=(context.applicationContext as LocalAgentApplication).container
        val key="qa-agenda-${UUID.randomUUID()}";val agenda=BriefingAgenda(context,app,key){true}
        val token=UUID.randomUUID().toString();val eventIds=mutableListOf<Long>()
        val provider=AndroidCalendarProvider(context)
        val calendar=app.calendarGateway.listWritableCalendars().firstOrNull {it.isGoogle}?:app.calendarGateway.listWritableCalendars().first()
        val text="2029년 5월 10일 오후4시부터 오후5시까지 합성 회의 확정. 서류 준비는 날짜 확인 필요."
        val source=buildJsonObject {put("at",1L);put("sha256","a".repeat(64));put("body",buildJsonObject {put("text",text);put("partial",false)})}
        fun event(title:String,confirmed:Boolean,start:String?,end:String?)=buildJsonObject {
            put("title",title);put("startIso",start);put("endIso",end);put("confirmed",confirmed);put("ownerRelevant",true);put("changed",false)
            put("quote","합성 회의 확정");put("reason","합성 검증");put("location","검증 장소");put("kind","meeting");put("recipient","");put("deliveryMethod","")
        }
        try {
            agenda.setCalendar(calendar.id)
            val auto=agenda.ingest("sms:qa-$token:auto",source,buildJsonArray {add(event("ZipHap QA 자동 $token",true,"2029-05-10T16:00:00+09:00","2029-05-10T17:00:00+09:00"))}).single()
            assertEquals("registered",auto["status"]?.jsonPrimitive?.content)
            eventIds+=auto.getValue("eventId").jsonPrimitive.long
            assertNotNull(provider.event(calendar.id,eventIds.last()))
            val replay=agenda.ingest("sms:qa-$token:auto",source,buildJsonArray {add(event("ZipHap QA 자동 $token",true,"2029-05-10T16:00:00+09:00","2029-05-10T17:00:00+09:00"))}).single()
            assertEquals(eventIds.last(),replay.getValue("eventId").jsonPrimitive.long)
            val unsure=agenda.ingest("sms:qa-$token:review",source,buildJsonArray {add(event("ZipHap QA 검토 $token",false,null,null))}).single()
            assertEquals("needs_review",unsure.getValue("status").jsonPrimitive.content)
            assertNull(unsure["eventId"])
            val edited=agenda.review(unsure.getValue("id").jsonPrimitive.content,"ZipHap QA 수정 $token","2029-05-11T10:00:00+09:00","2029-05-11T11:00:00+09:00","확인 장소")
            assertEquals("registered",edited.getValue("status").jsonPrimitive.content)
            eventIds+=edited.getValue("eventId").jsonPrimitive.long
            assertEquals("ZipHap QA 수정 $token",provider.event(calendar.id,eventIds.last())?.title)
        }finally {
            // Only exact fixture IDs/titles are removable; no pre-existing event is edited.
            for(item in agenda.items())item["eventId"]?.jsonPrimitive?.longOrNull?.let {id->
                val existing=provider.event(calendar.id,id)
                if(existing!=null){check(existing.title.contains(token));context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),null,null);check(provider.event(calendar.id,id)==null)}
            }
            app.repository.notes.first().filter {it.collection=="calendar-event-links"}.forEach {note->
                val data=Json.parseToJsonElement(note.body).jsonObject
                if(data["eventId"]?.jsonPrimitive?.longOrNull in eventIds)app.repository.deleteNote(note.id)
            }
            DraftStore(context,key,2_000_000).write("")
        }
    }
}
