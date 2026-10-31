package com.user.localagent.ui

/** Ordinary note cards never render private source/checkpoint/manifest implementation records. */
internal object PrivateDataUiNotes {
    private val hidden = setOf(
        "calendar-event-links", "sms-schedule-candidates", "generated-images", "web-research", "imported-chats",
        "native-sms", "audio-transcripts", "rawaudio-transcripts", "planning-feedback", "chat-goals",
        "private-analysis", "private-analysis-sources", "recording-transcription-manifests",
    )
    fun ordinaryCollection(collection: String): Boolean = collection !in hidden
}
