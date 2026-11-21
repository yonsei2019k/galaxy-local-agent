package com.user.localagent

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.office.ZipHapRoom
import com.user.localagent.externalagent.ExternalAgentSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in current-owner check. Only statuses, tool names and counts leave the device. */
@RunWith(AndroidJUnit4::class)
class OwnerCloudRecallDeviceValidation {
    @Test fun auditReportedFollowup() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownerRecallDeviceApproved") == "true")
        val context = instrument.targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val store = app.repository.chat
        val chair = checkNotNull(store.thread(ZipHapRoom.MAIN.threadId))
        val followup = store.runsForThread(chair.id).asReversed().firstOrNull { run ->
            val text = runCatching { QueuedChatRequest.fromJson(run.contextJson).text }.getOrDefault("")
            text.contains("문자") && text.contains("메일") && text.contains("카톡") && text.contains("알게")
        }
        val scopes = store.threads.first().distinctBy { it.projectId ?: it.id }
        var archivedAnalyses = 0
        var chairArchivedAnalyses = 0
        var scopesWithArchives = 0
        var finishedArchives = 0
        var otherScopeIndexedFindings = 0
        for (thread in scopes) {
            val repository = app.repository.forThread(thread.id)
            val archiveNotes = repository.notes.first().filter { archive ->
                archive.collection == "private-analysis" && archive.title == "개인 자료 분석 진행"
            }
            val archived = archiveNotes.size
            if (archived > 0) {
                scopesWithArchives++
                finishedArchives += archiveNotes.count { archive ->
                    val metadata = runCatching { Json.parseToJsonElement(archive.body).jsonObject }.getOrNull()
                    metadata?.get("sourceExhausted")?.jsonPrimitive?.booleanOrNull == true
                }
                if (thread.id != chair.id && (thread.projectId == null || thread.projectId != chair.projectId)) {
                    otherScopeIndexedFindings += repository.knowledge.first().count { it.sourceKind == "analysis_finding" }
                }
            }
            archivedAnalyses += archived
            if (thread.id == chair.id || thread.projectId != null && thread.projectId == chair.projectId) {
                chairArchivedAnalyses = archived
            }
        }
        val report = buildJsonObject {
            put("archivedAnalysesInLiveScopes", archivedAnalyses)
            put("chairArchivedAnalyses", chairArchivedAnalyses)
            put("scopesWithArchives", scopesWithArchives)
            put("finishedArchives", finishedArchives)
            put("otherScopeIndexedFindings", otherScopeIndexedFindings)
            put("found", followup != null)
            if (followup != null) {
                val ctx = Json.parseToJsonElement(followup.contextJson).jsonObject
                put("status", followup.status)
                val response = store.assistantForRun(followup.id)?.let { store.fullMessageText(it.id) }.orEmpty()
                val namedSources = listOf("문자", "메일", "카톡").count(response::contains)
                val limitationWords = listOf("확인되지", "확인하지 못", "확인할 수 없", "찾지 못", "근거가 없", "범위", "자료가 없", "조회하지").any(response::contains)
                put("namedSourceCount", namedSources)
                put("containsSourceLimitationLanguage", namedSources > 0 && limitationWords)
                put("code", followup.errorCode?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,100}")) })
                val planned = ctx["externalAgentPlan"]?.jsonObject?.get("requiredTools")?.jsonArray.orEmpty()
                put("plannedTools", JsonArray(planned.filter { it.jsonPrimitive.content.matches(Regex("[a-z_]{1,100}")) }))
                put("steps", buildJsonArray {
                    ctx["previousSteps"]?.jsonArray.orEmpty().forEach {
                        val step = it.jsonObject
                        val name = step["tool"]?.jsonPrimitive?.content.orEmpty()
                        val state = step["state"]?.jsonPrimitive?.content.orEmpty()
                        if (name.matches(Regex("[a-z_]{1,100}")) && state.matches(Regex("[A-Z_]{1,48}"))) {
                            add(buildJsonObject { put("tool",name);put("state",state) })
                        }
                    }
                })
                put("tools", buildJsonArray {
                    for (step in store.toolRunsForThread(chair.id,followup.id).first()) {
                        if (step.name.matches(Regex("[a-z_]{1,100}"))) {
                            add(buildJsonObject { put("tool",step.name);put("success",step.success) })
                        }
                    }
                })
            }
        }
        File(checkNotNull(context.getExternalFilesDir("diagnostics")), "owner-cloud-followup-aggregate.json").writeText(report.toString())
    }

    @Test fun compareAutoAndCodexInChairRoom() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownerRecallDeviceApproved") == "true")
        val context = instrument.targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val store = app.repository.chat
        val coordinator = app.runs
        val consent = ExternalAgentSettings.get(context).capture()
        check(consent.permitsPersonalData(consent.revision)) { "PERSONAL_CLOUD_CONSENT_REQUIRED" }
        val chair = checkNotNull(store.thread(ZipHapRoom.MAIN.threadId)) { "CHAIR_ROOM_MISSING" }
        val followup = InstrumentationRegistry.getArguments().getString("ownerRecallFollowup") == "true"
        val question = if (followup) "문자, 메일, 카톡등으로 알게된 정보도 많잖아" else "나에 대해 이야기해봐"
        val models = if (followup) listOf("auto") else listOf("auto", ExternalAgentSettings.MODEL_ID)
        suspend fun waitFor(ms: Long, predicate: suspend () -> Boolean) =
            withTimeout(ms) { while (!predicate()) delay(150) }
        waitFor(20_000) { coordinator.selectionReady.value }
        check(!coordinator.hasActiveRuns.value && store.queuedRuns().isEmpty()) { "OWNER_RUN_ACTIVE" }
        val originalThread = coordinator.selectedThreadId.value
        val originalModel = coordinator.preferredModelId.value
        val originalChairModel = store.readDraft(chair.id)?.modelId ?: "auto"
        val repo = app.repository.forThread(chair.id)
        val profile = Json.parseToJsonElement(repo.ownerProfileContext(question)).jsonObject
        val availableProfile = profile["coreProfile"]?.jsonArray.orEmpty().mapNotNull { claim ->
            claim.jsonObject["value"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.length >= 3 }
        }.distinct()
        val analysisAvailable = repo.retrieveKnowledge("analysis_finding", 5).count { it.sourceKind == "analysis_finding" }
        val visibleScopes = store.threads.first().distinctBy { it.projectId ?: it.id }
        val scopesWithArchives = visibleScopes.count { thread ->
            app.repository.forThread(thread.id).notes.first().any {
                it.collection == "private-analysis" && it.title == "개인 자료 분석 진행"
            }
        }
        val chairArchives = repo.notes.first().count {
            it.collection == "private-analysis" && it.title == "개인 자료 분석 진행"
        }
        val rows = mutableListOf<JsonObject>()
        val reportName = if (followup) "owner-cloud-followup-retest.json" else "owner-cloud-recall-aggregate.json"
        val reportFile = File(checkNotNull(context.getExternalFilesDir("diagnostics")), reportName)
        fun publish() {
            reportFile.writeText(buildJsonObject {
                put("version", 1)
                put("availableProfileValues", availableProfile.size)
                put("indexedAnalysisFoundUpToFive", analysisAvailable)
                put("chairArchivedAnalyses", chairArchives)
                put("scopesWithArchivedAnalyses", scopesWithArchives)
                put("results", JsonArray(rows))
            }.toString())
        }
        publish()
        try {
            withContext(Dispatchers.Main.immediate) { coordinator.selectThread(chair.id) }
            waitFor(20_000) { coordinator.selectionReady.value && coordinator.selectedThreadId.value == chair.id }
            for (model in models) {
                val previous = store.runsForThread(chair.id).map { it.id }.toSet()
                val started = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main.immediate) {
                    coordinator.chooseModel(model, chair.id)
                    check(coordinator.preferredModelId.value == model && coordinator.send(question, chair.id)) {
                        "OWNER_QUERY_NOT_ADMITTED"
                    }
                }
                waitFor(20_000) { store.runsForThread(chair.id).any { it.id !in previous } }
                val runId = store.runsForThread(chair.id).single { it.id !in previous }.id
                val terminal = setOf("completed", "paused", "failed", "cancelled", "interrupted")
                val settled = withTimeoutOrNull(170_000) {
                    waitFor(160_000) { store.run(runId)?.status in terminal || coordinator.approval.value != null }
                    store.run(runId)?.status in terminal
                } == true
                if (!settled) {
                    withContext(Dispatchers.Main.immediate) { coordinator.stopRun(runId, chair.id) }
                }
                waitFor(20_000) { !coordinator.hasActiveRuns.value }
                val run = checkNotNull(store.run(runId))
                val snapshot = Json.parseToJsonElement(run.contextJson).jsonObject
                val requested = snapshot["externalAgentPlan"]?.jsonObject?.get("requiredTools")?.jsonArray.orEmpty()
                val memoryReads = snapshot["previousSteps"]?.jsonArray.orEmpty().mapNotNull { step ->
                    val item = step.jsonObject
                    if (item["tool"]?.jsonPrimitive?.content != "search_memories") return@mapNotNull null
                    val query = item["arguments"]?.jsonObject?.get("query")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val recalled = Json.parseToJsonElement(repo.ownerProfileContext(query, includeCore = query.isBlank())).jsonObject
                    val facts = recalled[if (query.isBlank()) "coreProfile" else "relevantKnowledge"]?.jsonArray.orEmpty()
                    buildJsonObject {
                        put("overview", query.isBlank())
                        put("eligibleFacts", facts.size)
                        put("eligibleAnalysisFindings", facts.count { it.jsonObject["sourceKind"]?.jsonPrimitive?.content == "analysis_finding" })
                    }
                }
                val audit = store.toolRunsForThread(chair.id, runId).first()
                val answer = store.assistantForRun(runId)?.let { store.fullMessageText(it.id) }.orEmpty()
                rows += buildJsonObject {
                    put("mode", model)
                    put("capturedCloud", snapshot["allowExternalAgent"]?.jsonPrimitive?.booleanOrNull == true)
                    put("finalModel", run.modelId)
                    put("status", run.status)
                    put("code", run.errorCode?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,100}")) })
                    put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    put("settled", settled)
                    put("plannedTools", JsonArray(requested.filter { it.jsonPrimitive.content.matches(Regex("[a-z_]{1,100}")) }))
                    put("tools", buildJsonArray {
                        audit.filter { it.name.matches(Regex("[a-z_]{1,100}")) }.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("success", tool.success)
                            })
                        }
                    })
                    put("memoryReads", JsonArray(memoryReads))
                    put("matchingApprovedProfileValues", availableProfile.count { answer.contains(it, ignoreCase = true) })
                    put("answerPresent", answer.isNotBlank())
                }
                publish()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (coordinator.selectedThreadId.value == chair.id) coordinator.chooseModel(originalChairModel, chair.id)
                coordinator.selectThread(originalThread)
            }
            waitFor(20_000) { coordinator.selectionReady.value && coordinator.selectedThreadId.value == originalThread }
            withContext(Dispatchers.Main.immediate) { coordinator.chooseModel(originalModel, originalThread) }
        }
    }
}

