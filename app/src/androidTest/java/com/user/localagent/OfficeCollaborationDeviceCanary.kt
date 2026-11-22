package com.user.localagent

import android.os.Bundle
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.office.OfficeSeat
import com.user.localagent.core.office.ZipHapRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Retains only this new synthetic conversation evidence. Existing rooms and settings are left in place. */
@RunWith(AndroidJUnit4::class)
class OfficeCollaborationDeviceCanary {
    @Test fun distinctEmployeesReviewAndChairPublishes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("officeCanaryApproved") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = (instrumentation.targetContext.applicationContext as LocalAgentApplication).container
        val chat = app.repository.chat
        check(!app.runs.hasActiveRuns.value && !app.runs.busy.value && app.runs.queued.value.isEmpty() &&
            !app.imageStudio.state.value.running) { "phone_busy" }
        val office = chat.openFixedOfficeRooms()
        check(office.id == ZipHapRoom.OFFICE.threadId) { "office_binding" }
        val rooms = (officeEmployees + ZipHapRoom.MAIN).associateWith { room ->
            checkNotNull(chat.thread(room.threadId)) { "missing_staff_room" }
        }
        val parentId = UUID.randomUUID().toString()
        val text = "사무실 공동 작업용 합성 시험. 317 × 47 + 29를 계산하고 각 직원은 계산 방법과 검산 의견을 " +
            "자기 이름으로 작성하세요. 동료 의견을 읽고 검산한 뒤 회장이 최종 결과를 정리해 주세요. " +
            "시각이나 개인 자료, 파일, 실제 외부 서비스는 필요 없습니다."
        val stages = buildList {
            for (stage in listOf(OfficeStage.PROPOSAL, OfficeStage.REVIEW)) {
                officeEmployees.forEach { room -> add(room to stage) }
            }
            add(ZipHapRoom.MAIN to OfficeStage.SUMMARY)
        }
        val ids = stages.associateWith { (room, stage) -> officeChildId(parentId, room, stage) }
        check(ids.values.toSet().size == 7 && parentId !in ids.values) { "identity_collision" }

