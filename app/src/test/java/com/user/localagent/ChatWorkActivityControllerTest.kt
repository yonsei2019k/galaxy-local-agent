package com.user.localagent

import com.user.localagent.core.VerificationState
import com.user.localagent.data.*
import com.user.localagent.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ChatWorkActivityControllerTest {
    private fun run(status:String="running")=ChatRunRecord(id="run-a",threadId="thread-a",branchId="branch-a",projectId="project-a",
        threadRevision=0,projectRevision=0,status=status,startedAt=1000,updatedAt=5000,contextJson="{\"mode\":\"debug\"}")
    private fun trace(run:ChatRunRecord,sequence:Long,event:WorkTraceDraft)=WorkTraceRecord("event-$sequence",
        WorkTraceBinding(run.id,run.threadId,run.branchId,run.projectId,0,0),sequence,sequence*1000,event)
    private fun project(run:ChatRunRecord,records:List<WorkTraceRecord>,total:Long=records.size.toLong(),loading:Set<String> = emptySet())=
        mapWorkActivities("thread-a",listOf(WorkActivityTarget("assistant-a",run.id)),mapOf(run.id to "assistant-a"),mapOf(run.id to run),mapOf(run.id to records),mapOf(run.id to total),loading)

    @Test fun canonicalAssistantAndActualRunScopeAreRequiredAndForeignTraceIsOmitted(){
        val actual=run()
        val legitimate=trace(actual,1,WorkTraceDraft("plan",WorkTraceKind.PLAN,WorkTraceCode.PLAN_READY,publicSummary="Synthetic permitted goal"))
        val foreign=trace(actual.copy(threadId="thread-b"),2,WorkTraceDraft("foreign",WorkTraceKind.PLAN,WorkTraceCode.PLAN_READY,publicSummary="FOREIGN_PRIVATE_SUMMARY"))
        val otherRun=trace(actual.copy(id="run-b"),3,WorkTraceDraft("other",WorkTraceKind.PLAN,WorkTraceCode.PLAN_READY,publicSummary="OTHER_RUN_SUMMARY"))
        val activity=project(actual,listOf(legitimate,foreign,otherRun)).getValue("assistant-a")
        assertEquals(1,activity.events.size);assertTrue(activity.events.single().detail.contains("Synthetic permitted goal"))
        assertFalse(activity.events.any{it.detail.contains("FOREIGN")||it.detail.contains("OTHER_RUN")})
        assertTrue(project(actual.copy(threadId="thread-b"),listOf(legitimate)).isEmpty())
        assertTrue(mapWorkActivities("thread-a",listOf(WorkActivityTarget("forged-assistant",actual.id)),mapOf(actual.id to "assistant-a"),mapOf(actual.id to actual),emptyMap(),emptyMap()).isEmpty())
    }

    @Test fun oneInvocationCountsOnceWhileApprovalVerificationAndActualFileRemainVisible(){
        val actual=run();val args="1".repeat(64);val scope="2".repeat(64)
        val approval=trace(actual,1,WorkTraceDraft("approval",WorkTraceKind.APPROVAL,WorkTraceCode.APPROVAL_WAITING,"write_project_file","call-a",argsHash=args,scopeHash=scope))
        val start=trace(actual,2,WorkTraceDraft("start",WorkTraceKind.TOOL,WorkTraceCode.TOOL_STARTED,"write_project_file","call-a",argsHash=args,scopeHash=scope))
        val result=trace(actual,3,WorkTraceDraft("result",WorkTraceKind.TOOL,WorkTraceCode.TOOL_RESULT,"write_project_file","call-a",VerificationState.VERIFIED,"audit-a",args,scope))
        val verified=trace(actual,4,WorkTraceDraft("verify",WorkTraceKind.VERIFICATION,WorkTraceCode.VERIFICATION_RECORDED,"write_project_file","call-a",VerificationState.VERIFIED,"audit-a",args,scope))
        val file=WorkFileChange("sample.txt",WorkFileOperation.CREATED,null,"3".repeat(64),24,"4".repeat(64))
        val changed=trace(actual,5,WorkTraceDraft("file",WorkTraceKind.FILE,WorkTraceCode.FILE_CHANGE,"write_project_file","call-a",VerificationState.VERIFIED,"audit-a",args,scope,fileChange=file))
        val activity=project(actual,listOf(approval,start,result,verified,changed)).getValue("assistant-a")
        val counts=workEvidenceCounts(activity.events)
        assertEquals(1,counts.tools);assertEquals(1,counts.verified)
        assertEquals(1,activity.events.count{it.kind==UiWorkEventKind.CONTEXT})
        assertEquals(1,activity.events.count{it.kind==UiWorkEventKind.VERIFICATION})
        val change=activity.events.single{it.kind==UiWorkEventKind.FILE}.fileChanges.single()
        assertEquals("project-a",change.sourceProjectId);assertEquals(file.workspaceBinding,change.workspaceBinding);assertNull(change.diffRef)
        val tool=activity.events.single{it.kind==UiWorkEventKind.TOOL}
        assertEquals(start.atEpochMillis,tool.at);assertTrue(tool.detail.contains(Instant.ofEpochMilli(start.atEpochMillis).toString()))
        assertEquals(5L,activity.totalEvents);assertFalse(activity.hasOlder)
    }

    @Test fun publicReasonAndPlanStayVisibleWithoutLegacyCountersOrInferenceTelemetry(){
        val actual=run()
        val events=listOf(
            WorkTraceDraft("plan",WorkTraceKind.PLAN,WorkTraceCode.PLAN_READY,
                publicSummary="지난 약속을 확인해 가능한 시간을 비교하겠습니다.",summarySource=WorkSummarySource.MODEL_PLAN),
            WorkTraceDraft("legacy",WorkTraceKind.PLAN,WorkTraceCode.PLAN_READY,publicSummary="단계 14/36 · 선택: list_calendar_events"),
            WorkTraceDraft("inference",WorkTraceKind.INFERENCE,WorkTraceCode.INFERENCE_STARTED,publicSummary="모델 E4B · 문맥 8192"),
            WorkTraceCodec.prepare(WorkTraceDraft("choice",WorkTraceKind.RATIONALE,WorkTraceCode.DECISION_EXPLAINED,
                publicSummary="겹치는 일정이 있는지 먼저 보고 가능한 시간을 고르겠습니다.",summarySource=WorkSummarySource.MODEL_PLAN)),
        ).mapIndexed{index,event->trace(actual,index+1L,event)}
        val activity=project(actual,events).getValue("assistant-a")
        assertEquals(listOf("요청을 이렇게 이해했어요","이 방법을 고른 이유"),activity.events.map{it.label})
        assertEquals("겹치는 일정이 있는지 먼저 보고 가능한 시간을 고르겠습니다.",activity.events.last().detail)
        assertEquals(4L,activity.totalEvents)
    }

    @Test fun interruptedStartIsUnverifiedAndReservedQueuedAssistantHasActivityBeforeTokens(){
        val interrupted=run("interrupted")
        val start=trace(interrupted,1,WorkTraceDraft("start",WorkTraceKind.TOOL,WorkTraceCode.TOOL_STARTED,"save_note","call-a",argsHash="1".repeat(64),scopeHash="2".repeat(64)))
        val restored=project(interrupted,listOf(start)).getValue("assistant-a")
        assertEquals("interrupted",restored.status);assertEquals(UiWorkEvidence.UNVERIFIED,restored.events.single().evidence)
        assertEquals(0,workEvidenceCounts(restored.events).verified)
        val queued=project(run("queued").copy(startedAt=null),emptyList()).getValue("assistant-a")
        assertEquals("queued",queued.status);assertEquals(0L,queued.startedAt);assertEquals("debug",queued.mode);assertTrue(queued.events.isEmpty())
    }

    @Test fun olderPagingFillsTheNearestGapAndWindowFlagsCountRawRecordsNotCollapsedRows(){
        val actual=run()
        fun record(sequence:Long)=trace(actual,sequence,WorkTraceDraft("context.$sequence",WorkTraceKind.CONTEXT,WorkTraceCode.CONTEXT_READY))
        val scattered=(1L..3L).map(::record)+(100L..105L).map(::record)
        assertEquals(record(100).id,contiguousOlderCursor(scattered)?.id)
        assertEquals(record(1).id,contiguousOlderCursor(scattered+(4L..99L).map(::record))?.id)
        val activity=project(actual,scattered,total=200,loading=setOf(actual.id)).getValue("assistant-a")
        assertEquals(200L,activity.totalEvents);assertTrue(activity.hasOlder);assertTrue(activity.loadingOlder)
    }
}
