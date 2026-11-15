package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.*
import com.user.localagent.core.PcImageModelCatalog
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FastImageUpdateValidation {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun encryptedSyntheticRecoveryCanBeArchivedAndRestored() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fastImageUpdateApproved")=="true")
        val store=PcImageRecoveryStore(context,"synthetic-fast-archive-${UUID.randomUUID()}")
        val ticket=PcImageRecoveryTicket(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"a".repeat(64),
            PcImageModelCatalog.FAST_ID,42,4,System.currentTimeMillis(),buildJsonObject{put("originalPrompt","public synthetic cube")})
        try {
            store.save(ticket);assertTrue(store.archiveActive());assertNull(store.read())
            assertTrue(store.archived().single()==ticket)
            assertTrue(store.restoreMostRecent()==ticket);assertTrue(store.read()==ticket)
            assertTrue(store.archived().isEmpty())
        }finally{store.clear(ticket.id)}
    }
    /** Explicit host action approved by owner: preserve old task and select the new local fast model. */
    @Test fun preservePreviousImageAndSelectFastWithoutResumingIt()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("preserveImageUpdateApproved")=="true")
        val app=(context.applicationContext as LocalAgentApplication).container
        assertFalse(app.imageStudio.state.value.running)
        assertFalse(withContext(Dispatchers.Main.immediate){app.runs.hasActiveRuns.value})
        val store=PcImageRecoveryStore(context,"image-studio")
        val original=store.read()?:store.archived().firstOrNull{it.model==PcImageModelCatalog.QUALITY_ID}
            ?:error("Original preserved task was not found; no data changed")
        fun digest(raw:String)=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
        val before=digest(original.json())
        if(store.read()!=null){
            app.imageStudio.archivePcRecovery()
            withTimeout(10_000){while(store.read()!=null)delay(50)}
        }
        assertNull(store.read())
        assertTrue("Original recovery changed",store.archived().any{it.id==original.id&&digest(it.json())==before})
        val settings=PcComputeSettings.get(context)
        val revision=settings.authorizationRevision
        settings.imageModel(PcImageModelCatalog.FAST_ID)
        assertEquals(PcImageModelCatalog.FAST_ID,settings.state.value.imageModelId)
        assertEquals(revision,settings.authorizationRevision)
        app.imageStudio.refreshPcRecovery()
        withTimeout(10_000){while(app.imageStudio.state.value.archivedPcCount<1)delay(50)}
        assertFalse(app.imageStudio.state.value.running)
        File(context.getExternalFilesDir(null),"benchmarks/fast-image-update-preservation.json").apply{parentFile!!.mkdirs()}.writeText(buildJsonObject{
            put("passed",true);put("originalRecoveryPreserved",true);put("originalModelUnchanged",original.model==PcImageModelCatalog.QUALITY_ID)
            put("originalSteps",original.steps);put("archivedCount",store.archived().size);put("selectedModel",settings.state.value.imageModelId)
            put("automaticResume",false);put("pairingAuthorizationUnchanged",true)
        }.toString())
    }
}
