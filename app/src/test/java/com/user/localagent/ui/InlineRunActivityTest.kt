package com.user.localagent.ui

import org.junit.Assert.*
import org.junit.Test

class InlineRunActivityTest {
    private fun activity(events:List<UiRunActivityEvent> = emptyList())=UiRunActivity("run-a","thread-a","assistant-a","completed",events=events)
    @Test fun activityRequiresExactRoomAndReservedAssistantId(){
        assertTrue(activityMatchesMessage(activity(),"thread-a","assistant-a"))
        assertFalse(activityMatchesMessage(activity(),"thread-b","assistant-a"))
        assertFalse(activityMatchesMessage(activity(),"thread-a","assistant-b"))
        assertFalse(activityMatchesMessage(null,"thread-a","assistant-a"))
    }
    @Test fun completedRunDoesNotBecomeVerifiedWorkAndMetadataCannotAddEvidence(){
        val events=listOf(UiRunActivityEvent("plan",UiWorkEventKind.PLAN,"Synthetic plan",evidence=UiWorkEvidence.VERIFIED),
            UiRunActivityEvent("context",UiWorkEventKind.CONTEXT,"Read skill",evidence=UiWorkEvidence.VERIFIED),
            UiRunActivityEvent("answer",UiWorkEventKind.ANSWER,"Answer finished",evidence=UiWorkEvidence.VERIFIED))
        assertEquals("응답 종료",activityStatusLabel("completed"))
        assertEquals(UiWorkEvidenceCounts(0,0,0,0),workEvidenceCounts(events))
        assertEquals(UiWorkEvidence.NONE,displayedWorkEvidence(events[1]))
        assertEquals(UiWorkEvidence.FAILED,displayedWorkEvidence(events[1].copy(evidence=UiWorkEvidence.FAILED)))
    }
    @Test fun actualToolOutcomesKeepPreparedFailedAndVerifiedSeparate(){
        val events=listOf(UiRunActivityEvent("read",UiWorkEventKind.TOOL,"Read back",evidence=UiWorkEvidence.VERIFIED),
            UiRunActivityEvent("draft",UiWorkEventKind.TOOL,"Draft",evidence=UiWorkEvidence.PREPARED),
            UiRunActivityEvent("failed",UiWorkEventKind.TOOL,"Failed read",evidence=UiWorkEvidence.FAILED))
        assertEquals(UiWorkEvidenceCounts(3,1,1,1),workEvidenceCounts(events))
        assertTrue(workEvidenceLabel(UiWorkEvidence.PREPARED).contains("완료 미확인"))
    }
    @Test fun updatingSameActualEventDoesNotInflateToolCounts(){
        val pending=UiRunActivityEvent("same-call",UiWorkEventKind.TOOL,"Read",evidence=UiWorkEvidence.PENDING)
        val saved=visibleActivityEvents(activity(listOf(pending,pending.copy(evidence=UiWorkEvidence.VERIFIED))))
        assertEquals(1,saved.size);assertEquals(UiWorkEvidenceCounts(1,1,0,0),workEvidenceCounts(saved))
    }
    @Test fun fileAndVerificationRowsDoNotTripleTheSameVerifiedToolOutcome(){
        val events=listOf(UiRunActivityEvent("tool",UiWorkEventKind.TOOL,"Write",evidence=UiWorkEvidence.VERIFIED),
            UiRunActivityEvent("file",UiWorkEventKind.FILE,"Changed file",evidence=UiWorkEvidence.VERIFIED),
            UiRunActivityEvent("readback",UiWorkEventKind.VERIFICATION,"Read back",evidence=UiWorkEvidence.VERIFIED))
        assertEquals(UiWorkEvidenceCounts(1,1,0,0),workEvidenceCounts(events))
    }
    @Test fun rawInternalReasoningMarkupNeverAppearsInLabelsOrDetails(){
        val events=listOf(UiRunActivityEvent("bad",UiWorkEventKind.RATIONALE,"<think>private internal text</think>"),
            UiRunActivityEvent("safe",UiWorkEventKind.TOOL,"Public execution label",detail="<analysis>private hidden text</analysis>"))
        val saved=visibleActivityEvents(activity(events))
        assertEquals(1,saved.size);assertEquals("Public execution label",saved.single().label);assertEquals("",saved.single().detail)
    }
    @Test fun fileReferencesPreserveTheCapturedProjectAndWorkspaceInsteadOfCurrentSelection(){
        val file=UiRunFileChange("src/Example.kt",UiFileChangeOperation.UPDATED,diffRef="stored-diff-a",sourceProjectId="project-a",workspaceBinding="captured-workspace-a")
        val event=UiRunActivityEvent("change",UiWorkEventKind.FILE,"Verified synthetic file change",fileChanges=listOf(file))
        val visible=visibleActivityEvents(activity(listOf(event))).single().fileChanges.single()
        assertEquals(file,visible);assertEquals("project-a",visible.sourceProjectId);assertEquals("captured-workspace-a",visible.workspaceBinding)
    }
    @Test fun anAccidentallySuppliedFullBodyIsNotCopiedIntoAnActivityDetail(){
        val event=UiRunActivityEvent("large",UiWorkEventKind.ANSWER,"Response metadata",detail="SYNTHETIC_FULL_ANSWER".repeat(1000))
        val detail=visibleActivityEvents(activity(listOf(event))).single().detail
        assertTrue(detail.length<1600);assertTrue(detail.contains("표시하지 않았습니다"));assertFalse(detail.contains("SYNTHETIC_FULL_ANSWER"))
    }
}
