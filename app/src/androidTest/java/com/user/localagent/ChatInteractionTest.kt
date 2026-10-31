package com.user.localagent

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min

/** App-local UI checks; one explicit benign native inference case, with no external tools or messages. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ChatInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test
    fun keyboardNewlineAndArrowSendNotLost() {
        waitForChat()
        val input = compose.onNodeWithTag("chat_input")
        input.assertTextEquals("")
        input.performClick()
        input.performTextInput("지금 몇 시야")
        compose.waitUntil(timeoutMillis = 10_000) {
            var visible = false
            compose.runOnUiThread {
                visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
        compose.waitForIdle()
        input.assertIsDisplayed()
        val send = compose.onNodeWithTag("send_button").assertIsDisplayed().assertIsEnabled()
        val inputBounds = input.fetchSemanticsNode().boundsInRoot
        val sendBounds = send.fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val verticalOverlap = min(inputBounds.bottom, sendBounds.bottom) - max(inputBounds.top, sendBounds.top)
        assertTrue("Input and send must remain on the same visible row with the IME open",
            verticalOverlap > 0f && verticalOverlap >= min(inputBounds.height, sendBounds.height) / 2f)
        assertTrue("Send must stay inside the visible Compose root after the IME opens",
            sendBounds.width > 0f && sendBounds.height > 0f &&
                sendBounds.left >= rootBounds.left - 1f && sendBounds.top >= rootBounds.top - 1f &&
                sendBounds.right <= rootBounds.right + 1f && sendBounds.bottom <= rootBounds.bottom + 1f)

        var imeBottomPx = 0
        var decorHeightPx = 0
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor)) { "IME insets must be available" }
            assertTrue("The IME must still be visible for the occlusion check", insets.isVisible(WindowInsetsCompat.Type.ime()))
            imeBottomPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            decorHeightPx = decor.height
        }
        assertTrue("Visible keyboard must report a positive bottom inset", imeBottomPx > 0)
        assertTrue("Decor must have a measurable height above the keyboard", decorHeightPx > imeBottomPx)
        val sendWindowBottom = send.fetchSemanticsNode().boundsInWindow.bottom
        val keyboardTopInWindow = decorHeightPx - imeBottomPx
        assertTrue("Send bottom ($sendWindowBottom) must be above IME top ($keyboardTopInWindow) in window pixels",
            sendWindowBottom <= keyboardTopInWindow + 4f)
        input.performKeyInput { pressKey(Key.Enter) }
        input.performTextInput("한국 시간으로")
        input.assertTextEquals("지금 몇 시야\n한국 시간으로")
        send.performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onNodeWithTag("chat_input").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.EditableText)?.text == ""
        }
        assertEquals("Accepted arrow send must clear the submitted multiline draft", "",
            compose.onNodeWithTag("chat_input").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.EditableText)?.text)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("user_message").fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "지금 몇 시야\n한국 시간으로" }
            }
        }
    }

    @Test
    fun manualModelChoiceRestoresAutomaticRouting() {
        waitForChat()
        val selector = compose.onNodeWithTag("model_selector").assertIsDisplayed()
        selector.performClick()
        compose.onNodeWithTag("model_option_gemma-e4b").assertIsDisplayed().performClick()
        try {
            compose.onNodeWithTag("model_selector").assertTextContains("E4B", substring = true, ignoreCase = true)
        } finally {
            compose.onNodeWithTag("model_selector").performClick()
            compose.onNodeWithTag("model_option_auto").assertIsDisplayed().performClick()
        }
        compose.onNodeWithTag("model_selector").assertTextContains("자동", substring = true)
    }

    @Test
    fun manualE4bChoiceUsesSelectedResidentModel() {
        waitForChat()
        val app = (compose.activity.application as LocalAgentApplication).container
        compose.onNodeWithTag("model_selector").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("model_option_gemma-e4b").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("model_option_gemma-e4b").assertIsDisplayed().performClick()
        try {
            compose.onNodeWithTag("model_selector").assertTextContains("E4B", substring = true, ignoreCase = true)
            val expected = requireNotNull(app.models.models.value.firstOrNull {
                it.id == "gemma-e4b" && it.verified && it.runtime == "gguf"
            }) { "The native UI regression fixture requires an already installed verified E4B model" }
            val before = assistantTexts().toSet()
            val input = compose.onNodeWithTag("chat_input").assertIsDisplayed()
            assertEquals("The native test must not submit an existing draft", "",
                input.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
            input.performClick()
            input.performTextInput("한 문장으로 간단히 인사해 줘")
            compose.onNodeWithTag("send_button").performClick()
            compose.waitUntil(timeoutMillis = 90_000) {
                assistantTexts().any { it.isNotBlank() && it !in before } &&
                    app.engine.state.value == "준비됨"
            }
            compose.waitForIdle()
            assertTrue("A fresh assistant response must appear after the explicit E4B request",
                assistantTexts().any { it.isNotBlank() && it !in before })
            assertEquals("Manual selection must control the model actually resident after inference",
                expected.path, app.engine.residentModelPath)
        } finally {
            compose.onNodeWithTag("model_selector").performClick()
            compose.onNodeWithTag("model_option_auto").assertIsDisplayed().performClick()
        }
        compose.onNodeWithTag("model_selector").assertTextContains("자동", substring = true)
    }

    private fun waitForChat() {
        compose.waitUntil(timeoutMillis = 20_000) {
            runCatching {compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("model_selector").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)
        }
    }
    private fun assistantTexts(): List<String> = compose.onAllNodesWithTag("assistant_message", useUnmergedTree = false)
        .fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }
}



