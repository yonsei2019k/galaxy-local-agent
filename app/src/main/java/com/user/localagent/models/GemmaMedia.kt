package com.user.localagent.models

/** Projectors are verified model assets, never selectable text-generation models. */
object GemmaMediaCatalog {
    val modelIds = setOf("gemma-e2b", "gemma-e4b")
    fun auxiliary(id:String) = id.endsWith("-mmproj") || id == "gemma-e2b-media" || id.startsWith("asr-")
}

data class MediaSelection(val audio:String="gemma-e2b",val vision:String="gemma-e2b")
