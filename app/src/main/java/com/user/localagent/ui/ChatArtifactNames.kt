package com.user.localagent.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private const val artifactUuid="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
private val fileReferences=Regex("\\[\\[file:(artifact-$artifactUuid\\.(?:docx|xlsx|pdf|txt|csv))]]")
private val imageReferences=Regex("\\[\\[image:(image-$artifactUuid\\.png)]]")
internal fun chatFileNames(text:String):List<String> = fileReferences.findAll(text).map{it.groupValues[1]}.distinct().take(16).toList()
internal fun chatImageNames(text:String):List<String> = imageReferences.findAll(text).map{it.groupValues[1]}.distinct().take(16).toList()

internal fun verifiedChatFileNames(text:String, visible:List<String>, manifest:JsonArray):List<String> {
    val authorized = manifest.mapNotNull { (it as? JsonPrimitive)?.takeIf { field -> field.isString }?.content }.toSet()
    return chatFileNames(text).filter { it in visible && it in authorized }
}
