package com.user.localagent.core

import kotlinx.serialization.json.*
import kotlin.math.ceil

data class BrowserMediaRule(val expression:String,val waitSeconds:Int) {
    companion object {
        const val EXPRESSION="playbackRule"
        const val REPEAT_LIMIT=3
        fun isRuleRead(tool:String,arguments:JsonObject):Boolean =
            (tool==AppBrowserToolCatalog.READ||tool==PcBrowserToolCatalog.READ)&&EXPRESSION in arguments
        val arguments=mapOf(
            EXPRESSION to Argument("string",false,200,"Read-only expression over playing, advancing, ended, paused, seeking, buffering and duration_known. Supports !, &&, || and parentheses."),
            "waitSeconds" to Argument("integer",false,3,"Wait up to 1–90 seconds for the media rule. Defaults to 8."),
        )
        fun from(call:ToolCall):BrowserMediaRule? {
            if(call.arguments.isEmpty())return null
            require(call.arguments.keys.all {it in arguments})
            val code=(call.arguments[EXPRESSION] as? JsonPrimitive)?.takeIf {it.isString}?.content
            val seconds=(call.arguments["waitSeconds"] as? JsonPrimitive)?.takeUnless {it.isString}?.intOrNull
                ?:if("waitSeconds" !in call.arguments)8 else null
            require(!code.isNullOrBlank()&&code.length<=200&&code.none(Char::isISOControl)&&seconds!=null&&seconds in 1..90)
            return BrowserMediaRule(code,seconds)
        }
    }
}

data class BrowserMediaSample(val playerId:String,val epoch:Int,val positionMs:Long,val durationMs:Long?,
    val paused:Boolean,val ended:Boolean,val seeking:Boolean,val readyState:Int,val rate:Double) {
    companion object {
        private val identity=Regex("[0-9a-f]{32}:[1-9][0-9]{0,7}")
        fun from(media:JsonObject):BrowserMediaSample? {
            if(media["status"]!=JsonPrimitive("observed"))return null
            fun primitive(key:String)=media[key] as? JsonPrimitive
            fun number(key:String)=primitive(key)?.takeUnless {it.isString}
            fun boolean(key:String)=number(key)?.booleanOrNull
            val player=primitive("playerId")?.takeIf {it.isString}?.content?.takeIf(identity::matches)?:return null
            val epoch=number("epoch")?.intOrNull?.takeIf {it>=0}?:return null
            val position=number("positionMs")?.longOrNull?.takeIf {it in 0..86_400_000}?:return null
            val duration=number("durationMs")?.longOrNull?.takeIf {it in 1..86_400_000}
            val rate=number("rate")?.doubleOrNull?.takeIf {it in 0.05..16.0}?:return null
            val ready=number("readyState")?.intOrNull?.takeIf {it in 0..4}?:return null
            if(duration!=null&&position>duration+250)return null
            return BrowserMediaSample(player,epoch,position,duration,boolean("paused")?:return null,
                boolean("ended")?:return null,boolean("seeking")?:return null,ready,rate)
        }
    }
}

enum class BrowserMediaStatus { CHECKING, PLAYING, PAUSED, BUFFERING, SEEKING, ENDED }

data class BrowserMediaReading(val status:BrowserMediaStatus,val advancing:Boolean?,
    val remainingSeconds:Int?,val sample:BrowserMediaSample)

/** Comparison across actual samples of a single browser media element, independent of audio and pixels. */
class BrowserMediaProgress {
    private data class Timed(val sample:BrowserMediaSample,val at:Long)
    private var last:Timed?=null
    private var sawProgress=false

    fun observe(sample:BrowserMediaSample,now:Long):BrowserMediaReading {
        val previous=last
        last=Timed(sample,now)
        val interval=previous?.let {now-it.at}
        val delta=previous?.let {sample.positionMs-it.sample.positionMs}
        val comparable=previous!=null&&previous.sample.playerId==sample.playerId&&previous.sample.epoch==sample.epoch&&
            previous.sample.durationMs==sample.durationMs&&previous.sample.rate==sample.rate&&!previous.sample.seeking&&
            interval!=null&&interval in 400..30_000&&delta!=null&&delta>=0&&delta<=interval*sample.rate+1000
        if(!comparable||sample.seeking)sawProgress=false
        val advancing=if(comparable&&!sample.seeking)checkNotNull(delta)>=100 else null
        if(advancing==true)sawProgress=true
        val status=when {
            sample.seeking->BrowserMediaStatus.SEEKING
            sample.ended&&sawProgress&&sample.durationMs?.let {sample.positionMs>=it-250}==true->BrowserMediaStatus.ENDED
            sample.ended->BrowserMediaStatus.CHECKING
            sample.paused->BrowserMediaStatus.PAUSED
            advancing==true&&sample.readyState>=2->BrowserMediaStatus.PLAYING
            sample.readyState<3->BrowserMediaStatus.BUFFERING
            else->BrowserMediaStatus.CHECKING
        }
        val remaining=sample.durationMs?.takeIf {status==BrowserMediaStatus.PLAYING}
            ?.let {ceil(((it-sample.positionMs).coerceAtLeast(0)/1000.0)/sample.rate).toInt()}
        return BrowserMediaReading(status,advancing,remaining,sample)
    }
}