        val screen = ActivityScenario.launch(MainActivity::class.java)
        screen.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        var submitted = false
        try {
            check(withContext(Dispatchers.Main.immediate) {
                app.runs.submitVoiceCommand(text, office.id, "auto", parentId)
            }) { "submit_rejected" }
            submitted = true
            val liveStates = setOf("queued", "running", "waiting_approval")
            var observedChildren = -1
            val parent = withTimeout(21 * 60_000L) {
                while (true) {
                    val current = chat.run(parentId)
                    val completed = ids.values.count { chat.run(it)?.status == "completed" }
                    if (completed != observedChildren) {
                        observedChildren = completed
                        report("completedWorkers=$completed")
                    }
                    if (current != null && current.status !in liveStates) return@withTimeout current
                    delay(1_000)
                }
                error("unreachable")
            }
            check(parent.threadId == office.id && parent.branchId == office.activeBranchId) { "parent_scope" }
            val source = QueuedChatRequest.fromJson(parent.contextJson)
            check(source.officeStage == OfficeStage.WORKFLOW && source.text == text) { "parent_envelope" }
            if (parent.status != "completed") {
                report("parentStatus=" + parent.status + " completedWorkers=" + observedChildren)
                error("office_parent_incomplete")
            }

            val originals = linkedMapOf<Pair<ZipHapRoom, OfficeStage>, String>()
            val requestTexts = linkedMapOf<Pair<ZipHapRoom, OfficeStage>, String>()
            var cloudRoomHistoryVerified = 0
            for ((key, id) in ids) {
                val (room, stage) = key
                val run = checkNotNull(chat.run(id)) { "missing_worker_run" }
                val target = rooms.getValue(room)
                check(run.status == "completed" && run.threadId == target.id &&
                    run.branchId == target.activeBranchId && run.threadRevision == target.revision) { "worker_scope_or_status" }
                val worker = QueuedChatRequest.fromJson(run.contextJson)
                check(worker.officeParentId == parentId && worker.officeStage == stage &&
                    worker.userMessageId == run.userMessageId && worker.modelId == source.modelId &&
                    worker.externalAgentRevision == source.externalAgentRevision) { "worker_envelope" }
                val storedRequest = checkNotNull(app.repository.getMessage(worker.userMessageId)) { "missing_worker_instruction" }
                check(storedRequest.threadId == target.id && storedRequest.branchId == target.activeBranchId &&
                    storedRequest.text == worker.text) { "worker_instruction_scope" }
                val answer = checkNotNull(chat.assistantForRun(id)) { "missing_worker_answer" }
                check(answer.threadId == target.id && answer.branchId == target.activeBranchId && answer.runId == id) {
                    "worker_answer_scope"
                }
                val publication = checkNotNull(app.repository.getMessage(officePublicationId(id))) { "missing_office_publication" }
                check(publication.threadId == office.id && publication.branchId == office.activeBranchId &&
                    publication.originMessageId == answer.id && officeAuthor(publication.role) == room.agent &&
                    publication.text.isNotBlank() && publication.text == chat.fullMessageText(answer.id).trim()) {
                    "office_publication_provenance"
                }
                originals[key] = publication.text
                requestTexts[key] = worker.text
                if (stage == OfficeStage.REVIEW) {
                    val payload = Json.parseToJsonElement(run.contextJson).jsonObject
                    if ("cloudConversationContext" in payload) {
                        val context = payload["cloudConversationContext"] as? JsonObject
                        val turns = checkNotNull(context?.getValue("recentTurns")?.jsonArray) { "missing_room_history" }
                        val ownProposal = ids.getValue(room to OfficeStage.PROPOSAL)
                        val sources = turns.map { it.jsonObject.getValue("sourceRunId").jsonPrimitive.content }.toSet()
                        check(ownProposal in sources) { "missing_own_room_proposal" }
                        check(officeEmployees.filter { it != room }.none {
                            ids.getValue(it to OfficeStage.PROPOSAL) in sources
                        }) { "peer_history_crossed_room_boundary" }
                        cloudRoomHistoryVerified++
                    }
                }
            }

            for (room in officeEmployees) {
                val task = Json.parseToJsonElement(requestTexts.getValue(room to OfficeStage.REVIEW)).jsonObject
                val replies = task.getValue("colleagueReplies").jsonObject
                check(replies.keys == officeEmployees.filter { it != room }.map { checkNotNull(it.agent).label }.toSet()) {
                    "review_peer_identities"
                }
                for (peer in officeEmployees.filter { it != room }) {
                    val label = checkNotNull(peer.agent).label
                    check(replies.getValue(label).jsonPrimitive.content ==
                        originals.getValue(peer to OfficeStage.PROPOSAL).take(1800)) { "review_peer_evidence" }
                }
            }
            val summaryTask = Json.parseToJsonElement(requestTexts.getValue(ZipHapRoom.MAIN to OfficeStage.SUMMARY)).jsonObject
            val summaryMaterial = summaryTask.getValue("colleagueReplies").jsonObject
            check(summaryMaterial.keys == officeEmployees.map { checkNotNull(it.agent).label }.toSet()) {
                "summary_employee_identities"
            }
            for (room in officeEmployees) {
                val report = summaryMaterial.getValue(checkNotNull(room.agent).label).jsonPrimitive.content
                check(report.contains(originals.getValue(room to OfficeStage.PROPOSAL).take(850)) &&
                    report.contains(originals.getValue(room to OfficeStage.REVIEW).take(850))) { "summary_missing_staff_evidence" }
            }
            val summary = originals.getValue(ZipHapRoom.MAIN to OfficeStage.SUMMARY)
            check(Regex("(?<![0-9])14[, ]?928(?![0-9])").containsMatchIn(summary)) { "summary_arithmetic" }
            report("office=completed proposals=3 reviews=3 summaries=1 seats=${OfficeSeat.entries.size} " +
                "provenance=verified cloudRoomHistory=$cloudRoomHistoryVerified")
        } finally {
            if (submitted) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                if (chat.run(parentId)?.status in setOf("running", "waiting_approval")) {
                    app.runs.stopRun(parentId, office.id)
                }
            }
            runCatching { screen.close() }.onFailure { report("screen_teardown_unconfirmed") }
        }
    }

    private fun report(text: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "$text\n")
        })
    }
}
