package com.user.localagent

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.compute.PcTransport
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Health only: no model load, personal data, persistent route change or LAN discovery fallback. */
@RunWith(AndroidJUnit4::class)
class TailscaleTransportValidation {
    @Test fun pairedOverlayHealthThroughPinnedTransport()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tailscaleValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val config=checkNotNull(PcComputeSettings.get(context).snapshot()){"PAIRING_REQUIRED"}
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        val vpnPresent=connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true
        }
        var authenticated=false
        var identityMatched=false
        val started=SystemClock.elapsedRealtime()
        try {
            val transport=PcTransport(context)
            val overlay=config.overlayEndpoint?:transport.json(config,"/v1/health",timeout=8000)["overlayEndpoint"]?.jsonPrimitive?.contentOrNull
                ?:error("OVERLAY_NOT_ADVERTISED")
            val host=URI(overlay).host
            val parts=host.split('.').mapNotNull{it.toIntOrNull()}
            assertTrue("OVERLAY_MUST_BE_TAILSCALE_IPV4",parts.size==4&&parts[0]==100&&parts[1] in 64..127)
            // json() uses only this endpoint and the original pairing token/certificate pin.
            val result=transport.json(config.copy(endpoint=overlay),"/v1/health",timeout=15000)
            authenticated=result["service"]?.jsonPrimitive?.content=="ZipHapCompute"
            identityMatched=result["serverId"]?.jsonPrimitive?.content==config.serverId
            assertTrue("OVERLAY_SERVICE_MISMATCH",authenticated)
            assertTrue("OVERLAY_PAIRING_IDENTITY_MISMATCH",identityMatched)
        } finally {
            File(context.getExternalFilesDir(null),"diagnostics/tailscale-transport-validation.json").apply {
                parentFile?.mkdirs()
                writeText(buildJsonObject {
                    put("kind","phone_direct_overlay_authenticated_health")
                    put("vpnPresent",vpnPresent);put("authenticated",authenticated);put("identityMatched",identityMatched)
                    put("usesOriginalCertificatePin",true);put("lanFallbackAllowed",false)
                    put("elapsedMs",SystemClock.elapsedRealtime()-started)
                    put("externalNetworkHandoffTested",false)
                }.toString())
            }
        }
    }
}
