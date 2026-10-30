package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ModelRoutingTest {
    private fun request(deep: Boolean = false, complexity: Int = 0, thermal: Int = 0,
        powerSave: Boolean = false, memory: Long = 8_000, chars: Int = 1_000) =
        RequestProfile(false, deep, complexity, thermal, powerSave, memory, chars)
    private fun fast(id: String = "gemma-e2b", warm: Boolean = false) =
        ModelReadiness(id, "litertlm", true, 900.0, 30.0, 2_000, false, ModelQualityTier.FAST, warm)
    private fun quality() = ModelReadiness("quality-e4b", "litertlm", true, 1800.0, 18.0, 4_000, true, ModelQualityTier.QUALITY)
    private fun selected(profile: RequestProfile, models: List<ModelReadiness>) =
        LocalModelRouter.route(profile, models) as ModelRoute.Selected

    @Test fun ruleMatchBypassesModelsEvenForDeepRequest() {
        val route = LocalModelRouter.route(request(deep = true).copy(ruleMatched = true), emptyList())
        assertTrue(route is ModelRoute.RuleDirect)
    }
    @Test fun emptyOrUnvalidatedModelsDoNotBecomeReady() {
        assertTrue(LocalModelRouter.route(request(), emptyList()) is ModelRoute.NoReadyModel)
        assertTrue(LocalModelRouter.route(request(), listOf(fast().copy(validated = false))) is ModelRoute.NoReadyModel)
    }
    @Test fun severeThermalStateDefersModelsButKeepsRulesAvailable() {
        assertTrue(LocalModelRouter.route(request(thermal=3),listOf(fast(),quality())) is ModelRoute.NoReadyModel)
        assertTrue(LocalModelRouter.route(request(thermal=3).copy(ruleMatched=true),listOf(fast())) is ModelRoute.RuleDirect)
    }
    @Test fun unmeasuredGgufOrUnvalidatedGpuCannotBeRouted() {
        listOf(fast().copy(runtime = "gguf",validated=false), fast().copy(backend = "GPU")).forEach {
            assertTrue(LocalModelRouter.route(request(), listOf(it)) is ModelRoute.NoReadyModel)
        }
    }
    @Test fun measuredCpuGgufUsesTheSameMemoryAndThermalGates() {
        val model=fast().copy(runtime="gguf")
        assertTrue(LocalModelRouter.route(request(),listOf(model)) is ModelRoute.Selected)
        assertTrue(LocalModelRouter.route(request(thermal=3),listOf(model)) is ModelRoute.NoReadyModel)
    }
    @Test fun absentUnsupportedOrInvalidMeasurementsCannotEstablishReadiness() {
        listOf(fast().copy(ttftMs = null), fast().copy(decodeTPS = null), fast().copy(peakPssMb = null),
            fast().copy(ttftMs = Double.NaN), fast().copy(decodeTPS = Double.POSITIVE_INFINITY),
            fast().copy(peakPssMb = 0)).forEach {
            assertTrue(LocalModelRouter.route(request(), listOf(it)) is ModelRoute.NoReadyModel)
        }
    }
    @Test fun validatedE2bSmokeIsEnoughForRoutineCpuButNotAQualityClaim() {
        val route = selected(request(), listOf(fast()))
        assertEquals("gemma-e2b", route.modelId)
        assertFalse(route.thinking)
        assertEquals(4096, route.contextSize)
        assertEquals(192, route.maxOutputTokens)
    }
    @Test fun warmFastModelWinsOverFasterColdCandidate() {
        val warm = fast("warm", warm = true).copy(ttftMs = 2000.0)
        val cold = fast("cold").copy(ttftMs = 200.0)
        assertEquals("warm", selected(request(), listOf(cold, warm)).modelId)
    }
    @Test fun routineRequestDoesNotUseQualityEvenWhenQualityIsWarm() {
        assertEquals("gemma-e2b", selected(request(), listOf(quality().copy(warm = true), fast())).modelId)
        assertTrue(LocalModelRouter.route(request(), listOf(quality())) is ModelRoute.NoReadyModel)
    }
    @Test fun complexRequestUsesEnduranceValidatedQualityWithoutThinking() {
        val route = selected(request(complexity = 2), listOf(fast(), quality()))
        assertEquals("quality-e4b", route.modelId)
        assertFalse(route.thinking)
        assertEquals(512, route.maxOutputTokens)
    }
    @Test fun explicitDeepUsesQualityThinkingWith1024Cap() {
        val route = selected(request(deep = true, complexity = 3), listOf(fast(), quality()))
        assertEquals("quality-e4b", route.modelId)
        assertTrue(route.thinking)
        assertEquals(1024, route.maxOutputTokens)
    }
    @Test fun unpassedEnduranceNeverPromotesQuality() {
        val route = selected(request(deep = true), listOf(fast(), quality().copy(endurancePassed = false)))
        assertEquals("gemma-e2b", route.modelId)
        assertFalse(route.thinking)
        assertEquals(1024, route.maxOutputTokens)
    }
    @Test fun powerSaverAndModerateThermalFallBackToFast() {
        listOf(request(deep = true, powerSave = true), request(deep = true, thermal = 2)).forEach {
            val route = selected(it, listOf(fast(), quality()))
            assertEquals("gemma-e2b", route.modelId)
            assertFalse(route.thinking)
            assertEquals(4096, route.contextSize)
        }
    }
    @Test fun memoryHeadroomRequiredForQualityOtherwiseFast() {
        assertEquals("gemma-e2b", selected(request(deep = true, memory = 3_000), listOf(fast(), quality())).modelId)
        assertTrue(LocalModelRouter.route(request(memory = 2_511), listOf(fast())) is ModelRoute.NoReadyModel)
        assertEquals("gemma-e2b", selected(request(memory = 2_512), listOf(fast())).modelId)
    }
    @Test fun residentModelReusesMeasuredAllocationButStillNeedsReserve() {
        assertEquals("gemma-e2b", selected(request(memory = 512), listOf(fast(warm = true))).modelId)
        assertTrue(LocalModelRouter.route(request(memory = 511), listOf(fast(warm = true))) is ModelRoute.NoReadyModel)
    }
    @Test fun largeInputDoesNotInvent8kReadinessFrom4kMetrics() {
        assertEquals(4096, selected(request(chars = 20_000), listOf(fast())).contextSize)
        assertEquals(4096, selected(request(chars = 20_000), listOf(fast().copy(context8192PeakPssMb = 1500))).contextSize)
    }
    @Test fun separatelyMeasured8kRequiresLargeInputAndEnoughMemory() {
        val measured = fast().copy(context8192PeakPssMb = 4_000)
        assertEquals(8192, selected(request(chars = 20_000), listOf(measured)).contextSize)
        assertEquals(4096, selected(request(chars = 1_000), listOf(measured)).contextSize)
        assertEquals(4096, selected(request(memory = 3_000, chars = 20_000), listOf(measured)).contextSize)
        assertEquals(4096, selected(request(powerSave = true, chars = 20_000), listOf(measured)).contextSize)
        assertEquals(4096, selected(request(thermal = 2, chars = 20_000), listOf(measured)).contextSize)
    }
    @Test fun deterministicTieBreakDoesNotDependOnInputOrdering() {
        val a = fast("a")
        val b = fast("b")
        assertEquals(selected(request(), listOf(a,b)), selected(request(), listOf(b,a)))
    }
    @Test fun invalidComplexityCannotSilentlySelectAnExpensiveRoute() {
        assertTrue(runCatching { request(complexity = 4) }.isFailure)
        assertTrue(runCatching { request(complexity = -1) }.isFailure)
    }
}
