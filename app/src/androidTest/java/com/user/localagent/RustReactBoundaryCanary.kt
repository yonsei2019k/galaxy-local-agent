package com.user.localagent

import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import com.user.localagent.engine.RustExecutionJournal
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** JNI/packaged-asset checks only. These synthetic receipts are never evidence of a real tool action. */
class RustReactBoundaryCanary {
    private val binding="""{"runId":"synthetic-jni","threadId":"synthetic-thread","projectId":null,"branchId":"synthetic-branch","scopeId":"synthetic-scope","authorizationRevision":1}"""
    private val profile="""{"profileId":"test","provider":"local_pc","modelId":"synthetic","reasoning":null,"maxToolCalls":4,"deadlineMs":30000,"cloudAuthorized":false}"""
    @Test fun actualArm64BridgePersistsUncertainEffectsWithoutCompletion(){
        val call=ToolCall("synthetic-call","save_note",buildJsonObject{})
        val checkpoint=RustExecutionJournal(binding,profile).use{journal->
            journal.begin(binding,call)
            journal.checkpoint()
        }
        RustExecutionJournal(binding,profile,checkpoint).use{restored->
            assertTrue(runCatching{restored.begin(binding,call.copy(id="synthetic-retry"))}.isFailure)
            assertFalse(restored.snapshot().contains("completed"))
        }
        RustExecutionJournal(binding,profile).use{journal->
            val prepare=call.copy(name="prepare_sms")
            journal.begin(binding,prepare)
            journal.outcome(ToolResult(prepare.id,true,"synthetic handoff",VerificationState.PREPARED))
            assertTrue(journal.snapshot().contains("handed_off"))
            assertTrue(runCatching{journal.begin(binding,call.copy(id="synthetic-next"))}.isFailure)
        }
    }
    @Test fun actualArm64BridgeUsesTheExecutorReceiptId() {
        val call = ToolCall("synthetic-call", "save_note", buildJsonObject {})
        RustExecutionJournal(binding, profile).use { journal ->
            journal.begin(binding, call)
            val foreign = ToolResult("other-call", true, "synthetic", VerificationState.VERIFIED)
            assertTrue(runCatching { journal.outcome(foreign) }.isFailure)
            assertFalse(journal.snapshot().contains("tool_finished"))
            journal.outcome(foreign.copy(id = call.id))
            assertTrue(journal.snapshot().contains("verified"))
        }
    }

    @Test fun reactAssetsArePackagedOfflineWithMascot(){
        val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
        val html=assets.open("react/index.html").bufferedReader().use{it.readText()}
        assertTrue(html.contains("Content-Security-Policy"));assertTrue(html.replace("&#39;","'").contains("connect-src 'none'"))
        assertTrue(assets.open("react/ziphap-mascot.png").use{it.readBytes().size}>1000)
        val scripts=Regex("src=\"([^\"]+\\.js)\"").findAll(html).map{it.groupValues[1]}.toList()
        assertTrue(scripts.isNotEmpty())
        scripts.forEach{path->assertTrue(path.startsWith("./"));assertTrue(assets.open("react/"+path.removePrefix("./")).use{it.readBytes().size}>1000)}
    }
}
