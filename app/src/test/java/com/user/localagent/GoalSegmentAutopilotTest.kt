package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import com.user.localagent.data.ChatAccessScope
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic host checkpoints only. These do not establish actual model/device workflow success. */
class GoalSegmentAutopilotTest {
    private val started=1_000_000L
    private fun goal(ids:List<String> = listOf("run-a"))=GoalDefinition("goal-a","thread","project","Verify the requested QA result",
        listOf(GoalCriterion("criterion","Actual result is verified")),status=GoalStatus.ACTIVE,linkedRunIds=ids,updatedAt=20)
    private fun step(query:String="QA",state:VerificationState=VerificationState.VERIFIED,noEffect:Boolean=false)=HarnessStep("search_notes",
        buildJsonObject {put("query",query)},StepEvidence("search_notes",state,"Synthetic exact host result",effectNotApplied=noEffect,
            progressDigest=if(state==VerificationState.VERIFIED&&!noEffect)goalDigest("observed record $query") else null))
    private fun segment(goal:GoalDefinition=goal())=GoalSegmentBinding(goal.id,10,goalWorkDigest(goal),"scope",4,false,true,
        "run-a",null,null,1,started,0,0)
    private fun request(steps:List<HarnessStep> = listOf(step()),binding:GoalSegmentBinding=segment(),ledger:List<GoalLedgerEntry> = emptyList())=
        QueuedChatRequest("run-a","thread","branch","project","QA request","cloud-model",false,emptyList(),"user-a",
            previousSteps=steps,workspaceBinding="workspace",goal=goal((listOf("run-a")+ledger.map {it.sourceRunId}).distinct()),capturedPcRevision=5,allowExternalAgent=true,externalAgentRevision=7,
            goalSegment=binding,goalLedger=ledger)
    /** active=null models a run written before segment active time was recorded. */
    private fun context(request:QueuedChatRequest,active:Long?)=active?.let {JsonObject(Json.parseToJsonElement(request.toJson()).jsonObject+("goalActiveMs" to JsonPrimitive(it))).toString()}?:request.toJson()
    private fun row(request:QueuedChatRequest=request(),status:String="paused",errorCode:String?="reasoning_budget_exhausted",active:Long?=100,
        startedAt:Long?=null,finishedAt:Long?=null)=
        ChatRunRecord(id=request.runId,threadId=request.threadId,branchId=request.branchId,userMessageId=request.userMessageId,
            projectId=request.projectId,modelId=request.modelId,thinking=request.thinking,status=status,contextJson=context(request,active),errorCode=errorCode,
            updatedAt=started+10,startedAt=startedAt,finishedAt=finishedAt)
    private fun decide(request:QueuedChatRequest=request(),status:String="paused",errorCode:String?="reasoning_budget_exhausted",now:Long=started+100,
        unknown:Boolean=false,goal:GoalDefinition=checkNotNull(request.goal),active:Long?=100)=goalSegmentDecision(row(request,status,errorCode,active),goal,"scope",now,unknown)
    private fun pause(expected:GoalAutopilotPauseReason,actual:GoalSegmentDecision)=assertEquals(GoalSegmentDecision.Pause(expected),actual)
    private fun assertReplanScheduled(request:QueuedChatRequest) {
        assertTrue(goalTransientFailure(row(request)))
        val next=decide(request) as GoalSegmentDecision.Continue
        assertEquals(request.goalSegment!!.noProgressSegments,next.binding.noProgressSegments)
        assertTrue(next.binding.transientRetries>0)
    }
    private fun child(parent:QueuedChatRequest,next:GoalSegmentDecision.Continue)=parent.copy(runId="run-b",userMessageId="user-b",
        previousSteps=emptyList(),goal=goal(listOf("run-a","run-b")),goalSegment=next.binding,goalLedger=next.ledger)

