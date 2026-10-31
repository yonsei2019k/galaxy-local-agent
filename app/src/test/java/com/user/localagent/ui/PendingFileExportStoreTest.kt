package com.user.localagent.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PendingFileExportStoreTest {
    private fun fixture(block:suspend (PendingFileExportStore,java.io.File)->Unit)=runBlocking {
        val directory=Files.createTempDirectory("pending-export-test-").toFile()
        try{block(PendingFileExportStore(directory),directory)}finally{directory.deleteRecursively()}
    }
    @Test fun savedTokenRestoresOriginalSnapshotInsteadOfTheCurrentEditorValue()=fixture {store,_->
        var editor="Synthetic original";val request=store.prepare("office","docx"){it.writeText(editor)}
        editor="Synthetic changed editor"
        val restored=store.claim(request.token,"office","docx").request!!
        assertEquals("Synthetic original",restored.file.readText());assertNotEquals(editor,restored.file.readText())
        verifyPendingExportSource(restored)
    }
    @Test fun oneSnapshotCannotBeOverwrittenOrWrittenTwice()=fixture {store,_->
        val request=store.prepare("basic","txt"){it.writeText("Synthetic snapshot")}
        try{store.prepare("basic","csv"){it.writeText("Other")};fail("Pending picker must block overwrite")}catch(_:IllegalStateException){}
        assertNotNull(store.claim(request.token,"basic","txt").request)
        assertTrue(store.claim(request.token,"basic","txt").duplicate)
        store.cancel(request.token);assertTrue(request.file.exists()) // claimed writer owns cleanup
        store.finish(request.token);assertFalse(request.file.exists());assertNull(store.activeToken("basic"))
    }
    @Test fun missingProcessRequestAndWrongFormatNeverYieldWritableSnapshot()=fixture {store,directory->
        val request=store.prepare("basic","pdf"){it.writeText("Synthetic PDF stand-in")}
        assertNull(store.claim(request.token,"basic","txt").request)
        assertNull(store.claim(request.token,"office","pdf").request)
        assertEquals(request.token,store.activeToken("basic"))
        val restarted=PendingFileExportStore(directory)
        assertNull(restarted.claim(request.token,"basic","pdf").request)
        assertFalse(request.file.exists())
    }
    @Test fun alteredOrMissingSnapshotFailsBeforeAnyDestinationIsNeeded()=fixture {store,_->
        val request=store.prepare("basic","txt"){it.writeText("Synthetic old")}
        request.file.writeText("Synthetic new")
        try{verifyPendingExportSource(request);fail("Same-length altered bytes must fail")}catch(_:IllegalStateException){}
        request.file.delete()
        try{verifyPendingExportSource(request);fail("Missing file must fail")}catch(_:IllegalStateException){}
    }
    @Test fun cancellationAndProducerFailureReleaseOnlyTheirOwnFiles()=fixture {store,directory->
        val other=java.io.File(directory,"unrelated.txt").apply{writeText("Keep")}
        try{store.prepare("basic","txt"){it.writeText("Partial");throw CancellationException("synthetic")};fail()}catch(_:CancellationException){}
        assertNull(store.activeToken("basic"));assertEquals(listOf("unrelated.txt"),directory.list()!!.toList())
        val request=store.prepare("basic","csv"){it.writeText("a,b")}
        store.cancel(request.token)
        assertTrue(other.isFile);assertFalse(request.file.exists());assertTrue(store.claim(request.token,"basic","csv").duplicate)
    }
}
