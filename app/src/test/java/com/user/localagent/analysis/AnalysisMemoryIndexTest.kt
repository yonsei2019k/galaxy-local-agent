
package com.user.localagent.analysis

import com.user.localagent.data.NoteRecord
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisMemoryIndexTest {
    private val scope = "project:qa"
    private val evidence = "a".repeat(64)
    private val firstRoot = "5a662045-d503-4004-8bc4-9fa8a1f3cee0"
    private val firstStep = "f706f9df-e7c5-433d-9397-466b9ef02a55"

    @Test fun observationKeepsItsCategoryAndSourceWithoutCopyingTheQuote() {
        val (step, root) = fixture(firstRoot, firstStep, "AUDIO_TRANSCRIPT")
        val claim = AnalysisMemoryIndex.findings(step, root, scope).single()
        assertEquals(listOf("analysis_finding", "audio_transcript"), claim.tags)
        assertEquals("analysis.$firstRoot", claim.subject)
        assertEquals("analysis_finding.$evidence", claim.predicate)
        assertEquals(firstStep, claim.sourceId)
        assertEquals("observed", claim.status)
        assertEquals("One synthetic finding.", claim.value)
        assertNull(claim.sourceExcerpt)
        assertEquals(firstRoot, AnalysisMemoryIndex.parentId(step.body))
    }

    @Test fun mismatchedScopeAndUnlinkedStepAreIgnored() {
        val (step, root) = fixture(firstRoot, firstStep, "SMS")
        assertTrue(AnalysisMemoryIndex.findings(step, root, "project:another").isEmpty())
        assertTrue(AnalysisMemoryIndex.findings(step.copy(id = firstRoot), root, scope).isEmpty())
        assertTrue(AnalysisMemoryIndex.findings(step.copy(body = """{"result":null}"""), root, scope).isEmpty())
    }

    @Test fun theSameEvidenceIdentifierCanBelongToDistinctSavedAnalyses() {
        val (step, root) = fixture(firstRoot, firstStep, "IMPORTED_CONVERSATION")
        val (otherStep, otherRoot) = fixture(
            "45890a4b-712f-4212-8c2c-6adab6286c97", "0ab4e97b-0e28-48b9-9593-c17b4c56f89a", "IMPORTED_CONVERSATION")
        val saved = AnalysisMemoryIndex.findings(step, root, scope).single()
        val other = AnalysisMemoryIndex.findings(otherStep, otherRoot, scope).single()
        assertEquals(saved.predicate, other.predicate)
        assertNotEquals(saved.id, other.id)
        assertNotEquals(saved.subject, other.subject)
    }

    private fun fixture(analysisId: String, stepId: String, kind: String): Pair<NoteRecord, NoteRecord> {
        val step = buildJsonObject {
            put("kind", "private_analysis_step_v1")
            put("scopeId", scope)
            put("analysisId", analysisId)
            put("index", 0)
            put("result", buildJsonObject {
                put("findings", buildJsonArray {
                    add(buildJsonObject {
                        put("id", evidence)
                        put("text", "One synthetic finding.")
                        put("quote", "This source excerpt must stay in the original journal.")
                    })
                })
            })
        }
        val root = buildJsonObject {
            put("kind", "private_analysis_checkpoint_v1")
            put("id", analysisId)
            put("scopeId", scope)
            put("stepCount", 1)
            put("lastId", stepId)
            put("source", buildJsonObject { put("scopeId", scope); put("kind", kind) })
        }
        return NoteRecord(stepId, "분석 근거 조각", step.toString(), "private-analysis") to
            NoteRecord(analysisId, "개인 자료 분석 진행", root.toString(), "private-analysis")
    }
}
