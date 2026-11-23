package com.user.localagent.core

import kotlinx.serialization.json.*

/** A wait only observes the current page and grants no permission to click. */
data class ScreenObservationWait(val app:String?,val page:String?,val button:String?,val seconds:Int,
    val requirePlaybackEnd:Boolean=false) {
    enum class Status { READY, PENDING, CONTEXT_CHANGED }
    val automaticLesson: Boolean get() = requirePlaybackEnd && (page == null || button == null)

    fun status(packageName:String,sameWindow:Boolean,visibleLabels:List<String>,enabledButtons:List<String>):Status {
        if(packageName!=(app?:"com.android.chrome")||!sameWindow||page!=null&&page !in visibleLabels)return Status.CONTEXT_CHANGED
        return if(enabledButtons.count {it==button}==1)Status.READY else Status.PENDING
    }

    companion object {
        fun from(arguments:JsonObject):ScreenObservationWait? {
            if(arguments.keys.none {it in setOf("forApp","pageLabel","untilEnabledLabel","waitSeconds","requirePlaybackEnd")})return null
            val playbackEnd=arguments["requirePlaybackEnd"]?.jsonPrimitive?.booleanOrNull ?: false
            fun label(key:String):String? {
                if(playbackEnd&&key !in arguments)return null
                return (arguments[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
                    ?.trim()?.takeIf {it.isNotEmpty()&&it.length<=120}
                    ?: throw IllegalArgumentException("화면 대기에 $key 값이 필요합니다.")
            }
            val seconds=(arguments["waitSeconds"] as? JsonPrimitive)?.takeUnless {it.isString}?.intOrNull
                ?:if(playbackEnd&&"waitSeconds" !in arguments)90 else null
            require(seconds!=null&&seconds in 1..90){"화면 대기는 1~90초입니다."}
            return ScreenObservationWait(label("forApp"),label("pageLabel"),label("untilEnabledLabel"),seconds,playbackEnd)
        }
    }
}
