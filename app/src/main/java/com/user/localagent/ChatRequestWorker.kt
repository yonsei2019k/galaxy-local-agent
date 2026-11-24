package com.user.localagent

import com.user.localagent.inference.ComputeExecutionEngine

/** Execution data only. It never grants tool, file or account authority. */
internal data class ChatRequestWorkerBinding(
    val parent: ChatRunCoordinator,
    val request: QueuedChatRequest,
    val engine: ComputeExecutionEngine,
)

data class ActiveChatExecution(
    val runId: String,
    val threadId: String,
    val projectId: String?,
    val computeLocation: String?,
    val waitingForResource: String?,
    val status: String,
    val waitingForApproval: Boolean,
    val userMessageId: String? = null,
)

data class AuxiliaryChatExecution(val operationId:String,val threadId:String)

