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
