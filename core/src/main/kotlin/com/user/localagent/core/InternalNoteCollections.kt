package com.user.localagent.core

/** Host-generated provenance and state cannot be fabricated through generic model note tools. */
object InternalNoteCollections {
    val protected=setOf("chat-goals","imported-chats","native-sms","audio-transcripts","rawaudio-transcripts",
        "recording-transcription-manifests","private-analysis-sources","private-analysis",
        "calendar-event-links","sms-schedule-candidates","generated-images","web-research","planning-feedback","codex-briefings")
}
