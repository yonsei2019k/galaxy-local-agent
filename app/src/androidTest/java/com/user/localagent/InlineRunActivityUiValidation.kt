package com.user.localagent

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.ui.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendered injected host facts only; does not perform tools, inference, file changes or real chat writes. */
@RunWith(AndroidJUnit4::class)
class InlineRunActivityUiValidation {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun approved(){assumeTrue("workVisibilityUiApproved=true required",InstrumentationRegistry.getArguments().getString("workVisibilityUiApproved")=="true")}

    @Test fun realEventMetadataAppearsInlineBeforeAnswerWithoutEmptyBubbleOrFakeVerification(){
        val body=mutableStateOf("")
        val events=listOf(
            UiRunActivityEvent("plan",UiWorkEventKind.PLAN,"Synthetic bounded plan",evidence=UiWorkEvidence.VERIFIED),
            UiRunActivityEvent("context",UiWorkEventKind.CONTEXT,"Synthetic skill read",evidence=UiWorkEvidence.VERIFIED),
            UiRunActivityEvent("prepared",UiWorkEventKind.TOOL,"Synthetic email draft",evidence=UiWorkEvidence.PREPARED),
            UiRunActivityEvent("failed",UiWorkEventKind.TOOL,"Synthetic failed page read",evidence=UiWorkEvidence.FAILED),
            UiRunActivityEvent("verified",UiWorkEventKind.TOOL,"Synthetic datetime readback",evidence=UiWorkEvidence.VERIFIED),
            UiRunActivityEvent("hidden",UiWorkEventKind.RATIONALE,"<think>SYNTHETIC_INTERNAL_TEXT</think>"),
        )
        val activity=UiRunActivity("run-a","thread-a","assistant-a","running",events=events)
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
            ChatWorkspace(listOf(ChatLine("user-a","user","Synthetic owner request"),ChatLine("assistant-a","assistant",body.value)),
                false,"Synthetic activity fixture",emptyList(),onModelChange={},onSend={false},onStop={},onNavigate={},
                composerKey="thread-a",draftValue="",onDraftChange={},activityByMessageId=mapOf("assistant-a" to activity),
                messageActions=UiMessageActions(onCopy={error("Empty body has no copy action")},onExport={error("Empty body has no export action")}))
        }}}
        compose.onNodeWithTag("inline_activity_run-a").assertIsDisplayed()
        compose.onAllNodesWithTag("assistant_message").assertCountEquals(0)
        compose.onNodeWithTag("copy_message_assistant-a").assertDoesNotExist()
        compose.onNodeWithTag("message_actions_assistant-a").assertDoesNotExist()
        compose.onNodeWithTag("run_timeline").assertDoesNotExist()
        // Active work is visible immediately, without a disclosure click.
        compose.onNodeWithTag("activity_event_plan").assertExists()
        compose.onNodeWithText("도구 3 · 검증 1 · 준비 1 · 실패 1",substring=true).assertExists()
        compose.onNodeWithText("준비됨 · 외부 완료 미확인").assertExists()
        compose.onAllNodesWithText("검증됨",substring=false).assertCountEquals(1)
        compose.onNodeWithText("SYNTHETIC_INTERNAL_TEXT",substring=true).assertDoesNotExist()
        compose.onNodeWithTag("toggle_activity_run-a").performClick()
        compose.runOnIdle{body.value="SYNTHETIC_FINAL_ANSWER"}
        compose.onNodeWithTag("assistant_message").assertIsDisplayed()
        val card=compose.onNodeWithTag("inline_activity_run-a").fetchSemanticsNode().boundsInRoot
        val answer=compose.onNodeWithTag("assistant_message").fetchSemanticsNode().boundsInRoot
        assertTrue("Work process precedes the final bubble in the same stable message item",card.bottom<=answer.top+1f)
    }

    @Test fun diffButtonRequiresActualReferenceAndUsesOnlyInjectedCallback(){
        val path="synthetic/example.kt";val before="a".repeat(64);val after="b".repeat(64);var opened:UiRunFileChange?=null
        val file=UiRunFileChange(path,UiFileChangeOperation.UPDATED,before,after,diffRef="synthetic-retained-diff",sourceProjectId="original-project",workspaceBinding="original-workspace")
        var olderRun:String?=null
        val activity=UiRunActivity("run-file","thread-a","assistant-file","completed",events=listOf(
            UiRunActivityEvent("write",UiWorkEventKind.FILE,"Synthetic verified file edit",evidence=UiWorkEvidence.VERIFIED,fileChanges=listOf(file))),totalEvents=130,hasOlder=true)
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{InlineRunActivityCard(activity,onOpenFileChange={opened=it},onLoadOlder={olderRun=it})}}}
        compose.onNodeWithTag("toggle_activity_run-file").performClick()
        compose.onNodeWithText("실제 변경 내용 보기").performClick()
        compose.runOnIdle{assertEquals(file,opened)}
        compose.onNodeWithTag("load_older_activity_run-file").performClick()
        compose.runOnIdle{assertEquals("run-file",olderRun)}
    }

    @Test fun pausedEmptyBodyKeepsItsRealContinuationControlWithoutAnEmptyBubble(){
        var continued:String?=null
        val activity=UiRunActivity("run-paused","thread-a","assistant-paused","paused")
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
            ChatWorkspace(listOf(ChatLine("assistant-paused","assistant","")),false,"Synthetic paused fixture",emptyList(),
                onModelChange={},onSend={false},onStop={},onNavigate={},composerKey="thread-a",draftValue="",onDraftChange={},
                activityByMessageId=mapOf("assistant-paused" to activity),
                answerStates=mapOf("assistant-paused" to UiAnswerState(phase=UiAnswerPhase.PAUSED,canContinue=true)),onContinue={continued=it},
                messageActions=UiMessageActions(onCopy={error("No body")},onExport={error("No body")}))
        }}}
        compose.onNodeWithTag("inline_activity_run-paused").assertIsDisplayed()
        compose.onAllNodesWithTag("assistant_message").assertCountEquals(0)
        compose.onNodeWithTag("copy_message_assistant-paused").assertDoesNotExist()
        compose.onNodeWithText("이어서 쓰기").performClick()
        compose.runOnIdle{assertEquals("assistant-paused",continued)}
    }
}
