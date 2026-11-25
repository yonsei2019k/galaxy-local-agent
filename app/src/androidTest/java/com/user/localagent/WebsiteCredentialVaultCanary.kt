package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.integrations.WebsiteCredentialVault
import java.net.URI
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebsiteCredentialVaultCanary {
    @Test fun savedLoginIsScopedAndUsableAfterCreatingANewVaultInstance() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val site=URI("https://${UUID.randomUUID()}.test")
        val other=URI("https://alternate.test")
        val secret=UUID.randomUUID().toString()
        val vault=WebsiteCredentialVault(context)
        try{
            assertFalse(vault.has(site))
            vault.save(site,"fixture-account",secret)
            assertTrue(site in vault.sites())
            assertNull(vault.readForOwner(other))
            val recovered=checkNotNull(WebsiteCredentialVault(context).readForOwner(site))
            assertEquals(secret,recovered.password)
            assertFalse(recovered.toString().contains(secret))
            assertEquals(site.host,vault.recentAccess().first().first)
            vault.remove(site)
            assertNull(vault.readForOwner(site))
        }finally{vault.remove(site)}
    }
}
