package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.QueuedChatRequest
import com.user.localagent.ContinuationSnapshot
import com.user.localagent.inference.InferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Isolated synthetic v3 database. No model/network/owner DB access. */
@RunWith(AndroidJUnit4::class)
class ChatQueueValidation {
    @Test fun queuedTurnsReservePrimaryOrderAndNativeAnswersShareThatSlot()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val thread=repo.chat.createThread(title="Synthetic ordered queue")
        val allocated=UUID.randomUUID().toString()
        val a=repo.chat.enqueueUserRun(thread.id,thread.activeBranchId,"Synthetic turn A","auto",false,runId=allocated){user,run->
            QueuedChatRequest(run.id,thread.id,thread.activeBranchId,null,"Synthetic turn A","auto",false,emptyList(),user.id).toJson()
        }
        val b=enqueue(repo,thread,"Synthetic turn B")
        val aSlot=requireNotNull(repo.chat.assistantForRun(a.id));val bSlot=requireNotNull(repo.chat.assistantForRun(b.id))
        assertEquals(allocated,a.id)
        assertEquals(listOf(a.userMessageId,aSlot.id,b.userMessageId,bSlot.id),repo.chat.messages(thread.id).first().map {it.id})
        repo.chat.startRun(a.id)
        assertEquals(aSlot.id,repo.chat.beginAssistant(a.id).id)
        assertTrue(runCatching {repo.chat.beginAssistant(a.id,UUID.randomUUID().toString())}.isFailure)
        repo.chat.writeRunAnswer(a.id,"Synthetic native rule answer")
        val longBody="x".repeat(65533)+"\uD83D\uDE00tail"
        val written=repo.chat.writeRunAnswer(a.id,longBody)
        val expected="Synthetic native rule answer\n\n$longBody"
        assertEquals(aSlot.id,written.id);assertEquals(expected,written.text)
        val history=repo.chat.messages(thread.id).first()
        val bIndex=history.indexOfFirst {it.id==b.userMessageId}
        val prefix=history.take(bIndex+1)
        assertEquals(listOf(a.userMessageId,aSlot.id,b.userMessageId),prefix.map {it.id})
        assertEquals(expected,prefix[1].text)
        assertEquals(1,history.count {it.runId==a.id&&it.role=="assistant"})
        repo.chat.updateRun(a.id,"completed")
        repo.chat.cancelQueuedRun(b.id)
        assertNull(repo.chat.assistantForRun(b.id))
        assertEquals(expected,repo.chat.fullMessageText(aSlot.id))
        val before=repo.chat.messages(thread.id).first().map {it.id}
        assertTrue(runCatching {repo.chat.enqueueUserRun(thread.id,thread.activeBranchId,"Duplicate ID attempt","auto",false,runId=allocated){_,_->"{}"}}.isFailure)
        assertEquals(before,repo.chat.messages(thread.id).first().map {it.id})
    }}

    @Test fun lateManualRunReservesAfterItsUserAndLegacyFallbackBecomesDurableSegments()=runBlocking(Dispatchers.IO){fixture{repo,db,_->
        val thread=repo.chat.createThread(title="Synthetic late manual reservation")
        val first=repo.chat.addMessage(thread.id,thread.activeBranchId,"user","Synthetic earlier user")
        val later=repo.chat.addMessage(thread.id,thread.activeBranchId,"user","Synthetic later user")
        val run=repo.chat.createRun(thread.id,thread.activeBranchId,first.id)
        val slot=requireNotNull(repo.chat.assistantForRun(run.id))
        assertEquals(listOf(first.id,slot.id,later.id),repo.chat.messages(thread.id).first().map {it.id})
        repo.chat.startRun(run.id)
        db.chatDao().checkpoint(slot.id,"Synthetic legacy host fallback")
        assertEquals(slot.id,repo.chat.beginAssistant(run.id).id)
        assertEquals("Synthetic legacy host fallback",repo.chat.segmentText(repo.chat.segmentsForMessage(slot.id).single().id))
        val full=repo.chat.writeRunAnswer(run.id,"Synthetic later native body")
        assertEquals("Synthetic legacy host fallback\n\nSynthetic later native body",full.text)
        assertEquals(2,repo.chat.segmentsForMessage(slot.id).size)
    }}

    @Test fun queueOrderAndEditsPersistButStartedAndCancelledRequestsCannotBeEdited()=runBlocking(Dispatchers.IO){fixture{repo,_,reopen->
        val a=repo.chat.createThread(title="Synthetic queue A");val b=repo.chat.createThread(title="Synthetic queue B")
        val first=enqueue(repo,a,"Synthetic first request");val second=enqueue(repo,b,"Synthetic second request");val third=enqueue(repo,a,"Synthetic third request")
        repo.chat.reorderQueuedRuns(listOf(third.id,first.id,second.id))
        val replacement=QueuedChatRequest.fromJson(first.contextJson).copy(text="Synthetic edited first request").toJson()
        val edited=repo.chat.editQueuedRun(first.id,"Synthetic edited first request",replacement)
        assertEquals("Synthetic edited first request",repo.getMessage(first.userMessageId!!)?.text)
        assertEquals(1L,Json.parseToJsonElement(edited.contextJson).jsonObject.getValue("queueOrder").jsonPrimitive.long)
        val restored=reopen()
        assertEquals(listOf(third.id,first.id,second.id),restored.chat.queuedRuns().map {it.id})
        restored.chat.startRun(third.id)
        assertTrue(runCatching {restored.chat.editQueuedRun(third.id,"Started edit",QueuedChatRequest.fromJson(third.contextJson).copy(text="Started edit").toJson())}.isFailure)
        assertTrue(runCatching {restored.chat.reorderQueuedRuns(listOf(third.id,first.id,second.id))}.isFailure)
        assertEquals(listOf(first.id,second.id),restored.chat.queuedRuns().map {it.id})
        restored.chat.cancelQueuedRun(first.id)
        assertEquals("cancelled",restored.chat.run(first.id)?.status)
        assertTrue(runCatching {restored.chat.editQueuedRun(first.id,"Cancelled edit",replacement)}.isFailure)
        assertEquals(listOf(second.id),restored.chat.queuedRuns().map {it.id})
    }}

    @Test fun summaryRequiresExactBranchSourcesAndInvalidatesWhenQueuedSourceIsEdited()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val a=repo.chat.createThread(title="Synthetic summary A");val b=repo.chat.createThread(title="Synthetic summary B")
        val run=enqueue(repo,a,"Synthetic initial summary source")
        val source=requireNotNull(run.userMessageId)
        val summary=repo.chat.saveBranchSummary(a.id,a.activeBranchId,"Synthetic bounded branch summary",listOf(source))
        assertEquals("completed",summary.status)
        assertEquals("Synthetic bounded branch summary",repo.chat.branchSummary(a.id,a.activeBranchId)?.text)
        assertNull(repo.chat.branchSummary(b.id,a.activeBranchId))
        assertTrue(runCatching {repo.chat.saveBranchSummary(b.id,b.activeBranchId,"Wrong branch",listOf(source))}.isFailure)
        val edited=QueuedChatRequest.fromJson(run.contextJson).copy(text="Synthetic changed source")
        repo.chat.editQueuedRun(run.id,edited.text,edited.toJson())
        assertNull(repo.chat.branchSummary(a.id,a.activeBranchId))
        val settings=ProjectSettings(disabledTools=setOf("open_url","save_note"))
        val converter=ChatConverters();assertEquals(settings,converter.settings(converter.settings(settings)))
        assertTrue(converter.settings("{\"modelId\":\"auto\"}").disabledTools.isEmpty())
    }}

    @Test fun manualTextResumeQueueSurvivesRestartReordersAndCancelsWithoutLosingPausedText()=runBlocking(Dispatchers.IO){fixture{repo,_,reopen->
        val thread=repo.chat.createThread(title="Synthetic durable resume queue")
        val run=enqueue(repo,thread,"Synthetic original long request");repo.chat.startRun(run.id)
        val message=repo.chat.beginAssistant(run.id);val segment=repo.chat.beginSegment(run.id,message.id,1)
        repo.chat.appendChunk(run.id,message.id,segment.id,0,"Synthetic saved first part");repo.chat.finishSegment(segment.id)
        val snapshot=ContinuationSnapshot(message.id,"auto","Synthetic original long request","Synthetic system",emptyList(),InferenceOptions(),2)
        val request=QueuedChatRequest.fromJson(run.contextJson).copy(continuation=snapshot,allowTools=false)
        repo.chat.updateRunContext(run.id,request.toJson());repo.chat.updateRun(run.id,"paused")
        val other=repo.chat.createThread(title="Synthetic normal queue");val normal=enqueue(repo,other,"Synthetic ordinary queued request")
        val waiting=repo.chat.queueResumeRun(run.id)
        assertEquals("paused",waiting.status)
        assertEquals(true,Json.parseToJsonElement(waiting.contextJson).jsonObject.getValue("resumeQueued").jsonPrimitive.boolean)
        assertEquals(false,QueuedChatRequest.fromJson(waiting.contextJson).allowTools)
        assertTrue(runCatching {repo.chat.editQueuedRun(run.id,"Illegal resume edit",request.copy(text="Illegal resume edit").toJson())}.isFailure)
        repo.chat.reorderQueuedRuns(listOf(run.id,normal.id))
        val restored=reopen()
        assertEquals(listOf(run.id,normal.id),restored.chat.queuedRuns().map {it.id})
        assertEquals("paused",restored.chat.run(run.id)?.status)
        restored.chat.cancelQueuedRun(run.id)
        assertEquals("paused",restored.chat.run(run.id)?.status)
        assertEquals("Synthetic saved first part",restored.chat.fullMessageText(message.id))
        assertEquals(listOf(normal.id),restored.chat.queuedRuns().map {it.id})
        restored.chat.queueResumeRun(run.id)
        val resumed=restored.chat.resumeRun(run.id)
        assertEquals("running",resumed.status)
        assertFalse(Json.parseToJsonElement(resumed.contextJson).jsonObject.containsKey("resumeQueued"))
        assertFalse(QueuedChatRequest.fromJson(resumed.contextJson).allowTools)
        assertEquals(listOf(normal.id),restored.chat.queuedRuns().map {it.id})
        restored.chat.updateRun(run.id,"paused");restored.chat.queueResumeRun(run.id)
        restored.chat.cancelRuns(thread.id,"owner_scope_change")
        assertEquals(listOf(normal.id),restored.chat.queuedRuns().map {it.id})
        assertEquals("Synthetic saved first part",restored.chat.fullMessageText(message.id))
    }}

    @Test fun clearAndDeleteScrubRunEnvelopesDraftsSummariesAndExactToolBodies()=runBlocking(Dispatchers.IO){fixture{repo,db,_->
        val other=repo.chat.createThread(title="Synthetic unaffected thread")
        val otherRun=enqueue(repo,other,"Synthetic preserved other envelope")
        for(delete in listOf(false,true)){
            val thread=repo.chat.createThread(title="Synthetic erasure target")
            val token="Synthetic raw private envelope ${UUID.randomUUID()}"
            val run=enqueue(repo,thread,token);repo.chat.startRun(run.id)
            val message=repo.chat.beginAssistant(run.id);val segment=repo.chat.beginSegment(run.id,message.id,1)
            repo.chat.appendChunk(run.id,message.id,segment.id,0,token);repo.chat.finishSegment(segment.id)
            repo.chat.saveDraft(thread.id,token)
            repo.chat.saveBranchSummary(thread.id,thread.activeBranchId,token,listOf(run.userMessageId!!,message.id))
            repo.recordToolRun("save_note",true,token,run.id,thread.id)
            repo.recordToolRun("open_url",true,"Synthetic unrelated tool audit",otherRun.id,other.id)
            assertEquals(run.id,repo.toolRunsForThread(thread.id,run.id).first().single().runId)
            if(delete)repo.chat.deleteThread(thread.id)else repo.chat.clearThread(thread.id)
            assertTrue(repo.chat.runsForThread(thread.id).all {it.contextJson.isEmpty()})
            assertNull(db.chatDao().draft(thread.id));assertNull(repo.chat.branchSummary(thread.id,thread.activeBranchId))
            assertTrue(repo.toolRunsForThread(thread.id).first().all {it.summary.isEmpty()&&it.threadId==thread.id})
            assertTrue(db.chatDao().chunks(message.id).isEmpty());assertTrue(db.chatDao().refs(thread.activeBranchId).isEmpty())
            assertTrue(runCatching {repo.chat.updateRun(run.id,"cancelled",contextJson=run.contextJson)}.isFailure)
        }
        assertEquals(otherRun.contextJson,repo.chat.run(otherRun.id)?.contextJson)
        assertEquals(2,repo.toolRunsForThread(other.id,otherRun.id).first().size)
        assertTrue(repo.toolRunsForThread(other.id).first().all {it.summary=="Synthetic unrelated tool audit"})
    }}

    private suspend fun enqueue(repo:LocalRepository,thread:ChatThreadRecord,text:String):ChatRunRecord =
        repo.chat.enqueueUserRun(thread.id,thread.activeBranchId,text,"auto",false){user,run->
            QueuedChatRequest(run.id,thread.id,thread.activeBranchId,thread.projectId,text,"auto",false,emptyList(),user.id).toJson()
        }

    private suspend fun fixture(block:suspend(LocalRepository,LocalDatabase,suspend()->LocalRepository)->Unit){
        assumeTrue("chatDataValidationApproved=true required",InstrumentationRegistry.getArguments().getString("chatDataValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="chat-queue-qa-${UUID.randomUUID()}.db";val key=ByteArray(32).also {SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher");var database:LocalDatabase?=null
        fun open()=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .addMigrations(KnowledgeMigrations.MIGRATION_1_2,ChatMigrations.MIGRATION_2_3).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try{
            val first=open();database=first;first.openHelper.writableDatabase
            block(LocalRepository(first),first){database?.close();val next=open();database=next;next.openHelper.writableDatabase;LocalRepository(next)}
        }finally {database?.close();key.fill(0);check(name.startsWith("chat-queue-qa-"));context.deleteDatabase(name)}
    }
}
