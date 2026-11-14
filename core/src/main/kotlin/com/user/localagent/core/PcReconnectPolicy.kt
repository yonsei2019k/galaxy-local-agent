package com.user.localagent.core

/**
 * Worker restarts (crash recovery or a deploy) briefly fail health checks; the supervisor polls every
 * 10 s and the server starts in a few seconds. Only when the PC answered within the last 10 minutes is
 * a failure treated as a likely restart and retried; a PC that has been off is reported at once.
 */
object PcReconnectPolicy {
    const val RECENT_MILLIS=10*60_000L
    private val restartWaits=listOf(5_000L,5_000L,10_000L)
    fun retryDelays(waitForRestart:Boolean,sinceLastReachableMillis:Long):List<Long> =
        if(waitForRestart&&sinceLastReachableMillis in 0 until RECENT_MILLIS)restartWaits else emptyList()
}
