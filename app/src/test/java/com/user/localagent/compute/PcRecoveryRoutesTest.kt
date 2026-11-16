package com.user.localagent.compute

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PcRecoveryRoutesTest {
    // Synthetic route labels: no live pairing or network request is used.
    private val original=PcConnection("fixture","old-lan","fixture-token","fixture-pin",overlayEndpoint="overlay")

    @Test fun samePairRouteChangeRetainsOriginalAuthorityAndPrefersSavedRoute(){
        val saved=original.copy(endpoint="new-lan",overlayEndpoint="new-overlay")
        val routes=originalPcRoutes(original,saved,"old-lan")
        assertEquals(listOf("new-lan","new-overlay","overlay"),routes.map{it.endpoint})
        routes.forEach{assertTrue(original.samePair(it));assertEquals(original.overlayEndpoint,it.overlayEndpoint)}
    }

    @Test fun replacementPairNeverContributesRoutes(){
        for(replacement in listOf(original.copy(serverId="other"),original.copy(token="other"),original.copy(certificateSha256="other"))){
            val saved=replacement.copy(endpoint="foreign-lan",overlayEndpoint="foreign-overlay")
            assertEquals(listOf("overlay"),originalPcRoutes(original,saved,"old-lan").map{it.endpoint})
        }
    }

    @Test fun duplicateAndFailedRoutesAreRemovedAndCandidatesBounded(){
        assertEquals(listOf("overlay"),originalPcRoutes(original,original,"old-lan").map{it.endpoint})
        assertEquals(emptyList<PcConnection>(),originalPcRoutes(original.copy(overlayEndpoint=null),null,"old-lan"))
        val routes=originalPcRoutes(original,original.copy(endpoint="new-lan",overlayEndpoint="new-overlay"),"absent")
        assertEquals(4,routes.size)
    }

    @Test fun ioFailureFallsThroughOnlyKnownReadProbes()=runBlocking {
        val seen=mutableListOf<String>()
        val found=findOriginalPcRoute(original,original.copy(endpoint="new-lan"),"old-lan"){
            seen+=it.endpoint
            if(it.endpoint=="new-lan")throw IOException("fixture")
            true
        }
        assertEquals(listOf("new-lan","overlay"),seen)
        assertEquals("overlay",found?.endpoint)
    }

    @Test fun cancellationDoesNotProbeNextRoute()=runBlocking {
        var count=0
        try{
            findOriginalPcRoute(original,original.copy(endpoint="new-lan"),"old-lan"){
                count++;throw CancellationException("fixture")
            }
            fail("cancellation must propagate")
        }catch(_:CancellationException){}
        assertEquals(1,count)
    }

    @Test fun malformedProtocolIsNotTreatedAsReachable()=runBlocking {
        var count=0
        try{
            findOriginalPcRoute(original,original.copy(endpoint="new-lan"),"old-lan"){
                count++;throw IllegalArgumentException("fixture")
            }
            fail("invalid protocol must fail closed")
        }catch(_:IllegalArgumentException){}
        assertEquals(1,count)
    }

    @Test fun unsuccessfulChecksDoNotInventARecoveredConnection()=runBlocking {
        val seen=mutableListOf<String>()
        assertNull(findOriginalPcRoute(original,null,"old-lan"){seen+=it.endpoint;false})
        assertEquals(listOf("overlay"),seen)
    }
}
