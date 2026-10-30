package com.user.localagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.automation.AssistantNotificationListener
import com.user.localagent.automation.AutomationSettings
import com.user.localagent.automation.ReflectionController
import com.user.localagent.core.CapabilityPolicy
import com.user.localagent.data.GrantRecord
import com.user.localagent.integrations.MessengerInbox
import com.user.localagent.integrations.ScreenAdapters
import com.user.localagent.integrations.ScreenBridgeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit owner setup only. Skip during ordinary connected tests/CI.
 * Invoke this class/method with instrumentation argument: -e ownerSetupApproved true.
 * Changes only the owner's requested app-local defaults; Android grants remain unchanged.
 */
@RunWith(AndroidJUnit4::class)
class OwnerSetup {
    @Test
    fun applyRequestedLocalDefaults() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Owner setup requires explicit ownerSetupApproved=true invocation",
            InstrumentationRegistry.getArguments().getString("ownerSetupApproved") == "true")
        // Do not initialize or mutate application state before the explicit invocation guard.
        val context = instrumentation.targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val screen = ScreenBridgeController(context)
        val reflection = ReflectionController(context)
        val desired = AutomationSettings(assistantPatternsEnabled = true, usageStatsEnabled = true)
        val requestedMessenger = setOf("com.tionsoft.meettalk.eun", "com.meditong.gw", "com.kakao.talk",
            "org.telegram.messenger", "org.telegram.messenger.web")
        val requestedScreens = requestedMessenger + "com.android.chrome"
        val tools = setOf("save_note", "update_note", "create_reminder", "update_reminder")
        assertTrue(requestedMessenger.all { it in MessengerInbox.supported })
        assertTrue(requestedScreens.all { it in ScreenAdapters.packages })
        assertTrue(tools.all { CapabilityPolicy.canPreapprove(it) })
        val codeHash = withContext(Dispatchers.IO) { app.hostCodeHash }
        assertTrue(codeHash.matches(Regex("[a-f0-9]{64}")))
        val grants = tools.map { GrantRecord("builtin", codeHash, it, "default") }
        var fgsCallAttempted = false
        var fgsCallReturned = false
        var fgsCallFailed = false
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        // Launching the app is explicit test setup, not a third-party UI action. The Activity remains
        // visible while settings are applied, keeping background reflection out of foreground inference.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            app.automation.updateSettings(desired)
            app.automation.start()
            reflection.enable(true)
            grants.forEach { app.repository.addGrant(it) }
            requestedMessenger.forEach { MessengerInbox.setAllowed(context, it, true) }
            requestedScreens.forEach { screen.setAllowed(it, true) }
            if (notificationsGranted) {
                scenario.onActivity {
                    fgsCallAttempted = true
                    try {
                        // Exactly one request from a visible Activity. No Android permission is granted here.
                        app.automation.startVisibleMode()
                        fgsCallReturned = true
                    } catch (_: RuntimeException) {
                        fgsCallFailed = true // Report the request failure; do not claim service hosting succeeded.
                    }
                }
            }
            assertEquals(desired, app.automation.settings())
            assertTrue(app.automation.state().enabled)
            assertTrue(reflection.isEnabled())
            val currentGrants = app.repository.listGrants()
            assertTrue(grants.all { it in currentGrants })
            assertTrue(requestedMessenger.all { MessengerInbox.allowed(context, it) })
            assertTrue(screen.state().allowedPackages.containsAll(requestedScreens))
        } // Closing only this Activity leaves a successfully started foreground service running.

        assertTrue(app.automation.state().enabled)
        assertEquals(desired, app.automation.settings())
        val savedGrants = app.repository.listGrants()
        val flags = Bundle().apply {
            putBoolean("owner_setup_explicitly_approved", true)
            putBoolean("automation_enabled", app.automation.state().enabled)
            putBoolean("assistant_patterns_opt_in", app.automation.settings().assistantPatternsEnabled)
            putBoolean("usage_stats_opt_in", app.automation.settings().usageStatsEnabled)
            putBoolean("reflection_opt_in", reflection.isEnabled())
            putBoolean("current_host_default_grants_present", grants.all { it in savedGrants })
            putBoolean("requested_messenger_packages_allowed", requestedMessenger.all { MessengerInbox.allowed(context, it) })
            putBoolean("requested_screen_packages_allowed", screen.state().allowedPackages.containsAll(requestedScreens))
            putBoolean("post_notifications_granted", notificationsGranted)
            putBoolean("usage_access_granted", app.automation.hasUsageAccess())
            putBoolean("notification_listener_granted", AssistantNotificationListener.granted(context))
            putBoolean("accessibility_service_granted", screen.state().systemEnabled)
            putBoolean("visible_mode_start_attempted", fgsCallAttempted)
            putBoolean("visible_mode_start_call_returned", fgsCallReturned)
            putBoolean("visible_mode_start_call_failed", fgsCallFailed)
            putBoolean("visible_mode_requested_flag", app.automation.state().visibleModeRequested)
        }
        // Only booleans: no inbox reads, contacts, identities, message text, code hash, or action tokens.
        instrumentation.sendStatus(0, flags)
    }
}

