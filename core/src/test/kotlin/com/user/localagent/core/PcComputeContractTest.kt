package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class PcComputeContractTest {
    @Test fun explicitPrivateTlsEndpointIsAccepted(){for(host in listOf("10.1.2.3","172.16.2.3","192.168.1.2","127.0.0.1"))assertEquals(host,PcComputeContract.endpoint("https://$host:18434").host)}
    @Test fun arbitraryUploadsAndAmbiguousAddressesAreRejected(){
        for(url in listOf("http://192.168.1.2:18434","https://8.8.8.8:18434","https://example.com:18434","https://u:p@192.168.1.2:18434","https://192.168.1.2:18434/a","https://192.168.1.2:18434?q=secret","https://192.168.1.2:18434#x","https://010.1.2.3:18434","https://192.168.1.255:18434"))assertTrue(url,runCatching{PcComputeContract.endpoint(url)}.isFailure)
    }
    @Test fun selectedBackendsHaveDifferentQualityDefaults(){assertEquals(50,PcComputeContract.steps(true,null));assertEquals(8,PcComputeContract.steps(false,null))}
    @Test fun samplerBudgetsStayBoundedAndExplicit(){assertEquals(32,PcComputeContract.steps(true,32));assertEquals(4,PcComputeContract.steps(false,4));for(pair in listOf(true to 4,true to 51,false to 50,false to 0))assertTrue(runCatching{PcComputeContract.steps(pair.first,pair.second)}.isFailure)}
    @Test fun fastDefaultsAreExplicitAndDoNotLowerLegacyQualityBudgets(){
        val fast=PcImageModelCatalog.FAST_ID
        assertEquals(4,PcComputeContract.steps(true,null,pcModelId=fast))
        for(steps in listOf(4,8,50))assertEquals(steps,PcComputeContract.steps(true,steps,pcModelId=fast))
        for(steps in listOf(3,51))assertThrows(IllegalArgumentException::class.java){PcComputeContract.steps(true,steps,pcModelId=fast)}
        assertEquals(50,PcComputeContract.steps(true,null,pcModelId=PcImageModelCatalog.QUALITY_ID))
        for(id in listOf(PcImageModelCatalog.QUALITY_ID,PcImageModelCatalog.SDXL_ID))
            assertThrows(IllegalArgumentException::class.java){PcComputeContract.steps(true,4,pcModelId=id)}
        assertThrows(IllegalArgumentException::class.java){PcComputeContract.steps(true,4,pcModelId="unknown")}
    }
}
