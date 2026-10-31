package com.user.localagent.compute

import org.junit.Assert.*
import org.junit.Test

class ImageComputePairPinTest {
    @Test fun originalImagePermissionDoesNotDependOnAutomaticTextPreference() {
        val pin=ImageComputePairPin("public-server","a".repeat(64),"b".repeat(64),true,7L)
        assertTrue(pin.permits("public-server","a".repeat(64),"b".repeat(64),true,7L))
        assertFalse(pin.permits("public-server","a".repeat(64),"b".repeat(64),false,7L))
    }
    @Test fun rePairingServerCertificateOrTokenCannotRetargetAnExistingImage() {
        val pin=ImageComputePairPin("public-server","a".repeat(64),"b".repeat(64),true,7L)
        assertFalse(pin.permits("new-public-server","a".repeat(64),"b".repeat(64),true,7L))
        assertFalse(pin.permits("public-server","c".repeat(64),"b".repeat(64),true,7L))
        assertFalse(pin.permits("public-server","a".repeat(64),"c".repeat(64),true,7L))
        assertFalse(pin.permits(null,null,null,false,7L))
    }
    @Test fun aNewPermissionOrPairCannotUpgradeAnAlreadyCapturedRequest() {
        val forbidden=ImageComputePairPin("public-server","a".repeat(64),"b".repeat(64),false,7L)
        assertFalse(forbidden.permits("public-server","a".repeat(64),"b".repeat(64),true,7L))
        val unpaired=ImageComputePairPin(null,null,null,false,7L)
        assertFalse(unpaired.permits("public-server","a".repeat(64),"b".repeat(64),true,7L))
    }
    @Test fun diagnosticsNeverContainCapturedCredentials() {
        val token="b".repeat(64);val pin=ImageComputePairPin("public-server","a".repeat(64),token,true,7L)
        assertFalse(pin.toString().contains(token));assertFalse(pin.toString().contains("public-server"))
    }
    @Test fun revocationAndRegrantWithIdenticalCredentialsCannotReviveTheOldRequest() {
        val pin=ImageComputePairPin("public-server","a".repeat(64),"b".repeat(64),true,7L)
        assertTrue(pin.permits("public-server","a".repeat(64),"b".repeat(64),true,7L))
        assertFalse(pin.permits("public-server","a".repeat(64),"b".repeat(64),true,9L))
    }
}
