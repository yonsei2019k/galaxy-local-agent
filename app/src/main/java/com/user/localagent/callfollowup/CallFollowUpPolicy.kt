package com.user.localagent.callfollowup

/** Host metadata only. This never parses a filename/transcript to create authority or identity. */
internal object CallFollowUpPolicy {
    const val STABLE_MILLIS = 30_000L
    fun phoneState(config: CallFollowUpConfig, state: String, now: Long): CallFollowUpConfig {
        require(now > 0)
        if (!config.enabled) return config
        return when (state) {
            "OFFHOOK" -> if (config.phoneOffHook) config else config.copy(phoneOffHook = true, callStartedAtMillis = now)
            "IDLE" -> if (!config.phoneOffHook) config else config.copy(phoneOffHook = false,
                lastCallStartedAtMillis = config.callStartedAtMillis, lastCallEndedAtMillis = now,
                status = "call_ended_waiting_for_recording")
            else -> config
        }
    }
    fun eligible(observation: CallRecordingObservation, enabledAt: Long): Boolean =
        observation.addedAtMillis >= enabledAt && observation.modifiedAtMillis >= enabledAt &&
            observation.byteCount in 1..MAX_RECORDING_BYTES

    fun stable(observation: CallRecordingObservation, stableSince: Long, now: Long): Boolean =
        !observation.pending && now - stableSince >= STABLE_MILLIS && now - observation.modifiedAtMillis >= STABLE_MILLIS

    /** A retry must not replace the immutable snapshot already held by a durable queued operation. */
    fun reuseSameSnapshot(previous: StableCallRecording?, checked: StableCallRecording): StableCallRecording =
        previous?.takeIf { it.copy(capturedAtMillis = checked.capturedAtMillis) == checked } ?: checked

    /** A time-near signal is merely a correlation, never participant/call identity proof. */
    fun matchingEndedSignal(config: CallFollowUpConfig, observation: CallRecordingObservation, durationMillis: Long? = null): Long? =
        config.lastCallEndedAtMillis.takeIf { end ->
            durationMillis != null && durationMillis > 0 &&
                config.lastCallStartedAtMillis >= config.enabledAtMillis && end >= config.lastCallStartedAtMillis &&
                kotlin.math.abs(end - config.lastCallStartedAtMillis - durationMillis) <= 5000 &&
                observation.addedAtMillis >= config.lastCallStartedAtMillis - 2000 &&
                observation.modifiedAtMillis >= end - 5000 && observation.modifiedAtMillis <= end + 120_000
        }
}
