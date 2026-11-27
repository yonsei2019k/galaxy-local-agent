package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoalPdfEmailTest {
    @get:Rule val folder = TemporaryFolder()
    private val name = "artifact-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.pdf"
    private fun call(files: String, to: String = "owner@example.org") = ToolCall("id","prepare_email",buildJsonObject {
        put("to",to); put("subject","증빙"); put("body","첨부합니다."); put("pdfFiles",files)
    })

    @Test fun exactFileSelectionsRejectDuplicatesPathsAndInjection() {
        assertEquals(listOf(name),PdfMailAttachments.requested(call(name)))
        for (bad in listOf("../$name","content://$name","$name,$name",name.uppercase(),"")) {
            assertThrows(IllegalArgumentException::class.java) { ToolRegistry.validate(call(bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry.validate(call(name,"a@example.org\r\nBcc: b@example.org")) }
    }

    @Test fun oldDownloadedPdfRequiresOriginalContentAndNewGeneratedPdfRequiresSavedDigest() {
        val bytes = "%PDF-synthetic".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val legacy = File(folder.root, "artifact-" + UUID.nameUUIDFromBytes(digest) + ".pdf").apply { writeBytes(bytes) }
        val generated = File(folder.root,name).apply { writeBytes(bytes) }
        val sha = digest.joinToString("") { "%02x".format(it) }
        assertEquals(legacy,GoalPdfEmail.verifyFile(folder.root, legacy.name, null))
        assertEquals(generated,GoalPdfEmail.verifyFile(folder.root, name, sha))
        assertThrows(IllegalArgumentException::class.java) { GoalPdfEmail.verifyFile(folder.root,name,null) }
        legacy.appendText("changed")
        generated.appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { GoalPdfEmail.verifyFile(folder.root, legacy.name, null) }
        assertThrows(IllegalArgumentException::class.java) { GoalPdfEmail.verifyFile(folder.root,name,sha) }
    }

    @Test fun onlyTheReviewedDraftWithEveryOriginalCanCloseTheDraftCriterion() {
        val a=GoalProofTarget(GoalProofKind.PUBLIC_PDF_SOURCE,"https://example.org/a.pdf")
        val b=GoalProofTarget(GoalProofKind.BROWSER_PDF_SOURCE,"https://example.org/b.pdf")
        val target=GoalProofTarget(GoalProofKind.PDF_EMAIL_DRAFT,"[\"owner@example.org\",\"증빙\"]")
        val criterion=GoalCriterion(text=target.connectorCriterion(),proofTarget=target)
        val goal=GoalDefinition("g","thread",outcome="첨부 초안",criteria=listOf(
            GoalCriterion(text=a.target,proofTarget=a),GoalCriterion(text=b.browserPdfCriterion(),proofTarget=b),criterion),
            status=GoalStatus.ACTIVE)
        val reviewed=target.reviewedDraft(goal)
        val frozen=goal.copy(criteria=goal.criteria.dropLast(1)+criterion.copy(proofTarget=reviewed))
        val two=call(name+",artifact-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.pdf")
        val sources=listOf(goalOutcomeDigest(a),goalOutcomeDigest(b))
        val digest=checkNotNull(GoalPdfEmail.verifiedDraftProof(frozen,two,sources))
        val evidence=HarnessStep(two,StepEvidence("prepare_email",VerificationState.VERIFIED,"초안 재조회",auditId="draft-audit",outcomeProof=digest))
        assertTrue(goalCriterionMatches(frozen.criteria.last(),evidence,frozen))
        assertNull(GoalPdfEmail.verifiedDraftProof(goal,two,sources))
        assertNull(GoalPdfEmail.verifiedDraftProof(frozen,call(name),sources.take(1)))
        assertNull(GoalPdfEmail.verifiedDraftProof(frozen,two,listOf(sources[0],null)))
        assertNull(GoalPdfEmail.verifiedDraftProof(frozen,two,listOf(sources[0],sources[0])))
        assertNull(GoalPdfEmail.verifiedDraftProof(frozen,two.copy(arguments=JsonObject(two.arguments+("subject" to JsonPrimitive("다른 제목")))),sources))
        assertNull(GoalPdfEmail.verifiedDraftProof(frozen,two.copy(arguments=JsonObject(two.arguments+("to" to JsonPrimitive("other@example.org")))),sources))
        assertNull(GoalPdfEmail.verifiedDraftProof(frozen.copy(id="other"),two,sources))
        assertNull(GoalPdfEmail.verifiedDraftProof(frozen.copy(outcome="실제 전달"),two,sources))
        val sent=target.copy(kind=GoalProofKind.GMAIL_SENT)
        assertFalse(goalCriterionMatches(criterion.copy(text=sent.connectorCriterion(),proofTarget=sent),evidence,frozen))
        assertTrue(target.matchesOwnerDraftScope(listOf(a.target,b.target,criterion.text).joinToString("\n"),listOf(a,b)))
        assertFalse(target.matchesOwnerDraftScope("PDF 두 개를 실제로 발송해줘",listOf(a,b)))
        assertFalse(target.matchesOwnerDraftScope(listOf(a.target,b.target,criterion.text,"그리고 실제로 전달해").joinToString("\n"),listOf(a,b)))
    }

    @Test fun sourceMustBelongToCurrentRunOrSameGoalBranchAndProject() {
        val goal = GoalDefinition("goal","thread",outcome="서류 제출",criteria=listOf(GoalCriterion(text="준비")),linkedRunIds=listOf("before","now"))
        val request = QueuedChatRequest("now","thread","branch",null,"준비","model",false,emptyList(),"msg",goal=goal)
        fun row(r:QueuedChatRequest) = ChatRunRecord(r.runId,r.threadId,r.branchId,projectId=r.projectId,contextJson=r.toJson())
        val earlier = request.copy(runId="before")
        assertTrue(GoalPdfEmail.matchesScope(request,row(earlier)))
        assertFalse(GoalPdfEmail.matchesScope(request,row(earlier.copy(branchId="other"))))
        assertFalse(GoalPdfEmail.matchesScope(request,row(earlier.copy(goal=goal.copy(id="other")))))
        assertFalse(GoalPdfEmail.matchesScope(request,row(earlier.copy(runId="unrelated"))))
        assertFalse(GoalPdfEmail.matchesScope(request.copy(goal=null),row(earlier.copy(goal=null))))
    }
}
