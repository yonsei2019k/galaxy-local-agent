package com.user.localagent

import com.user.localagent.inference.GenerationEvidence
import com.user.localagent.inference.GenerationStopReason
import com.user.localagent.inference.GenerationSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledReadGenerationTimingTest {
    private val summary = GenerationSummary("fixture", GenerationStopReason.EOG, GenerationEvidence.SDK, false, 512)

    @Test fun reportsOnlyAvailableGenerationMeasurements() {
        assertEquals(" · 생성 4100ms · 입력 24 토큰 · 첫 표시 750ms", scheduledReadGenerationTiming(
            summary.copy(elapsedMillis = 4100, prefillTokens = 24, visibleChars = 1, observedTimeToFirstVisibleTokenSeconds = 0.75)
        ))
    }

    @Test fun omitsUnknownAndInvalidMeasurements() {
        assertEquals("", scheduledReadGenerationTiming(summary))
        assertEquals("", scheduledReadGenerationTiming(
            summary.copy(elapsedMillis = -1, prefillTokens = -1, visibleChars = 1, observedTimeToFirstVisibleTokenSeconds = Double.NaN)
        ))
    }
}
