package com.user.localagent

/**
 * Excerpt budgets count UTF-16 code units, like take(), while keeping Unicode scalar boundaries.
 * A scalar crossing the budget is omitted from the excerpt; the original text is never modified.
 * These functions do not repair malformed source text or promise grapheme-cluster boundaries.
 */
internal fun CharSequence.safeHead(maxChars:Int):String {
    require(maxChars>=0)
    var end=minOf(length,maxChars)
    if(end>0&&end<length&&this[end-1].isHighSurrogate()&&this[end].isLowSurrogate())end--
    return subSequence(0,end).toString()
}

internal fun CharSequence.safeTail(maxChars:Int):String {
    require(maxChars>=0)
    var start=(length-maxChars).coerceAtLeast(0)
    if(start>0&&start<length&&this[start].isLowSurrogate()&&this[start-1].isHighSurrogate())start++
    return subSequence(start,length).toString()
}
