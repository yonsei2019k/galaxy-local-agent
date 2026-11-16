package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Read-only metadata of recent cloud (external agent) runs. Writes nothing to the database.
 * Never exports request text, tool arguments, results, summaries or answers: only tool names,
 * states, statuses, timings and the accepted plan's tool list.
 */
@RunWith(AndroidJUnit4::class)
class ExternalAgentRecentRunDiagnostic {
    @Test fun exportRecentCloudRunMetadata()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("externalAgentRecentDiagnosticApproved")=="true")
        val hours=(args.getString("externalAgentRecentHours")?.toLongOrNull()?:3L).coerceIn(1L,48L)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val since=System.currentTimeMillis()-hours*3_600_000L
        val store=app.repository.chat
        val threads=store.observeThreads(includeArchived=true).first().filter{it.updatedAt>=since}
        val runs=threads.flatMap{store.runsForThread(it.id)}.filter{it.createdAt>=since}.sortedBy{it.createdAt}
        val rows=buildJsonArray {
            runs.forEach {run->
                val ctx=runCatching{Json.parseToJsonElement(run.contextJson).jsonObject}.getOrNull()
                val external=ctx?.get("allowExternalAgent")?.jsonPrimitive?.booleanOrNull==true||ctx?.containsKey("externalAgentPlan")==true
                if(!external)return@forEach
                add(buildJsonObject {
                    put("run",run.id.take(8));put("status",run.status);put("errorCode",run.errorCode)
                    put("modelId",run.modelId);put("createdAt",run.createdAt)
                    put("elapsedMs",(run.finishedAt?:run.updatedAt)-(run.startedAt?:run.createdAt))
                    put("mode",ctx?.get("mode")?:JsonNull);put("requestChars",ctx?.get("text")?.jsonPrimitive?.contentOrNull?.length)
                    put("hasAttachments",ctx?.get("attachments")?.let{it is JsonArray&&it.isNotEmpty()})
                    put("planRequiredTools",ctx?.get("externalAgentPlan")?.jsonObject?.get("requiredTools")?:JsonNull)
                    put("hasReceipt",ctx?.containsKey("externalAgentReceipt")==true)
                    put("steps",buildJsonArray {
                        ctx?.get("previousSteps")?.jsonArray.orEmpty().forEach {step->
                            val o=step.jsonObject
                            add(buildJsonObject{put("tool",o["tool"]?:JsonNull);put("state",o["state"]?:JsonNull)})
                        }
                    })
                })
            }
        }
        val out=File(checkNotNull(context.getExternalFilesDir("diagnostics")),"external-agent-recent-runs.json")
        out.writeText(buildJsonObject{put("hours",hours);put("runs",rows)}.toString())
    }
}
