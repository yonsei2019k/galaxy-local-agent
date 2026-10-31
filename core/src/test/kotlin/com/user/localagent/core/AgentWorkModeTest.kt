package com.user.localagent.core

import com.user.localagent.core.connections.ConnectionCatalog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentWorkModeTest {
    @Test fun allSixModesHaveStableWireValuesAndPurposefulSkillDefaults(){
        assertEquals(listOf("act","plan","goal","debug","review","research"),AgentWorkMode.entries.map{it.wireName})
        AgentWorkMode.entries.forEach{assertEquals(it,AgentWorkMode.parse(it.wireName));assertTrue(it.label.isNotBlank()&&it.promptHint.isNotBlank())}
        assertNull(AgentWorkMode.ACT.defaultSkillId)
        assertEquals("web-research",AgentWorkMode.RESEARCH.defaultSkillId)
        assertTrue(runCatching{AgentWorkMode.parse("do_anything")}.isFailure)
    }
    @Test fun actAndDebugKeepRegisteredWritesUnderExistingExecutorChecks(){
        for(mode in listOf(AgentWorkMode.ACT,AgentWorkMode.DEBUG)){
            ToolRegistry.definitions.keys.forEach{assertTrue("$mode must not blanket-disable $it",AgentWorkModePolicy.allows(mode,it))}
            assertFalse(AgentWorkModePolicy.allows(mode,"unregistered_shell"))
        }
    }
    @Test fun nonExecutingModesAllowDeclaredLocalCloudReadsButNotLowRiskWritesOrPublicQueries(){
        for(mode in listOf(AgentWorkMode.PLAN,AgentWorkMode.GOAL,AgentWorkMode.REVIEW)){
            assertTrue(AgentWorkModePolicy.allows(mode,"read_project_file"))
            assertTrue(AgentWorkModePolicy.allows(mode,"query_selected_data"))
            ConnectionCatalog.tools.keys.forEach{name->assertEquals("Read effects and public-query scope must decide $name",name in ConnectionCatalog.privateReadNames,AgentWorkModePolicy.allows(mode,name))}
            for(name in listOf("create_document","generate_image","open_url","write_project_file","save_note","query_notebooklm","search_web","search_shopping","read_webpage","search_youtube_videos")){
                assertFalse("Risk labels must not allow $name in $mode",AgentWorkModePolicy.allows(mode,name))
            }
        }
    }
    @Test fun researchIsPublicOnlyAndCannotReadPhoneAccountsProjectsOrMemory(){
        val allowed=AgentWorkModePolicy.filterTools(AgentWorkMode.RESEARCH,ToolRegistry.definitions.keys+setOf("list_skills","read_skill"))
        assertEquals(setOf("get_current_datetime","search_web","search_shopping","read_webpage","search_youtube_videos","list_skills","read_skill","calculate","date_calculate","find_free_time","search_publications","verify_reference"),allowed)
        for(name in listOf("read_project_file","search_memories","get_sms_summary","read_gmail_message","query_notebooklm"))assertFalse(AgentWorkModePolicy.allows(AgentWorkMode.RESEARCH,name))
    }
    @Test fun skillInstructionsRemainContextReadsAcrossModesAndDoNotInventReferenceTool(){
        AgentWorkMode.entries.forEach{mode->
            assertTrue(AgentWorkModePolicy.allows(mode,"list_skills"));assertTrue(AgentWorkModePolicy.allows(mode,"read_skill"))
            assertFalse(AgentWorkModePolicy.allows(mode,"read_reference"))
        }
    }
    @Test fun verifiedCriterionRequiresEvidenceAndPendingCriteriaPreventCompletion(){
        val goal=goal()
        assertTrue(runCatching{goal.copy(criteria=listOf(GoalCriterion("c","Output exists",GoalCriterionStatus.VERIFIED))).validate()}.isFailure)
        assertTrue(runCatching{goal.copy(status=GoalStatus.COMPLETE).validate()}.isFailure)
    }
    @Test fun manualConfirmationIsSeparateAndNeverAutomaticallyCompletesGoal(){
        val ready=goal().copy(criteria=listOf(GoalCriterion("c","Output checked",GoalCriterionStatus.USER_CONFIRMED)))
        assertTrue(ready.completionReady);assertEquals(GoalStatus.DRAFT,ready.status)
        assertEquals(GoalStatus.COMPLETE,ready.copy(status=GoalStatus.COMPLETE).validate().status)
    }
    @Test fun goalJsonPreservesLongTextScopeCriteriaAndEvidenceWithoutTruncation(){
        val text="한글🙂 target with required details\n".repeat(90)
        val original=goal().copy(outcome=text,constraints=listOf("Keep the exact selected scope"),linkedRunIds=listOf("run-a"),
            criteria=listOf(GoalCriterion("c","Actual checked output",GoalCriterionStatus.VERIFIED,listOf("tool-run:audit-a"))))
        val restored=Json.decodeFromString<GoalDefinition>(Json.encodeToString(original.validate()))
        assertEquals(original,restored);assertEquals(text,restored.outcome)
    }
    @Test fun duplicateCriterionIdsAndInvalidStatusesCannotBeSmuggledThroughGoalJson(){
        assertTrue(runCatching{goal().copy(criteria=listOf(GoalCriterion("same","A"),GoalCriterion("same","B"))).validate()}.isFailure)
        val serialized=goal().toJson().toString()
        assertTrue(serialized.contains("\"draft\""))
        assertTrue(runCatching{GoalDefinition.parse(serialized.replace("\"draft\"","\"executed\""))}.isFailure)
    }
    private fun goal()=GoalDefinition("goal-a","thread-a","project-a","Produce the requested output",listOf(GoalCriterion("c","Output was checked")))
}
