package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Staged regression: synthetic isolated SQLCipher DB only; never opens the owner's DB. */
@RunWith(AndroidJUnit4::class)
class LegacyTimelineValidation {
    @Test fun newestThousandRemainChronologicalAndOldAuditProofIsStillAddressable()=runBlocking(Dispatchers.IO){
        assumeTrue("legacyTimelineValidationApproved=true required",InstrumentationRegistry.getArguments().getString("legacyTimelineValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="legacy-timeline-qa-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also{SecureRandom().nextBytes(it)};System.loadLibrary("sqlcipher")
        val database=Room.databaseBuilder(context,LocalDatabase::class.java,name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone())).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val repository=LocalRepository(database)
            val thread=repository.chat.createThread();val other=repository.chat.createThread()
            val a=repository.chat.createRun(thread.id,thread.activeBranchId);val b=repository.chat.createRun(thread.id,thread.activeBranchId)
            val foreign=repository.chat.createRun(other.id,other.activeBranchId)
            fun id(index:Int)="legacy-"+index.toString().padStart(4,'0')
            val rows=(0 until 1005).map{index->ToolRunRecord(id=id(index),name="save_note",success=true,
                summary="VERIFIED · synthetic legacy readback",at=1000L+index/3,runId=if(index%2==0)a.id else b.id,threadId=thread.id)}
            database.withTransaction {
                rows.forEach{database.dao().toolRun(it)}
                database.dao().toolRun(ToolRunRecord(id="trace-newest",name="work_trace_v1",success=false,summary="SYNTHETIC_TRACE_METADATA",at=900000,runId=a.id,threadId=thread.id))
                database.dao().toolRun(ToolRunRecord(id="receipt-newest",name="work_receipt_v1",success=false,summary="SYNTHETIC_RECEIPT_METADATA",at=900001,runId=a.id,threadId=thread.id))
                database.dao().toolRun(ToolRunRecord(id="foreign-newest",name="save_note",success=true,summary="VERIFIED · synthetic other room",at=900002,runId=foreign.id,threadId=other.id))
            }
            val window=repository.chat.toolRunsForThread(thread.id).first()
            assertEquals(1000,window.size)
            assertEquals(rows.drop(5),window)
            assertEquals((955 until 1005).map(::id),window.takeLast(50).map{it.id})
            assertTrue(window.zipWithNext().all{(left,right)->left.at<right.at||left.at==right.at&&left.id<right.id})
            assertTrue(window.all{it.threadId==thread.id&&it.name !in setOf("work_trace_v1","work_receipt_v1")})

            val onlyA=repository.chat.toolRunsForThread(thread.id,a.id).first()
            assertEquals(rows.filter{it.runId==a.id},onlyA)
            assertEquals(rows.first(),repository.forThread(thread.id).toolRunEvidence(id(0)))
            assertTrue(runCatching{repository.forThread(other.id).toolRunEvidence(id(0))}.isFailure)
            assertTrue(runCatching{repository.forThread(thread.id).toolRunEvidence("trace-newest")}.isFailure)
            assertTrue(runCatching{repository.forThread(thread.id).toolRunEvidence("receipt-newest")}.isFailure)
            assertTrue(repository.toolAuditMetadata(0,Long.MAX_VALUE,1001).none{it.name in setOf("work_trace_v1","work_receipt_v1")})
        } finally {database.close();key.fill(0);check(name.startsWith("legacy-timeline-qa-"));context.deleteDatabase(name)}
    }
}
