package com.user.localagent

import android.graphics.Bitmap
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
import java.util.UUID

/** Reads only the already-completed public fixture identified by the host. Never starts a fresh image. */
@RunWith(AndroidJUnit4::class)
class PcImageRecoveryDeliveryValidation {
    @Test fun completedOriginalJobDownloadsWithoutRecomputeThenAcknowledgesAfterDurableWrite()=runBlocking(Dispatchers.IO){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("pcRecoveryDeliveryApproved=true required",args.getString("pcRecoveryDeliveryApproved")=="true")
        val id=checkNotNull(args.getString("fixtureJobId"));val owner=checkNotNull(args.getString("fixtureOwner"))
        require(listOf(id,owner).all{UUID.fromString(it).toString()==it})
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        assertFalse("Owner image must remain untouched",app.imageStudio.state.value.running)
        val coordinator=withContext(Dispatchers.Main.immediate){app.runs}
        assertFalse("Owner agent work must remain untouched",coordinator.hasActiveRuns.value)
        val settings=PcComputeSettings.get(context);val selected=settings.state.value.imageModelId
        val config=checkNotNull(PcTransport(context).available())
        val transport=PcTransport(context)
        val before=transport.json(config,"/v1/images/$id",computeOwner=owner)
        assertEquals("completed",before.getValue("phase").jsonPrimitive.content)
        assertTrue(before.getValue("cleanup_complete").jsonPrimitive.boolean)
        val attempts=before.getValue("attempts").jsonPrimitive.int
        val scope="synthetic-phone-delivery-$owner";val store=PcImageRecoveryStore(context,scope)
        val prompt="A simple red ceramic cube on a white table, studio photograph."
        val ticket=PcImageRecoveryTicket(id,owner,PcImageRecoveryTicket.pair(config,settings.authorizationRevision),
            PcImageModelCatalog.SDXL_ID,424242,20,System.currentTimeMillis(),buildJsonObject{
                put("originalPrompt",prompt);put("effectivePrompt",prompt);put("negativePrompt","");put("mode","txt2img")
            })
        val client=RemoteImageClient(context,owner,scope,allowRecovery=true)
        val file=File(context.cacheDir,"recovery-fixture-${UUID.randomUUID()}.png")
        var created=false;var bitmap:Bitmap?=null
        try{
            store.save(ticket)
            val result=withTimeout(90_000){client.generate(config,prompt,"",424242,20,null,{},ticket)}
            bitmap=result.bitmap
            assertEquals("pc",result.computeLocation);assertEquals(PcImageModelCatalog.SDXL_ID,result.sourceModelId)
            assertEquals(1024,result.width);assertEquals(1024,result.height);assertEquals(424242L,result.seed)
            client.releaseCompute()
            val retained=transport.json(config,"/v1/images/$id",computeOwner=owner)
            assertEquals("completed",retained.getValue("phase").jsonPrimitive.content)
            assertEquals(attempts,retained.getValue("attempts").jsonPrimitive.int)
            assertNotNull(store.read())
            created=file.createNewFile();assertTrue(created)
            java.io.FileOutputStream(file).use {output->assertTrue(result.bitmap.compress(Bitmap.CompressFormat.PNG,100,output));output.fd.sync()}
            client.acknowledgeSavedFile(file)
            assertNull(store.read())
            val after=transport.json(config,"/v1/images/$id",computeOwner=owner)
            assertEquals("acknowledged",after.getValue("phase").jsonPrimitive.content)
            assertEquals(attempts,after.getValue("attempts").jsonPrimitive.int)
            assertEquals(selected,settings.state.value.imageModelId)
        }finally{bitmap?.recycle();store.clear(id);if(created)file.delete()}
    }
}
