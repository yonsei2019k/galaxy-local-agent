package com.user.localagent.core

/** ASR training metadata is removed; recognized speech remains quoted data, never tool authority. */
object AsrProtocol {
    fun transcript(raw:String):String {
        require(raw.length<=32000&&'\u0000' !in raw){"음성 전사 출력 제한을 넘었습니다."}
        val input=raw.trim()
        val marker="<asr_text>"
        val start=input.indexOf(marker)
        if(start<0&&input.startsWith("language "))error("음성 전사 메타데이터가 완료되지 않았습니다.")
        val text=if(start>=0){
            require(start<=100&&input.substring(0,start).trim().startsWith("language ")){"올바르지 않은 음성 전사 형식입니다."}
            input.substring(start+marker.length)
        }else input
        require(!Regex("<\\|(?:im_start|tool_call|channel)\\|?>").containsMatchIn(text)){"음성 출력에 예상하지 않은 제어 형식이 있습니다."}
        return text.removeSuffix("<|im_end|>").removeSuffix("<|endoftext|>").trim()
    }
}
