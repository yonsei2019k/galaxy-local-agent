package com.user.localagent.ui

import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PendingExportFormatsTest {
    private fun fixture(block:suspend (PendingFileExportStore,java.io.File)->Unit)=runBlocking {
        val directory=Files.createTempDirectory("settings-export-test-").toFile()
        try{block(PendingFileExportStore(directory),directory)}finally{directory.deleteRecursively()}
    }
    @Test fun historyQueryCountsAndPartialFlagRemainBoundToOriginalSnapshot()=fixture{store,_->
        var count=3;var query="SYNTHETIC_FIRST_QUERY";var partial=true
        val request=store.prepare("history","json",details={PendingExportDetails(messageCount=count,truncated=partial,query=query)}){it.writeText("{\"messages\":[]}")}
        count=900;query="";partial=false
        val restored=store.request(request.token,"history","json")!!
        assertEquals(3,restored.details?.messageCount);assertEquals("SYNTHETIC_FIRST_QUERY",restored.details?.query);assertEquals(true,restored.details?.truncated)
        assertNotNull(store.claim(request.token,"history","json").request);assertTrue(store.claim(request.token,"history","json").duplicate)
    }
    @Test fun videoAllowsMoreThan20MiBWhileDocumentsAndHistoryStayAt20MiB()=fixture{store,_->
        val length=20L*1024*1024+1
        for(format in listOf("txt","json")){
            try{store.prepare(format,format){RandomAccessFile(it,"rw").use{file->file.setLength(length)}};fail("Document cap must remain 20MiB")}catch(_:IllegalStateException){}
            assertNull(store.activeToken(format))
        }
        val video=store.prepare("video","mp4",details={PendingExportDetails(durationMillis=1000,width=1280,height=720)}){RandomAccessFile(it,"rw").use{file->file.setLength(length)}}
        assertEquals(length,video.bytes);verifyPendingExportSource(video)
        store.cancel(video.token);assertFalse(video.file.exists())
        try{store.prepare("huge","mp4"){RandomAccessFile(it,"rw").use{file->file.setLength(256L*1024*1024+1)}};fail("Video cap must remain 256MiB")}catch(_:IllegalStateException){}
        assertNull(store.activeToken("huge"))
    }
    @Test fun activeClaimCannotBeReclaimedAfterConsumedTokenHistoryRotates()=fixture{store,_->
        val active=store.prepare("active","mp4"){it.writeText("Synthetic immutable bytes")}
        assertNotNull(store.claim(active.token,"active","mp4").request)
        repeat(33){index->val other=store.prepare("other-$index","json"){it.writeText("{}")};store.cancel(other.token)}
        val repeated=store.claim(active.token,"active","mp4")
        assertTrue(repeated.duplicate);assertNull(repeated.request);assertTrue(active.file.exists())
        store.finish(active.token);assertFalse(active.file.exists())
    }
    @Test fun expiredSnapshotIsRejectedAndRemovedEvenWhenNoOtherExportBegins()=runBlocking{
        val directory=Files.createTempDirectory("expired-export-test-").toFile();var time=0L
        try{
            val store=PendingFileExportStore(directory){time}
            val first=store.prepare("history","json"){it.writeText("{}")}
            time=31*60*1000L
            assertNull(store.request(first.token,"history","json"));assertFalse(first.file.exists())
            val second=store.prepare("video","mp4"){it.writeText("Synthetic bytes")}
            time+=31*60*1000L
            val claim=store.claim(second.token,"video","mp4")
            assertNull(claim.request);assertFalse(second.file.exists())
        }finally{directory.deleteRecursively()}
    }
    @Test fun cancellationAtPublicationBoundaryCleansAllocatedSnapshot()=fixture{store,directory->
        var producerJob:Job?=null
        val task=CoroutineScope(SupervisorJob()+Dispatchers.Default).async{
            store.prepare("history","json",details={producerJob!!.cancel();PendingExportDetails(messageCount=0,truncated=false)}){
                producerJob=currentCoroutineContext().job;it.writeText("{}")
            }
        }
        try{task.await();fail("Canceled publication must not return a snapshot")}catch(_:CancellationException){}
        assertNull(store.activeToken("history"));assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
}
