package com.user.localagent.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalWaitTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(2026, 11, 24, 9)
    private val media = WaitCondition.MediaEnd("https://lms.example.com/v/1", "video#player", "item-1")
    private fun record(condition: WaitCondition = media, wakesToday: Int = 0, wakesTotal: Int = 0, pcRetries: Int = 0, deadline: Long = ms(2026, 12, 8, 9), id: String = "wait-1") =
        GoalWaitRecord(id, "goal-1", "thread-1", "branch-1", "run-1", 7L, condition, now - 1000, now - 1000, deadline,
            wakesToday, wakesTotal, "2026-11-24", pcRetries, anchorMinuteOfDay = 540)

    @Test fun pcOfflineRetriesDoNotCountAsWakes() {
        var r = record(wakesToday = 3, wakesTotal = 5)
        repeat(GoalWaitPolicy.PC_RETRY_LIMIT - 1) { i ->
            val d = GoalWaitPolicy.onWake(r, WakeEvent.MEDIA_ENDED, now, zone, false)
            assertTrue(d is WaitDecision.RetryLater)
            d as WaitDecision.RetryLater
            assertEquals(now + GoalWaitPolicy.PC_RETRY_INTERVAL_MS, d.atEpochMillis)
            assertEquals(3, d.record.wakesToday)
            assertEquals(5, d.record.wakesTotal)
            assertEquals(i + 1, d.record.pcRetries)
            r = d.record
        }
        val last = GoalWaitPolicy.onWake(r, WakeEvent.MEDIA_ENDED, now, zone, false)
        assertTrue(last is WaitDecision.RetryLater)
        last as WaitDecision.RetryLater
        assertEquals(now + 3_600_000L, last.atEpochMillis)
        assertEquals(media, last.record.condition)
        assertEquals(3, last.record.wakesToday)
    }

    @Test fun dailyThrottlePreservesObservedConditionAndLifetimeCounterCannotStopAnUndatedGoal() {
        val delayed = GoalWaitPolicy.onWake(record(wakesToday = 12), WakeEvent.MEDIA_ENDED, now, zone, true) as WaitDecision.RetryLater
        assertEquals(ms(2026, 11, 25, 9), delayed.atEpochMillis)
        assertEquals(media, delayed.record.condition)
        val old = record(wakesTotal = 120, deadline = GoalWaitPolicy.deadline(null, null))
        val next = GoalWaitPolicy.onWake(old, WakeEvent.TIME_REACHED, now + 40L * 86_400_000L, zone, true) as WaitDecision.Resume
        assertEquals(121, next.record.wakesTotal)
        assertEquals(WaitDecision.Stop("wait_deadline"), GoalWaitPolicy.onWake(record(deadline = now), WakeEvent.MEDIA_ENDED, now, zone, true))
    }

    @Test fun undatedAuthSchedulesNoTimerUntilTheOwnerConfirms() {
        val auth = record(WaitCondition.OwnerAuth("handoff-1"), deadline = Long.MAX_VALUE)
        assertNull(GoalWaitPolicy.dueAt(auth))
        val completed=auth.copy(observedEvent = WakeEvent.OWNER_AUTH_DONE)
        assertEquals(0L, GoalWaitPolicy.dueAt(completed))
        val decided = GoalWaitPolicy.onWake(completed, WakeEvent.OWNER_AUTH_DONE, now+40L*86_400_000L, zone, true) as WaitDecision.Resume
        assertEquals(0, decided.record.wakesTotal)
        assertNull(GoalWaitPolicy.dueAt(decided.record))
    }

    @Test fun offlineRetryCannotReplaceAuthenticationOrOutliveTheSiteDeadline() {
        val auth=record(WaitCondition.OwnerAuth("bound"),deadline=now+1000)
        val wait=GoalWaitPolicy.onWake(auth,WakeEvent.OWNER_AUTH_DONE,now,zone,false) as WaitDecision.RetryLater
        assertEquals(auth.condition,wait.record.condition)
        assertEquals(auth.deadlineAt,wait.atEpochMillis)
        assertEquals(WaitDecision.Stop("wait_deadline"),GoalWaitPolicy.onWake(auth,WakeEvent.OWNER_AUTH_DONE,auth.deadlineAt,zone,false))
    }

    @Test fun restoredPastDueResumesOnce() {
        val first = GoalWaitPolicy.onWake(record(), WakeEvent.RESTORED_PAST_DUE, now, zone, true)
        assertTrue(first is WaitDecision.Resume)
        first as WaitDecision.Resume
        assertEquals(1, first.record.wakesToday)
        assertEquals(1, first.record.wakesTotal)
        assertEquals("wait-1", first.record.resumedOnceFor)
        assertEquals(WaitDecision.Stop("wait_duplicate_wake"), GoalWaitPolicy.onWake(first.record, WakeEvent.RESTORED_PAST_DUE, now, zone, true))
    }

    @Test fun dayRollResetsWakesToday() {
        val d = GoalWaitPolicy.onWake(record(wakesToday = 12), WakeEvent.MEDIA_ENDED, now + 24 * 3_600_000L, zone, true)
        assertTrue(d is WaitDecision.Resume)
        d as WaitDecision.Resume
        assertEquals(1, d.record.wakesToday)
        assertEquals("2026-11-25", d.record.dayKey)
    }

    @Test fun repeatFailureStops() {
        val first = GoalWaitPolicy.onResumeFailedImmediately(record(), "k1")
        assertTrue(first is WaitDecision.Rearm)
        first as WaitDecision.Rearm
        assertEquals(WaitDecision.Stop("wait_repeat_failure"), GoalWaitPolicy.onResumeFailedImmediately(first.record, "k1"))
    }

    @Test fun newUndatedGoalsHaveNoExpiryAndADeclaredDeadlineCanOnlyTighten() {
        assertEquals(Long.MAX_VALUE, GoalWaitPolicy.deadline(null, null))
        assertEquals(5000L, GoalWaitPolicy.deadline(null, 5000))
        assertEquals(3000L, GoalWaitPolicy.deadline(5000, 3000))
        assertEquals(3000L, GoalWaitPolicy.deadline(3000, 5000))
    }

    @Test fun activePlayerUsesItsRemainingTimeAndPeriodicStallChecks() {
        assertEquals(GoalWaitPolicy.MEDIA_STALL_MS/2, GoalWaitPolicy.mediaPollDelay(1_000, 2_000_000, 1.0, true))
        assertEquals(10_000L, GoalWaitPolicy.mediaPollDelay(20_000, 40_000, 2.0, true))
        assertEquals(GoalWaitPolicy.MEDIA_POLL_MS, GoalWaitPolicy.mediaPollDelay(1_000, null, 1.0, true))
        assertEquals(5_000L, GoalWaitPolicy.mediaPollDelay(1_000, 1_001, 1.0, true))
    }

    @Test fun ledgerAndToolDeadlinesAreComparableAtTheSameInstant() {
        assertEquals(ms(2026, 11, 25, 0), GoalWaitPolicy.ledgerDeadline("2026-11-24",zone))
        assertEquals(ms(2026, 11, 24, 9), GoalWaitPolicy.ledgerDeadline("2026-11-24T00:00:00Z",zone))
        val timed=call(buildJsonObject { put("kind","at_time"); put("atEpochMillis",now); put("needsPlayback",false) })
        val bounded=timed.copy(arguments=JsonObject(timed.arguments+("deadlineEpochMillis" to JsonPrimitive(now.toString()))))
        ToolRegistry.validate(bounded)
        assertEquals(now,GoalWaitToolCatalog.deadline(bounded))
    }

    private fun call(condition: JsonElement) = ToolCall("c", GoalWaitToolCatalog.WAIT, buildJsonObject {
        put("goalId", "g1"); put("expectedUpdatedAt", "123"); put("condition", condition)
    })

    @Test fun parseAcceptsKnownKindsAndRejectsUnknown() {
        val m = buildJsonObject { put("kind", "media_end"); put("pageUrl", "https://lms.example.com/v/1"); put("playerSignature", "video"); put("ledgerItemId", "i1") }
        assertEquals(media.copy(playerSignature = "video", ledgerItemId = "i1"), GoalWaitToolCatalog.parse(call(m)))
        val t = buildJsonObject { put("kind", "at_time"); put("atEpochMillis", 5L); put("needsPlayback", true) }
        assertEquals(WaitCondition.AtTime(5L, true), GoalWaitToolCatalog.parse(call(t)))
        val o = buildJsonObject { put("kind", "owner_auth"); put("handoffId", "h1") }
        assertEquals(WaitCondition.OwnerAuth("h1"), GoalWaitToolCatalog.parse(call(o)))
    }

    @Test fun parseRejectsUnknownKind() {
        val bad = listOf(
            buildJsonObject { put("kind", "sleep"); put("handoffId", "h") },
            buildJsonObject { put("kind", "owner_auth"); put("handoffId", "h"); put("extra", 1) },
            buildJsonObject { put("kind", "at_time"); put("atEpochMillis", 0L); put("needsPlayback", false) },
            buildJsonObject { put("kind", "media_end"); put("pageUrl", "http://lms.example.com/v"); put("playerSignature", "v"); put("ledgerItemId", "i") },
            buildJsonObject { put("kind", "media_end"); put("pageUrl", "https://localhost/v"); put("playerSignature", "v"); put("ledgerItemId", "i") },
            buildJsonObject { put("kind", "owner_auth"); put("handoffId", "") },
        )
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { GoalWaitToolCatalog.parse(call(it)) } }
        assertThrows(IllegalArgumentException::class.java) { GoalWaitToolCatalog.parse(call(JsonPrimitive("media_end"))) }
    }
}
