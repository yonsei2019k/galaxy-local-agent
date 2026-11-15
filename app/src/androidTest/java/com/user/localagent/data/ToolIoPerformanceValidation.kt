package com.user.localagent.data

import android.os.SystemClock
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.workspace.ProjectWorkspace
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Root-only synthetic encrypted DB/private files. No owner DB, model, pairing or network access. */
@RunWith(AndroidJUnit4::class)
class ToolIoPerformanceValidation {
    private fun approved() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("toolIoPerformanceApproved=true required",args.getString("toolIoPerformanceApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val version=checkNotNull(args.getString("toolIoPerformanceExpectedVersion")?.toLongOrNull())
        check(context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==version)
        check(!RuntimeGate.inference.get()&&!RuntimeGate.benchmark.get())
    }
    private fun publish(name:String,body:JsonObject) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(checkNotNull(context.getExternalFilesDir(null)),"diagnostics").apply{check(isDirectory||mkdirs())}
        val atomic=AtomicFile(File(directory,"tool-io-$name.json"));val output=atomic.startWrite()
        try{output.write(body.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(output)}
        catch(error:Throwable){atomic.failWrite(output);throw error}
    }
    private suspend fun fixture(block:suspend(LocalRepository,LocalDatabase,suspend()->LocalRepository)->Unit) {
        approved();val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="tool-io-qa-${UUID.randomUUID()}.db";val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher");var current:LocalDatabase?=null
        fun open():LocalDatabase=Room.databaseBuilder(context,LocalDatabase::class.java,name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone())).build().also{current=it;it.openHelper.writableDatabase}
        try{val db=open();block(LocalRepository(db),db){current?.close();LocalRepository(open())}}
        finally{current?.close();key.fill(0);check(name.startsWith("tool-io-qa-"));context.deleteDatabase(name)
            check(!context.getDatabasePath(name).exists()){"QA_DATABASE_CLEANUP"}}
    }

