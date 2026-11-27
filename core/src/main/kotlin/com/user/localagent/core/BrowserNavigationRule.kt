package com.user.localagent.core

import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** The model can describe one link navigation and its readback using data fields. */
data class BrowserNavigationRule(val label:String,val verifyText:String) {
    fun sees(title:String,text:String):Boolean =
        title.trim()==verifyText||text.lineSequence().any {it.trim()==verifyText}

    companion object {
        const val LABEL="expectedLinkLabel"
        const val VERIFIED_SUMMARY="같은 링크 이동 · 도착 페이지와 새 확인 문구 확인"
        const val VERIFY="verifyPageText"
        val arguments=mapOf(
            LABEL to Argument("string",false,120,"Exact label of an observed recoverable-link. Use together with verifyPageText."),
            VERIFY to Argument("string",false,160,"A new exact page title or visible line expected after navigation."),
        )
        fun from(call:ToolCall):BrowserNavigationRule? {
            if(LABEL !in call.arguments&&VERIFY !in call.arguments)return null
            fun line(key:String):String {
                val value=call.arguments[key] as? JsonPrimitive
                require(value!=null&&value.isString&&value.content.isNotBlank()&&value.content==value.content.trim()&&
                    value.content.none(Char::isISOControl)&&value.content.length<=arguments.getValue(key).maxLength)
                return value.content
            }
            require(call.arguments["action"]==JsonPrimitive("click"))
            require("text" !in call.arguments&&"direction" !in call.arguments)
            return BrowserNavigationRule(line(LABEL),line(VERIFY))
        }

        private val protectedPath=setOf("login","signin","sign-in","auth","oauth","authorize","checkout","payment",
            "pay","order","orders","purchase","confirm","submit","delete","logout","signout","account","verify","reset")

        private fun publicPath(url:String):URI? {
            if(!WebAgentPolicy.urlAllowed(url))return null
            val uri=URI(url)
            return uri.takeIf {it.scheme=="https"&&it.port in setOf(-1,443)&&it.rawQuery==null&&it.rawFragment==null&&
                it.path.split('/').none {segment->segment.lowercase() in protectedPath}}
        }

        fun sameSiteLink(from:String,to:String):Boolean {
            val source=publicPath(from)?:return false
            val destination=publicPath(to)?:return false
            return source.host.equals(destination.host,true)&&source.normalize().path!=destination.normalize().path
        }
    }
}
