package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit local developer migration only, preserving an unsent draft across upgrades/test runs. */
@RunWith(AndroidJUnit4::class)
class DraftMigration {
    @Test fun preserveUnsentDraft() {
        val args=InstrumentationRegistry.getArguments()
        if(args.getString("draftMigrationApproved")!="true")return
        val encoded=args.getString("draftBase64")?:return
        require(encoded.length<=64_000 && encoded.matches(Regex("[A-Za-z0-9+/=]*")))
        val text=String(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT),Charsets.UTF_8)
        require(text.length<=12_000)
        val store=com.user.localagent.data.DraftStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.write(text)
        assertTrue(store.read()==text)
    }
}
