package com.user.localagent

import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.connections.ConnectionHub
import com.user.localagent.core.ScreenControlToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.connections.ConnectedService
import com.user.localagent.integrations.ScreenControl
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime

@RunWith(AndroidJUnit4::class)
class ScreenObservationDeviceValidation {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()

    @Test fun usesTheChosenRowAndRejectsDisabledAndStaleReferences()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("observeFixture")=="true")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context=instrumentation.targetContext
        val control=ScreenControl(context)
        val waitSeconds=InstrumentationRegistry.getArguments().getString("screenBindingWaitSeconds")
            ?.toLongOrNull()?.coerceIn(1,120)?:30
        val ready=withTimeoutOrNull(waitSeconds*1_000) {
            while(!control.ready())delay(250)
            true
        }?:false
        assertTrue("Enable the existing Android screen service and ZipHap screen access. App setting: ${control.enabled()}",ready)
        val fixturePackage=instrumentation.context.packageName
        context.startActivity(Intent().setClassName(fixturePackage,ScreenObservationFixtureActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        suspend fun read():JsonObject {
            repeat(24) {
                val result=control.execute(ToolCall("qa-read",ScreenControlToolCatalog.READ,buildJsonObject {}))
                if(result.success) {
                    val payload=Json.parseToJsonElement(result.content).jsonObject
                    if(payload["packageName"]?.jsonPrimitive?.content==fixturePackage)return payload
                }
                delay(250)
            }
            error("Synthetic screen did not become observable")
        }
        fun elements(payload:JsonObject)=payload.getValue("elements").jsonArray.map {it.jsonObject}
        suspend fun tap(snapshot:JsonObject,target:JsonObject)=control.execute(
            ToolCall("qa-tap",ScreenControlToolCatalog.ACTION,buildJsonObject {
                put("action","tap");put("expectedLabel","Choose")
                put("snapshotId",snapshot.getValue("snapshotId"))
                put("ref",target.getValue("ref"))
            })
        )
        val before=read()
        val choices=elements(before).filter {it["label"]==JsonPrimitive("Choose")}
        assertEquals(3,choices.size)
        val byRow=choices.associateBy {it.getValue("collectionItem").jsonObject.getValue("rowIndex").jsonPrimitive.int}
        assertEquals(setOf(0,1,2),byRow.keys)
        val disabled=byRow.getValue(2)
        assertEquals(JsonPrimitive(true),disabled["blocked"])
        assertTrue(tap(before,disabled).let {!it.success&&it.effectNotApplied})
        val after=Json.parseToJsonElement(tap(before,byRow.getValue(1)).also {assertTrue(it.success)}.content).jsonObject
        val clicked=elements(after).single {it["label"]==JsonPrimitive("Chosen row 2")}
        assertEquals(JsonPrimitive(true),clicked["selected"])
        assertFalse(elements(after).any {it["label"]==JsonPrimitive("Chosen row 1")})
        assertTrue(tap(before,byRow.getValue(0)).let {!it.success&&it.effectNotApplied})
        assertEquals(after["snapshotId"],read()["snapshotId"])
    }

    @Test fun checksExistingGoogleConnectionsWithReadOnlyServiceCalls()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("googleReadProbe")=="true")
        val hub=ConnectionHub.get(instrumentation.targetContext)
        val checks=mapOf(
            ConnectedService.GMAIL to ToolCall("qa-gmail","search_gmail_messages",buildJsonObject {
                put("query","subject:ziphap-connection-canary-no-match")
            }),
            ConnectedService.CALENDAR to ToolCall("qa-calendar","list_google_calendar_events",buildJsonObject {
                val now=OffsetDateTime.now()
                put("start",now.toString());put("end",now.plusMinutes(1).toString())
            })
        )
        val states=checks.mapValues {(service,call)->
            if(hub.store.connection(service)==null)"connection_required"
            else {
                val result=hub.execute(call)
                if(result.success)"read_verified" else "read_failed"
            }
        }
        instrumentation.sendStatus(0,Bundle().apply {
            putString("stream",states.entries.joinToString(" ") {"${it.key.id}=${it.value}"}+"\n")
        })
        assertTrue("Read probe: ${states.entries.joinToString()}",states.values.all {it=="read_verified"})
    }
}

