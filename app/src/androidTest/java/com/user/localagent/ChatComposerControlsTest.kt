package com.user.localagent

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.user.localagent.ui.ChatAttachmentChip
import com.user.localagent.ui.ChatWorkspace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled composer fixtures: no inference, tools, real drafts or queue database writes. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ChatComposerControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val draft = mutableStateOf("")
    private val activeRun = mutableStateOf("run-1")
    private val queued = mutableListOf<String>()
    private val steered = mutableListOf<String>()
    private var stops = 0

    private fun mount(attachments: List<ChatAttachmentChip> = emptyList()) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    ChatWorkspace(
                        messages=emptyList(), busy=true, status="진행 중", models=emptyList(),
                        onModelChange={}, onSend={ error("Busy requests must use the selected queue/steer action") },
                        onStop={stops++}, onNavigate={}, composerKey="synthetic-composer",
                        draftValue=draft.value, onDraftChange={draft.value=it},
                        onQueue={queued+=it;true}, onSteer={steered+=it}, activeRunKey=activeRun.value,
                        attachments=attachments,
                    )
                }
            }
        }
    }

    @Test fun enterInsertsNewlineAndArrowQueuesWithoutStopping() {
        mount()
        val input=compose.onNodeWithTag("chat_input")
        input.performClick().performTextInput("첫 줄")
        input.performKeyInput { pressKey(Key.Enter) }
        input.performTextInput("둘째 줄")
        input.assertEditableValue("첫 줄\n둘째 줄")
        compose.runOnIdle { assertEquals(emptyList<String>(),queued);assertEquals(0,stops) }
        compose.onNodeWithTag("send_button").performClick()
        input.assertEditableValue("")
        compose.runOnIdle { assertEquals(listOf("첫 줄\n둘째 줄"),queued);assertEquals(0,stops) }
        compose.onNodeWithTag("stop_button").performClick()
        compose.runOnIdle { assertEquals(1,stops) }
    }

    @Test fun selectedImmediateInstructionUsesSteerThenDefaultsBackToQueue() {
        mount()
        compose.onNodeWithTag("submit_mode_steer").performClick()
        compose.onNodeWithTag("chat_input").performTextInput("비교 기준을 바꿔줘")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNodeWithTag("chat_input").performTextInput("다음 작업")
        compose.onNodeWithTag("send_button").performClick()
        compose.runOnIdle {
            assertEquals(listOf("비교 기준을 바꿔줘"),steered)
            assertEquals(listOf("다음 작업"),queued)
            assertEquals(0,stops)
        }
    }

    @Test fun changingActiveRunClearsImmediateSelectionWithoutLosingDraft() {
        mount()
        compose.onNodeWithTag("submit_mode_steer").performClick()
        compose.onNodeWithTag("chat_input").performTextInput("아직 보내지 않은 요청")
        compose.runOnIdle { activeRun.value="run-2" }
        compose.onNodeWithTag("chat_input").assertEditableValue("아직 보내지 않은 요청")
        compose.onNodeWithTag("send_button").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<String>(),steered)
            assertEquals(listOf("아직 보내지 않은 요청"),queued)
        }
    }

    @Test fun attachmentsDisableImmediateModeAndUseQueue() {
        mount(listOf(ChatAttachmentChip("fixture","fixture.txt","파일")))
        compose.onNodeWithTag("submit_mode_steer").assertIsNotEnabled()
        compose.onNodeWithTag("chat_input").performTextInput("첨부를 분석해줘")
        compose.onNodeWithTag("send_button").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<String>(),steered)
            assertEquals(listOf("첨부를 분석해줘"),queued)
        }
    }
}
