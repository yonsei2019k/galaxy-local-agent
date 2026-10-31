package com.user.localagent.imports

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.data.LocalDatabase
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Gated synthetic encrypted-DB tests; never opens owner records or invokes a model/provider. */
@RunWith(AndroidJUnit4::class)
class ConversationImportCleanupValidation {
    @Test fun cancellationCapturesCommittedBodyHashAndCleansInNonCancellableContext()=runBlocking(Dispatchers.IO){fixture{context,root->
        val thread=root.chat.createThread();val scoped=root.forThread(thread.id)
        val witness=scoped.saveNote("Synthetic witness","SYNTHETIC_KEEP",id=UUID.randomUUID().toString())
        var calls=0;var expected:List<IncompleteConversationNote> = emptyList()
        val importer=ConversationImport(context,scoped){run,items->
            calls++;expected=items;assertTrue(currentCoroutineContext().isActive)
            assertEquals(1,items.size)
            items.forEach {assertEquals(it.bodySha256,ConversationImportCleanup.sha256(checkNotNull(scoped.getNote(it.noteId)).body))}
            scoped.discardIncompleteConversationNotes(run,items)
        }
        coroutineScope {
            lateinit var worker:Job
            worker=launch(start=CoroutineStart.LAZY){importer.importText("SYNTHETIC_MESSAGE ".repeat(1000)){_,_->worker.cancel()}}
            worker.start();worker.join();assertTrue(worker.isCancelled)
        }
        assertEquals(1,calls);assertEquals(1,importer.lastCleanupResult?.deleted)
        assertTrue(importer.lastCleanupResult?.verified==true)
        expected.forEach {assertNull(scoped.getNote(it.noteId))}
        assertEquals("SYNTHETIC_KEEP",scoped.getNote(witness.id)?.body)
    }}

    @Test fun failingProvidedCleanupNeverFallsBackToOrdinaryRepositoryDeletion()=runBlocking(Dispatchers.IO){fixture{context,root->
        val thread=root.chat.createThread();val scoped=root.forThread(thread.id)
        var capturedRun="";var captured:List<IncompleteConversationNote> = emptyList()
        val importer=ConversationImport(context,scoped){run,items->capturedRun=run;captured=items;error("SYNTHETIC_CLEANUP_FAILURE")}
        coroutineScope {
            lateinit var worker:Job
            worker=launch(start=CoroutineStart.LAZY){importer.importText("SYNTHETIC_MESSAGE ".repeat(1000)){_,_->worker.cancel()}}
            worker.start();worker.join()
        }
        assertEquals(1,captured.size);assertEquals(1,importer.lastCleanupResult?.retainedUnverified)
        assertNotNull(scoped.getNote(captured.single().noteId))
        assertEquals(1,scoped.discardIncompleteConversationNotes(capturedRun,captured).deleted)
    }}

    @Test fun scopeMoveStopsImportAndCapturedCleanupCanStillRemoveOnlyItsCommittedOutput()=runBlocking(Dispatchers.IO){fixture{context,root->
        val a=root.chat.createProject("Synthetic import A");val b=root.chat.createProject("Synthetic import B")
        val thread=root.chat.createThread(a.id);val scoped=root.forThread(thread.id)
        val witness=scoped.saveNote("Synthetic witness","SYNTHETIC_KEEP")
        var calls=0
        val importer=ConversationImport(context,scoped){run,items->calls++;scoped.discardIncompleteConversationNotes(run,items)}
        assertTrue(runCatching {importer.importText("SYNTHETIC_MESSAGE ".repeat(1000)){index,_->
            if(index==1)runBlocking {root.chat.moveThread(thread.id,b.id)}
        }}.isFailure)
        assertEquals(1,calls);assertEquals(1,importer.lastCleanupResult?.deleted)
        assertEquals("SYNTHETIC_KEEP",root.forThread(root.chat.createThread(a.id).id).getNote(witness.id)?.body)
    }}

