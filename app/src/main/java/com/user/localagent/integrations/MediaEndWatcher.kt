package com.user.localagent.integrations

import com.user.localagent.core.BrowserMediaProgress
import com.user.localagent.core.BrowserMediaSample
import com.user.localagent.core.BrowserMediaStatus
import com.user.localagent.core.GoalWaitPolicy
import com.user.localagent.core.WakeEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Read-only monitoring of a single bound player. A changed page/player never completes a lecture. */
internal class MediaEndWatcher(
    private val sample: suspend () -> Observation?,
    private val conditionsMet: () -> Boolean,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
    data class Observation(val pageUrl: String, val player: BrowserMediaSample?, val loginRequired: Boolean = false)

    companion object {
        /** An already-ended player can still be observed if the worker started just after the end. */
        fun endedDuringHandoff(sample:BrowserMediaSample,earlierPosition:Long?,earlierDuration:Long?,elapsedMs:Long):Boolean {
            val start=earlierPosition ?: return false
            val duration=sample.durationMs ?: return false
            val moved=sample.positionMs-start
            return sample.ended && !sample.seeking && duration==earlierDuration &&
                start in 0 until duration && sample.positionMs in (duration-250)..(duration+250) &&
                elapsedMs in 0..GoalWaitPolicy.MEDIA_MAX_MS && moved in 100..(elapsedMs*sample.rate+2_000).toLong()
        }
    }

    suspend fun watch(pageUrl: String, initial: BrowserMediaSample): WakeEvent {
        val started = clock()
        var lastProgress = started
        val progress = BrowserMediaProgress()
        progress.observe(initial,started)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!conditionsMet()) return WakeEvent.CONDITIONS_UNMET
            val elapsed = clock() - started
            if (elapsed !in 0 until GoalWaitPolicy.MEDIA_MAX_MS) return WakeEvent.MEDIA_LIMIT
            val observed = sample() ?: return WakeEvent.PLAYER_GONE
            if (observed.loginRequired) return WakeEvent.LOGIN_EXPIRED
            if (!conditionsMet()) return WakeEvent.CONDITIONS_UNMET
            val player = observed.player ?: return WakeEvent.PLAYER_GONE
            if (observed.pageUrl != pageUrl || player.playerId != initial.playerId || player.epoch != initial.epoch)
                return WakeEvent.PLAYER_GONE
            val now = clock()
            val reading = progress.observe(player,now)
            if (reading.status == BrowserMediaStatus.ENDED) return WakeEvent.MEDIA_ENDED
            if (reading.advancing == true) lastProgress = now
            if (now - lastProgress >= GoalWaitPolicy.MEDIA_STALL_MS) return WakeEvent.MEDIA_STALLED
            // Reserve room for sampling and dispatcher latency in BrowserMediaProgress's 30 second comparison window.
            sleep(minOf(GoalWaitPolicy.MEDIA_POLL_MS - 2_000,GoalWaitPolicy.MEDIA_MAX_MS - elapsed))
        }
    }
}
