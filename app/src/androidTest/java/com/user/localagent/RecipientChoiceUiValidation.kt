package com.user.localagent

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class RecipientChoiceUiValidation {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun selectionHasNoDefaultAndDisabledConfirmationCannotExecute()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recipientValidationApproved")=="true")
        val action=PendingAction(ToolCall("qa-ui","prepare_sms",buildJsonObject{put("phoneNumber","00000000001");put("message","QA UI 확인")}),CompletableDeferred(),
            recipientChoices=listOf(ContactCandidate("QA 동명이인","00000000001"),ContactCandidate("QA 동명이인","00000000002")))
        compose.activity.runOnUiThread{compose.activity.setContent{MaterialTheme{ActionApprovalDialog(action,"QA UI 확인 · 실제 문자 발송 없음")}}}
        val confirmation=compose.onNodeWithText("선택한 번호로 초안 준비")
        confirmation.assertIsNotEnabled().performTouchInput{click()}
        compose.waitForIdle()
        assertNull(action.selectedRecipient.value);assertFalse(action.decision.isCompleted)
        val radios=compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton))
        radios.assertCountEquals(2);radios[0].assertIsNotSelected();radios[1].assertIsNotSelected()
        radios[1].performClick();compose.waitForIdle()
        assertEquals("00000000002",action.selectedRecipient.value)
        confirmation.assertIsEnabled().performClick()
        assertTrue(action.decision.await())
    }
}
