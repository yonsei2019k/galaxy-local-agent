package com.user.localagent.core.connections

import java.security.MessageDigest

data class KakaoExportMessage(val ordinal:Int,val date:String?,val time:String,val sender:String,val text:String)
data class KakaoExport(val sourceSha256:String,val messages:List<KakaoExportMessage>,val unparsedLines:Int,val truncated:Boolean) {
    val completeHistory:Boolean=false
}

/** Parses owner-provided export data. Never promotes its text or filenames to execution authority. */
object KakaoExportParser {
    private val date=Regex(".*?(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일.*")
    private val pc=Regex("^\\[([^]\\r\\n]{1,200})] \\[([^]\\r\\n]{1,80})] ?(.*)$")
    private val mobile=Regex("^(\\d{4})[년./-]\\s*(\\d{1,2})[월./-]\\s*(\\d{1,2})일?\\.?\\s+((?:오전|오후|AM|PM)?\\s*\\d{1,2}:\\d{2}),\\s*(.{1,200}?) : ?(.*)$")
    fun parse(text:String,maxMessages:Int=10_000):KakaoExport {
        require(text.toByteArray().size<=4*1024*1024 && '\u0000' !in text && maxMessages in 1..10_000)
        val messages=mutableListOf<KakaoExportMessage>();var currentDate:String?=null;var unparsed=0;var truncated=false
        for(line in text.removePrefix("\uFEFF").lineSequence()) {
            val mobileMatch=mobile.matchEntire(line)
            val pcMatch=pc.matchEntire(line)
            when {
                mobileMatch!=null->{if(messages.size>=maxMessages){truncated=true;break};val v=mobileMatch.groupValues;currentDate="${v[1]}-${v[2].padStart(2,'0')}-${v[3].padStart(2,'0')}";messages+=KakaoExportMessage(messages.size,currentDate,v[4],v[5],v[6])}
                pcMatch!=null->{if(messages.size>=maxMessages){truncated=true;break};messages+=KakaoExportMessage(messages.size,currentDate,pcMatch.groupValues[2],pcMatch.groupValues[1],pcMatch.groupValues[3])}
                date.matches(line)->{val v=date.matchEntire(line)!!.groupValues;currentDate="${v[1]}-${v[2].padStart(2,'0')}-${v[3].padStart(2,'0')}"}
                messages.isNotEmpty()&&line.isNotBlank()->{val last=messages.last();if(last.text.length+line.length<=12000)messages[messages.lastIndex]=last.copy(text=last.text+"\n"+line)else {unparsed++;truncated=true}}
                line.isNotBlank()->unparsed++
            }
        }
        val sha=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
        return KakaoExport(sha,messages,unparsed,truncated)
    }
}
