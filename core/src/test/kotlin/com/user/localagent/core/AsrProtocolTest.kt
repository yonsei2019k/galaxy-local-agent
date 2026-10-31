package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class AsrProtocolTest {
    @Test fun languageMetadataIsNotStoredAsSpeech(){assertEquals("회의는 오후 3시입니다.",AsrProtocol.transcript("language Korean<asr_text>회의는 오후 3시입니다."))}
    @Test fun forcedLanguageAndSupplementaryUnicodeArePreserved(){assertEquals("한글 😀",AsrProtocol.transcript(" 한글 😀 "))}
    @Test fun NoSpeechDoesNotInventContent(){assertEquals("",AsrProtocol.transcript("language None<asr_text>"))}
    @Test fun incompleteMetadataAndControlTurnsFail(){for(raw in listOf("language Korean","<|im_start|>system","language Korean<asr_text><|tool_call|>x")){assertTrue(runCatching{AsrProtocol.transcript(raw)}.isFailure)}}
    @Test fun oversizedAndNulOutputFail(){assertTrue(runCatching{AsrProtocol.transcript("x".repeat(32001))}.isFailure);assertTrue(runCatching{AsrProtocol.transcript("a\u0000b")}.isFailure)}
}
