package com.user.localagent

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI

/** Explicit live public-query check. No account, private history, SMS or purchase operations. */
@RunWith(AndroidJUnit4::class)
class WebResearchValidation {
    @Test fun observedPublicShoppingSources()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit webResearchApproved=true is required",args.getString("webResearchApproved")=="true")
        val context=instrumentation.targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val output=File(File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").apply {mkdirs()},"web-research.json")
        var saved=false
        try {
            instrumentation.sendStatus(0,Bundle().apply {putString("stream","Public web research: searching approved public product query\n")})
            val result=withTimeout(130_000){app.webResearch.research("샤오미 공기청정기",shopping=true,store="coupang")}
            withContext(Dispatchers.IO){output.writeText(result.toToolJson());saved=true}
            assertEquals("샤오미 공기청정기",result.query)
            assertTrue("Live public research must return observed sources, not a model refusal",result.items.isNotEmpty())
            assertTrue("The requested Coupang product link must actually be observed",result.items.any {it.section=="preferred"&&URI(it.url).host.endsWith("coupang.com")&&URI(it.url).path.startsWith("/vp/products/")})
            result.items.forEach {item->
                assertTrue(item.title.isNotBlank());assertEquals("https",URI(item.url).scheme)
                if(item.price!=null){assertNotNull(item.currency);assertNotNull(item.offerUrl);assertNotNull(item.priceFetchedAt);assertNotNull(item.priceSource)}
            }
            assertFalse(app.webResearch.state.value.running)
            assertTrue(Json.parseToJsonElement(result.toToolJson()).jsonObject.getValue("purchaseExecuted").jsonPrimitive.boolean.not())
        }catch(error:Throwable){
            if(!saved)withContext(NonCancellable+Dispatchers.IO){output.writeText(buildJsonObject {put("query","샤오미 공기청정기");put("scope","public_web_only");put("success",false);put("failureClass",error.javaClass.name);put("privateSourcesRead",false);put("purchaseExecuted",false)}.toString())}
            throw error
        }
    }
}
