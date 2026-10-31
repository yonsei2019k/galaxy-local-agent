package com.user.localagent.connections

import org.junit.Assert.*
import org.junit.Test

class GoogleConsentRequestStoreTest {
    @Test fun recreatedActivityClaimsTheOriginalServiceAndScopeExactlyOnce(){
        val store=GoogleConsentRequestStore();val initial=store.begin("gmail",false)
        assertEquals(initial,store.peek(initial.token))
        val restored=store.claim(initial.token)!!
        assertEquals("gmail",restored.serviceId);assertFalse(restored.write)
        assertNull(store.claim(initial.token));assertTrue(store.wasConsumed(initial.token))
    }
    @Test fun missingProcessStateOrUnrelatedResultCannotAcceptOrCancelAnotherRequest(){
        val store=GoogleConsentRequestStore();val initial=store.begin("drive",true)
        assertNull(store.claim("not-the-request"));store.cancel("not-the-request")
        assertEquals(initial,store.peek(initial.token))
        assertNull(GoogleConsentRequestStore().claim(initial.token))
        assertEquals(initial,store.claim(initial.token))
    }
    @Test fun pendingRequestCannotBeOverwrittenAndCancellationAllowsFreshConsent(){
        val store=GoogleConsentRequestStore();val initial=store.begin("gmail",false)
        try{store.begin("calendar",true);fail("Another service must not overwrite pending binding")}catch(_:IllegalStateException){}
        assertEquals(initial,store.peek(initial.token));store.cancel(initial.token)
        assertNull(store.claim(initial.token));assertTrue(store.wasConsumed(initial.token))
        assertEquals("calendar",store.begin("calendar",true).serviceId)
    }
}
