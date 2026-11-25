package com.user.localagent.core.connections

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConsumerAppCatalogTest {
    private fun call(name:String,vararg args:Pair<String,String>)=ToolCall("consumer-test",name,buildJsonObject {args.forEach {(key,value)->put(key,value)}})
    @Test fun allFixedAppToolsAreRegistered() {
        assertEquals(13,ConsumerAppCatalog.names.size)
        assertTrue(ToolRegistry.definitions.keys.containsAll(ConsumerAppCatalog.names))
        assertEquals(ConsumerAppCatalog.names,ConsumerAppCatalog.appForTool.keys)
        assertTrue(ConsumerAppCatalog.definitions.all {it.risk==Risk.LOW})
    }
    @Test fun merchantHandoffUsesExactReadOnlyListings() {
        val listings = mapOf(
            "https://www.coupang.com/vp/products/12?itemId=34&vendorItemId=56" to "com.coupang.mobile",
            "https://booking.naver.com/booking/3/bizes/123/items/45" to "com.nhn.android.nmap",
            "https://www.yeogi.com/domestic-accommodations/4279" to "kr.goodchoice.abouthere",
            "https://nol.yanolja.com/stay/domestic/3012159?ratePlanId=769347&roomTypeId=667376" to "com.cultsotry.yanolja.nativeapp"
        )
        listings.forEach { (link, app) -> assertEquals(app, CommerceListingLinks.packageFor(link)) }
        for (link in listOf(
            "https://www.yeogi.com.evil.example/domestic-accommodations/4279",
            "https://user@www.yeogi.com/domestic-accommodations/4279",
            "https://nol.yanolja.com/stay/domestic/123?roomTypeId=1&roomTypeId=2",
            "https://nol.yanolja.com/stay/domestic/123?next=https://example.com",
            "https://www.coupang.com/vp/products/12/checkout",
            "https://www.coupang.com/vp/products/12%2fcheckout"
        )) assertNull(link, CommerceListingLinks.packageFor(link))
    }
    @Test fun serviceHomeLaunchPreservesMerchantAndOrderLinks() {
        val homes = mapOf(
            "https://www.baemin.com/" to "com.sampleapp",
            "https://www.yogiyo.co.kr" to "com.fineapp.yogiyo",
            "https://www.kakaomobility.com/kakaot/index.html" to "com.kakao.taxi",
            "https://app.catchtable.co.kr/" to "co.kr.catchtable.android.catchtable_app",
            "https://www.tabling.co.kr/" to "com.mealant.tabling",
            "https://map.naver.com/" to "com.nhn.android.nmap"
        )
        homes.forEach { (link, app) ->
            assertEquals(app, CommerceListingLinks.homePackage(link))
            assertNull(CommerceListingLinks.homePackage(link + "?order=1"))
        }
        val restaurant = "https://app.catchtable.co.kr/ct/shop/legume.seoul"
        val table = "https://www.tabling.co.kr/place/677cc51766de5f06987262ef"
        assertEquals("com.nhn.android.nmap", CommerceListingLinks.packageFor("https://map.naver.com/p/entry/place/36161110"))
        assertNull(CommerceListingLinks.homePackage(restaurant))
        assertEquals("co.kr.catchtable.android.catchtable_app", CommerceListingLinks.packageFor(restaurant))
        assertEquals("com.mealant.tabling", CommerceListingLinks.packageFor(table))
        assertNull(CommerceListingLinks.packageFor(restaurant + "/confirm"))
        assertNull(CommerceListingLinks.packageFor(table + "?order=1"))
    }
    @Test fun purchaseComparisonRetainsExactSellerOption() {
        val tool = "open_coupang_product"
        val pair = call(tool, "id" to "123", "itemId" to "456", "vendorItemId" to "789")
        assertEquals("https://www.coupang.com/vp/products/123?itemId=456&vendorItemId=789", ConsumerAppCatalog.target(pair).uri)
        assertEquals("https://www.coupang.com/vp/products/123", ConsumerAppCatalog.target(call(tool, "id" to "123")).uri)
        assertTrue(runCatching { ConsumerAppCatalog.target(call(tool, "id" to "123","itemId" to "456")) }.isFailure)
        assertTrue(runCatching { ConsumerAppCatalog.target(call(tool,"id" to "123","itemId" to "4&buy=1","vendorItemId" to "789")) }.isFailure)
    }
    @Test fun launchOnlyUsesFourKnownPackages() {
        val targets=listOf("open_tmap","open_netflix","open_coupang","open_coupang_eats").map {ConsumerAppCatalog.target(call(it))}
        assertEquals(setOf(ConsumerApp.TMAP, ConsumerApp.NETFLIX, ConsumerApp.COUPANG, ConsumerApp.EATS), targets.map {it.app}.toSet())
        assertTrue(targets.all {it.uri==null})
    }
    @Test fun railLinksKeepPrivateInputsOnThePhone() {
        val targets = mapOf(
            "open_korail_booking" to (ConsumerApp.KORAIL to "https://www.korail.com/ticket/main"),
            "open_korail_guest_receipt" to (ConsumerApp.KORAIL to "https://www.korail.com/ticket/reserve/nonmember/receipt"),
            "open_srt_guest_receipt" to (ConsumerApp.SRT to "https://eticket.srail.kr/hpg/hma/04/selectReceiptForm.do?pageId=TK0104050000"),
        )
        for ((name, expected) in targets) {
            assertEquals(ConsumerTarget(expected.first, expected.second), ConsumerAppCatalog.target(call(name)))
            assertTrue(PreparedHandoff.opensScreenOnly(name))
            val available = ToolCapabilityCatalog.snapshot(CapabilityEnvironment(installedHandlers=setOf("browser"))).single { it.id == name }
            assertEquals(CapabilityAvailability.INSTALLED, available.availability)
            assertFalse(available.offline)
            assertTrue(runCatching { ConsumerAppCatalog.target(call(name, "ticketNumber" to "123")) }.isFailure)
        }
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
