package com.user.localagent.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a parked persistent goal is waiting for. Stored with the wait record; the host wakes it later. */
@Serializable
sealed interface WaitCondition {
    @Serializable @SerialName("media_end")
    data class MediaEnd(val pageUrl: String, val playerSignature: String, val ledgerItemId: String) : WaitCondition
    @Serializable @SerialName("at_time")
    data class AtTime(val atEpochMillis: Long, val needsPlayback: Boolean) : WaitCondition
    @Serializable @SerialName("owner_auth")
    data class OwnerAuth(val handoffId: String) : WaitCondition
}

@Serializable
data class GoalWaitRecord(
    val id: String,
    val goalId: String,
    val threadId: String,
    val branchId: String,
    val parentRunId: String,
    val authorityRevision: Long?,
    val condition: WaitCondition,
    val createdAt: Long,
    val startedAt: Long,
    val deadlineAt: Long,
    val wakesToday: Int = 0,
    val wakesTotal: Int = 0,
    val dayKey: String,
    val pcRetries: Int = 0,
    val resumedOnceFor: String? = null,
    /** Local minute-of-day of the original timed wake, used for "next day, same time". */
    val anchorMinuteOfDay: Int? = null,
    val lastFailureKey: String? = null,
    val observedEvent: WakeEvent? = null,
    val ordinaryBrowserAuth: Boolean = false,
) {
    init {
        require(id.isNotBlank() && goalId.isNotBlank()) { "대기 기록에는 식별자가 필요합니다." }
        require(wakesToday >= 0 && wakesTotal >= 0 && pcRetries >= 0) { "대기 횟수는 0 이상이어야 합니다." }
        require(anchorMinuteOfDay == null || anchorMinuteOfDay in 0..1439) { "기준 시각이 하루 범위를 벗어났습니다." }
    }
}

@Serializable
enum class WakeEvent {
    MEDIA_ENDED, MEDIA_STALLED, PLAYER_GONE, LOGIN_EXPIRED, MEDIA_LIMIT, CONDITIONS_UNMET,
    TIME_REACHED, OWNER_AUTH_DONE, OWNER_AUTH_TIMEOUT, RESTORED_PAST_DUE
}

sealed interface WaitDecision {
    data class Resume(val record: GoalWaitRecord) : WaitDecision
    data class RetryLater(val atEpochMillis: Long, val record: GoalWaitRecord) : WaitDecision
    data class Rearm(val condition: WaitCondition, val record: GoalWaitRecord) : WaitDecision
    data class Stop(val code: String) : WaitDecision
}

/** Pure wake decisions. Time always comes from the caller; records change only through returned copies. */
object GoalWaitPolicy {
    const val MAX_DAYS = 14
    const val MAX_WAKES_PER_DAY = 12
    const val MAX_WAKES_TOTAL = 120
    const val PC_RETRY_INTERVAL_MS = 15 * 60_000L
    const val PC_RETRY_LIMIT = 8
    const val MEDIA_POLL_MS = 30_000L
    const val MEDIA_STALL_MS = 10 * 60_000L
    const val MEDIA_MAX_MS = 6 * 3_600_000L
    const val AUTH_CUTOFF_HOUR = 21
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    fun onWake(record: GoalWaitRecord, event: WakeEvent, now: Long, zone: ZoneId, pcAvailable: Boolean): WaitDecision {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val rolled = if (record.dayKey != today) record.copy(wakesToday = 0, dayKey = today) else record
        if (now > rolled.deadlineAt) return WaitDecision.Stop("wait_deadline")
        if (rolled.resumedOnceFor == rolled.id) return WaitDecision.Stop("wait_duplicate_wake")
        if (rolled.wakesTotal >= MAX_WAKES_TOTAL) return WaitDecision.Stop("wait_total_limit")
        if (!pcAvailable) {
            if (rolled.pcRetries < PC_RETRY_LIMIT - 1) {
                return WaitDecision.RetryLater(now + PC_RETRY_INTERVAL_MS, rolled.copy(pcRetries = rolled.pcRetries + 1))
            }
            return rearmSameKind(rolled, now, zone)
        }
        if (event == WakeEvent.OWNER_AUTH_TIMEOUT) {
            return rearm(rolled, WaitCondition.AtTime(nextDayAtAnchor(rolled, now, zone), false))
        }
        val counted = event != WakeEvent.OWNER_AUTH_DONE
        if (counted && rolled.wakesToday >= MAX_WAKES_PER_DAY) {
            val playback = rolled.condition is WaitCondition.MediaEnd
            return rearm(rolled, WaitCondition.AtTime(nextDayAtAnchor(rolled, now, zone), playback))
        }
        val bump = if (counted) 1 else 0
        return WaitDecision.Resume(rolled.copy(wakesToday = rolled.wakesToday + bump, wakesTotal = rolled.wakesTotal + bump,
            pcRetries = 0, resumedOnceFor = rolled.id))
    }

    fun onResumeFailedImmediately(record: GoalWaitRecord, conditionKey: String): WaitDecision =
        if (record.lastFailureKey == conditionKey) WaitDecision.Stop("wait_repeat_failure")
        else WaitDecision.Rearm(record.condition, record.copy(lastFailureKey = conditionKey))

    fun deadline(startedAt: Long, siteDeadline: Long?): Long =
        minOf(startedAt + MAX_DAYS * DAY_MS, siteDeadline ?: Long.MAX_VALUE)

    private fun rearm(record: GoalWaitRecord, condition: WaitCondition) =
        WaitDecision.Rearm(condition, record.copy(condition = condition, pcRetries = 0))

    private fun rearmSameKind(record: GoalWaitRecord, now: Long, zone: ZoneId): WaitDecision {
        val next = when (val c = record.condition) {
            is WaitCondition.AtTime -> c.copy(atEpochMillis = nextDayAtAnchor(record, now, zone))
            is WaitCondition.MediaEnd -> WaitCondition.AtTime(now + HOUR_MS, true)
            is WaitCondition.OwnerAuth -> WaitCondition.AtTime(now + HOUR_MS, false)
        }
        return rearm(record, next)
    }

    private fun nextDayAtAnchor(record: GoalWaitRecord, now: Long, zone: ZoneId): Long {
        val minute = record.anchorMinuteOfDay
            ?: Instant.ofEpochMilli(record.createdAt).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        return date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
    }
}
