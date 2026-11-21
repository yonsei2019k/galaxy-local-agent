package com.user.localagent.models

/** Projectors are verified model assets, never selectable text-generation models. */
object GemmaMediaCatalog {
    val modelIds = setOf("gemma-e2b", "gemma-e4b")
    fun imageOnly(id: String, sha256: String = ""): Boolean = id == "gemma-e4b" ||
        sha256.equals(com.user.localagent.core.GemmaImagePreparation.hashes.getValue("gemma-e4b"), ignoreCase = true)

    fun supportsText(id: String, sha256: String = ""): Boolean = !imageOnly(id, sha256) && !auxiliary(id)

    fun auxiliary(id:String) = id.endsWith("-mmproj") || id == "gemma-e2b-media" || id.startsWith("asr-")
}

data class MediaSelection(val audio:String="gemma-e2b",val vision:String="gemma-e2b")
