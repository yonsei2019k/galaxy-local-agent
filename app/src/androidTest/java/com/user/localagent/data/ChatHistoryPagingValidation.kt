package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.collectChatCorpus
import com.user.localagent.searchChatCorpus
import com.user.localagent.writeChatCorpus
import com.user.localagent.ui.UiChatExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringWriter
import java.security.SecureRandom
import java.util.UUID

/** Isolated encrypted fixtures only; no models, owner history, network, or external files. */
@RunWith(AndroidJUnit4::class)
class ChatHistoryPagingValidation {
    @Test fun latestWindowUsesOnlyMemberChunksAndAnchorLoadsEnoughHistory()=runBlocking(Dispatchers.IO){fixture{repo,db->
        val chat=repo.chat;val thread=chat.createThread(title="Synthetic paged history");val branch=thread.activeBranchId
        val first=chat.addMessage(thread.id,branch,"user","SYNTHETIC_FIRST")
        val oldRun=chat.createRun(thread.id,branch,first.id);chat.startRun(oldRun.id)
        val oldAnswer=chat.beginAssistant(oldRun.id);val oldSegment=chat.beginSegment(oldRun.id,oldAnswer.id,1)
        chat.appendChunk(oldRun.id,oldAnswer.id,oldSegment.id,0,"SYNTHETIC_OLD_CHUNK")
        chat.finishSegment(oldSegment.id);chat.updateRun(oldRun.id,"completed")
        repeat(130){chat.addMessage(thread.id,branch,"user","Synthetic middle $it")}
        val latest=chat.addMessage(thread.id,branch,"user","SYNTHETIC_LAST_REQUEST")
        val liveRun=chat.createRun(thread.id,branch,latest.id);chat.startRun(liveRun.id)
        val liveAnswer=chat.beginAssistant(liveRun.id);val liveSegment=chat.beginSegment(liveRun.id,liveAnswer.id,1)
        chat.appendChunk(liveRun.id,liveAnswer.id,liveSegment.id,0,"SYNTHETIC_LIVE_UNCHECKPOINTED_TAIL")
        val other=chat.forkBranch(thread.id,first.id,title="Synthetic other version")
        val otherUser=chat.addMessage(thread.id,other.id,"user","SYNTHETIC_OTHER_REQUEST")
        val otherRun=chat.createRun(thread.id,other.id,otherUser.id);chat.startRun(otherRun.id)
        val otherAnswer=chat.beginAssistant(otherRun.id);val otherSegment=chat.beginSegment(otherRun.id,otherAnswer.id,1)
        chat.appendChunk(otherRun.id,otherAnswer.id,otherSegment.id,0,"SYNTHETIC_OTHER_BRANCH_CHUNK")

        val visible=chat.pagedMessages(thread.id,branch,60).first()
        assertEquals(60,visible.size);assertFalse(visible.any{it.id==first.id||it.id==oldAnswer.id||it.id==otherAnswer.id})
        assertEquals("SYNTHETIC_LIVE_UNCHECKPOINTED_TAIL",visible.last().text)
        assertEquals(setOf(liveAnswer.id),db.chatDao().pagedChunks(thread.id,branch,60).first().map{it.messageId}.toSet())
        assertEquals(setOf(liveAnswer.id),db.chatDao().pagedSegments(thread.id,branch,60).first().map{it.messageId}.toSet())
        val count=chat.historyCount(thread.id,branch).first();assertTrue(count>60)
        val limit=chat.historyLimitForAnchor(thread.id,branch,first.id,60)
        assertTrue(limit>=count);assertEquals(0,limit%60)
        assertEquals(first.id,chat.pagedMessages(thread.id,branch,limit).first().first().id)
        assertEquals(60,chat.historyLimitForAnchor(thread.id,branch,otherAnswer.id,60))
        chat.clearThread(thread.id,true)
        assertTrue(chat.pagedMessages(thread.id,branch,60).first().isEmpty());assertEquals(0,chat.historyCount(thread.id,branch).first())
    }}

    @Test fun allVersionExportAndLocalSearchUseFullLiveTailAndSingleSharedBody()=runBlocking(Dispatchers.IO){fixture{repo,_->
        val chat=repo.chat;val thread=chat.createThread(title="Synthetic corpus");val original=thread.activeBranchId
        val user=chat.addMessage(thread.id,original,"user","SYNTHETIC_SHARED_PREFIX")
        val run=chat.createRun(thread.id,original,user.id);chat.startRun(run.id)
        val answer=chat.beginAssistant(run.id);val segment=chat.beginSegment(run.id,answer.id,1)
        val tail="합성🙂 ".repeat(5000)+"SYNTHETIC_OLD_BRANCH_EXACT_TAIL"
        chat.appendChunk(run.id,answer.id,segment.id,0,tail)
        val revised=chat.forkBranch(thread.id,user.id,title="Synthetic revised")
        chat.addMessage(thread.id,revised.id,"assistant","SYNTHETIC_REVISED_ANSWER")
        val live=checkNotNull(chat.thread(thread.id))
        val corpus=collectChatCorpus(live,chat.branches(thread.id)){branch,before->chat.messagePage(thread.id,branch,before,1)}
        val output=StringWriter();writeChatCorpus(corpus,UiChatExportFormat.JSON,"synthetic-time",output){chat.fullMessageText(it)}
        val json=Json.parseToJsonElement(output.toString()).jsonObject
        assertEquals(2,json.getValue("branches").jsonArray.size)
        val messages=json.getValue("messages").jsonArray.map{it.jsonObject}
        assertEquals(1,messages.count{it.getValue("id").jsonPrimitive.content==user.id})
        assertEquals(tail,messages.first{it.getValue("id").jsonPrimitive.content==answer.id}.getValue("text").jsonPrimitive.content)
        assertEquals(setOf(thread.id),searchChatCorpus(listOf(live),"old_branch_exact_tail",{chat.branches(it)}){id,branch,before->chat.messagePage(id,branch,before,1)})
    }}

    private suspend fun fixture(block:suspend(LocalRepository,LocalDatabase)->Unit){
        assumeTrue("chatDataValidationApproved=true required",InstrumentationRegistry.getArguments().getString("chatDataValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="chat-paging-qa-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also{SecureRandom().nextBytes(it)};System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try{block(LocalRepository(db),db)}finally{db.close();context.deleteDatabase(name);key.fill(0)}
    }
}
