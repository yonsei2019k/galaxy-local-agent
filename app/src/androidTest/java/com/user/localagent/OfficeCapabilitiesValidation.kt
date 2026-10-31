package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.skills.BundledSkillStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real installed handlers and signed assets. No private accounts, calendar reads or writes. */
@RunWith(AndroidJUnit4::class)
class OfficeCapabilitiesValidation {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=(context.applicationContext as LocalAgentApplication).container
    private suspend fun run(name:String,args:JsonObject):JsonObject {
        val result=app.tools.execute(ToolCall(UUID.randomUUID().toString(),name,args))
        assertTrue("Installed handler failed: $name",result.success)
        assertEquals(VerificationState.VERIFIED,result.verification)
        return Json.parseToJsonElement(result.content).jsonObject
    }

    @Test fun installedOfflineHandlersCalculateAndSubtractOvernightBusyIntervals()=runBlocking {
        val value=run("calculate",buildJsonObject {put("expression","(1234.5+65.5)*0.9");put("decimalPlaces",2)})
        assertEquals("1170",value.getValue("value").jsonPrimitive.content)
        val date=run("date_calculate",buildJsonObject {put("operation","add_days");put("date","2028-02-28");put("days",2)})
        assertEquals("2028-03-01",date.getValue("resultDate").jsonPrimitive.content)
        val windows=run("find_free_time",buildJsonObject {
            put("start","2026-10-31T22:00:00+09:00");put("end","2026-11-01T08:00:00+09:00");put("timeZone","Asia/Seoul")
            put("durationMinutes",60);put("bufferMinutes",30)
            put("busy",buildJsonArray {add(buildJsonObject {put("start","2026-11-01T01:00:00+09:00");put("end","2026-11-01T03:00:00+09:00")})})
        })
        assertFalse(windows.getValue("calendarRead").jsonPrimitive.boolean)
        assertEquals(2,windows.getValue("matchingWindowCount").jsonPrimitive.int)
        assertEquals("2026-11-01T00:30+09:00",windows.getValue("windows").jsonArray.first().jsonObject.getValue("end").jsonPrimitive.content)
    }

    @Test fun installedBundlePreservesExistingSkillsAndLoadsAllNewBodiesWithHashes() {
        val store=BundledSkillStore(context)
        val catalog=store.catalog()
        assertTrue(catalog.size>=21)
        for(id in listOf("web-research","calendar","email","work-briefing","availability-planning","evidence-research")) {
            val read=store.read(id)
            assertTrue(read.body.isNotBlank());assertEquals(64,read.sha256.length)
            assertTrue(read.metadata.tools.all {it in ToolRegistry.definitions})
        }
        assertTrue(store.summaryForPrompt().length<=4000)
    }

    @Test fun installedPublicApiHandlersReturnObservedPublicationAndExactDoiMetadata()=runBlocking {
        assumeTrue("Explicit public-query test flag required",InstrumentationRegistry.getArguments().getString("officeToolsPublicApproved")=="true")
        withTimeout(100_000) {
            val publications=run("search_publications",buildJsonObject {put("query","TITLE_ABS:(shift work sleep)");put("limit",1)})
            assertTrue(publications.getValue("hitCount").jsonPrimitive.long>0)
            assertEquals(1,publications.getValue("sources").jsonArray.size)
            assertTrue(publications.getValue("observedUrls").jsonArray.isNotEmpty())
            val reference=run("verify_reference",buildJsonObject {put("doi","10.1038/nature12373")})
            assertEquals("exact_doi",reference.getValue("matchType").jsonPrimitive.content)
            assertFalse(reference.getValue("exactCitationMatch").jsonPrimitive.boolean)
            assertEquals("10.1038/nature12373",reference.getValue("doi").jsonPrimitive.content)
        }
    }
}
