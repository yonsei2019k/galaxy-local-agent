package com.user.localagent.core

import kotlinx.serialization.json.JsonPrimitive

/** Transcription reads only a recording returned by the actual current request's MediaStore listing. */
object RecordingToolCatalog {
    val definitions = listOf(ToolDefinition(
        "transcribe_recording", Risk.LOW,
        mapOf("recordingId" to Argument("string", true, 36)),
        "Transcribe one recordingId returned by list_audio_recordings in this same request. The host resolves the opaque ID; URI/path arguments are not accepted. Uses the selected already-installed phone ASR model, creates verified private transcript chunks and a manifest for analyze_audio_transcript. Max100MiB/2hours; long recordings can take a long time. No cloud upload, new model download, word timestamps, diarization or speaker identity. Listing metadata alone is not transcription.",
        12L * 60 * 60 * 1000,
    ))
    val names = definitions.map { it.name }.toSet()
    fun validate(call: ToolCall) {
        require(call.name == "transcribe_recording" && call.arguments.keys == setOf("recordingId")) {
            "현재 목록에서 받은 녹음 ID만 사용할 수 있습니다."
        }
        val value = call.arguments["recordingId"]
        require(value is JsonPrimitive && value.isString &&
            value.content.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) {
            "현재 목록에서 받은 녹음 ID가 필요합니다."
        }
    }
}
