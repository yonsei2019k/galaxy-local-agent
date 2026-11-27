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
