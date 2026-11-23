package com.user.localagent.core

import java.util.Locale

/** Narrow recognition of visible player-navigation controls after the model selects lesson observation. */
data class ScreenLessonControl(val label:String,val ref:String?,val besidePlayer:Boolean=false) {
    companion object {
        private val specific = Regex("""(?:다음\s*(?:강의|강좌|강|차시|회차|수업|레슨)|next\s+(?:lesson|lecture|unit|chapter|class))(?:\s*(?:로|보기|듣기|이동|열기))?""")
        private val generic = Regex("""(?:다음(?:으로|\s*(?:이동|단계|페이지))?|계속하기|next(?:\s*(?:step|page))?|continue)""")
        private val sign = Regex("""^[›»→❯▶\s]+$""")

        private fun rank(control:ScreenLessonControl):Int {
            val text=control.label.trim().lowercase(Locale.ROOT).trimEnd('›','»','→','❯','▶',' ').trim()
            return when {
                specific.matches(text)->3
                control.besidePlayer&&generic.matches(text)->2
                control.besidePlayer&&sign.matches(control.label)&&control.label.any {it in "›»→❯"}->1
                else->0
            }
        }

        fun isCandidate(control:ScreenLessonControl,exactLabel:String?):Boolean =
            if(exactLabel!=null)control.label==exactLabel else rank(control)>0

        fun choose(controls:List<ScreenLessonControl>,exactLabel:String?=null):ScreenLessonControl? {
            if(exactLabel!=null)return controls.singleOrNull {it.label==exactLabel}
            val ranked=controls.mapNotNull {control->
                val rank=rank(control)
                if(rank>0)rank to control else null
            }
            val best=ranked.maxOfOrNull {it.first}?:return null
            return ranked.singleOrNull {it.first==best}?.second
        }
    }
}
