package com.user.localagent

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.AssistantPolicy
import com.user.localagent.data.KnowledgeClaim
import com.user.localagent.inference.InferenceOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.ZoneId

/** Owner-authorized private fixture transfer. No identity, fixture, or response is compiled into source. */
@RunWith(AndroidJUnit4::class)
class DeveloperOwnerMemoryImport {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=(context.applicationContext as LocalAgentApplication).container
    private fun authorized()=assumeTrue("Explicit ownerKnowledgeImportApproved invocation required",
        InstrumentationRegistry.getArguments().getString("ownerKnowledgeImportApproved")=="true")
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}

    @Test fun importApprovedPrivateOwnerFactsAndVerifyInference()=runBlocking {
        authorized()
        val fixture=File(context.getExternalFilesDir(null),"diagnostics/owner-knowledge-seed.json")
        var count=0;var probePassed=false;var ownsInference=false;var ownsBenchmark=false
        try {
            require(fixture.length() in 1..64_000)
            val input=Json.parseToJsonElement(fixture.readText()) as JsonObject
            require(input.getValue("approvedByOwner").jsonPrimitive.boolean)
            val claims=input.getValue("claims").jsonArray
            require(claims.size in 1..64)
            val ledger=mutableMapOf<String,String>()
            for(item in claims) {
                val obj=item.jsonObject
                val key=obj.getValue("key").jsonPrimitive.content
                require(key.matches(Regex("[a-z0-9_.-]{1,80}")))
                val saved=app.repository.saveKnowledgeClaim(KnowledgeClaim(
                    id="owner.seed.$key",subject="owner",predicate=obj.getValue("predicate").jsonPrimitive.content,
                    value=obj.getValue("value").jsonPrimitive.content,status="confirmed",confidence=1.0,
                    sourceKind="owner_transfer",sourceId="owner-transfer-v1:$key",
                    sourceExcerpt=obj["sourceExcerpt"]?.jsonPrimitive?.content,
                    tags=obj["tags"]?.jsonArray?.map {it.jsonPrimitive.content}.orEmpty()))
                val stored=app.repository.getKnowledge(saved.id)
                assertNotNull("Approved claim must be read back",stored)
                assertEquals("Stored claim content must match",digest(saved.value),digest(stored!!.value))
                ledger[saved.id]=digest(saved.value)
                count++
            }
            // Digests and opaque IDs only, to verify a fresh process without another plaintext fixture.
            context.getSharedPreferences("owner-seed-validation",0).edit().clear().apply {
                ledger.forEach {(id,hash)->putString(id,hash)}
            }.commit().also {check(it)}
            app.knowledgeIngestion.setEnabled(true)
            app.knowledgeIngestion.scanNow(manual=true,maxSources=200)
            val probe=input.getValue("probe").jsonObject
            val question=probe.getValue("question").jsonPrimitive.content
            val expected=probe.getValue("expectedRegex").jsonArray.map {Regex(it.jsonPrimitive.content)}
            val ownerContext=app.repository.ownerProfileContext(question)
            assertTrue("Owner context must be populated",ownerContext.isNotBlank())
            ownsBenchmark=RuntimeGate.benchmark.compareAndSet(false,true);check(ownsBenchmark)
            ownsInference=RuntimeGate.inference.compareAndSet(false,true);check(ownsInference)
            app.models.refresh()
            val model=app.models.models.value.first {it.id=="gemma-e2b"&&it.verified}
            val output=StringBuilder()
            withTimeout(240_000) {
                app.engine.unload()
                app.engine.load(model.path,InferenceOptions(contextSize=4096,thinking=false,backend="CPU",maxOutputTokens=256))
                app.engine.generateChat(question,
                    AssistantPolicy.build(emptySet(),ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toString())+"\n"+ownerContext,
                    emptyList()).collect {output.append(it)}
            }
            probePassed=expected.all {it.containsMatchIn(output)}
            assertTrue("Actual local response must use the transferred owner facts",probePassed)
        } finally {
            fixture.delete()
            withContext(NonCancellable) {
                try {if(ownsInference)app.engine.unload()} finally {
                    if(ownsInference)RuntimeGate.inference.set(false)
                    if(ownsBenchmark)RuntimeGate.benchmark.set(false)
                }
            }
            val directory=File(context.getExternalFilesDir(null),"benchmarks").apply {check(isDirectory||mkdirs())}
            File(directory,"owner-memory-transfer.json").writeText(buildJsonObject {
                put("transferred_claims",count);put("actual_local_probe_passed",probePassed)
                put("fixture_deleted",!fixture.exists());put("profile_or_response_exported",false)
            }.toString())
            instrumentation.sendStatus(0,Bundle().apply {putString("stream","Private owner transfer: $count claims; inference verified=$probePassed; no profile text exported.\n")})
        }
    }

    @Test fun verifyOwnerFactsPersistInFreshProcess()=runBlocking {
        authorized()
        val hashes=context.getSharedPreferences("owner-seed-validation",0).all
        assertTrue("An approved transfer must precede persistence verification",hashes.isNotEmpty())
        hashes.forEach {(id,hash)->
            val record=app.repository.getKnowledge(id)
            assertNotNull("Transferred claim must survive process restart",record)
            assertEquals("Persisted encrypted claim must match",hash,digest(record!!.value))
        }
        instrumentation.sendStatus(0,Bundle().apply {putString("stream","Private owner facts persisted: ${hashes.size}; no profile text exported.\n")})
    }
}
