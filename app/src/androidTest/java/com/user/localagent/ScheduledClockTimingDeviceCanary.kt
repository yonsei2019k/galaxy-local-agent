package com.user.localagent

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.ScheduledReadPolicy
import com.user.localagent.core.ScheduledReadToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolRegistry
import com.user.localagent.core.VerificationState
import com.user.localagent.data.ProjectSettings
import com.user.localagent.data.WorkTraceCode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledClockTimingDeviceCanary {
    @Test fun readsOnceAndSavesTheDeviceTime() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("scheduledClockCanaryApproved") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = (instrumentation.targetContext.applicationContext as LocalAgentApplication).container
        check(!app.runs.hasActiveRuns.value && !app.imageStudio.state.value.running) { "phone_busy" }
        val chat = app.repository.chat
        val screen = ActivityScenario.launch(MainActivity::class.java)
        val project = chat.createProject("QA scheduled clock ${UUID.randomUUID()}",
            ProjectSettings(memoryMode = "off", useOwnerMemory = false, learnOwnerFacts = false))
        val room = chat.createThread(projectId = project.id, title = "QA scheduled clock")
        val registration = chat.startRun(chat.createRun(room.id, room.activeBranchId).id)
        var scheduleId: String? = null
        val live = setOf(ScheduledReadPolicy.SCHEDULED, "queued", "running", "waiting_approval")
        try {
            val due = System.currentTimeMillis() + 15_000L
            val zone = ZoneId.of("Asia/Seoul")
            val call = ToolRegistry.validate(ToolCall(UUID.randomUUID().toString(), ScheduledReadToolCatalog.CREATE, buildJsonObject {
                put("title", "합성 시각 조회")
                put("prompt", "예정 시각에 기기의 날짜와 시각을 한 번 조회해서 그대로 알려 주세요.")
                put("datetime", Instant.ofEpochMilli(due).atZone(zone).toOffsetDateTime().toString())
                put("readTool", ScheduledReadPolicy.CLOCK_TOOL)
                put(ScheduledReadToolCatalog.CLOCK_RECEIPT_ONLY, true)
            }))
            val registered = app.scheduledReads.execute(call, registration.id)
            val id = Json.parseToJsonElement(registered.content).jsonObject.getValue("scheduleId").jsonPrimitive.content
            check(ScheduledReadPolicy.isId(id)) { "invalid_schedule" }
            scheduleId = id
            check(registered.success && registered.verification == VerificationState.VERIFIED) { "registration_unverified" }
            val terminal = withTimeout(150_000L) {
                var row = checkNotNull(chat.run(id))
                while (row.status in live) {
                    delay(250L)
                    row = checkNotNull(chat.run(id))
                }
                row
            }
            check(terminal.status == "completed" && terminal.threadId == room.id) { "clock_schedule_incomplete" }
            val request = QueuedChatRequest.fromJson(terminal.contextJson)
            check(request.scheduledClockReceiptOnly && request.previousSteps.count {
                it.tool == ScheduledReadPolicy.CLOCK_TOOL && it.evidence.state == VerificationState.VERIFIED
            } == 1) { "clock_readback_count" }
            val page = app.repository.workTrace.page(room.id, id)
            check(page.nextCursor == null && page.totalCount == page.events.size.toLong()) { "trace_incomplete" }
            val start = page.events.single { it.event.code == WorkTraceCode.TOOL_STARTED && it.event.toolName == ScheduledReadPolicy.CLOCK_TOOL }
            val end = page.events.single { it.event.code == WorkTraceCode.TOOL_RESULT && it.event.toolName == ScheduledReadPolicy.CLOCK_TOOL }
            check(start.event.invocationId != null && start.event.invocationId == end.event.invocationId) { "clock_trace_join" }
            val assistant = checkNotNull(chat.assistantForRun(id))
            val line = chat.fullMessageText(assistant.id).lineSequence().first()
            val prefix = "기기에서 조회한 시각: "
            check(line.startsWith(prefix)) { "clock_render_missing" }
            val rendered = ZonedDateTime.parse(line.removePrefix(prefix))
            check(rendered.zone == zone && rendered.toInstant().toEpochMilli() in start.atEpochMillis..end.atEpochMillis) {
                "rendered_clock_outside_verified_tool_window"
            }
            val modelEvents = page.events.count {
                it.event.code in setOf(WorkTraceCode.SEMANTIC_STARTED, WorkTraceCode.INFERENCE_STARTED)
            }
            check(modelEvents == 0) { "unexpected_model_trace" }
            val started = checkNotNull(terminal.startedAt)
            val finished = checkNotNull(terminal.finishedAt)
            check(started >= due && finished >= started) { "invalid_schedule_timing" }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "scheduledClock=verified startDelayMs=${started - due} elapsedMs=${finished - started} " +
                    "verifiedReads=1 modelTraceCount=0 renderedWithinToolWindow=true\n")
            })
        } finally {
            withContext(NonCancellable) {
                scheduleId?.let { id ->
                    if (chat.run(id)?.status in live) {
                        app.scheduledReads.execute(ToolCall(UUID.randomUUID().toString(), ScheduledReadToolCatalog.CANCEL,
                            buildJsonObject { put("scheduleId", id) }), registration.id)
                    }
                    check(chat.run(id)?.status !in live) { "clock_fixture_still_active" }
                }
                chat.updateRun(registration.id, "completed")
                chat.deleteProject(project.id)
                check(chat.thread(room.id) == null && chat.project(project.id) == null) { "clock_fixture_cleanup_unconfirmed" }
                runCatching { screen.close() }
            }
        }
    }
}
