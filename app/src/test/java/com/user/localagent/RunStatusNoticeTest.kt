package com.user.localagent

import com.user.localagent.core.ToolRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RunStatusNoticeTest {
    private val technical=Regex("_|\\b(read_screen|screen_action|UNKNOWN_EFFECT|null|true|false)\\b|[a-z]+[A-Z]")

    @Test fun everyRegisteredToolHasAPlainReportLabel(){
        val missing=ToolRegistry.definitions.keys.filter {ToolReportLabels.label(it)==ToolReportLabels.UNKNOWN}
        assertTrue("도구 이름표 없음: $missing",missing.isEmpty())
        ToolRegistry.definitions.keys.forEach {name->
            val label=ToolReportLabels.label(name)
            assertNotEquals(name,label)
            assertFalse("$name -> $label",technical.containsMatchIn(label))
        }
        assertEquals("작업 단계",ToolReportLabels.label("made_up_tool"))
    }

    @Test fun statusLabelsReasonsAndButtonsAreShortPlainWords(){
        val labels=RunStatusLabels.phases.values+RunStatusLabels.actions.values+GoalAutopilotPauseReason.entries.map {it.label}
        labels.forEach {
            assertFalse(it,technical.containsMatchIn(it))
            assertTrue(it,it.length<=30)
        }
    }

    @Test fun cardCarriesStructuredStatusInsteadOfASentence(){
        val notice=RunNotice("화면 연결 설정 필요","open_screen_setup","휴대폰 설정 → 접근성 → 설치된 앱")
        val context=RunStatusLabels.write(JsonObject(emptyMap()),notice)
        val card=RunStatusLabels.card("paused","screen_setup_system",context)
        assertEquals("paused",card.phase)
        assertEquals("잠깐 멈춤",card.label)
        assertEquals(notice.reason,card.reason)
        assertEquals("설정 열기",card.actionLabel)
        assertEquals(notice.instruction,card.instruction)
        val json=card.json()
        assertEquals(setOf("phase","label","reason","action","actionLabel","instruction"),json.keys)

        val failed=RunStatusLabels.card("failed","IllegalStateException",JsonObject(emptyMap()))
        assertEquals("failed",failed.phase);assertEquals("끝내지 못함",failed.label);assertNull(failed.reason)
        assertEquals("completed",RunStatusLabels.card("completed",null,JsonObject(emptyMap())).phase)
        val reply=RunStatusLabels.card("paused",GOAL_OWNER_INPUT,JsonObject(emptyMap()))
        assertEquals("waiting_reply",reply.phase);assertEquals("reply",reply.action)
        val goal=RunStatusLabels.card("paused",GoalAutopilotPauseReason.TOOL_BUDGET.code,JsonObject(emptyMap()))
        assertEquals(GoalAutopilotPauseReason.TOOL_BUDGET.label,goal.reason)
    }

    @Test fun noCannedSentenceIsChosenWithoutModelText(){
        // Pause, failure and completion without model text post nothing to the chat; the card carries the facts.
        assertNull(ModelStatusText.choose(null,completed=false))
        assertNull(ModelStatusText.choose("  ",completed=false))
        assertNull(ModelStatusText.choose(null,completed=true))
    }

    @Test fun modelTextIsPreferredWhenItExists(){
        val text="출석 버튼을 눌렀는데 화면이 그대로라 아직 출석이 됐는지는 몰라요. 화면을 한 번 더 볼게요."
        assertEquals(text,ModelStatusText.choose(text,completed=false))
        assertEquals("오늘 출석을 마쳤어요.",ModelStatusText.choose("오늘 출석을 마쳤어요.",completed=true))
    }

    @Test fun modelPhraseThatFalselyClaimsSuccessIsRejected(){
        listOf("오늘 출석체크를 완료했어요.","메모를 저장해 뒀어요!","메일을 보냈어요.","예약했어요. 확인해 보세요.",
            "출석이 됐어요.","All done").forEach {
            assertFalse(it,ModelStatusText.acceptable(it,completed=false))
            assertNull(ModelStatusText.choose(it,completed=false))
        }
        listOf("메일은 아직 보내지 못했어요.","저장했는지 확인이 필요해요.","로그인 화면에서 멈췄어요.").forEach {
            assertTrue(it,ModelStatusText.acceptable(it,completed=false))
        }
    }
}
