package com.user.localagent

import com.user.localagent.data.*
import com.user.localagent.ui.UiChatExportFormat
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class ChatCorpusOperationsTest {
    private val thread=ChatThreadRecord(id="thread-a",title="Synthetic title",activeBranchId="edited")
    private val branches=listOf(ChatBranchRecord(id="original",threadId=thread.id,title="Original",createdAt=1),
        ChatBranchRecord(id="edited",threadId=thread.id,title="Edited",parentBranchId="original",createdAt=2))
    private fun message(id:String,text:String,at:Long)=MessageRecord(id=id,role=if(id=="shared-user")"user"else"assistant",text=text,threadId=thread.id,branchId="original",createdAt=at)
    private val shared=message("shared-user","SYNTHETIC_SHARED_OWNER_TEXT",1)
    private val old=message("old-answer","SYNTHETIC_ONLY_IN_OLD_VERSION",2)
    private val revised=message("new-answer","한글🙂 body\n".repeat(4000)+"EXACT_CANONICAL_TAIL",3)
    private fun records(branch:String)=if(branch=="original")listOf(shared,old)else listOf(shared,revised)
    private fun page(branch:String,before:Long?):ChatMessagePage {
        val found=records(branch).mapIndexed {index,value->index+1L to value}.filter{before==null||it.first<before}.asReversed()
        val selected=found.take(1)
        return ChatMessagePage(selected.map{it.second},if(found.size>1)selected.last().first else null)
    }
    @Test fun everyBranchKeepsExactOrderWhileSharedBodyIsStoredAndLoadedOnce()=runBlocking {
        val corpus=collectChatCorpus(thread,branches,::page)
        assertEquals(3,corpus.messages.size)
        assertEquals(listOf(shared.id,old.id),corpus.branches[0].messageIds)
        assertEquals(listOf(shared.id,revised.id),corpus.branches[1].messageIds)
        val reads=mutableMapOf<String,Int>();val bodies=listOf(shared,old,revised).associateBy{it.id}
        val output=StringWriter()
        writeChatCorpus(corpus,UiChatExportFormat.JSON,"synthetic-time",output){id->reads[id]=(reads[id]?:0)+1;bodies.getValue(id).text}
        val json=Json.parseToJsonElement(output.toString()).jsonObject
        assertEquals(2,json.getValue("version").jsonPrimitive.int)
        assertEquals(2,json.getValue("branches").jsonArray.size)
        assertEquals(3,json.getValue("messages").jsonArray.size)
        assertEquals(setOf(1),reads.values.toSet())
        val restored=json.getValue("messages").jsonArray.associate{it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("text").jsonPrimitive.content}
        assertEquals(revised.text,restored[revised.id]);assertTrue(restored.getValue(revised.id).endsWith("EXACT_CANONICAL_TAIL"))
    }
    @Test fun textAndMarkdownLabelAllVersionsAndNeverRepeatSharedRawBodies()=runBlocking {
        val corpus=collectChatCorpus(thread,branches,::page);val bodies=listOf(shared,old,revised).associateBy{it.id}
        for(format in listOf(UiChatExportFormat.TEXT,UiChatExportFormat.MARKDOWN)){
            val output=StringWriter();writeChatCorpus(corpus,format,"synthetic-time",output){bodies.getValue(it).text}
            assertTrue(output.toString().contains("Original"));assertTrue(output.toString().contains("Edited [현재]"))
            assertEquals(1,Regex("SYNTHETIC_SHARED_OWNER_TEXT").findAll(output.toString()).count())
            assertTrue(output.toString().contains(old.text));assertTrue(output.toString().contains("EXACT_CANONICAL_TAIL"))
        }
    }
    @Test fun searchIncludesInactiveVersionsLongTailAndTitleWithoutReadingPublicSources()=runBlocking {
        suspend fun search(query:String)=searchChatCorpus(listOf(thread),query,{branches}){_,branch,before->page(branch,before)}
        assertEquals(setOf(thread.id),search("only_in_old_version"))
        assertEquals(setOf(thread.id),search("EXACT_CANONICAL_TAIL"))
        assertEquals(setOf(thread.id),searchChatCorpus(listOf(thread),"synthetic TITLE",{error("Title match requires no body read")}){_,_,_->error("No body read")})
        assertTrue(search("absent synthetic token").isEmpty())
    }
    @Test fun deletedThreadsNeverParticipateAndCrossThreadMessagesFailClosed()=runBlocking {
        assertTrue(searchChatCorpus(listOf(thread.copy(deletedAt=1)),"anything",{error("Deleted")}){_,_,_->error("Deleted")}.isEmpty())
        try{
            collectChatCorpus(thread,branches){_,_->ChatMessagePage(listOf(shared.copy(threadId="other")),null)}
            fail("Cross-thread export must fail")
        }catch(_:IllegalArgumentException){}
    }
    @Test fun queryCancellationStopsTheLocalPageScan()=runBlocking {
        val entered=CompletableDeferred<Unit>();val blocked=CompletableDeferred<ChatMessagePage>();var calls=0
        val search=async{searchChatCorpus(listOf(thread),"absent",{branches}){_,_,_->calls++;entered.complete(Unit);blocked.await()}}
        entered.await();search.cancelAndJoin()
        assertTrue(search.isCancelled);assertEquals(1,calls)
    }
}
