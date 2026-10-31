package com.user.localagent

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.api.ApiException
import com.user.localagent.connections.ConnectionHub
import com.user.localagent.core.ToolCall
import com.user.localagent.core.connections.ConnectedService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real SDK preflight. Pending consent is explicitly not a successful account connection. */
class GoogleAccountAuthorizationValidation {
    @Test fun connectRequestedGoogleServicesThroughConsent()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("googleConnectionValidationApproved")=="true")
        val context=instrumentation.targetContext
        val hub=ConnectionHub.get(context)
        hub.store.write("google.batch.status",null)
        fun buttons(view:View):List<Button> = (if(view is Button)listOf(view)else emptyList())+
            if(view is ViewGroup)(0 until view.childCount).flatMap {buttons(view.getChildAt(it))}else emptyList()
        var state="not_started"
        ActivityScenario.launch<com.user.localagent.connections.ConnectionsActivity>(Intent(context,com.user.localagent.connections.ConnectionsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {scenario->
            scenario.onActivity {activity->
                buttons(activity.findViewById(android.R.id.content)).single {it.text.toString()=="구글 전체 연결 · 서비스별 권한 확인"}.performClick()
            }
            withTimeout(300_000){
                while(true){
                    state=hub.store.read("google.batch.status")?:"starting"
                    if(state in setOf("accounts_connected_unverified","partial_accounts_connected","user_canceled","consent_result_error","authorization_error")||state.startsWith("google_sdk_error:"))break
                    delay(500)
                }
            }
            val connected=com.user.localagent.core.connections.ConnectedService.entries.filter {it.google}.associate {it.id to (hub.store.connection(it)!=null)}
            val report=buildJsonObject {
                put("state",state);put("connections",buildJsonObject {connected.forEach {(id,value)->put(id,value)}})
                put("accountNameOrTokenLogged",false);put("permissionBypassed",false)
            }
            val output=File(context.getExternalFilesDir(null),"diagnostics/google-account-connections.json")
            output.parentFile!!.mkdirs();output.writeText(report.toString())
            instrumentation.sendStatus(0,Bundle().apply {putString("stream",report.toString()+"\n")})
            assertTrue("Google account connection still requires owner consent or configuration",state=="accounts_connected_unverified")
        }
    }
    @Test fun driveAuthorizationPreflight()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("googleConnectionValidationApproved")=="true")
        val context=instrumentation.targetContext
        val hub=ConnectionHub.get(context)
        var state="not_started";var apiCode:Int?=null
        try {
            hub.google.registration.verifyInstalledIdentity()
            val result=withTimeout(35_000){hub.google.authorize(ConnectedService.DRIVE,false)}
            if(result.hasResolution())state="user_consent_required"
            else {
                hub.google.accept(ConnectedService.DRIVE,false,result)
                val read=hub.execute(ToolCall(UUID.randomUUID().toString(),"search_drive_files",buildJsonObject {put("query","ZipHap")}))
                state=if(read.success)"account_read_verified"else"account_connected_read_failed"
            }
        }catch(error:ApiException){state="google_sdk_error";apiCode=error.statusCode}
        catch(_:Exception){state="identity_or_authorization_error"}
        val report=buildJsonObject {
            put("state",state);put("apiStatusCode",apiCode);put("accountAuthenticated",state=="account_read_verified")
            put("permissionBypassed",false);put("accountNameOrTokenLogged",false)
        }
        val file=File(context.getExternalFilesDir(null),"diagnostics/google-authorization-validation.json")
        file.parentFile!!.mkdirs();file.writeText(report.toString())
        instrumentation.sendStatus(0,Bundle().apply {putString("stream",report.toString()+"\n")})
        assertTrue("Actual Google authorization did not reach consent or a verified read",state in setOf("user_consent_required","account_read_verified"))
    }
}
