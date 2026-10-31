package com.user.localagent.core.connections

import org.junit.Assert.*
import org.junit.Test

class GoogleAuthorizationPlanTest {
    @Test fun everyRequestedGoogleServiceHasItsOwnConsentStep() {
        assertEquals(11,GoogleAuthorizationPlan.services.size)
        assertEquals(ConnectedService.entries.filter {it.google}.toSet(),GoogleAuthorizationPlan.services.toSet())
        assertEquals(ConnectedService.YOUTUBE,GoogleAuthorizationPlan.services.last())
    }
    @Test fun driveAndYoutubeScopesAreNeverCombinedAndUploadIsNeverRequested() {
        GoogleAuthorizationPlan.services.forEach {service->
            val scopes=GoogleAuthorizationPlan.scopes(service,GoogleAuthorizationPlan.writes(service))
            assertFalse(scopes.any {it.endsWith("/drive.file")}&&scopes.any {it.contains("/youtube")})
            assertFalse(scopes.any {it.endsWith("/youtube.upload")})
        }
    }
    @Test fun consentScopeIsolationPreservesExactServicePermissionsAndIdentity() {
        GoogleAuthorizationPlan.services.forEach {service->
            val scopes=GoogleAuthorizationPlan.scopes(service,GoogleAuthorizationPlan.writes(service))
            assertTrue(service.coveredBy(scopes,GoogleAuthorizationPlan.writes(service)))
            assertTrue(scopes.containsAll(listOf("openid","email")))
            assertTrue(scopes.none {it.contains("chat.")||it.contains("keep")||it.contains("classroom")})
        }
    }
}