    @Test fun identicalCheckpointSkipsOnlyTheRowUpdateAndKeepsCancelledDurableReadback()=runBlocking(Dispatchers.IO){fixture{repo,db,reopen->
        val chat=repo.chat;val thread=chat.createThread();val run=chat.createRun(thread.id,thread.activeBranchId);chat.startRun(run.id)
        val message=chat.beginAssistant(run.id);val segment=chat.beginSegment(run.id,message.id,1)
        val head="동일 본문  보존 😀 e\u0301\r\n".repeat(900)
        val tail="\r\n취소 직전 실제 저장된 추가 조각 😀 e\u0301  끝"
        chat.appendChunk(run.id,message.id,segment.id,0,head)
        val sql=db.openHelper.writableDatabase
        // Private fixture only: count actual messages.text row-update trigger firings, not elapsed time.
        // Unlike total_changes(), this ignores Room's own invalidation-table trigger writes.
        sql.execSQL("CREATE TABLE qa_checkpoint_probe (singleton INTEGER PRIMARY KEY, updates INTEGER NOT NULL)")
        sql.execSQL("INSERT INTO qa_checkpoint_probe VALUES (1,0)")
        sql.execSQL("CREATE TRIGGER qa_checkpoint_probe_update AFTER UPDATE OF text ON messages BEGIN UPDATE qa_checkpoint_probe SET updates=updates+1 WHERE singleton=1; END")
        fun updates():Long=sql.query("SELECT updates FROM qa_checkpoint_probe WHERE singleton=1").use{check(it.moveToFirst());it.getLong(0)}
        suspend fun checkedCheckpoint(body:String,rows:Long):MessageRecord=db.withTransaction{
            val before=updates();val stored=chat.checkpointMessage(run.id,message.id);val after=updates()
            assertEquals("EXACT_TEXT_ROW_UPDATE_COUNT",rows,after-before)
            assertEquals(body,stored.text);assertEquals(message.copy(text=body),stored)
            assertEquals(stored,db.dao().messageById(message.id));assertEquals(body,chat.fullMessageText(message.id))
            stored
        }
        checkedCheckpoint(head,1);checkedCheckpoint(head,0)
        // Positive control proves that a legacy unconditional no-op UPDATE really is observable.
        db.withTransaction{val before=updates()
            sql.execSQL("UPDATE messages SET text=text WHERE id=?",arrayOf<Any?>(message.id))
            assertEquals("LEGACY_NOOP_UPDATE_OBSERVED",1L,updates()-before)
        }
        chat.appendChunk(run.id,message.id,segment.id,1,tail)
        val full=head+tail;checkedCheckpoint(full,1);checkedCheckpoint(full,0)
        val chunksBefore=db.chatDao().chunks(message.id)
        val foreign=chat.createThread();val foreignMessage=chat.addMessage(foreign.id,foreign.activeBranchId,"user","synthetic other thread")
        val beforeWrong=updates()
        assertTrue("ORIGINAL_MESSAGE_BINDING_REQUIRED",runCatching{chat.checkpointMessage(run.id,foreignMessage.id)}.isFailure)
        assertEquals(beforeWrong,updates());assertEquals("synthetic other thread",chat.fullMessageText(foreignMessage.id))

        val ready=kotlinx.coroutines.CompletableDeferred<Unit>()
        var reconciled:com.user.localagent.CancelledAnswerReadback?=null
        val views=mutableListOf<com.user.localagent.ui.UiAnswerState>()
        val job=launch {
            ready.complete(Unit)
            try{awaitCancellation()}catch(cancel:CancellationException){
                withContext(NonCancellable){
                    chat.finishSegment(segment.id,"cancelled");chat.cancelRuns(thread.id)
                    assertEquals("cancelled",chat.run(run.id)?.status)
                    reconciled=com.user.localagent.CancelledAnswerFinalizer.finish(cancel,
                        listOf(com.user.localagent.ui.UiAnswerPart(1,full)),
                        reconcile={
                            val durable=checkedCheckpoint(full,0)
                            com.user.localagent.CancelledAnswerReadback(durable.text,
                                chat.segmentsForMessage(message.id).map{com.user.localagent.ui.UiAnswerPart(it.partNumber,chat.segmentText(it.id))})
                        },publish={views+=it})
                    assertTrue("CANCEL_RECONCILE_NO_SUPPRESSED_FAILURE",cancel.suppressed.isEmpty())
                }
                throw cancel
            }
        }
        withTimeout(5_000){ready.await()};withTimeout(15_000){job.cancelAndJoin()}
        assertEquals(full,checkNotNull(reconciled).text)
        assertEquals(listOf(com.user.localagent.ui.UiAnswerPart(1,full)),checkNotNull(reconciled).parts)
        assertEquals(2,views.size);assertTrue(views.all{it.phase==com.user.localagent.ui.UiAnswerPhase.CANCELLED&&!it.canContinue})
        assertEquals(chunksBefore,db.chatDao().chunks(message.id))
        assertEquals(full,chat.segmentText(segment.id));assertEquals(full,chat.fullMessageText(message.id))
        val restored=reopen()
        assertEquals("REOPEN_FULL_DURABLE_BODY",full,restored.chat.fullMessageText(message.id))
        assertEquals(full,restored.chat.segmentText(segment.id));assertEquals("cancelled",restored.chat.run(run.id)?.status)
        assertEquals("cancelled",restored.chat.segmentsForMessage(message.id).single().status)
        assertEquals(message.copy(text=full),restored.getMessage(message.id))
    }}

    @Test fun segmentQueriesPreserveOrderEmptyMissingForeignAndLiveRowsAfterRestart()=runBlocking(Dispatchers.IO){fixture{repo,db,reopen->
        val thread=repo.chat.createThread();val run=repo.chat.createRun(thread.id,thread.activeBranchId);repo.chat.startRun(run.id)
        val message=repo.chat.beginAssistant(run.id);val first=repo.chat.beginSegment(run.id,message.id,1)
        val texts=listOf("첫째\r\n두  공백 😀 e\u0301","second\r\n끝")
        texts.forEachIndexed{index,text->repo.chat.appendChunk(run.id,message.id,first.id,index,text)}
        repo.chat.finishSegment(first.id)
        val empty=repo.chat.beginSegment(run.id,message.id,2);repo.chat.finishSegment(empty.id)
        val live=repo.chat.beginSegment(run.id,message.id,3);repo.chat.appendChunk(run.id,message.id,live.id,0,"live first")
        val foreign=repo.chat.addMessage(thread.id,thread.activeBranchId,"user","synthetic foreign row")
        // Deliberately inconsistent QA row must remain excluded by BOTH message and segment identities.
        db.chatDao().chunk(MessageChunkRecord(first.id,99,run.id,foreign.id,"FOREIGN_MESSAGE"))
        suspend fun legacy(id:String):String{val segment=checkNotNull(db.chatDao().segment(id));return db.chatDao().chunks(segment.messageId)
            .filter{it.segmentId==id}.sortedBy{it.sequence}.joinToString(""){it.text}}
        for(id in listOf(first.id,empty.id,live.id))assertEquals("LEGACY_EQUIVALENCE",legacy(id),repo.chat.segmentText(id))
        assertEquals(texts.joinToString(""),repo.chat.segmentText(first.id));assertEquals("",repo.chat.segmentText(empty.id))
        assertFalse(repo.chat.segmentText(first.id).contains("FOREIGN_MESSAGE"))
        assertTrue(runCatching{repo.chat.segmentText("missing-${UUID.randomUUID()}")}.isFailure)
        val restored=reopen();assertEquals("REOPEN_EQUIVALENCE",texts.joinToString(""),restored.chat.segmentText(first.id))
        assertEquals("",restored.chat.segmentText(empty.id))
        restored.chat.appendChunk(run.id,message.id,live.id,1,"\r\nnew acknowledged 😀")
        assertEquals("LIVE_APPEND","live first\r\nnew acknowledged 😀",restored.chat.segmentText(live.id))
        restored.chat.finishSegment(live.id);restored.chat.updateRun(run.id,"completed")
    }}

