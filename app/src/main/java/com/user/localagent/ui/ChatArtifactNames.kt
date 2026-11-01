package com.user.localagent.ui

private const val artifactUuid="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
private val fileReferences=Regex("\\[\\[file:(artifact-$artifactUuid\\.(?:docx|xlsx|pdf|txt|csv))]]")
private val imageReferences=Regex("\\[\\[image:(image-$artifactUuid\\.png)]]")
internal fun chatFileNames(text:String):List<String> = fileReferences.findAll(text).map{it.groupValues[1]}.distinct().take(16).toList()
internal fun chatImageNames(text:String):List<String> = imageReferences.findAll(text).map{it.groupValues[1]}.distinct().take(16).toList()
