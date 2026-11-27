package com.user.localagent.core

import kotlinx.serialization.json.JsonPrimitive

object PdfMailAttachments {
    const val FIELD = "pdfFiles"
    private val name = Regex("artifact-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\\.pdf")

    fun requested(call: ToolCall): List<String>? {
        val value = call.arguments[FIELD] ?: return null
        require(call.name == "prepare_email" && value is JsonPrimitive && value.isString)
        val names = value.content.split(',').map(String::trim)
        require(names.size in 1..16 && names.toSet().size == names.size && names.all(name::matches)) {
            "이번 작업에서 저장한 PDF 파일 이름을 중복 없이 선택해 주세요."
        }
        com.user.localagent.core.connections.GmailSendContract.mailbox((call.arguments.getValue("to") as JsonPrimitive).content)
        require((call.arguments.getValue("subject") as JsonPrimitive).content.none(Char::isISOControl))
        return names
    }
}
