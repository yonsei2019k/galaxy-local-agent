package com.user.localagent.core

/** Input capture only. This result grants no tool, account, source or Android permission. */
data class ConversationSubmission(
    val rawText: String,
    val requestText: String,
    val mode: AgentWorkMode,
    val explicitModeCommand: String?,
) {
    /** No task payload: the host may show guidance or handle the explicit chat control. */
    val commandOnly: Boolean get() = explicitModeCommand != null && requestText.isBlank()

    override fun toString(): String = "ConversationSubmission(mode=${mode.wireName}, explicitModeCommand=$explicitModeCommand, commandOnly=$commandOnly, rawTextLength=${rawText.length}, requestTextLength=${requestText.length})"
}

/**
 * New conversation requests use the ordinary action loop independently of old composer preferences.
 * Only an explicitly registered slash command changes this one captured request. The new /goal
 * directive uses the ordinary action loop for chat-native goal management; it is not the old
 * persisted GOAL read-only mode. Natural-language
 * task meaning remains the model's responsibility; this policy never examines task vocabulary.
 * Persisted queued requests and answer continuations must keep their already captured mode and
 * allowTools value rather than passing through this new-submission policy again.
 */
object ConversationSubmissionPolicy {
    private val legacyCommands: Map<String, AgentWorkMode> = linkedMapOf(
        "/act" to AgentWorkMode.ACT,
        "/plan" to AgentWorkMode.PLAN,
        "/goal" to AgentWorkMode.ACT,
        "/debug" to AgentWorkMode.DEBUG,
        "/review" to AgentWorkMode.REVIEW,
        "/research" to AgentWorkMode.RESEARCH,
    )

    fun newRequestMode(): AgentWorkMode = AgentWorkMode.ACT

    fun parse(input: String): ConversationSubmission {
        val commandStart = input.indexOfFirst { !it.isWhitespace() }
        if (commandStart < 0 || input[commandStart] != '/') return ordinary(input)

        var commandEnd = commandStart
        while (commandEnd < input.length && !input[commandEnd].isWhitespace()) commandEnd++
        val command = input.substring(commandStart, commandEnd)
        val mode = legacyCommands[command] ?: return ordinary(input)
        return ConversationSubmission(input, input.substring(commandEnd).trimStart(), mode, command)
    }

    private fun ordinary(input: String) = ConversationSubmission(input, input, newRequestMode(), null)
}
