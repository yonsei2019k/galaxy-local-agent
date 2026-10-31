package com.user.localagent

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.ui.ChatWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic presentation only. No real model run, goal store, provider, or SAF document is used. */
@RunWith(AndroidJUnit4::class)
class ConversationFirstUiValidation {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun optIn(){assumeTrue("conversationFirstUiApproved=true required",
        InstrumentationRegistry.getArguments().getString("conversationFirstUiApproved")=="true")}

    @Test fun complexRequestUsesTheSameComposerEvenWithALegacyGoalMode(){
        val draft=mutableStateOf("");val sent=mutableListOf<String>()
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
            ChatWorkspace(emptyList(),false,"합성 대화",emptyList(),onModelChange={},onSend={sent+=it;true},onStop={},onNavigate={},
                composerKey="conversation-first-a",draftValue=draft.value,onDraftChange={draft.value=it},
                runMode="goal",onModeChange={error("A request must not switch persistent modes")},
                onManageGoal={error("No active goal was supplied")},onShowSkills={})
        }}}
        compose.onNodeWithTag("run_mode_selector").assertDoesNotExist()
        compose.onNodeWithTag("manage_chat_goal").assertDoesNotExist()
        compose.onNodeWithTag("show_skill_library").assertDoesNotExist()
        val request="자료를 먼저 비교해서 계획하고, 둘째 안으로 문서를 만든 다음 출처가 부족하면 보완해줘."
        compose.onNodeWithTag("chat_input").performTextReplacement(request)
        compose.onNodeWithTag("send_button").assertIsEnabled().performClick()
        compose.runOnIdle{assertEquals(listOf(request),sent)}
    }

    @Test fun optionalSkillAndAttachmentActionsDoNotSendOrSelectAMode(){
        var skills=0;var attachments=0
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
            ChatWorkspace(emptyList(),false,"합성 대화",emptyList(),onModelChange={},onSend={error("Browsing must not send")},onStop={},onNavigate={},
                composerKey="conversation-first-actions",draftValue="",onDraftChange={},onAttach={attachments++},onShowSkills={skills++},
                onManageGoal={error("No current goal")},onModeChange={error("No workflow mode")})
        }}}
        compose.onNodeWithTag("conversation_browse_skills").assertDoesNotExist()
        compose.onNodeWithTag("attach_button").performClick()
        compose.onNodeWithTag("conversation_view_goal").assertDoesNotExist()
        compose.onNodeWithTag("conversation_browse_skills").performClick()
        compose.runOnIdle{assertEquals(1,skills);assertEquals(0,attachments)}
        compose.onNodeWithTag("attach_button").performClick()
        compose.onNodeWithTag("conversation_attach").performClick()
        compose.runOnIdle{assertEquals(1,skills);assertEquals(1,attachments)}
    }

    @Test fun goalReferenceAppearsOnlyInsideAnOpenedOptionalMenu(){
        var opened=0
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
            ChatWorkspace(emptyList(),false,"합성 대화",emptyList(),onModelChange={},onSend={false},onStop={},onNavigate={},
                composerKey="conversation-first-goal",draftValue="",onDraftChange={},activeGoalLabel="합성 자료 검증",
                onManageGoal={opened++})
        }}}
        compose.onNodeWithTag("conversation_view_goal").assertDoesNotExist()
        compose.onNodeWithTag("attach_button").performClick()
        compose.onNodeWithTag("conversation_view_goal").assertExists().performClick()
        compose.runOnIdle{assertEquals(1,opened)}
    }

    @Test fun changingRoomOrBranchClosesAnOpenGoalMenu(){
        val room=mutableStateOf("conversation-first-old");val branch=mutableStateOf("branch-a");var opened=0
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
            ChatWorkspace(emptyList(),false,"합성 대화",emptyList(),onModelChange={},onSend={false},onStop={},onNavigate={},
                composerKey=room.value,currentBranchId=branch.value,draftValue="",onDraftChange={},activeGoalLabel="합성 목표",onManageGoal={opened++})
        }}}
        compose.onNodeWithTag("attach_button").performClick()
        compose.onNodeWithTag("conversation_view_goal").assertExists()
        compose.runOnIdle{branch.value="branch-b"}
        compose.onNodeWithTag("conversation_view_goal").assertDoesNotExist()
        compose.onNodeWithTag("attach_button").performClick()
        compose.runOnIdle{room.value="conversation-first-new"}
        compose.onNodeWithTag("conversation_view_goal").assertDoesNotExist()
        compose.runOnIdle{assertEquals(0,opened)}
    }
}
