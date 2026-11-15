package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual WorkNote + issued-page/current-input checks. All text is synthetic, no storage or model. */
class NotePageEvidenceRetentionTest {
    private val binding = NoteReadBinding("run-retention", "thread-retention", "branch-retention", "scope-retention")
    private val literal = "원래  문구 😀"
    private fun note(body: String = fixtureBody(), version: Long = 1_794_700_000_000) = NoteSource(
        "00000000-0000-4000-8000-000000000001", binding.scopeKey,
        "qa-retention-" + "s".repeat(31), "synthetic", body, version
    )
    private fun fixtureBody(): String {
        val prefix = "header\n" + literal
        return prefix + "\nrow0123456789".repeat(120).take(1_322 - prefix.length)
    }
    private fun page(s: NoteReadPatchSession, n: NoteSource, offset: Int = 0, limit: Int = 900) =
        evidence(s.read(n, NoteReadRequest(n.id, n.collection, n.updatedAt, offset, limit)))
    private fun evidence(p: NoteReadPage) = buildJsonObject {
        put("tool", "read_note"); put("success", true); put("verification", "VERIFIED")
        put("verificationSummary", "실제 현재 원문 페이지 · 합성 회귀 자료")
        put("result", p.modelResult())
    }
    private fun ordinary(tool: String = "calculate", value: String = "value") = buildJsonObject {
        put("tool", tool); put("success", true); put("verification", "VERIFIED")
        put("result", buildJsonObject { put("value", value) })
    }
    private fun patch(n: NoteSource, old: String = literal) =
        NotePatchRequest(n.id, n.collection, n.updatedAt, old, "변경한 문구")
    private fun query(live: List<JsonObject>, work: WorkNote, result: NotePageEvidenceRetention.Result): String =
        "synthetic user request\n" + work.text() + result.notice() + "도구 실행 결과(인용 자료): ${JsonArray(live)}\n"
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: NotePatchRejected) { assertEquals(code, e.code) }
    }
    private fun data(item: JsonObject) = item.getValue("result").jsonObject.getValue("data").jsonObject
    private fun token(item: JsonObject) = data(item).getValue("readToken").jsonPrimitive.content

    @Test fun originalWorkNotePolicyDropsFirstOfTwoRealPagesAndPreviewRejectsItsSummary() {
        val n = note(); val s = NoteReadPatchSession(binding); val work = WorkNote()
        val first = page(s, n); val second = page(s, n, 900, 640)
        assertEquals(1_322, n.body.length); assertEquals(7, n.body.indexOf(literal))
        assertEquals(422, data(second).getValue("returnedLength").jsonPrimitive.int)
        val live = mutableListOf(first, second)
        assertTrue("Fixture must exercise the actual default 2600-char fold", live.sumOf { it.toString().length } > 2_600)
        work.absorb(live)
        assertEquals(listOf(second), live); assertEquals(1, work.size)
        s.acceptDelivered(live, work.text() + JsonArray(live))
        rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { s.previewPatch(n, patch(n)) }
    }

    @Test fun retainedTwoPagesAreIntactInActualQueryAndAllowOnlyTheExactLiteralPreview() {
        val n = note(); val s = NoteReadPatchSession(binding); val work = WorkNote()
        val first = page(s, n); val second = page(s, n, 900, 640)
        val live = mutableListOf(first, second)
        assertTrue(live.sumOf { it.toString().length } > 2_600)
        val result = NotePageEvidenceRetention.compact(live, work)
        assertEquals(2, result.retainedPages); assertTrue(result.retainedChars <= 6_000)
        assertSame(first, live[0]); assertSame(second, live[1]); assertEquals(0, work.size)
        val actualQuery = query(live, work, result)
        assertTrue(actualQuery.contains(first.toString()))
        assertTrue(actualQuery.contains(second.toString()))
        s.acceptDelivered(live, actualQuery)
        assertEquals(setOf(token(first), token(second)), s.acceptedPageTokens())
        val preview = s.previewPatch(n, patch(n))
        assertEquals(n.body.replace(literal, "변경한 문구"), preview.afterBody)
        assertEquals(n.body.substring(7 + literal.length), preview.afterBody.substring(7 + "변경한 문구".length))
        rejected("OLD_TEXT_NOT_FOUND") { s.previewPatch(n, patch(n, "원래 문구 😀")) }
    }

    @Test fun segmentBoundaryKeepsRawNotePagesWhileOrdinaryResultsUseActualExistingPolicy() {
        val n = note(); val s = NoteReadPatchSession(binding); val work = WorkNote()
        val first = page(s, n); val second = page(s, n, 900, 640)
        val oldest = ordinary("calculate", "1"); val latest = ordinary("date_calculate", "2")
        val live = mutableListOf(oldest, first, latest, second)
        val result = NotePageEvidenceRetention.compact(live, work, segmentBoundary = true)
        assertEquals(listOf(first, latest, second), live); assertEquals(1, work.size)
        s.acceptDelivered(live, query(live, work, result))
        assertTrue(s.previewPatch(n, patch(n)).changed)
    }

    @Test fun generalWorkNoteBehaviorAndSummaryBytesAreUnchangedForBothBoundaryModes() {
        for (boundary in listOf(false, true)) {
            val items = (0..6).map { ordinary("tool-$it", "x".repeat(600)) }
            val old = items.toMutableList(); val new = items.toMutableList()
            val oldWork = WorkNote(); val newWork = WorkNote()
            if (boundary) oldWork.absorbAll(old) else oldWork.absorb(old)
            val result = NotePageEvidenceRetention.compact(new, newWork, boundary)
            assertEquals(old, new); assertEquals(oldWork.size, newWork.size)
            assertEquals(oldWork.text(), newWork.text()); assertEquals(0, result.retainedPages)
        }
    }

    @Test fun exactRereadDeduplicatesTheRangeKeepingOnlyNewestIssuedToken() {
        val n = note(); val s = NoteReadPatchSession(binding); val work = WorkNote()
        val first = page(s, n); val tail = page(s, n, 900, 640); val reread = page(s, n)
        assertNotEquals(token(first), token(reread))
        val live = mutableListOf(first, tail, reread)
        val result = NotePageEvidenceRetention.compact(live, work)
        assertEquals(listOf(tail, reread), live)
        s.acceptDelivered(live, query(live, work, result))
        assertEquals(setOf(token(tail), token(reread)), s.acceptedPageTokens())
        assertTrue(s.previewPatch(n, patch(n)).changed)
    }

    @Test fun latestObservedVersionSupersedesOldPagesRatherThanCombiningSnapshots() {
        val n = note(); val updated = n.copy(updatedAt = n.updatedAt + 1)
        val s = NoteReadPatchSession(binding); val work = WorkNote()
        val old = page(s, n); val freshTail = page(s, updated, 900, 640)
        val live = mutableListOf(old, freshTail)
        val result = NotePageEvidenceRetention.compact(live, work)
        assertEquals(listOf(freshTail), live)
        s.acceptDelivered(live, query(live, work, result))
        rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { s.previewPatch(updated, patch(updated)) }
        rejected("NOTE_VERSION_CHANGED") { s.previewPatch(updated, patch(n)) }
    }

    @Test fun changedHashOrCollectionCannotKeepEarlierSnapshotEvenWithSameVersion() {
        for (changedCollection in listOf(false, true)) {
            val n = note()
            val changed = if (changedCollection) n.copy(collection = "new-synthetic-collection") else n.copy(body = n.body + " changed")
            val s = NoteReadPatchSession(binding); val work = WorkNote()
            val old = page(s, n); val freshTail = page(s, changed, 900, 640)
            val live = mutableListOf(old, freshTail)
            val result = NotePageEvidenceRetention.compact(live, work)
            assertEquals(listOf(freshTail), live)
            s.acceptDelivered(live, query(live, work, result))
            rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { s.previewPatch(changed, patch(changed)) }
        }
    }

    @Test fun summaryOnlyOrMissingCurrentPageStillRejectsAfterEarlierFullDelivery() {
        val n = note(); val s = NoteReadPatchSession(binding); val work = WorkNote()
        val first = page(s, n); val tail = page(s, n, 900, 640); val live = mutableListOf(first, tail)
        val result = NotePageEvidenceRetention.compact(live, work)
        s.acceptDelivered(live, query(live, work, result)); assertTrue(s.previewPatch(n, patch(n)).changed)
        s.acceptDelivered(live, "summary only: exact wording was read earlier")
        rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { s.previewPatch(n, patch(n)) }
        s.acceptDelivered(listOf(tail), JsonArray(listOf(tail)).toString())
        rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { s.previewPatch(n, patch(n)) }
    }

    @Test fun clippedOrPromotedWrapperNeitherRetainsEarlierPageNorCreatesDeliveryAuthority() {
        for (promoted in listOf(false, true)) {
            val n = note(); val s = NoteReadPatchSession(binding); val work = WorkNote(); val original = page(s, n)
            val wrapper = original.getValue("result").jsonObject
            val altered = if (promoted) JsonObject(wrapper + ("sourceScopePartial" to JsonPrimitive(false)))
                else JsonObject(wrapper + ("truncated" to JsonPrimitive(true)))
            val invalid = JsonObject(original + ("result" to altered))
            val live = mutableListOf(original, invalid)
            val result = NotePageEvidenceRetention.compact(live, work)
            assertEquals(0, result.retainedPages); assertEquals(listOf(invalid), live)
            s.acceptDelivered(live, query(live, work, result))
            rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { s.previewPatch(n, patch(n)) }
        }
    }

    @Test fun overflowEvictsWholePagesRequiresRefetchAndNeverUsesSummaryAsProof() {
        val n = note("needle\n" + "x\n".repeat(4_496) + "x")
        val s = NoteReadPatchSession(binding); val work = WorkNote()
        val all = (0 until n.body.length step 900).map { page(s, n, it) }
        val live = all.toMutableList()
        val result = NotePageEvidenceRetention.compact(live, work)
        assertTrue(result.evictedPages > 0); assertTrue(result.notice().contains("read_note"))
        assertTrue(result.retainedChars <= 6_000); assertFalse(live.contains(all.first()))
        assertTrue(live.all { it in all }); assertEquals(live.sumOf { it.toString().length }, result.retainedChars)
        s.acceptDelivered(live, query(live, work, result))
        rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { s.previewPatch(n, patch(n, "needle")) }
        val reread = page(s, n); live += reread
        val next = NotePageEvidenceRetention.compact(live, work)
        assertTrue(next.retainedChars <= 6_000); assertSame(reread, live.last())
        s.acceptDelivered(live, query(live, work, next))
        assertTrue(s.previewPatch(n, patch(n, "needle")).changed)
    }

    @Test fun singleOversizedWrapperIsRemovedWithoutClippingOrFallingBackToSummary() {
        val n = note(); val s = NoteReadPatchSession(binding); val work = WorkNote(); val original = page(s, n)
        val oversized = JsonObject(original + ("verificationSummary" to JsonPrimitive("x".repeat(6_001))))
        val live = mutableListOf(oversized)
        val result = NotePageEvidenceRetention.compact(live, work)
        assertTrue(live.isEmpty()); assertEquals(0, result.retainedChars); assertEquals(1, result.evictedPages)
        s.acceptDelivered(live, query(live, work, result))
        rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { s.previewPatch(n, patch(n)) }
    }

    @Test fun failedReadAndVerifiedWriteInvalidateEarlierRawPages() {
        for (failure in listOf(true, false)) {
            val n = note(); val s = NoteReadPatchSession(binding); val work = WorkNote(); val first = page(s, n)
            val event = if (failure) buildJsonObject {
                put("tool", "read_note"); put("success", false); put("verification", "FAILED")
            } else ordinary("patch_note", "verified synthetic write")
            val live = mutableListOf(first, event)
            val result = NotePageEvidenceRetention.compact(live, work)
            assertEquals(listOf(event), live); assertEquals(0, result.retainedPages)
            s.acceptDelivered(live, query(live, work, result))
            rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { s.previewPatch(n, patch(n)) }
        }
    }

    @Test fun retainingAnotherRunsPageCannotAuthenticateItAndHelperKeepsNoRequestCache() {
        val n = note(); val firstRun = NoteReadPatchSession(binding); val issuedElsewhere = page(firstRun, n)
        val secondRun = NoteReadPatchSession(binding.copy(runId = "different-run")); val work = WorkNote()
        val live = mutableListOf(issuedElsewhere)
        val result = NotePageEvidenceRetention.compact(live, work)
        assertEquals(1, result.retainedPages) // Retention deliberately is not authority.
        secondRun.acceptDelivered(live, query(live, work, result))
        assertTrue(secondRun.acceptedPageTokens().isEmpty())
        rejected("ORIGINAL_FRAGMENT_NOT_DELIVERED") { secondRun.previewPatch(n, patch(n)) }
        val empty = mutableListOf<JsonObject>()
        assertEquals(0, NotePageEvidenceRetention.compact(empty, WorkNote()).retainedPages)
        assertTrue(empty.isEmpty())
    }
}