    @Test fun boundedEncryptedSegmentBenchmarkReportsAllSamplesAndExactEquivalence()=runBlocking(Dispatchers.IO){
        var measured:JsonObject?=null
        fixture{repo,db,_->
        val segmentCount=24;val chunksEach=4;val chunkChars=1024;val rounds=5
        val thread=repo.chat.createThread();val run=repo.chat.createRun(thread.id,thread.activeBranchId);repo.chat.startRun(run.id)
        val message=repo.chat.beginAssistant(run.id);val ids=mutableListOf<String>();val expected=linkedMapOf<String,String>()
        db.withTransaction{repeat(segmentCount){part->
            val segment=repo.chat.beginSegment(run.id,message.id,part+1);ids+=segment.id
            val pieces=List(chunksEach){sequence->"$part/$sequence 😀 e\u0301\r\n".padEnd(chunkChars,'x')}
            pieces.forEachIndexed{sequence,text->repo.chat.appendChunk(run.id,message.id,segment.id,sequence,text)}
            expected[segment.id]=pieces.joinToString("");repo.chat.finishSegment(segment.id)
        }}
        repo.chat.updateRun(run.id,"completed")
        suspend fun legacy(id:String):String{val segment=checkNotNull(db.chatDao().segment(id));return db.chatDao().chunks(segment.messageId)
            .filter{it.segmentId==id}.sortedBy{it.sequence}.joinToString(""){it.text}}
        for(id in ids){assertEquals(expected.getValue(id),legacy(id));assertEquals(expected.getValue(id),repo.chat.segmentText(id))}
        suspend fun timed(new:Boolean):Long{val start=SystemClock.elapsedRealtimeNanos();var count=0L
            for(id in ids){val text=if(new)repo.chat.segmentText(id)else legacy(id);count+=text.length}
            val elapsed=SystemClock.elapsedRealtimeNanos()-start
            assertEquals(segmentCount.toLong()*chunksEach*chunkChars,count);return elapsed}
        repeat(2){timed(false);timed(true)}
        val old=mutableListOf<Long>();val fresh=mutableListOf<Long>()
        repeat(rounds){round->if(round%2==0){old+=timed(false);fresh+=timed(true)}else{fresh+=timed(true);old+=timed(false)}}
        for(id in ids)assertEquals(expected.getValue(id),repo.chat.segmentText(id))
        measured=buildJsonObject{
            put("kind","synthetic_sqlcipher_same_database_segment_queries");put("equivalenceVerified",true)
            put("segmentCount",segmentCount);put("chunksEach",chunksEach);put("chunkChars",chunkChars);put("warmups",2);put("rounds",rounds)
            put("legacyNs",JsonArray(old.map(::JsonPrimitive)));put("candidateNs",JsonArray(fresh.map(::JsonPrimitive)))
            put("legacyMedianNs",old.sorted()[rounds/2]);put("candidateMedianNs",fresh.sorted()[rounds/2])
            put("legacyRowsMaterializedPerPass",segmentCount*segmentCount*chunksEach)
            put("candidateRowsMaterializedPerPass",segmentCount*chunksEach)
            put("sameTwoQueriesPerSegment",true);put("modelEndToEndSpeedProven",false)
        }
        }
        publish("segment",JsonObject(checkNotNull(measured)+("cleanupVerified" to JsonPrimitive(true))))
    }