    @Test fun hostRollbackGuardsThreadProjectEditedBodyProtocolAndManifestWhileLegacyConstructorStillWorks()=runBlocking(Dispatchers.IO){fixture{context,root->
        val a=root.chat.createProject("Synthetic import A");val b=root.chat.createProject("Synthetic import B")
        val own=root.forThread(root.chat.createThread(a.id).id)
        val sameProject=root.forThread(root.chat.createThread(a.id).id)
        val otherProject=root.forThread(root.chat.createThread(b.id).id)
        val run=UUID.randomUUID().toString()
        suspend fun save(repo:LocalRepository,body:String,collection:String="imported-chats"):IncompleteConversationNote {
            val id=UUID.randomUUID().toString();repo.saveNote("Synthetic import output",body,collection,id)
            return IncompleteConversationNote(id,ConversationImportCleanup.sha256(body))
        }
        val raw=protocolBody(run,false);val ownChunk=save(own,raw);val ownManifest=save(own,protocolBody(run,true))
        val same=save(sameProject,raw);val other=save(otherProject,raw)
        val changed=save(own,raw);own.saveNote("Synthetic edit",raw+" edited","imported-chats",changed.noteId)
        val wrongCollection=save(own,raw,"default")
        val missing=IncompleteConversationNote(UUID.randomUUID().toString(),"a".repeat(64))
        val result=own.discardIncompleteConversationNotes(run,listOf(ownChunk,ownManifest,same,other,changed,wrongCollection,missing))
        assertEquals(2,result.deleted);assertEquals(1,result.missing)
        assertEquals(2,result.retainedChanged);assertEquals(2,result.retainedUnverified)
        assertNotNull(sameProject.getNote(same.noteId));assertNotNull(otherProject.getNote(other.noteId))
        assertNotNull(own.getNote(changed.noteId));assertNotNull(own.getNote(wrongCollection.noteId))
        val legacy=ConversationImport(context,own)
        val first=legacy.importText("SYNTHETIC_NORMAL_IMPORT")
        assertFalse(first.duplicate);assertEquals(2,first.noteIds.size)
        assertTrue(legacy.importText("SYNTHETIC_NORMAL_IMPORT").duplicate)
    }}

    @Test fun requestedOlderDescriptorIsNotHiddenByTheHundredItemCatalogLimit()=runBlocking(Dispatchers.IO){fixture{context,root->
        val scoped=root.forThread(root.chat.createThread().id)
        val importer=ConversationImport(context,scoped)
        val oldest=importer.importText("SYNTHETIC_OLDEST_SELECTED_IMPORT")
        delay(5)
        repeat(100){importer.importText("SYNTHETIC_NEWER_IMPORT_$it")}
        assertFalse(importer.catalog(100).any {it.contentSha256==oldest.contentSha256})
        val descriptor=checkNotNull(importer.descriptor(oldest.contentSha256.uppercase(java.util.Locale.ROOT)))
        assertEquals(oldest.contentSha256,descriptor.contentSha256)
        assertEquals(oldest.chunkCount,descriptor.chunkCount);assertEquals(oldest.messageCount,descriptor.messageCount)
        assertNull(importer.descriptor("b".repeat(64)))
        assertTrue(runCatching {importer.descriptor("not-a-hash")}.isFailure)
        importer.deleteImport(oldest.contentSha256)
        assertNull(importer.descriptor(oldest.contentSha256))
    }}

    @Test fun genericNoteSearchCannotExposeCachedSmsAnalysisOrRecordingManifests()=runBlocking(Dispatchers.IO){fixture{_,root->
        val scoped=root.forThread(root.chat.createThread().id)
        val marker="SYNTHETIC_SEARCH_${UUID.randomUUID()}"
        val visible=scoped.saveNote(marker,"SYNTHETIC_PUBLIC_NOTE","default")
        val hidden=listOf("private-analysis-sources","private-analysis","recording-transcription-manifests")
        delay(5)
        repeat(60){scoped.saveNote(marker,"SYNTHETIC_PRIVATE_HOST_RECORD",hidden[it%hidden.size])}
        assertEquals(listOf(visible.id),scoped.searchUserNotes(marker).map {it.id})
        assertEquals(listOf(visible.id),scoped.searchUserNotes(marker,"default").map {it.id})
        hidden.forEach {assertTrue(scoped.searchUserNotes(marker,it).isEmpty())}
    }}

    private fun protocolBody(run:String,manifest:Boolean):String=buildJsonObject {
        put("type","conversation-import-v1");put("kind",if(manifest)"manifest"else"chunk")
        put("sha256","a".repeat(64));put("run",run);put("chunks",1);if(!manifest)put("chunk",1)
        put("format","plain-text");put("encoding","UTF-8");put("recognized_messages",0)
        put("first_timestamp",JsonNull);put("last_timestamp",JsonNull);put("scope","user_supplied_single_file_only")
    }.toString()+if(manifest)""else"\nSYNTHETIC_ONLY"

    private suspend fun fixture(block:suspend(Context,LocalRepository)->Unit) {
        assumeTrue("conversationImportCleanupApproved=true required",InstrumentationRegistry.getArguments().getString("conversationImportCleanupApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="conversation-import-cleanup-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)};System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {block(context,LocalRepository(db))}finally {db.close();key.fill(0);context.deleteDatabase(name)}
    }
}
