package com.user.localagent.core.connections

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConsumerAppCatalogTest {
    private fun call(name:String,vararg args:Pair<String,String>)=ToolCall("consumer-test",name,buildJsonObject {args.forEach {(key,value)->put(key,value)}})
    @Test fun allNineFixedAppToolsAreRegistered() {
        assertEquals(9,ConsumerAppCatalog.names.size)
        assertTrue(ToolRegistry.definitions.keys.containsAll(ConsumerAppCatalog.names))
        assertEquals(ConsumerAppCatalog.names,ConsumerAppCatalog.appForTool.keys)
        assertTrue(ConsumerAppCatalog.definitions.all {it.risk==Risk.LOW})
    }
    @Test fun launchOnlyUsesFourKnownPackages() {
        val targets=listOf("open_tmap","open_netflix","open_coupang","open_coupang_eats").map {ConsumerAppCatalog.target(call(it))}
        assertEquals(ConsumerApp.entries.toSet(),targets.map {it.app}.toSet())
        assertTrue(targets.all {it.uri==null})
    }
    @Test fun searchTextCannotBecomeIntentParametersOrAnotherHost() {
        val result=ConsumerAppCatalog.target(call("search_coupang","query" to "물 & next=https://invalid.example/ #fragment"))
        assertTrue(result.uri!!.startsWith("https://www.coupang.com/np/search?q="))
        assertFalse(result.uri!!.contains("&next="));assertFalse(result.uri!!.contains("#fragment"))
        assertTrue(result.uri!!.contains("%26"));assertTrue(result.uri!!.contains("%23"))
    }
    @Test fun tmapUsesDocumentedGeoEncoding() {
        val target=ConsumerAppCatalog.target(call("open_tmap","query" to "서울역 & 카페"))
        assertTrue(target.uri!!.startsWith("geo:0,0?q="));assertTrue(target.uri!!.contains("%26"))
        assertEquals(ConsumerApp.TMAP,target.app)
    }
    @Test fun coordinatesMustBeFiniteAndWithinTheEarth() {
        for((lat,lon) in listOf("NaN" to "1","91" to "1","1" to "181","1" to "Infinity")){
            try{ConsumerAppCatalog.target(call("open_tmap_destination","name" to "검사","latitude" to lat,"longitude" to lon));fail("Invalid coordinate accepted")}catch(_:IllegalArgumentException){}
        }
        assertTrue(ConsumerAppCatalog.target(call("open_tmap_destination","name" to "서울역","latitude" to "37.5547","longitude" to "126.9707")).uri!!.contains("37.5547,126.9707"))
    }
    @Test fun titleAndProductIdsCannotInjectPathsOrActions() {
        for(name in listOf("open_netflix_title","open_coupang_product")){
            for(id in listOf("../account","123?buy=true","https://invalid.example","1/checkout")){
                try{ConsumerAppCatalog.target(call(name,"id" to id));fail("Invalid ID accepted")}catch(_:IllegalArgumentException){}
            }
        }
        assertEquals("https://www.netflix.com/title/80057281",ConsumerAppCatalog.target(call("open_netflix_title","id" to "80057281")).uri)
    }
    @Test fun installedHandlerDoesNotClaimAccountAuthenticationOrOfflineOperation() {
        val empty=ToolCapabilityCatalog.snapshot().first {it.id=="open_netflix"}
        assertEquals(CapabilityAvailability.UNVALIDATED,empty.availability)
        val installed=ToolCapabilityCatalog.snapshot(CapabilityEnvironment(installedHandlers=setOf("consumer:netflix"))).first {it.id=="open_netflix"}
        assertEquals(CapabilityAvailability.INSTALLED,installed.availability);assertFalse(installed.offline)
    }
    @Test fun arbitraryCodeUrlAndAccountArgumentsAreRejected() {
        for(name in ConsumerAppCatalog.names){
            assertFalse(ToolRegistry.definitions.getValue(name).arguments.keys.any {it in setOf("url","token","password","command","code")})
        }
        try{ConsumerAppCatalog.target(call("open_url","url" to "https://invalid.example"));fail("Arbitrary tool accepted")}catch(_:IllegalArgumentException){}
    }
}
