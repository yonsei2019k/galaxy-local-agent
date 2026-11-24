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
        assertTrue(last is WaitDecision.Rearm)
        last as WaitDecision.Rearm
        assertEquals(0, last.record.pcRetries)
        assertEquals(3, last.record.wakesToday)
        assertEquals(WaitCondition.AtTime(now + 3_600_000L, true), last.condition)
    }

    @Test fun dailyAndTotalLimits() {
        val daily = GoalWaitPolicy.onWake(record(wakesToday = 12), WakeEvent.TIME_REACHED, now, zone, true)
        assertEquals(WaitDecision.Rearm::class, daily::class)
        daily as WaitDecision.Rearm
        assertEquals(WaitCondition.AtTime(ms(2026, 11, 25, 9), true), daily.condition)
        assertEquals(WaitDecision.Stop("wait_total_limit"), GoalWaitPolicy.onWake(record(wakesTotal = 120), WakeEvent.MEDIA_ENDED, now, zone, true))
        assertEquals(WaitDecision.Stop("wait_deadline"), GoalWaitPolicy.onWake(record(deadline = now - 1), WakeEvent.MEDIA_ENDED, now, zone, true))
    }

    @Test fun authTimeoutRearmsNextDaySameTime() {
        val auth = record(WaitCondition.OwnerAuth("handoff-1"), wakesToday = 1, wakesTotal = 1)
        val d = GoalWaitPolicy.onWake(auth, WakeEvent.OWNER_AUTH_TIMEOUT, ms(2026, 11, 24, 21, 30), zone, true)
        assertTrue(d is WaitDecision.Rearm)
        d as WaitDecision.Rearm
        assertEquals(WaitCondition.AtTime(ms(2026, 11, 25, 9), false), d.condition)
        assertEquals(1, d.record.wakesToday)
        val done = GoalWaitPolicy.onWake(auth, WakeEvent.OWNER_AUTH_DONE, now, zone, true)
        assertTrue(done is WaitDecision.Resume)
        assertEquals(1, (done as WaitDecision.Resume).record.wakesToday)
        assertEquals(1, done.record.wakesTotal)
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

    @Test fun deadlineIsEarlierOf14DaysAndSiteDeadline() {
        val day = 24 * 3_600_000L
        assertEquals(1000 + 14 * day, GoalWaitPolicy.deadline(1000, null))
        assertEquals(5000L, GoalWaitPolicy.deadline(1000, 5000))
        assertEquals(1000 + 14 * day, GoalWaitPolicy.deadline(1000, 1000 + 30 * day))
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
