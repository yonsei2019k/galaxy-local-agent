package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeGoalBudgetParityTest {
    @Test fun nativeDecisionsMatchHostReferenceAtAllContinuationBoundaries() {
        val start = 100L
        for (segments in listOf(1, 7, 8)) {
            val binding = GoalSegmentBinding(
                "goal", 0, "a".repeat(64), "scope", 0, false, false,
                "root", null, null, 1, start, 0, 0, segments,
            )
            for (calls in listOf(0, 143, 144)) {
                for (active in listOf(0L, 1L, 1_799_999L, 1_800_000L, Long.MAX_VALUE)) {
                    for (progress in listOf(0, 2, 3)) {
                        assertEquals(
                            goalBudgetStop(binding, calls, active, progress, useNative = false),
                            goalBudgetStop(binding, calls, active, progress, useNative = true),
                        )
                    }
                }
            }
        }
    }
}
