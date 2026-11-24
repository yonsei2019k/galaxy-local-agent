package com.user.localagent.ui

import com.user.localagent.externalagent.CloudAgentFinals

/** Speak short visible prose, avoiding attachments, navigation URLs and code. */
internal object ChatSpeechText {
    private val code=Regex("(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)")
    private val file=Regex("""\[\[(?:file|artifact):[^\]]*]]""")
    private val image=Regex("""!\[[^\]]*]\([^\n]*?\)""")
    private val link=Regex("""\[([^\]\n]+)]\((?:https?://|mailto:)[^\n]*?\)""")
    private val url=Regex("""https?://\S+""")
    private val layout=Regex("""(?m)^\s{0,3}(?:#{1,6}\s+|[-*+]\s+|>\s*)|[*`_]+""")
    private val whitespace=Regex("""\s+""")

    fun prepare(answer:String,maxChars:Int):String {
        var text=CloudAgentFinals.visibleAnswer(answer)
        text=code.replace(text," ")
        text=file.replace(text," ")
        text=image.replace(text," ")
        text=link.replace(text){it.groupValues[1]}
        text=url.replace(text," ")
        text=layout.replace(text," ")
        val clean=whitespace.replace(text," ").trim()
        if(clean.length<=maxChars)return clean
        val suffix=" 나머지는 채팅에서 볼 수 있어요."
        val limit=maxChars-suffix.length
        val end=clean.lastIndexOf(' ',limit).takeIf {it>=limit/2}?:limit
        return clean.take(if(clean[end-1].isHighSurrogate())end-1 else end).trimEnd()+suffix
    }
}
