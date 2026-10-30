package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.data.LocalDao
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.MessageCursor
import com.user.localagent.data.MessageRecord
import com.user.localagent.diagnostics.ConversationDiagnostics
import com.user.localagent.diagnostics.ConversationExportFormat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** Own UUID fixtures only; exported queries cannot return unrelated owner conversation text. */
@RunWith(AndroidJUnit4::class)
class ConversationDiagnosticsValidation {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy {LocalRepository(context)}
    // Test-only fixture access avoids a production arbitrary-history insertion API.
    private val fixtureDao:LocalDao by lazy {LocalRepository::class.java.getDeclaredField("dao").apply {isAccessible=true}.get(repository) as LocalDao}

    @Test fun pagesBeyondRecent200AndStableTimestampTies()=runBlocking {
        val marker="diagnostic-page-"+UUID.randomUUID()
        val rows=List(205){MessageRecord(id=UUID.randomUUID().toString(),role=if(it%2==0)"user" else "assistant",text="$marker row $it",createdAt=FIXTURE_TIME)}
        withRows(rows){
            assertEquals(205L,repository.messageCount(marker))
            var cursor:MessageCursor?=null
            val actual=mutableListOf<String>()
            do {
                val page=repository.messagePage(cursor,37,marker)
                assertTrue(page.messages.size<=37)
                actual+=page.messages.map {it.id};cursor=page.nextCursor
            }while(cursor!=null)
            assertEquals(rows.map {it.id}.sortedDescending(),actual)
            assertEquals(205,actual.toSet().size)
        }
    }

    @Test fun jsonEscapingTextOmissionAndCountTruncation()=runBlocking {
        val marker="diagnostic-export-"+UUID.randomUUID()
        val raw="$marker \"quoted\"\n한글 😀 🧑‍💻 & <tag> \\ end"
        val rows=List(3){MessageRecord(id=UUID.randomUUID().toString(),role="user",text=raw,createdAt=FIXTURE_TIME+it)}
        withRows(rows){
            val output=ByteArrayOutputStream()
            val result=ConversationDiagnostics(repository).exportSnapshot(output,limit=2,query=marker)
            val parsed=Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject
            assertEquals(2,result.messageCount);assertTrue(result.truncated)
            assertEquals(2,parsed.getValue("messageCount").jsonPrimitive.int)
            assertTrue(parsed.getValue("truncated").jsonPrimitive.boolean)
            assertEquals("ziphap_stored_app_messages_only",parsed.getValue("scope").jsonPrimitive.content)
            assertTrue(parsed.getValue("messages").jsonArray.all {it.jsonObject.getValue("text").jsonPrimitive.content==raw})
            assertTrue(parsed.getValue("toolMetadata").jsonArray.isEmpty())
            val metadataOnly=ByteArrayOutputStream()
            ConversationDiagnostics(repository).exportSnapshot(metadataOnly,includeText=false,query=marker)
            assertTrue(Json.parseToJsonElement(metadataOnly.toString(Charsets.UTF_8.name())).jsonObject.getValue("messages").jsonArray.all {"text" !in it.jsonObject})
            val text=ByteArrayOutputStream()
            val plain=ConversationDiagnostics(repository).exportSnapshot(text,query=marker,format=ConversationExportFormat.TEXT)
            assertEquals(3,plain.messageCount);assertFalse(plain.truncated)
            assertTrue(text.toString(Charsets.UTF_8.name()).contains(raw))
        }
    }

    @Test fun encodedByteBudgetStopsBefore20MiBWithoutWholeOutputBuffer()=runBlocking {
        val marker="diagnostic-byte-cap-"+UUID.randomUUID()
        val raw=marker+"\n"+"\u0001".repeat(19_800)
        val rows=List(190){MessageRecord(id=UUID.randomUUID().toString(),role="user",text=raw,createdAt=FIXTURE_TIME+it)}
        withRows(rows){
            val output=CountingOutput()
            val result=ConversationDiagnostics(repository).exportSnapshot(output,query=marker)
            assertTrue(result.truncated);assertTrue(result.messageCount in 1 until rows.size)
            assertTrue(result.byteCount<=ConversationDiagnostics.MAX_BYTES)
            assertEquals(output.bytes,result.byteCount)
            assertTrue(output.lastWrite.endsWith("}"))
            assertTrue(output.lastWrite.contains("\"truncated\":true"))
            assertFalse(output.closed)
        }
    }

    @Test fun streamFailurePropagatesWithoutClosingCallerOrDeletingMessages()=runBlocking {
        val marker="diagnostic-output-failure-"+UUID.randomUUID()
        val row=MessageRecord(id=UUID.randomUUID().toString(),role="user",text=marker,createdAt=FIXTURE_TIME)
        withRows(listOf(row)){
            val output=object:OutputStream(){var closed=false;override fun write(b:Int){throw IOException("synthetic output failure")};override fun close(){closed=true}}
            var failed=false
            try{ConversationDiagnostics(repository).exportSnapshot(output,query=marker)}catch(_:IOException){failed=true}
            assertTrue(failed);assertFalse(output.closed)
            assertEquals(listOf(row.id),repository.messagePage(query=marker).messages.map {it.id})
        }
    }

    private suspend fun <T> withRows(rows:List<MessageRecord>,block:suspend()->T):T {
        val owned=mutableListOf<String>()
        try{rows.forEach {owned+=it.id;fixtureDao.message(it)};return block()}
        finally{withContext(NonCancellable){owned.forEach {fixtureDao.deleteMessage(it)}}}
    }
    private class CountingOutput:OutputStream(){
        var bytes=0L;var lastWrite="";var closed=false
        override fun write(b:Int){bytes++}
        override fun write(b:ByteArray,off:Int,len:Int){bytes+=len;lastWrite=String(b,off,len,Charsets.UTF_8)}
        override fun close(){closed=true}
    }
    companion object {private const val FIXTURE_TIME=1_793_330_000_000L}
}
