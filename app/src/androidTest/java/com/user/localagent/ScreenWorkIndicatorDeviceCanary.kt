package com.user.localagent

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.integrations.ScreenBridgeService
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScreenWorkIndicatorDeviceCanary {
    @Test
    fun activeScreenWorkHoldsDisplayUntilLastRunStops() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenWorkIndicatorApproved") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val bridge = checkNotNull(ScreenBridgeService.current()) { "Enable and connect the screen service before running the canary" }
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        try {
            instrumentation.runOnMainSync { bridge.markScreenWork(first); bridge.markScreenWork(second) }
            await(automation, true)
            instrumentation.runOnMainSync { bridge.endScreenWork(first) }
            await(automation, true)
            instrumentation.runOnMainSync { bridge.endScreenWork(second) }
            await(automation, false)
        } finally {
            instrumentation.runOnMainSync { bridge.endScreenWork(first); bridge.endScreenWork(second) }
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "screenWorkIndicator=verified screenHold=activeUntilLastRunStopped\n")
        })
    }

    private fun await(automation: UiAutomation, held: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            val dump = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys window displays"))
                .bufferedReader().use { it.readText() }
            val line = dump.lineSequence().firstOrNull { "mHoldScreenWindow=" in it }
            if (line != null && ("ZipHap screen work" in line) == held) return
            SystemClock.sleep(200)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("Expected the ZipHap display hold to be ${if (held) "active" else "released"}", false)
    }
}
