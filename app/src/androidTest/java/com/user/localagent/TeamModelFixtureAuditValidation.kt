package com.user.localagent

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only audit of an exactly named QA scope after an interrupted model batch. No deletion. */
class TeamModelFixtureAuditValidation {
    @Test fun interruptedAttemptHasNoRemainingQaProjectOrThread()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("teamModelFixtureAuditApproved")=="true")
        val token=checkNotNull(args.getString("teamModelFixtureAuditBatchToken"))
        require(UUID.fromString(token).toString()==token)
        val kind=checkNotNull(args.getString("teamModelFixtureAuditKind"))
        require(kind in setOf("calculate_note","correct_note","reminder_lifecycle","file_typo","storage_report","capabilities","exact_quote"))
        val round=checkNotNull(args.getString("teamModelFixtureAuditRound")?.toIntOrNull())
        require(round in 0..1)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val expected=checkNotNull(args.getString("teamModelFixtureAuditExpectedVersion")?.toLongOrNull())
        check(context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==expected)
        val chat=(context.applicationContext as LocalAgentApplication).container.repository.chat
        val title="qa-team-model-$token-$round-$kind"
        withTimeout(10_000) {
            assertEquals("Interrupted QA project remains; no automatic deletion was attempted",0,chat.projects.first().count{it.title==title})
            assertEquals("Interrupted QA thread remains; no automatic deletion was attempted",0,chat.threads.first().count{it.title==title})
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putBoolean("exactInterruptedQaScopeAbsent",true)})
    }
}
