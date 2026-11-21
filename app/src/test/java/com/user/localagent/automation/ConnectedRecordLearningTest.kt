package com.user.localagent.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectedRecordLearningTest {
    private val before = "팀 회의는 금요일 오후 2시에 합니다."
    private val after = "팀 회의는 금요일 오후 4시로 변경됐어요."
    private val sources = listOf(LearningSource("sms_provider", "sms:1", 1, before, "first"),
        LearningSource("kakao_notification", "kakao:2", 2, after, "second"))
    private val original = LearnedRecord(0, "appointment", "stated", "팀 회의", before)
    private val change = LearnedRecord(1, "appointment", "changed", "팀 회의", after)

    @Test fun keepsBothVerbatimObservationsAndExplicitChange() {
        val validated = ConnectedRecordLearning.validate(LearnedBatch(listOf(0, 1), listOf(original, change)), sources)
        assertEquals(listOf(original, change), validated)
    }

    @Test fun fabricatedOrMisattributedEvidenceRejectsTheWholeBatch() {
        for (bad in listOf(change.copy(quote = before), change.copy(label = "가족 모임"), change.copy(index = 5))) {
            assertThrows(IllegalArgumentException::class.java) {
                ConnectedRecordLearning.validate(LearnedBatch(listOf(0, 1), listOf(original, bad)), sources)
            }
        }
    }

    @Test fun requiresExplicitCoverageEvenWhenNoReusableRecordWasFound() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectedRecordLearning.validate(LearnedBatch(listOf(0), listOf(original)), sources)
        }
        assertEquals(emptyList<LearnedRecord>(),
            ConnectedRecordLearning.validate(LearnedBatch(listOf(0, 1), emptyList()), sources))
    }
}
