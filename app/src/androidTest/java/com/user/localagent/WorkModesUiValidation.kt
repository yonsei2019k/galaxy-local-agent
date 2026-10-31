package com.user.localagent

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.ui.GoalEditorDialog
import com.user.localagent.ui.RunModeSelector
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI only: never submits a real request, changes stored goals or calls a provider. */
@RunWith(AndroidJUnit4::class)
class WorkModesUiValidation {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun optIn(){assumeTrue("workModesUiApproved=true required",InstrumentationRegistry.getArguments().getString("workModesUiApproved")=="true")}

    @Test fun allSixModesAreSelectableInTheCompactMenu(){
        val selected=mutableStateOf("act")
        compose.runOnUiThread {compose.activity.setContent {MaterialTheme {RunModeSelector(selected.value,{selected.value=it})}}}
        for(mode in AgentWorkMode.entries){
            compose.onNodeWithTag("run_mode_selector").performClick()
            compose.onNodeWithTag("run_mode_${mode.wireName}").performClick()
            compose.runOnIdle {assertEquals(mode.wireName,selected.value)}
        }
    }

    @Test fun checkedCriteriaNeverAutoCompleteAndStartIsAnExplicitCallback(){
        val goal=GoalDefinition(threadId="synthetic-goal-ui",projectId=null,outcome="합성 문서 준비",
            criteria=listOf(GoalCriterion(id="criterion-1",text="파일을 직접 확인")))
        var saved:GoalDefinition?=null;var started:GoalDefinition?=null
        compose.runOnUiThread {compose.activity.setContent {MaterialTheme {
            GoalEditorDialog(goal,goal.threadId,null,{saved=it},{started=it},{},{})
        }}}
        compose.onNodeWithTag("goal_confirm_criterion-1").performScrollTo().performClick()
        compose.onNodeWithTag("goal_save").performClick()
        compose.runOnIdle {
            assertEquals(GoalStatus.DRAFT,saved!!.status)
            assertEquals(GoalCriterionStatus.USER_CONFIRMED,saved!!.criteria.single().status)
            assertNull(started)
        }
        compose.onNodeWithTag("goal_start").performClick()
        compose.runOnIdle {assertEquals(GoalStatus.ACTIVE,started!!.status)}
    }

    @Test fun aConcurrentGoalUpdateKeepsTypedEditsAndBlocksStaleSaving(){
        val goal=GoalDefinition(threadId="synthetic-stale-ui",projectId=null,outcome="처음 목표",
            criteria=listOf(GoalCriterion(id="criterion-1",text="확인 기준")),updatedAt=1)
        val current=mutableStateOf(goal);var saves=0
        compose.runOnUiThread {compose.activity.setContent {MaterialTheme {
            GoalEditorDialog(current.value,goal.threadId,null,{saves++},{},{},{})
        }}}
        compose.onNodeWithTag("goal_outcome").performTextReplacement("작성 중인 목표🙂")
        compose.runOnIdle {current.value=goal.copy(updatedAt=2)}
        compose.onNodeWithTag("goal_outcome").assertTextContains("작성 중인 목표🙂")
        compose.onNodeWithTag("goal_save").assertIsNotEnabled()
        compose.runOnIdle {assertEquals(0,saves)}
    }
}
