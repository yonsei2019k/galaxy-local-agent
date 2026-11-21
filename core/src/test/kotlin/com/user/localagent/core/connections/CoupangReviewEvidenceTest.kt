package com.user.localagent.core.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoupangReviewEvidenceTest {
    private val product = "https://www.coupang.com/vp/products/101"

    @Test fun exactVariantRequiresBothOptionIdentifiers() {
        val variant = CoupangReviewEvidence.variant("$product?vendorItemId=301&itemId=201&source=order")
        assertEquals("$product?itemId=201&vendorItemId=301", variant?.url)
        assertNotEquals(variant, CoupangReviewEvidence.variant("$product?itemId=202&vendorItemId=302"))
        assertNull(CoupangReviewEvidence.variant(product))
        assertNull(CoupangReviewEvidence.variant("$product?itemId=201"))
        assertNull(CoupangReviewEvidence.variant("$product?itemId=201&itemId=202&vendorItemId=301"))
    }

    @Test fun onlyExactOfficialListingRoutesAreReadable() {
        val evidence = CoupangReviewEvidence
        assertEquals(CoupangReviewEvidence.Page.ORDERS, evidence.page(evidence.ORDERS))
        assertEquals(CoupangReviewEvidence.Page.REVIEWABLE, evidence.page(evidence.REVIEWABLE + "?page=2"))
        assertNull(evidence.page("https://login.coupang.com/login/login.pang"))
        assertNull(evidence.page("https://my.coupang.com/productreview/reviewable/foreign"))
        assertNull(evidence.page("https://my.coupang.com:444/productreview/reviewable"))
    }

    @Test fun crossOriginCredentialsAndActionUrlsCannotBecomeEvidence() {
        val evidence = CoupangReviewEvidence
        assertFalse(evidence.navigationAllowed("https://www.coupang.com.evil.test/vp/products/101"))
        assertFalse(evidence.navigationAllowed("https://abc@www.coupang.com/vp/products/101"))
        assertFalse(evidence.navigationAllowed("intent://www.coupang.com/vp/products/101"))
        assertNull(evidence.variant("$product/checkout?itemId=201&vendorItemId=301"))
        assertNull(evidence.variant("$product?itemId=%32%30%31&vendorItemId=301"))
    }
}
