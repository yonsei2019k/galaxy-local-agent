package com.user.localagent

import android.Manifest
import android.database.Cursor
import android.database.CursorWrapper
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.automation.SmsAnalysisController
import com.user.localagent.core.ScheduleCandidate
import com.user.localagent.data.LocalRepository
import com.user.localagent.imports.PhoneRecordSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SmsScheduleValidation {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val repository by lazy{LocalRepository(context)}
    private val at=OffsetDateTime.parse("2026-10-30T12:00:00+09:00").toInstant().toEpochMilli()
    private data class Row(val id:Long,val date:Long,val type:Int,val body:String)
    private class FakeSource(val rows:List<Row>,var granted:Boolean=true):PhoneRecordSource {
        var queried=false;var closed=false;var lastArgs:Array<String>?=null
        override fun hasPermission(permission:String)=permission==Manifest.permission.READ_SMS&&granted
        override fun query(uri:Uri,projection:Array<String>,selection:String?,args:Array<String>?,order:String):Cursor {
            check(uri==Telephony.Sms.CONTENT_URI);check(Telephony.Sms.ADDRESS !in projection)
            queried=true;lastArgs=args
            val filtered=rows.filter {args==null||(it.date>=args[0].toLong()&&it.date<args[1].toLong())}
            val matrix=MatrixCursor(projection)
            filtered.forEach {r->matrix.addRow(projection.map<String,Any?> {key->when(key){Telephony.Sms._ID->r.id;Telephony.Sms.DATE->r.date;Telephony.Sms.TYPE->r.type;Telephony.Sms.BODY->r.body;else->null}}.toTypedArray())}
            return object:CursorWrapper(matrix){override fun close(){closed=true;super.close()}}
        }
    }
    @Test fun deniedPermissionNeverQueriesProvider()=runBlocking {
        val source=FakeSource(emptyList(),false)
        val result=SmsAnalysisController(context,repository,source).scan()
        assertNotNull(result.error);assertEquals(0,result.scannedCount);assertFalse(source.queried)
    }
    @Test fun allRowsStatsCandidatePersistenceDedupeAndRestore()=runBlocking {
        val marker="sms-qa-"+UUID.randomUUID()
        val rows=listOf(
            Row(1,at,1,"$marker 예약 2026.11.2 오후3시"),Row(2,at-1,2,"$marker 인증번호 123456"),
            Row(3,at-2,1,"$marker 은행 입금 10000원"),Row(4,at-3,1,"$marker 택배 배송 완료"),
            Row(5,at-4,1,"$marker 예약취소 안내 2026.11.2 15:00. 취소를 원하시면 연락"),
            Row(6,at-5,1,"$marker (광고) 공연 예약 2026.11.3 15:00"),Row(7,at-6,3,"$marker 예약 2026.11.4 16:00"))
        val controller=SmsAnalysisController(context,repository,FakeSource(rows))
        val owned=mutableSetOf<String>()
        try {
            val first=controller.scan();owned+=first.candidates.map {it.id}
            assertNull(first.error);assertFalse(first.truncated);assertEquals(7,first.scannedCount);assertEquals(7,first.totalCount)
            assertEquals(5,first.incomingCount);assertEquals(1,first.outgoingCount);assertEquals(7,first.categories.values.sum())
            assertEquals(1,first.categories["광고"]);assertEquals(1,first.categories["금융"]);assertEquals(1,first.categories["배송"])
            assertEquals(2,first.candidates.size);assertTrue(first.candidates.any {it.needsReview});assertTrue(first.candidates.all {it.excerpt.length<=1000})
            first.candidates.forEach {candidate->assertEquals(SmsAnalysisController.COLLECTION,repository.getNote("sms-schedule-${candidate.id}")?.collection)}
            val again=controller.scan();owned+=again.candidates.map {it.id};assertEquals(first.candidates.map {it.id},again.candidates.map {it.id})
            val restored=SmsAnalysisController(context,repository,FakeSource(emptyList(),false))
            withTimeout(5000){while(!restored.state.value.candidates.map {it.id}.containsAll(owned))delay(20)}
        }finally{withContext(NonCancellable){owned.forEach {repository.deleteNote("sms-schedule-$it")}}}
    }
    @Test fun currentYearFilterUsesSeoulMidnight()=runBlocking {
        val marker="sms-year-"+UUID.randomUUID()
        val midnight=OffsetDateTime.parse("2026-01-01T00:00:00+09:00").toInstant().toEpochMilli()
        val source=FakeSource(listOf(Row(1,midnight-1,1,"$marker 예약 2026.11.2 15:00"),Row(2,midnight,1,"$marker 예약 2026.11.3 15:00")))
        val result=SmsAnalysisController(context,repository,source).scan(2026)
        try{assertEquals(1,result.scannedCount);assertEquals(midnight,source.lastArgs?.get(0)?.toLong());assertEquals(midnight,result.firstAtMillis)}
        finally{withContext(NonCancellable){result.candidates.forEach {repository.deleteNote("sms-schedule-${it.id}")}}}
    }
    @Test fun progressCancellationClosesCursorAndStopsFurtherScan()=runBlocking {
        val marker="sms-cancel-"+UUID.randomUUID()
        val source=FakeSource(List(100){Row(it.toLong(),at-it,1,"$marker 예약 2026.11.2 15:00")})
        val controller=SmsAnalysisController(context,repository,source)
        try {
            var cancelled=false
            try{controller.scan(onProgress={done,_->if(done>=50)throw CancellationException("synthetic cancellation")})}catch(_:CancellationException){cancelled=true}
            assertTrue(cancelled);assertTrue(source.closed);assertEquals(50,controller.state.value.scannedCount);assertFalse(controller.state.value.running)
        }finally{withContext(NonCancellable){controller.state.value.candidates.filter {it.excerpt.contains(marker)}.forEach {repository.deleteNote("sms-schedule-${it.id}")}}}
    }

    /** Explicit owner-approved real read, with aggregate-only external evidence and no calendar writes. */
    @Test fun readGrantedSmsAggregates()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit smsReadApproved=true is required",args.getString("smsReadApproved")=="true")
        val app=(context.applicationContext as LocalAgentApplication).container
        val year=args.getString("smsYear")?.toIntOrNull()
        val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").apply {mkdirs()}
        val output=File(directory,"sms-scan.json")
        var failure:Throwable?=null
        var reportSaved=false
        try {
            val result=app.smsAnalysis.scan(year){done,total->if(done%500==0)instrumentation.sendStatus(0,Bundle().apply {putString("stream","SMS scan progress: $done/$total\n")})}
            val report=buildJsonObject {
                put("scope","android_sms_provider_only");put("scannedCount",result.scannedCount);put("totalCount",result.totalCount)
                put("firstAtMillis",result.firstAtMillis);put("lastAtMillis",result.lastAtMillis);put("truncated",result.truncated)
                put("candidateCount",result.candidates.size);put("reviewRequiredCount",result.candidates.count {it.needsReview})
                put("incomingCount",result.incomingCount);put("outgoingCount",result.outgoingCount);put("bytesRead",result.bytesRead)
                put("categories",buildJsonObject {result.categories.forEach {(key,value)->put(key,value)}})
                put("scanSucceeded",result.error==null);put("calendarWritten",false);put("smsModified",false);put("rawContentExported",false)
            }.toString()
            withContext(Dispatchers.IO){output.writeText(report);reportSaved=true}
            assertTrue("The granted SMS scan must complete without provider error",result.error==null)
            assertEquals(result.scannedCount,result.categories.values.sum())
        }catch(error:Throwable){failure=error;throw error}
        finally{if(failure!=null&&!reportSaved)withContext(NonCancellable+Dispatchers.IO){output.writeText(buildJsonObject {put("scope","android_sms_provider_only");put("scanSucceeded",false);put("failureClass",failure!!.javaClass.name);put("rawContentExported",false);put("calendarWritten",false)}.toString())}}
    }
}
