package com.user.localagent.web

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PairedPublicSearchTest {
    @Test fun matchingListingRetainsObservedMerchantPrice() {
        val url = "https://shop.example.org/sample"
        fun result(row: JsonObject) = buildJsonObject {
            put("items", JsonArray(listOf(row))); put("success", true)
        }
        val naver = result(buildJsonObject { put("url", url); put("listingPriceKrw", "9000"); put("price", JsonNull) })
        val merchant = result(buildJsonObject { put("url", url); put("price", "12000"); put("shipping", "3000") })
        val comparison = PairedPublicSearch.combinedShopping(naver, merchant)
        val row = comparison.getValue("items").jsonArray.single().jsonObject
        assertEquals("12000", row.getValue("price").jsonPrimitive.content)
        assertEquals("9000", row.getValue("naverListing").jsonObject.getValue("listingPriceKrw").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), comparison["partial"])
    }
}