    @Test fun immutableWriteHashMicrobenchmarkMeasuresComputationOnly()=runBlocking(Dispatchers.IO){
        approved();val previous="p".repeat(256*1024);val content="c".repeat(12000);val verified=content
        val previousExpected=ProjectWorkspace.sha256(previous);val contentExpected=ProjectWorkspace.sha256(content)
        var sink=0
        fun work(candidate:Boolean){
            val prior=ProjectWorkspace.sha256(previous)
            val written=ProjectWorkspace.sha256(content)
            val observed=ProjectWorkspace.sha256(verified)
            val acceptedPrior=if(candidate)prior else ProjectWorkspace.sha256(previous)
            val evidence=if(candidate)observed else ProjectWorkspace.sha256(verified)
            val receipt=if(candidate)observed else ProjectWorkspace.sha256(verified)
            check(prior==previousExpected&&acceptedPrior==prior&&written==contentExpected&&observed==written&&evidence==observed&&receipt==observed)
            sink=sink xor receipt.hashCode()
        }
        fun timed(candidate:Boolean):Long{val start=SystemClock.elapsedRealtimeNanos();repeat(10){work(candidate)};return SystemClock.elapsedRealtimeNanos()-start}
        repeat(2){timed(false);timed(true)}
        val old=mutableListOf<Long>();val fresh=mutableListOf<Long>()
        repeat(5){round->if(round%2==0){old+=timed(false);fresh+=timed(true)}else{fresh+=timed(true);old+=timed(false)}}
        publish("hash",buildJsonObject{
            put("kind","immutable_private_overwrite_hash_computation_only");put("equivalenceVerified",true)
            put("previousUtf8Bytes",previous.length);put("contentUtf8Bytes",content.length);put("repetitionsPerSample",10);put("rounds",5);put("warmups",2)
            put("legacyNs",JsonArray(old.map(::JsonPrimitive)));put("candidateNs",JsonArray(fresh.map(::JsonPrimitive)))
            put("legacyMedianNs",old.sorted()[2]);put("candidateMedianNs",fresh.sorted()[2]);put("sink",sink)
            put("providerIoMeasured",false);put("modelEndToEndSpeedProven",false);put("actualFileReadCountReduced",false)
        })
    }

    @Test fun invocationLocalHashesPreserveActualFileReadbackAndStaleSourceRejection()=runBlocking(Dispatchers.IO){
        approved();val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id="ioqa-${UUID.randomUUID()}";val workspace=ProjectWorkspace(context,id)
        val before=("보존 😀 e\u0301\r\n두  공백\r\n".repeat(450)).take(11000)+"\r\nold"
        val after=before.removeSuffix("old")+"new";val reader="synthetic-io-reader"
        try{
            val first=Json.parseToJsonElement(workspace.write("same.txt",before)).jsonObject
            assertEquals(ProjectWorkspace.sha256(before),first.getValue("sha256").jsonPrimitive.content)
            val issued=workspace.readForTool("same.txt",reader)
            assertTrue("FULL_DELIVERY_REQUIRED",runCatching{workspace.writeForTool("same.txt",after,issued.sha256,reader)}.isFailure)
            assertEquals(before,workspace.read("same.txt"))
            // Owner reads are independent; the actual issued tool token still needs explicit delivery acceptance.
            workspace.acceptToolReadContext(reader,listOf(com.user.localagent.workspace.DeliveredProjectRead(issued.path,issued.text,issued.sha256,issued.token)))
            assertTrue("STALE_SHA",runCatching{workspace.writeForTool("same.txt",after,"0".repeat(64),reader)}.isFailure)
            val receipt=Json.parseToJsonElement(workspace.writeForTool("same.txt",after,issued.sha256,reader)).jsonObject
            assertEquals(ProjectWorkspace.sha256(after),receipt.getValue("sha256").jsonPrimitive.content)
            assertEquals("INDEPENDENT_FRESH_READ",after,workspace.read("same.txt"))
            assertEquals(after.toByteArray(Charsets.UTF_8).size,receipt.getValue("bytes").jsonPrimitive.int)
            assertEquals(true,receipt.getValue("verified").jsonPrimitive.boolean)
            assertTrue("OLD_HASH_AFTER_SAVE",runCatching{workspace.writeForTool("same.txt",before,issued.sha256,reader)}.isFailure)
            assertEquals(after,workspace.read("same.txt"))
        }finally{workspace.releaseToolReadContext(reader);workspace.discard()
            check(!File(context.filesDir,"projects/$id").exists()){"QA_WORKSPACE_CLEANUP"}}
    }
}
