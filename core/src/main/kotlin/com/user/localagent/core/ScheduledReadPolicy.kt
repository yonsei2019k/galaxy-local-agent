package com.user.localagent.core

import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ScheduledReadScope(
    val threadId: String, val branchId: String, val projectId: String?,
    val threadRevision: Long, val projectRevision: Long,
)

@Serializable
data class ScheduledReadRequest(
    val title: String, val prompt: String, val atEpochMillis: Long, val tool: String, val scope: ScheduledReadScope,
) {
    fun idFor(registrationRunId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(registrationRunId, scope.threadId, scope.branchId, atEpochMillis.toString(), tool, title, prompt).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        return ScheduledReadPolicy.ID_PREFIX + digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun allows(current: ScheduledReadScope?, requestedTool: String, disabled: Set<String>): Boolean =
        current == scope && requestedTool == tool && tool in ScheduledReadPolicy.localReads && tool !in disabled

    fun admission(status: String, now: Long, current: ScheduledReadScope?, disabled: Set<String>): ScheduledReadAdmission = when {
        status != ScheduledReadPolicy.SCHEDULED -> ScheduledReadAdmission.CLOSED
        now < atEpochMillis -> ScheduledReadAdmission.WAIT
        !allows(current, tool, disabled) -> ScheduledReadAdmission.REVOKED
        else -> ScheduledReadAdmission.READY
    }
}

enum class ScheduledReadAdmission { CLOSED, WAIT, REVOKED, READY }
enum class ScheduledReadCancellation { CANCEL_BEFORE_START, REQUEST_STOP, CLOSED }

object ScheduledReadPolicy {
    const val ID_PREFIX = "scheduled-read-"
    const val SCHEDULED = "scheduled"
    const val STOP_REQUESTED = "scheduled_owner_stop"
    const val WORKER_STOPPED = "scheduled_worker_stopped"
    val localReads = setOf("get_current_datetime", "search_notes", "read_note", "list_reminders")

    fun isId(value: String): Boolean = value.length == ID_PREFIX.length + 64 &&
        value.startsWith(ID_PREFIX) && value.drop(ID_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }

    fun cancellation(status: String): ScheduledReadCancellation = when (status) {
        SCHEDULED, "queued" -> ScheduledReadCancellation.CANCEL_BEFORE_START
        "running", "waiting_approval" -> ScheduledReadCancellation.REQUEST_STOP
        else -> ScheduledReadCancellation.CLOSED
    }
}

object ScheduledReadToolCatalog {
    const val CREATE = "schedule_agent_task"
    const val CANCEL = "cancel_scheduled_agent_task"
    val definitions = listOf(
        ToolDefinition(CREATE, Risk.MEDIUM, mapOf(
            "title" to Argument("string", true, 120, "Short label for the scheduled read."),
            "prompt" to Argument("string", true, 2000, "Complete original request to execute once at the scheduled time."),
            "datetime" to Argument("string", true, 80, "Future time as an ISO datetime with offset."),
            "readTool" to Argument("string", true, 64, "One allowed local read: get_current_datetime, search_notes, read_note, list_reminders."),
        ), "After owner approval, schedule a single on-device read in this conversation. Registration does not verify future execution."),
        ToolDefinition(CANCEL, Risk.MEDIUM, mapOf(
            "scheduleId" to Argument("string", true, 80, "Schedule ID from the original conversation's registration receipt."),
        ), "After owner approval, cancel a local read scheduled from this conversation. If started, request stop for its exact run."),
    )

    fun validate(call: ToolCall) {
        fun text(key: String) = call.arguments.getValue(key).jsonPrimitive.content
        when (call.name) {
            CREATE -> {
                require(text("title").isNotBlank() && text("prompt").isNotBlank())
                require(text("title").none { it.isISOControl() } && '\u0000' !in text("prompt"))
                require(text("readTool") in ScheduledReadPolicy.localReads)
                OffsetDateTime.parse(text("datetime"))
            }
            CANCEL -> require(ScheduledReadPolicy.isId(text("scheduleId")))
        }
    }
}