    @Test fun nextSegmentStartsWithAnEmptyLocalBudgetAndRetainsEveryOriginalPosition() {
        val parent=request((1..18).map {step("QA-$it")})
        val next=decide(parent) as GoalSegmentDecision.Continue
        val child=child(parent,next)
        assertTrue(child.previousSteps.isEmpty());assertEquals(18,child.goalLedger.size)
        assertEquals(18,next.binding.consumedToolCalls);assertEquals(2,next.binding.segmentIndex)
        assertEquals(10L,next.binding.originalGoalVersion);assertEquals("run-a",next.binding.rootRunId)
        assertEquals(goalDigest(row(parent).contextJson),next.binding.parentCheckpointDigest)
        assertEquals((0..17).toList(),child.goalLedger.map {it.stepIndex})
        assertEquals(child.toJson(),QueuedChatRequest.fromJson(child.toJson()).toJson())
        assertEquals(child,QueuedChatRequest.fromJson(child.toJson()))
    }
    @Test fun unknownRuntimeEffectCannotBeReplayedEvenWhenAllVisibleStepsSayVerified() {
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(unknown=true))
    }
    @Test fun unverifiedStepsAndKnownFailuresAreRetainedAndStopAutomaticContinuation() {
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(request(listOf(step(state=VerificationState.UNVERIFIED)))))
        for(noEffect in listOf(false,true)) {
            val failed=HarnessStep("update_note",buildJsonObject {put("id","QA-id");put("content","QA corrected");put("scope","QA")},
                StepEvidence("update_note",VerificationState.FAILED,"Synthetic failure",effectNotApplied=noEffect))
            val parent=request(listOf(failed))
            assertEquals(VerificationState.FAILED,completedGoalLedger(parent).single().step.evidence.state)
            if(noEffect)assertTrue(decide(parent) is GoalSegmentDecision.Continue)
            else pause(GoalAutopilotPauseReason.FAILED_STEP,decide(parent))
        }
    }
    @Test fun pendingExternalEffectDoesNotBecomeCompletedOrAnAutomaticNextSegment() {
        val prepared=HarnessStep("prepare_sms",buildJsonObject {put("phoneNumber","01000000000");put("message","QA")},
            StepEvidence("prepare_sms",VerificationState.PREPARED,"Prepared only"))
        pause(GoalAutopilotPauseReason.OWNER_HANDOFF,decide(request(listOf(prepared))))
    }
    @Test fun screenOpeningIsPreparedWithoutInventingAProviderEffect() {
        val opener=HarnessStep("open_url",buildJsonObject {put("url","https://example.com")},StepEvidence("open_url",VerificationState.PREPARED,"Opened only"))
        assertTrue(decide(request(listOf(opener))) is GoalSegmentDecision.Continue)
    }
    @Test fun missingReadbackProofNeverDisappearsAcrossARunBoundary() {
        val write=HarnessStep("save_note",buildJsonObject {put("content","QA content")},StepEvidence("save_note",VerificationState.VERIFIED,"Saved"))
        pause(GoalAutopilotPauseReason.REQUIRED_READBACK,decide(request(listOf(write))))
    }
    @Test fun pausedClarificationCancelledFailedAndInterruptedParentsCannotContinue() {
        pause(GoalAutopilotPauseReason.OWNER_HANDOFF,decide(errorCode="awaiting_owner_input"))
        for(status in listOf("cancelled","interrupted","failed","running","queued","waiting_approval"))
            pause(GoalAutopilotPauseReason.STOPPED,decide(status=status))
    }
    @Test fun partialAndInterruptedCheckpointsCanContinueOnlyWithoutUnknownEffects() {
        for(code in listOf("partial_verified_browser_reads","partial_verified_tool_budget","partial_recoverable_provider")) {
            assertTrue(decide(errorCode=code) is GoalSegmentDecision.Continue)
            pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(errorCode=code,unknown=true))
        }
        assertTrue(decide(status="interrupted",errorCode="process_restarted") is GoalSegmentDecision.Continue)
        pause(GoalAutopilotPauseReason.STOPPED,decide(status="interrupted",errorCode="process_restarted_approval"))
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(status="interrupted",errorCode="process_restarted",unknown=true))
    }

    @Test fun browserProgressUsesObservedPageRatherThanSnapshotOrCallArguments() {
        fun read(snapshot:String,body:String):HarnessStep {
            val call=ToolCall("fixture","read_app_browser_page",buildJsonObject {})
            val result=ToolResult(call.id,true,buildJsonObject {
                put("snapshotId",snapshot);put("url","https://example.com/status");put("title","Reservation");put("text",body)
            }.toString(),VerificationState.VERIFIED,"Observed page")
            return HarnessStep(call,StepEvidence(call.name,result.verification,result.verificationSummary,
                progressDigest=observedGoalProgress(call,result)))
        }
        val old=read("old-snapshot","Reservation pending")
        val history=listOf(GoalLedgerEntry("past",0,old))
        val binding=segment().copy(consumedToolCalls=1,noProgressSegments=2)
        assertReplanScheduled(request(listOf(read("new-snapshot","Reservation pending")),binding,history))
        val changed=request(listOf(read("new-snapshot","Reservation confirmed")),binding,history)
        assertReplanScheduled(changed)
        assertEquals(changed.toJson(),QueuedChatRequest.fromJson(changed.toJson()).toJson())
    }

    @Test fun verifiedNavigationCountsOncePerDestinationAcrossChangingPageText() {
        val call = ToolCall("link", PcBrowserToolCatalog.ACTION, buildJsonObject {
            put("action","click"); put(BrowserNavigationRule.LABEL,"자료"); put(BrowserNavigationRule.VERIFY,"자료 목록")
        })
        fun milestone(path:String, text:String, status:String):String? {
            val result=ToolResult(call.id,true,buildJsonObject {
                put("url","https://example.com/$path"); put("text",text); put("navigationRuleStatus",status)
            }.toString(),VerificationState.VERIFIED,BrowserNavigationRule.VERIFIED_SUMMARY)
            return goalMilestoneKey(HarnessStep(call,StepEvidence(call.name,result.verification,result.verificationSummary,
                progressDigest=observedGoalProgress(call,result))))
        }
        val first=milestone("materials","10:00","verified")
        assertNotNull(first)
        assertEquals(first,milestone("materials","10:01","verified"))
        assertNotEquals(first,milestone("archive","10:01","verified"))
        assertNull(milestone("materials","10:02","unverified"))
    }

    @Test fun criterionEvidenceIsBoundToRealCurrentOrInheritedAuditSteps() {
        val original=step()
        val call=ToolCall("test-file","write_project_file",buildJsonObject {put("path","result.txt");put("content","QA")})
        val result=ToolResult(call.id,true,buildJsonObject {
            put("verified",true);put("path","result.txt");put("sha256","a".repeat(64))
        }.toString(),VerificationState.VERIFIED,"Saved and checked")
        val verified=HarnessStep(call,StepEvidence(call.name,result.verification,result.verificationSummary,
            auditId="real-proof",outcomeProof=goalOutcomeProof(call,result)))
        val checkpoint=request(listOf(original),segment().copy(consumedToolCalls=1),listOf(GoalLedgerEntry("past",0,verified)))
        assertEquals(setOf("real-proof"),goalCriterionEvidence(checkpoint,checkpoint.previousSteps).keys)
        val expected=GoalCriterion("artifact","Requested file",proofTarget=GoalProofTarget(GoalProofKind.PROJECT_FILE,"result.txt"))
        assertTrue(goalCriterionMatches(expected,verified))
        assertFalse(goalCriterionMatches(expected,original))
        assertFalse(goalCriterionMatches(expected.copy(proofTarget=GoalProofTarget(GoalProofKind.PROJECT_FILE,"other.txt")),verified))
        assertFalse(goalCriterionMatches(expected.copy(proofTarget=null),verified))
        assertEquals(checkpoint.toJson(),QueuedChatRequest.fromJson(checkpoint.toJson()).toJson())
        assertFalse(goalCriterionEvidenceTool(GoalProgressToolCatalog.VERIFY))
    }

    @Test fun knownPendingFileReadbackQueuesOnlyTheBoundReadWithoutDroppingPriorMutation() {
        val mutation=HarnessStep("write_project_file",buildJsonObject {put("path","result.txt");put("content","QA")},
            StepEvidence("write_project_file",VerificationState.VERIFIED,"Saved file"))
        val original=request(listOf(mutation))
        val scope=RequiredReadbackObligations.Context(original.runId,original.threadId,original.projectId,"scope",original.workspaceBinding)
        val selected=RequiredReadbackObligations.select(null,scope,setOf("write_project_file","read_project_file"))
        val expected=RequiredReadbackPolicy.Snapshot(RequiredReadbackPolicy.Resource("project_file","result.txt"),"r1","a".repeat(64))
        val applied=RequiredReadbackPolicy.Mutation(scope.proofBinding(),0,mutation.tool,expected)
        val state=RequiredReadbackObligations.capture(selected,scope,listOf(mutation),listOf(applied),emptyList(),true)
        val parent=original.copy(readbackObligations=state)
        val decision=decide(parent,errorCode="execution_not_verified") as GoalSegmentDecision.Continue
        val seed=checkNotNull(decision.readback)
        val queued=child(parent,decision).copy(previousSteps=seed.steps,readbackObligations=seed.forRun("run-b"),goalReadbackSeedCount=1)
        requireGoalLaunchBinding(queued,queued.goal,row(parent,errorCode="execution_not_verified"),false,started+200)
        val pending=RequiredReadbackObligations.assess(queued.readbackObligations,scope.copy(runId="run-b"),
            queued.previousSteps,1,setOf("read_project_file"),6,1).pending
        val read=checkNotNull(goalReadbackCalls(queued.previousSteps,pending)).single()
        assertEquals("read_project_file",read.name)
        assertEquals("result.txt",read.arguments.getValue("path").jsonPrimitive.content)
        assertEquals(1,completedGoalLedger(queued).size) // the inherited write stays in the ledger exactly once
        assertThrows(IllegalStateException::class.java) {
            requireGoalLaunchBinding(queued.copy(previousSteps=emptyList(),goalReadbackSeedCount=0,readbackObligations=null),
                queued.goal,row(parent,errorCode="execution_not_verified"),false,started+200)
        }
    }

    @Test fun ownerReplyQueuesReadbackOfTheLastSettledWrite() {
        val mutation=HarnessStep("write_project_file",buildJsonObject {put("path","result.txt");put("content","QA")},
            StepEvidence("write_project_file",VerificationState.VERIFIED,"Saved file"))
        val original=request(listOf(mutation))
        val scope=RequiredReadbackObligations.Context(original.runId,original.threadId,original.projectId,"scope",original.workspaceBinding)
        val selected=RequiredReadbackObligations.select(null,scope,setOf("write_project_file","read_project_file"))
        val expected=RequiredReadbackPolicy.Snapshot(RequiredReadbackPolicy.Resource("project_file","result.txt"),"r1","a".repeat(64))
        val applied=RequiredReadbackPolicy.Mutation(scope.proofBinding(),0,mutation.tool,expected)
        val state=RequiredReadbackObligations.capture(selected,scope,listOf(mutation),listOf(applied),emptyList(),true)
        val parent=original.copy(readbackObligations=state)
        val paused=row(parent,errorCode=GOAL_OWNER_INPUT)
        val sources=listOf(GoalRunEvidenceSource(parent.runId,parent.threadId,parent.projectId,paused.status,paused.contextJson))
        val live=goal(listOf("run-a","run-b"))
        val seed=checkNotNull(GoalReadbackSeed.from(parent,assessGoalReadback(parent,"scope")))
        assertThrows(IllegalStateException::class.java) {collectGoalLedger(live,sources,"workspace","scope")}
        val ledger=collectGoalLedger(live,sources,"workspace","scope",parent.runId)
        val queued=parent.copy(runId="run-b",userMessageId="user-b",goal=live,
            goalSegment=segment(live).copy(rootRunId="run-b",consumedToolCalls=ledger.size,totalSegments=2),
            goalLedger=ledger,goalReadbackSeedCount=seed.steps.size,previousSteps=seed.steps,readbackObligations=seed.forRun("run-b"),
            goalOwnerReplyParentId=parent.runId,goalOwnerReplyParentDigest=goalDigest(paused.contextJson),
            browserWorkRootRunId=parent.runId)
        val restored=QueuedChatRequest.fromJson(queued.toJson())
        requireGoalLaunchBinding(restored,live,paused,false,started+200)
        assertEquals(1,completedGoalLedger(restored).size)
        val childScope=scope.copy(runId=restored.runId)
        val read=HarnessStep("read_project_file",buildJsonObject {put("path","result.txt")},
            StepEvidence("read_project_file",VerificationState.VERIFIED,"Read the same version"))
        val verifiedSteps=restored.previousSteps+read
        val readProof=RequiredReadbackPolicy.Read(childScope.proofBinding(),1,read.tool,listOf(expected))
        val verified=restored.copy(previousSteps=verifiedSteps,readbackObligations=RequiredReadbackObligations.capture(
            restored.readbackObligations,childScope,verifiedSteps,emptyList(),listOf(readProof),true))
        val verifiedSources=sources+GoalRunEvidenceSource(verified.runId,verified.threadId,verified.projectId,"completed",verified.toJson())
        assertEquals(listOf(mutation.tool,read.tool),collectGoalLedger(live,verifiedSources,"workspace","scope").map{it.step.tool})
        assertThrows(IllegalStateException::class.java) {
            requireGoalLaunchBinding(restored,live,paused.copy(errorCode="reasoning_budget_exhausted"),false,started+200)
        }
    }

    @Test fun anotherTasksBrowserPageCannotBeReadOrInherited() {
        val parent=com.user.localagent.integrations.AppWebAgent.WorkBinding("parent","thread","branch")
        val child=parent.copy(runId="child")
        val concurrent=parent.copy(runId="other",threadId="different")
        assertFalse(com.user.localagent.integrations.ownsBrowserPage(concurrent,parent))
        assertTrue(com.user.localagent.integrations.canInheritBrowserPage(parent,parent,child))
        assertFalse(com.user.localagent.integrations.canInheritBrowserPage(concurrent,parent,child))
        assertFalse(com.user.localagent.integrations.canInheritBrowserPage(parent,parent,child.copy(branchId="different")))
    }

    @Test fun timedWaitExcludesOnlyHostRecordedIdleTimeAndStillRequiresVerifiedCheckpoint() {
        val parkedAt=started+3_000
        val wokeAt=parkedAt+86_400_000
        val wait=HarnessStep(GoalWaitToolCatalog.WAIT,buildJsonObject {
            put("goalId","goal-a");put("expectedUpdatedAt","20")
            put("condition",buildJsonObject {put("kind","at_time");put("atEpochMillis",wokeAt);put("needsPlayback",false)})
        },StepEvidence(GoalWaitToolCatalog.WAIT,VerificationState.VERIFIED,"Wait recorded"))
        val request=request(listOf(step(),wait))
        val parked=row(request,errorCode="waiting_goal_condition")
        fun decideParked(parent:ChatRunRecord,unknown:Boolean=false)=
            goalSegmentDecision(parent,checkNotNull(request.goal),"scope",wokeAt,unknown)
        pause(GoalAutopilotPauseReason.OWNER_HANDOFF,decideParked(parked))
        val woken=parked.copy(contextJson=JsonObject(Json.parseToJsonElement(parked.contextJson).jsonObject+
            ("hostGoalWake" to buildJsonObject {put("parkedAt",parkedAt);put("wokeAt",wokeAt)})).toString())
        val next=decideParked(woken) as GoalSegmentDecision.Continue
        assertEquals(3_000L,wokeAt-next.binding.startedAtMs)
        assertEquals(2,next.binding.consumedToolCalls)
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decideParked(woken,true))
    }

    @Test fun parkedPcAuthenticationContinuesWithTheOriginalWorkIdentity() {
        val request=request(listOf(HarnessStep("open_pc_browser_page",buildJsonObject {
            put("url","https://www.kairb.org/cme/cme_ing.php")
        },StepEvidence("open_pc_browser_page",VerificationState.FAILED,"PHONE_BROWSER_AUTH_REQUIRED",effectNotApplied=true))))
        val now=started+5_000L
        val row=row(request,errorCode="waiting_goal_condition")
        val woken=row.copy(contextJson=JsonObject(Json.parseToJsonElement(row.contextJson).jsonObject+
            ("hostGoalWake" to buildJsonObject {
                put("event",WakeEvent.OWNER_AUTH_DONE.name);put("parkedAt",started+1_000L);put("wokeAt",now)
            })).toString())
        val decision=goalSegmentDecision(woken,checkNotNull(request.goal),"scope",now,false) as GoalSegmentDecision.Continue
        val resumed=child(request,decision)
        assertEquals(pcBrowserWorkKey(request),pcBrowserWorkKey(resumed))
        assertNotEquals(pcBrowserWorkKey(request),pcBrowserWorkKey(resumed.copy(branchId="other")))
    }

    @Test fun aRedirectedPdfIsNotProofOfTheRequestedDocument() {
        val original="https://example.org/current.pdf"
        val call=ToolCall("pdf","save_public_pdf",buildJsonObject {put("url",original)})
        fun result(actual:String)=ToolResult(call.id,true,buildJsonObject {
            put("mimeType","application/pdf");put("pages",1);put("fileName","result.pdf")
            put("sourceUrl",actual);put("sha256","a".repeat(64))
        }.toString(),VerificationState.VERIFIED,"saved")
        assertNotNull(goalOutcomeProof(call,result(original)))
        assertNull(goalOutcomeProof(call,result("https://example.org/another.pdf")))
    }

    @Test fun completedCourseReadMatchesOnlyTheExactOfficialCourseListCriterion() {
        val title="Synthetic IRB Course"
        val proof=GoalProofTarget(GoalProofKind.KAIRB_COMPLETED_COURSE,title)
        val call=ToolCall("course",PcBrowserToolCatalog.READ,buildJsonObject {
            put(PcBrowserToolCatalog.COURSE_TITLE,title)
        })
        fun result(source:String,matched:String)=ToolResult(call.id,true,buildJsonObject {
            put("url",source);put("verifiedCompletedCourseTitle",matched)
        }.toString(),VerificationState.VERIFIED,"Observed course row")
        val official=result(PcBrowserToolCatalog.KAIRB_COMPLETED_URL,title)
        val step=HarnessStep(call,StepEvidence(call.name,official.verification,official.verificationSummary,
            auditId="official-course-read",outcomeProof=goalOutcomeProof(call,official)))
        val criterion=GoalCriterion(text=proof.courseListCriterion(),proofTarget=proof)
        assertTrue(goalCriterionMatches(criterion,step))
        assertFalse(goalCriterionMatches(criterion.copy(text="올해 이수증 발급"),step))
        assertNull(goalOutcomeProof(call,result(PcBrowserToolCatalog.KAIRB_COMPLETED_URL,"Different title")))
        assertNull(goalOutcomeProof(call,result("https://example.org/status",title)))
    }

    @Test fun browserPdfProofRequiresTheExactSourceAndSavedDocumentReceipt() {
        val target=GoalProofTarget(GoalProofKind.BROWSER_PDF_SOURCE,"https://example.org/receipt.pdf")
        val call=ToolCall("pdf","save_browser_pdf",buildJsonObject {put("snapshotId","snapshot");put("ref","e1")})
        val receipt=buildJsonObject {
            put("mimeType","application/pdf");put("pages",1);put("fileName","receipt.pdf");put("sha256","b".repeat(64))
        }
        fun proof(source:String?)=goalOutcomeProof(call,ToolResult(call.id,true,
            JsonObject(if(source==null)receipt else receipt+("sourceSha256" to JsonPrimitive(goalDigest(source)))).toString(),
            VerificationState.VERIFIED,"Saved"))
        val step=HarnessStep(call,StepEvidence(call.name,VerificationState.VERIFIED,"Saved",auditId="pdf-saved",outcomeProof=proof(target.target)))
        val criterion=GoalCriterion(text=target.browserPdfCriterion(),proofTarget=target)
        assertTrue(goalCriterionMatches(criterion,step))
        assertFalse(goalCriterionMatches(criterion.copy(text="올해 이수증 발급"),step))
        assertNotEquals(goalOutcomeDigest(target),proof("https://example.org/other.pdf"))
        assertNull(proof(null))
    }

    @Test fun completedBrowserGoalGetsOneRestrictedArtifactPass() {
        val done=goal().let { it.copy(criteria=it.criteria.map { c->c.copy(status=GoalCriterionStatus.USER_CONFIRMED) }) }
        val observation=HarnessStep(PcBrowserToolCatalog.READ,buildJsonObject {},
            StepEvidence(PcBrowserToolCatalog.READ,VerificationState.VERIFIED,"Observed official page"))
        val earlierPdf=HarnessStep("save_browser_pdf",buildJsonObject {put("snapshotId","observed");put("ref","e1")},
            StepEvidence("save_browser_pdf",VerificationState.VERIFIED,"Saved another PDF"))
        val history=listOf(GoalLedgerEntry("earlier",0,observation),GoalLedgerEntry("earlier",1,earlierPdf))
        val fullGoal=done.copy(linkedRunIds=listOf("earlier","run-a"))
        val source=request(emptyList(),binding=segment(fullGoal).copy(consumedToolCalls=history.size),ledger=history).copy(goal=fullGoal)
        val next=goalSegmentDecision(row(source),fullGoal,"scope",started+100L,false) as GoalSegmentDecision.Continue
        assertTrue(next.binding.artifactWrapUp)
        assertTrue("save_browser_pdf" in goalArtifactWrapUpTools)
        assertFalse("act_on_pc_browser_page" in goalArtifactWrapUpTools)
        val linked=fullGoal.copy(linkedRunIds=fullGoal.linkedRunIds+"run-b")
        val wrapped=source.copy(runId="run-b",userMessageId="user-b",goal=linked,goalSegment=next.binding,goalLedger=next.ledger)
        assertTrue(QueuedChatRequest.fromJson(wrapped.toJson()).goalSegment!!.artifactWrapUp)
        assertEquals(GoalSegmentDecision.Complete,goalSegmentDecision(row(wrapped),linked,"scope",started+200L,false))
        val needsAuth=wrapped.copy(previousSteps=listOf(HarnessStep(PcBrowserToolCatalog.READ,buildJsonObject {},
            StepEvidence(PcBrowserToolCatalog.READ,VerificationState.FAILED,
                com.user.localagent.compute.BrowserAgentTools.PHONE_AUTH_REQUIRED,effectNotApplied=true))))
        fun wake(request:QueuedChatRequest):ChatRunRecord {
            val parked=row(request,errorCode="waiting_goal_condition")
            return parked.copy(contextJson=JsonObject(Json.parseToJsonElement(parked.contextJson).jsonObject+
                ("hostGoalWake" to buildJsonObject {
                    put("parkedAt",started+175);put("wokeAt",started+190);put("event","OWNER_AUTH_DONE")
                })).toString())
        }
        val resumed=goalSegmentDecision(wake(needsAuth),linked,"scope",started+200L,false) as GoalSegmentDecision.Continue
        assertTrue(resumed.binding.artifactWrapUp)
        assertTrue(resumed.binding.artifactAuthResumed)
        val finalGoal=linked.copy(linkedRunIds=linked.linkedRunIds+"run-c")
        val last=needsAuth.copy(runId="run-c",userMessageId="user-c",goal=finalGoal,
            goalSegment=resumed.binding,goalLedger=resumed.ledger)
        assertEquals(GoalSegmentDecision.Complete,goalSegmentDecision(wake(last),finalGoal,"scope",started+210L,false))
    }

    @Test fun eightSegmentsAndThirtyMinutesArePausesRatherThanGoalCompletion() {
        val eighth=segment().copy(rootRunId="root-run",segmentIndex=8,totalSegments=8,parentRunId="previous",parentCheckpointDigest="a".repeat(64))
        pause(GoalAutopilotPauseReason.SEGMENT_BUDGET,decide(request(emptyList(),binding=eighth)))
        pause(GoalAutopilotPauseReason.TIME_BUDGET,decide(request(emptyList()),active=GoalSegmentBinding.MAX_DURATION_MS))
        val extended=decide(request(binding=eighth)) as GoalSegmentDecision.Continue
        assertEquals(9,extended.binding.totalSegments)
        assertEquals(8,extended.binding.phaseStartSegments)
        assertEquals(1,extended.binding.phaseStartCalls)
        assertEquals(144,goalToolRoom(extended.binding,0))
        val fourthPhase=eighth.copy(totalSegments=32,phaseStartSegments=24)
        assertEquals(33,(decide(request(binding=fourthPhase)) as GoalSegmentDecision.Continue).binding.totalSegments)
        pause(GoalAutopilotPauseReason.SEGMENT_BUDGET,decide(request(emptyList(),binding=fourthPhase)))
        val spent=segment().copy(activeElapsedMs=GoalSegmentBinding.MAX_DURATION_MS-100)
        pause(GoalAutopilotPauseReason.TIME_BUDGET,decide(request(emptyList(),binding=spent),active=100))
        assertTrue(decide(request(emptyList(),binding=spent),active=99) is GoalSegmentDecision.Continue)
        assertEquals(GoalStatus.ACTIVE,goal().status)
    }
    @Test fun thirtyMinutesCountActiveSegmentTimeNotWallClockGapsOrClockSteps() {
        val binding=segment().copy(activeElapsedMs=120_000)
        for(now in listOf(started-1,started+10*GoalSegmentBinding.MAX_DURATION_MS)) {
            val next=decide(request(binding=binding),now=now,active=60_000) as GoalSegmentDecision.Continue
            assertEquals(180_000L,next.binding.activeElapsedMs)
        }
    }
    @Test fun aParentWithoutRecordedActiveTimeIsChargedItsWallSpanOrTheWholeRemainder() {
        val parent=request(binding=segment().copy(activeElapsedMs=1_000))
        val spanned=goalSegmentDecision(row(parent,active=null,startedAt=50_000,finishedAt=55_000),checkNotNull(parent.goal),"scope",started+100,false) as GoalSegmentDecision.Continue
        assertEquals(6_000L,spanned.binding.activeElapsedMs)
        pause(GoalAutopilotPauseReason.TIME_BUDGET,goalSegmentDecision(row(parent.copy(previousSteps=emptyList()),active=null),checkNotNull(parent.goal),"scope",started+100,false))
        for(bad in listOf(-1L,GoalSegmentBinding.MAX_DURATION_MS+1))
            pause(GoalAutopilotPauseReason.TIME_BUDGET,decide(parent.copy(previousSteps=emptyList()),active=bad))
    }
    @Test fun aLegacyBindingWithoutActiveTimeIsReadConservativelyAndNeverRenewed() {
        val encoded=Json.parseToJsonElement(request().toJson()).jsonObject
        val added=setOf("activeElapsedMs","budgetWindow","budgetBaseToolCalls","transientRetries")
        val legacy=JsonObject(encoded+("goalSegment" to JsonObject(encoded.getValue("goalSegment").jsonObject-added))).toString()
        val partial=JsonObject(encoded+("goalSegment" to JsonObject(encoded.getValue("goalSegment").jsonObject-"budgetWindow"))).toString()
        assertTrue(runCatching {QueuedChatRequest.fromJson(partial)}.isFailure)
        assertEquals(0,QueuedChatRequest.fromJson(legacy).goalSegment!!.budgetWindow)
        fun readAtQueueTime(time: Long?): Long {
            val envelope = Json.parseToJsonElement(legacy).jsonObject
            val saved = if (time == null) envelope - "queueOrder" else envelope + ("queueOrder" to JsonPrimitive(time))
            return checkNotNull(QueuedChatRequest.fromJson(JsonObject(saved).toString()).goalSegment).activeElapsedMs
        }
        assertEquals(1_000L, readAtQueueTime(started+1_000))
        assertEquals(GoalSegmentBinding.MAX_DURATION_MS, readAtQueueTime(started+5*GoalSegmentBinding.MAX_DURATION_MS))
        assertEquals(GoalSegmentBinding.MAX_DURATION_MS, readAtQueueTime(started-5))
        assertEquals(GoalSegmentBinding.MAX_DURATION_MS, readAtQueueTime(null))
        val negative=JsonObject(encoded+("goalSegment" to JsonObject(encoded.getValue("goalSegment").jsonObject+("activeElapsedMs" to JsonPrimitive(-1))))).toString()
        assertTrue(runCatching {QueuedChatRequest.fromJson(negative)}.isFailure)
        val plain=request()
        assertEquals(plain.toJson(),QueuedChatRequest.fromJson(context(plain,100)).toJson()) // The run-context key is not request state.
    }
    @Test fun legacyParentProducesTheSameChildAfterAnOvernightQueueWait() {
        val original = request().copy(queueOrder=started+1_000)
        val parent = row(original, active=2_000)
        val envelope = Json.parseToJsonElement(parent.contextJson).jsonObject
        val legacy = envelope.getValue("goalSegment").jsonObject -
            setOf("activeElapsedMs", "budgetWindow", "budgetBaseToolCalls", "transientRetries")
        val saved = parent.copy(contextJson=JsonObject(envelope + ("goalSegment" to JsonObject(legacy))).toString())
        fun childAt(now: Long) = (goalSegmentDecision(saved, checkNotNull(original.goal), "scope", now, false)
            as GoalSegmentDecision.Continue).binding
        val child = childAt(started+5_000)
        assertEquals(3_000L, child.activeElapsedMs)
        assertEquals(child, childAt(started+24*60*60*1000L))
    }
    @Test fun runtimeTimeBudgetUsesTheCarriedActiveTimePlusTheCurrentSegment() {
        val segment=segment().copy(activeElapsedMs=GoalSegmentBinding.MAX_DURATION_MS-1_000)
        assertNull(goalBudgetStop(segment,0,segment.activeElapsedMs+999,0,false))
        assertEquals(GoalAutopilotPauseReason.TIME_BUDGET,goalBudgetStop(segment,0,segment.activeElapsedMs+1_000,0,false))
        var now=0L;val clock=GoalActiveClock({now},baselineMs=500)
        now=10_000;assertEquals(10_500L,clock.activeMs())
        kotlinx.coroutines.runBlocking {clock.excluding {now=70_000}} // Owner approval wait is not active time.
        now=71_000;assertEquals(11_500L,clock.activeMs())
    }
    @Test fun cumulativeToolBudgetIncludesAllEarlierSegmentsAndCurrentAttempts() {
        val history=(0 until 126).map {GoalLedgerEntry("past-${it/18}",it%18,step("old-$it"))}
        val binding=segment().copy(consumedToolCalls=126)
        val next=decide(request((0 until 18).map {step("new-$it")},binding,history)) as GoalSegmentDecision.Continue
        assertEquals(144,next.binding.consumedToolCalls)
        assertEquals(144,next.binding.phaseStartCalls)
        assertEquals(144,next.ledger.size)
        pause(GoalAutopilotPauseReason.TOOL_BUDGET,decide(request((0 until 18).map {step("old-$it")},binding,history)))
    }
    @Test fun threeSegmentsOfRepeatedReadsScheduleAReplanWhileFreshEvidenceClearsStagnation() {
        val same=step()
        val history=listOf(GoalLedgerEntry("past",0,same))
        assertReplanScheduled(request(listOf(same),segment().copy(consumedToolCalls=1,noProgressSegments=2),history))
        val next=decide(request(listOf(step("different")),segment().copy(consumedToolCalls=1,noProgressSegments=2),history)) as GoalSegmentDecision.Continue
        assertEquals(0,next.binding.noProgressSegments)
    }
    @Test fun completionCriteriaReadyReturnsExplicitCompletionDecision() {
        val current=goal().copy(criteria=goal().criteria.map {it.copy(status=GoalCriterionStatus.USER_CONFIRMED)})
        assertEquals(GoalSegmentDecision.Complete,decide(goal=current))
        pause(GoalAutopilotPauseReason.UNKNOWN_EFFECT,decide(goal=current,unknown=true))
        assertEquals(GoalStatus.ACTIVE,current.status)
    }
    @Test fun oldQueuedGoalDoesNotAcquireAutomaticRunAuthority() {
        val legacy=request().copy(goalSegment=null,goalLedger=emptyList())
        pause(GoalAutopilotPauseReason.LEGACY_CONTEXT,decide(legacy))
        val encoded=Json.parseToJsonElement(legacy.toJson()).jsonObject
        assertFalse(encoded.containsKey("goalSegment"));assertFalse(encoded.containsKey("goalLedger"))
    }
    @Test fun exactParentCheckpointAndScopeAreRequiredAtChildLaunch() {
        val parent=request();val next=decide(parent) as GoalSegmentDecision.Continue;val child=child(parent,next)
        requireGoalLaunchBinding(child,child.goal,row(parent),false,started+200)
        assertTrue(runCatching {requireGoalLaunchBinding(child,child.goal,row(parent.copy(text="Changed after checkpoint")),false,started+200)}.isFailure)
        assertTrue(runCatching {goalSegmentDecision(row(parent),goal(),"other-scope",started+100,false)}.isFailure)
        assertTrue(runCatching {requireGoalLaunchBinding(child,child.goal,row(parent),true,started+200)}.isFailure)
    }
    @Test fun noChangedModelBranchProjectOrConsentCanBorrowParentAuthority() {
        val parent=request();val next=decide(parent) as GoalSegmentDecision.Continue;val child=child(parent,next)
        val changed=listOf(child.copy(branchId="other"),child.copy(modelId="other"),child.copy(capturedPcRevision=6),
            child.copy(externalAgentRevision=8),child.copy(allowExternalAgent=false),child.copy(pcDataBinding="a".repeat(64)),
            child.copy(workspaceBinding="other"),child.copy(mode="debug"))
        changed.forEach {assertTrue(runCatching {requireGoalLaunchBinding(it,it.goal,row(parent),false,started+200)}.isFailure)}
        for(binding in listOf(child.goalSegment!!.copy(scopeRevision=5),child.goalSegment.copy(useOwnerMemory=true),child.goalSegment.copy(memoryEnabled=false))) {
            assertTrue(runCatching {requireSameGoalAuthority(parent,child.copy(goalSegment=binding))}.isFailure)
        }
    }
    @Test fun missingOrAlteredLedgerCannotBorrowAnOtherwiseValidParentDigest() {
        val parent=request();val next=decide(parent) as GoalSegmentDecision.Continue;val child=child(parent,next)
        val altered=child.copy(goalLedger=listOf(GoalLedgerEntry("run-a",0,step("Altered body"))))
        assertTrue(runCatching {requireGoalLaunchBinding(altered,altered.goal,row(parent),false,started+200)}.isFailure)
        assertTrue(runCatching {child.copy(goalLedger=emptyList())}.isFailure)
    }
    @Test fun aVerifiedWriteOlderThanSixEntriesStillBlocksAnExactDuplicate() {
        val args=buildJsonObject {put("content","Exact QA report")}
        val write=HarnessStep("save_note",args,StepEvidence("save_note",VerificationState.VERIFIED,"Verified before boundary"))
        val history=listOf(GoalLedgerEntry("first",0,write))+(1..20).map {GoalLedgerEntry("other-${it/18}",it%18,step("old-$it"))}
        val request=request(emptyList(),segment().copy(consumedToolCalls=history.size),history)
        val task=TaskGoal("Save the QA report",listOf("Read back"),requiredTools=setOf("save_note"))
        assertTrue(runCatching {requireGoalLedgerAllowsCall(request,task,ToolCall("duplicate","save_note",args),emptyList())}.isFailure)
        requireGoalLedgerAllowsCall(request,task,ToolCall("new","save_note",buildJsonObject {put("content","A distinct requested report")}),emptyList())
    }
    @Test fun failedAndOwnerHandoffLedgersCannotBeStrippedToEnableWrites() {
        val failed=GoalLedgerEntry("past",0,HarnessStep("save_note",buildJsonObject {put("content","QA")},StepEvidence("save_note",VerificationState.FAILED,"Uncertain save")))
        val request=request(emptyList(),segment().copy(consumedToolCalls=1),listOf(failed))
        val task=TaskGoal("Save QA",listOf("Read back"),requiredTools=setOf("save_note"))
        assertTrue(runCatching {requireGoalLedgerAllowsCall(request,task,ToolCall("write","save_note",buildJsonObject {put("content","QA")}),emptyList())}.isFailure)
        assertEquals(VerificationState.FAILED,QueuedChatRequest.fromJson(request.toJson()).goalLedger.single().step.evidence.state)
    }
    @Test fun malformedUnknownOrOverBudgetBindingsFailClosedDuringRestore() {
        val o=Json.parseToJsonElement(request().toJson()).jsonObject
        val binding=o.getValue("goalSegment").jsonObject
        for(change in listOf(binding+mapOf("segmentIndex" to JsonPrimitive(9)),binding+mapOf("consumedToolCalls" to JsonPrimitive(-1)),
            binding+mapOf("scopeRevision" to JsonPrimitive("4")),binding+mapOf("authority" to JsonPrimitive(true)))) {
            assertTrue(runCatching {QueuedChatRequest.fromJson(JsonObject(o+("goalSegment" to JsonObject(change))).toString())}.isFailure)
        }
    }
    @Test fun legacyArchivedPhasesAndCurrentRetryMetadataRestoreTogether() {
        val archive=GoalObservationIndex.decode("").apply {add("previous-observation")}
        val original=segment().copy(totalSegments=33,phaseStartSegments=32,phaseStartCalls=149,
            consumedToolCalls=150,archivedCalls=145,archivedObservations=archive.encode(),artifactWrapUp=true,
            budgetWindow=1,budgetBaseToolCalls=140,transientRetries=1,activeElapsedMs=80)
        assertEquals(original,GoalSegmentBinding.parse(original.json(),started+1000))
        val old=JsonObject(original.json()-setOf("activeElapsedMs","budgetWindow","budgetBaseToolCalls","transientRetries"))
        val restored=GoalSegmentBinding.parse(old,started+1000)
        assertEquals(1000L,restored.activeElapsedMs)
        assertEquals(145,restored.archivedCalls)
        assertEquals(143,goalToolRoom(restored,0))
        assertTrue(restored.artifactWrapUp)
    }
    @Test fun actualAccessScopeAndItsRevisionMustMatchBeforeLaunch() {
        val request=request()
        val access=ChatAccessScope("thread","project","scope",false,4,true)
        requireGoalSegmentScope(request,access)
        for(changed in listOf(access.copy(threadId="other"),access.copy(projectId=null),access.copy(scopeKey="owner"),
            access.copy(useOwnerMemory=true),access.copy(threadRevision=5),access.copy(memoryEnabled=false))) {
            assertTrue(runCatching {requireGoalSegmentScope(request,changed)}.isFailure)
        }
    }
    @Test fun removedHistoricalRunCannotSilentlyEraseTheCumulativeLedger() {
        val old=GoalLedgerEntry("earlier",0,step("earlier"))
        val parent=request(binding=segment().copy(consumedToolCalls=1),ledger=listOf(old))
        assertTrue(runCatching {decide(parent,goal=goal())}.isFailure)
        assertTrue(runCatching {parent.copy(goal=goal())}.isFailure)
    }
    @Test fun aNarrowCurrentStepBudgetDoesNotConsumeTheHistoricalLedgerBudget() {
        val history=(0..21).map {GoalLedgerEntry("earlier-${it/18}",it%18,step("read-$it"))}
        val request=request(emptyList(),segment().copy(consumedToolCalls=history.size),history)
        val task=TaskGoal("Save requested QA report",listOf("Verify QA"),stepBudget=1,requiredTools=setOf("save_note"))
        requireGoalLedgerAllowsCall(request,task,ToolCall("new","save_note",buildJsonObject {put("content","QA report")}),emptyList())
    }
    @Test fun ownerResumeOpensANewBudgetWindowWithoutDroppingTheLedgerOrItsDuplicateGuards() {
        val args=buildJsonObject {put("content","Exact QA report")}
        val write=GoalLedgerEntry("first",0,HarnessStep("save_note",args,StepEvidence("save_note",VerificationState.VERIFIED,"Verified write")))
        val spent=request(binding=segment().copy(totalSegments=8,consumedToolCalls=1,noProgressSegments=2,activeElapsedMs=GoalSegmentBinding.MAX_DURATION_MS-1),ledger=listOf(write))
        val source=GoalRunEvidenceSource(spent.runId,spent.threadId,spent.projectId,"completed",context(spent,GoalSegmentBinding.MAX_DURATION_MS))
        assertTrue(runCatching {goalEntryBudget(goal(listOf("first","run-a")),listOf(source),started+100)}.isFailure) // Without an owner action, still spent.
        val renewed=goalEntryBudget(goal(listOf("first","run-a")),listOf(source),started+100,renewBudget=true)
        assertEquals(1,renewed.totalSegments);assertEquals(0,renewed.noProgressSegments);assertEquals(0L,renewed.activeElapsedMs)
        assertEquals(1,renewed.budgetWindow);assertEquals(10L,renewed.originalGoalVersion)
        // The new window's chain carries the whole ledger; an exact earlier write is still blocked.
        val ledger=completedGoalLedger(spent)
        val linked=goal(listOf("first","run-a","run-n"))
        val window=segment(linked).copy(rootRunId="run-n",consumedToolCalls=ledger.size,budgetWindow=1,budgetBaseToolCalls=ledger.size)
        val next=QueuedChatRequest("run-n","thread","branch","project","QA request","cloud-model",false,emptyList(),"user-n",workspaceBinding="workspace",
            goal=linked,capturedPcRevision=5,allowExternalAgent=true,externalAgentRevision=7,goalSegment=window,goalLedger=ledger)
        val task=TaskGoal("Save the QA report",listOf("Read back"),requiredTools=setOf("save_note"))
        assertTrue(runCatching {requireGoalLedgerAllowsCall(next,task,ToolCall("duplicate","save_note",args),emptyList())}.isFailure)
        assertTrue(decide(next.copy(previousSteps=listOf(step("fresh")))) is GoalSegmentDecision.Continue)
    }
    @Test fun anUncertainOrHandedOffEffectAlsoRefusesARenewedWindow() {
        val unverified=request(listOf(HarnessStep("save_note",buildJsonObject {put("content","QA")},StepEvidence("save_note",VerificationState.UNVERIFIED,"Timed out"))))
        val prepared=request(listOf(HarnessStep("prepare_sms",buildJsonObject {put("phoneNumber","01000000000");put("message","QA")},StepEvidence("prepare_sms",VerificationState.PREPARED,"Prepared only"))))
        for(parent in listOf(unverified,prepared)) {
            val source=GoalRunEvidenceSource(parent.runId,parent.threadId,parent.projectId,"completed",context(parent,100))
            assertTrue(runCatching {collectGoalLedger(goal(),listOf(source),"workspace","scope",renewBudget=true)}.isFailure)
        }
    }
    @Test fun aRenewedWindowFreesSpaceOnlyByDroppingOldReadOnlyEntries() {
        val writes=(0 until 14).map {GoalLedgerEntry("w-${it/18}",it%18,HarnessStep("save_note",buildJsonObject {put("content","QA $it")},StepEvidence("save_note",VerificationState.VERIFIED,"Saved")))}
        val reads=(0 until 130).map {GoalLedgerEntry("r-${it/18}",it%18,step("read-$it"))}
        val pruned=goalLedgerForRenewedWindow(reads.take(65)+writes+reads.drop(65))
        assertEquals(GoalSegmentBinding.PHASE_TOOL_CALLS-WorkSegments.STEP_BUDGET,pruned.size)
        assertEquals(writes.map {it.json()},pruned.filter {it.step.tool=="save_note"}.map {it.json()})
        assertEquals(reads.takeLast(112).map {it.json()},pruned.filter {it.step.tool=="search_notes"}.map {it.json()}) // Oldest reads go first.
        val small=reads.take(10)+writes
        assertEquals(small,goalLedgerForRenewedWindow(small))
        val onlyWrites=(0 until 144).map {GoalLedgerEntry("m-${it/18}",it%18,HarnessStep("save_note",buildJsonObject {put("content","M $it")},StepEvidence("save_note",VerificationState.VERIFIED,"Saved")))}
        assertEquals(GoalAutopilotPauseReason.TOOL_BUDGET,(runCatching {goalLedgerForRenewedWindow(onlyWrites)}.exceptionOrNull() as GoalAutopilotPaused).reason)
    }
    @Test fun windowToolBudgetCountsOnlyCallsAfterTheWindowStartedAndTheWindowCountIsBounded() {
        val history=(0 until 120).map {GoalLedgerEntry("past-${it/18}",it%18,step("past-$it"))}
        val windowed=segment().copy(consumedToolCalls=120,budgetWindow=3,budgetBaseToolCalls=120)
        assertTrue(decide(request((0 until 18).map {step("new-$it")},windowed,history)) is GoalSegmentDecision.Continue)
        val parent=request(binding=segment().copy(budgetWindow=GoalSegmentBinding.MAX_BUDGET_WINDOWS))
        val source=GoalRunEvidenceSource(parent.runId,parent.threadId,parent.projectId,"completed",context(parent,100))
        assertEquals(GoalAutopilotPauseReason.SEGMENT_BUDGET,(runCatching {goalEntryBudget(goal(),listOf(source),started+100,renewBudget=true)}.exceptionOrNull() as GoalAutopilotPaused).reason)
        assertTrue(runCatching {segment().copy(budgetBaseToolCalls=1)}.isFailure)
    }
    @Test fun historyLargerThanOneWindowPausesANonRenewingReentryOnTheToolBudget() {
        val pastA=(0 until 130).map {GoalLedgerEntry("pa-${it/18}",it%18,step("pa-$it"))}
        val pastB=(0 until 30).map {GoalLedgerEntry("pb-${it/18}",it%18,step("pb-$it"))}
        val a=request(listOf(step("a")),segment().copy(consumedToolCalls=130),pastA)
        val bGoal=goal((listOf("run-c")+pastB.map {it.sourceRunId}).distinct())
        val b=QueuedChatRequest("run-c","thread","branch","project","QA request","cloud-model",false,emptyList(),"user-c",
            previousSteps=listOf(step("b")),workspaceBinding="workspace",goal=bGoal,capturedPcRevision=5,allowExternalAgent=true,externalAgentRevision=7,
            goalSegment=segment(bGoal).copy(rootRunId="run-c",consumedToolCalls=30),goalLedger=pastB)
        val all=goal((listOf("run-a","run-c")+pastA.map {it.sourceRunId}+pastB.map {it.sourceRunId}).distinct())
        val sources=listOf(a,b).map {GoalRunEvidenceSource(it.runId,it.threadId,it.projectId,"completed",context(it,100))}
        val error=runCatching {collectGoalLedger(all,sources,"workspace","scope")}.exceptionOrNull()
        assertEquals(GoalAutopilotPauseReason.TOOL_BUDGET,(error as GoalAutopilotPaused).reason)
        assertEquals(162,collectGoalLedger(all,sources,"workspace","scope",renewBudget=true).size)
    }
    @Test fun steeringAndAResumeInsideAGoalSegmentCannotRenewTheOriginalGoalBudget() {
        val parent=request(binding=segment().copy(totalSegments=7))
        val source=GoalRunEvidenceSource(parent.runId,parent.threadId,parent.projectId,"completed",context(parent,100))
        val budget=goalEntryBudget(goal(),listOf(source),started+10*GoalSegmentBinding.MAX_DURATION_MS)
        assertEquals(started,budget.startedAtMs);assertEquals(8,budget.totalSegments);assertEquals(10L,budget.originalGoalVersion)
        assertEquals(100L,budget.activeElapsedMs)
        val eighth=source.copy(contextJson=context(parent.copy(goalSegment=parent.goalSegment!!.copy(totalSegments=8)),100))
        val segmentError=runCatching {goalEntryBudget(goal(),listOf(eighth),started+100)}.exceptionOrNull() as GoalAutopilotPaused
        assertEquals(GoalAutopilotPauseReason.SEGMENT_BUDGET,segmentError.reason)
        val spent=source.copy(contextJson=context(parent,GoalSegmentBinding.MAX_DURATION_MS))
        val timeError=runCatching {goalEntryBudget(goal(),listOf(spent),started+100)}.exceptionOrNull() as GoalAutopilotPaused
        assertEquals(GoalAutopilotPauseReason.TIME_BUDGET,timeError.reason)
        val unrecorded=runCatching {goalEntryBudget(goal(),listOf(source.copy(contextJson=parent.toJson())),started+100)}.exceptionOrNull() as GoalAutopilotPaused
        assertEquals(GoalAutopilotPauseReason.TIME_BUDGET,unrecorded.reason)
    }
    @Test fun liveCurrentCallsCannotPassTheCumulative144BoundaryOfAnImmutableQueuedRequest() {
        val history=(0 until 143).map {GoalLedgerEntry("past-${it/18}",it%18,step("past-$it"))}
        val request=request(emptyList(),segment().copy(consumedToolCalls=143),history)
        val task=TaskGoal("Read QA",listOf("Read actual result"))
        val call=ToolCall("last","search_notes",buildJsonObject {put("query","last")})
        requireGoalLedgerAllowsCall(request,task,call,emptyList())
        assertTrue(runCatching {requireGoalLedgerAllowsCall(request,task,call,listOf(step("actual-144")))}.isFailure)
    }
    @Test fun eightSequentialSegmentsKeepAll144PositionsWhenProgressUnlocksAnotherPhase() {
        var current=request()
        for(index in 1..8) {
            current=current.copy(previousSteps=(0 until 18).map {step("segment-$index-$it")})
            assertEquals(index*18,completedGoalLedger(current).size)
            val next=decide(current) as GoalSegmentDecision.Continue
            val id="run-${index+1}"
            current=current.copy(runId=id,userMessageId="user-${index+1}",previousSteps=emptyList(),
                goal=current.goal!!.copy(linkedRunIds=current.goal.linkedRunIds+id),goalSegment=next.binding,goalLedger=next.ledger)
            assertEquals(current,QueuedChatRequest.fromJson(current.toJson()))
        }
        assertEquals(144,current.goalLedger.size)
        assertEquals(9,current.goalSegment!!.totalSegments)
        assertEquals(8,current.goalSegment!!.phaseStartSegments)
        assertEquals(GoalStatus.ACTIVE,current.goal!!.status)
    }
    @Test fun observedWorkContinuesPastThirtyTwoSegmentsWithoutReplayingArchivedReadsOrWrites() {
        var current=request()
        val sources=mutableListOf<QueuedChatRequest>()
        val mutation=HarnessStep("save_public_pdf",buildJsonObject {put("url","https://example.org/a.pdf")},
            StepEvidence("save_public_pdf",VerificationState.VERIFIED,"Saved PDF"))
        for(segment in 1..35) {
            val steps=(0 until 18).map {if(segment==1&&it==0)mutation else step("record-$segment-$it")}
            current=current.copy(previousSteps=steps)
            sources+=current
            val next=decide(current) as GoalSegmentDecision.Continue
            val id="successor-$segment"
            current=current.copy(runId=id,userMessageId="user-$segment",previousSteps=emptyList(),
                goal=current.goal!!.copy(linkedRunIds=current.goal.linkedRunIds+id),goalSegment=next.binding,goalLedger=next.ledger)
        }
        assertEquals(630,current.goalSegment!!.consumedToolCalls)
        assertTrue(current.goalLedger.size<144)
        assertEquals(current,QueuedChatRequest.fromJson(current.toJson()))
        val repeated=current.copy(previousSteps=listOf(step("record-1-1")),
            goalSegment=current.goalSegment!!.copy(noProgressSegments=2))
        assertReplanScheduled(repeated)
        val task=TaskGoal("Save the document",listOf("Read it"),requiredTools=setOf(mutation.tool))
        val duplicate=ArchivedGoalWriteGuard(current,task,ToolCall("repeat",mutation.tool,mutation.arguments))
        assertThrows(IllegalStateException::class.java){sources.forEach(duplicate::inspect)}
        val other=ArchivedGoalWriteGuard(current,task,ToolCall("new",mutation.tool,buildJsonObject {put("url","https://example.org/b.pdf")}))
        assertThrows(IllegalStateException::class.java){other.finish()}
        sources.forEach(other::inspect)
        other.finish()
    }
    @Test fun registeredReadFailureThenVerifiedAlternativeMayContinueWithoutErasingTheFailure() {
        val parent=request(listOf(step("first read",VerificationState.FAILED),step("alternative")))
        val next=decide(parent) as GoalSegmentDecision.Continue
        assertEquals(listOf(VerificationState.FAILED,VerificationState.VERIFIED),next.ledger.map {it.step.evidence.state})
        val nextRequest=child(parent,next)
        requireGoalLedgerAllowsCall(nextRequest,TaskGoal("Read QA",listOf("Actual read")),
            ToolCall("alternative","search_notes",buildJsonObject {put("query","another query")}),emptyList())
    }
    @Test fun anUnregisteredReadPrefixNeverEstablishesAReadOnlyContract() {
        val unknown=HarnessStep("read_unregistered",buildJsonObject {},StepEvidence("read_unregistered",VerificationState.FAILED,"Not a registered tool"))
        assertFalse(ToolReadPolicy.isReadOnly(unknown.tool));assertTrue(goalUncertainFailure(unknown))
        pause(GoalAutopilotPauseReason.INVALID_CHECKPOINT,decide(request(listOf(unknown))))
    }
    @Test fun repeatingOnlyASafeFailedReadStopsAfterThreeSegmentsWithoutProgress() {
        var current=request(listOf(step("same failed read",VerificationState.FAILED)))
        for(index in 1..3) {
            val result=decide(current)
            if(index==3)pause(GoalAutopilotPauseReason.NO_PROGRESS,result)
            else {
                val next=result as GoalSegmentDecision.Continue
                val id="retry-$index"
                current=current.copy(runId=id,userMessageId="retry-user-$index",goal=current.goal!!.copy(linkedRunIds=current.goal.linkedRunIds+id),
                    goalSegment=next.binding,goalLedger=next.ledger)
            }
        }
    }
}
