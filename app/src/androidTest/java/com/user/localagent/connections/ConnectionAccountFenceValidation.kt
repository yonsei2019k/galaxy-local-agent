package com.user.localagent.connections

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.connections.ConnectedService
import com.user.localagent.core.connections.ConnectionAccess
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Local encrypted preference fixture only: no Google account, token, provider, or network. */
@RunWith(AndroidJUnit4::class)
class ConnectionAccountFenceValidation {
    @Test fun accountReconnectNeverReusesAnApprovalEpochAndVerificationUsesExactAccess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("connectionAccountFenceApproved") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "qa-connection-fence-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "ziphap-connections-v1") { "Unexpected fixture preference request" }
                return base.getSharedPreferences(preferenceName, mode)
            }
        }
        val service = ConnectedService.GMAIL
        fun LocalConnection.access() = ConnectionAccess(principal, generation, true, writeAllowed)
        try {
            val first = ConnectionStore(isolated)
            val second = ConnectionStore(isolated)
            assertNull(first.connection(service))
            first.connect(service, LocalConnection("synthetic-principal-a", "synthetic-a@example.invalid", true, 0))
            val a1 = checkNotNull(second.connection(service))
            assertTrue(a1.generation > 0)
            assertEquals(0L, a1.verifiedAt)

            second.disconnect(service)
            assertNull(first.connection(service))
            second.connect(service, LocalConnection("synthetic-principal-b", "synthetic-b@example.invalid", true, 0))
            val b = checkNotNull(first.connection(service))
            assertTrue(b.generation > a1.generation)
            assertFalse(first.markVerified(service, a1.access()))
            assertEquals(0L, checkNotNull(second.connection(service)).verifiedAt)

            first.disconnect(service)
            assertNull(second.connection(service))
            first.connect(service, LocalConnection("synthetic-principal-a", "synthetic-a@example.invalid", true, 0))
            val a2 = checkNotNull(second.connection(service))
            assertTrue(a2.generation > b.generation)
            assertEquals(a1.principal, a2.principal)
            assertFalse(second.markVerified(service, a1.access()))
            assertFalse(second.markVerified(service, b.access()))
            assertFalse(first.markVerified(service, a2.access().copy(writeAllowed = false)))
            assertEquals(0L, checkNotNull(first.connection(service)).verifiedAt)

            assertTrue(second.markVerified(service, a2.access()))
            val verified = checkNotNull(first.connection(service))
            assertTrue(verified.verifiedAt > 0)
            assertEquals(a2.copy(verifiedAt = verified.verifiedAt), verified)
            assertEquals(a2.generation, verified.generation)
            assertEquals(verified, ConnectionStore(isolated).connection(service))
        } finally {
            // Never disconnect/delete the real owner store or remove its shared Keystore key.
            check(base.deleteSharedPreferences(preferenceName)) { "Synthetic preference cleanup failed" }
        }
    }
}
