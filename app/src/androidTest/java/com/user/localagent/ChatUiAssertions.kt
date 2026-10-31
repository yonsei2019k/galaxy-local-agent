package com.user.localagent

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.assertTrue

/** Check the editable value, excluding placeholder/supporting Text merged into a Material field. */
internal fun SemanticsNodeInteraction.assertEditableValue(expected:String):SemanticsNodeInteraction {
    val actual=fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
    assertTrue("EditableText mismatch: expectedChars=${expected.length}, actualChars=${actual?.length ?: -1}",actual==expected)
    return this
}

/** MainActivity already owns a ComposeView; install the saved-state harness through that view. */
internal fun restorationTesterInActivity(rule:ComposeContentTestRule,activity:ComponentActivity):StateRestorationTester =
    StateRestorationTester(object:ComposeContentTestRule by rule {
        override fun setContent(composable:@Composable ()->Unit) {
            rule.runOnUiThread {activity.setContent(content=composable)}
        }
    })
