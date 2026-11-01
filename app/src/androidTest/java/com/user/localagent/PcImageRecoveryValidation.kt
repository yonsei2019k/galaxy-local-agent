package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.PcImageRecoveryStore
import com.user.localagent.compute.PcImageRecoveryTicket
import com.user.localagent.core.PcImageModelCatalog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PcImageRecoveryValidation {
    @Test fun encryptedRestartAndStaleCleanupPreserveTheNewerJob(){
        assumeTrue("pcImageRecoveryApproved=true required",InstrumentationRegistry.getArguments().getString("pcImageRecoveryApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope="synthetic-recovery-${UUID.randomUUID()}"
        val store=PcImageRecoveryStore(context,scope)
        fun ticket()=PcImageRecoveryTicket(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"a".repeat(64),
            PcImageModelCatalog.QUALITY_ID,123,50,System.currentTimeMillis(),buildJsonObject{put("originalPrompt","SYNTHETIC_RECOVERY")})
        val first=ticket();val second=ticket()
        try{
            store.save(first);assertEquals(first,PcImageRecoveryStore(context,scope).read())
            assertTrue(runCatching{store.save(second)}.isFailure);assertEquals(first,store.read())
            store.clear(second.id);assertEquals(first,store.read())
            store.clear(first.id);store.save(second);store.clear(first.id)
            assertEquals(second,PcImageRecoveryStore(context,scope).read())
        }finally{store.clear(first.id);store.clear(second.id)}
        assertNull(store.read())
    }
}
