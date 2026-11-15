package com.user.localagent.compute

import com.user.localagent.core.PcImageModelCatalog
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PcImageRecoveryTicketTest {
    private fun ticket()=PcImageRecoveryTicket("0d93349d-087b-4cd4-830f-97fa67701309","74cd6aa0-f264-45c2-b41e-ad077f429176",
        "a".repeat(64),PcImageModelCatalog.QUALITY_ID,123,50,1_000_000,buildJsonObject{put("originalPrompt","synthetic fixture")})
    @Test fun restartRetainsExactRequestOwnerModelAndSeedWithoutExtendingExpiry(){
        val saved=ticket();assertEquals(saved,PcImageRecoveryTicket.parse(saved.json()))
        assertTrue(saved.validAt(saved.createdAt+23*60*60_000L))
        assertFalse(saved.validAt(saved.createdAt+24*60*60_000L))
        assertFalse(saved.validAt(saved.createdAt-300_001))
        assertThrows(IllegalArgumentException::class.java){saved.copy(owner="foreign")}
        assertThrows(IllegalArgumentException::class.java){saved.copy(model="untrusted-model")}
    }
    @Test fun fourStepRecoveryRequiresTheExplicitFastModelAndRetainsOldTickets(){
        val legacy=ticket()
        val fast=legacy.copy(model=PcImageModelCatalog.FAST_ID,steps=4)
        assertEquals(fast,PcImageRecoveryTicket.parse(fast.json()))
        assertEquals(legacy,PcImageRecoveryTicket.parse(legacy.json()))
        assertThrows(IllegalArgumentException::class.java){legacy.copy(steps=4)}
        assertThrows(IllegalArgumentException::class.java){fast.copy(steps=3)}
    }
    @Test fun rePairOrPermissionRevisionChangeCannotReuseAnOldTicket(){
        val config=PcConnection("synthetic-server","synthetic-endpoint","synthetic-token","b".repeat(64))
        assertNotEquals(PcImageRecoveryTicket.pair(config,1),PcImageRecoveryTicket.pair(config,2))
        assertNotEquals(PcImageRecoveryTicket.pair(config,1),PcImageRecoveryTicket.pair(config.copy(token="replacement"),1))
        assertNotEquals(PcImageRecoveryTicket.pair(config,1),PcImageRecoveryTicket.pair(config.copy(certificateSha256="c".repeat(64)),1))
    }
}
