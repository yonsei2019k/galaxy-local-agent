package com.user.localagent.data

/** An offline title from the first submitted turn. No extra model request or external upload. */
internal fun firstChatTitle(text:String):String? {
    val firstLine=text.lineSequence().firstOrNull{it.isNotBlank()}?.trim()?:return null
    if(firstLine.startsWith('/'))return null
    val clean=firstLine.map{if(it.isISOControl())' ' else it}.joinToString("")
        .replace(Regex("\\s+")," ").trim()
    if(clean.isEmpty())return null
    val count=clean.codePointCount(0,clean.length)
    if(count<=48)return clean
    return clean.substring(0,clean.offsetByCodePoints(0,47)).trimEnd()+"…"
}
