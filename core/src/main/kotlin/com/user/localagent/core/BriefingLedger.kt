package com.user.localagent.core

/** Content-version acknowledgement, independent of clock order or connection state. */
object BriefingLedger {
    fun version(source:String,id:String,sha256:String):String {
        require(source.matches(Regex("[a-z_]{1,40}")) && id.length in 1..300)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        return "$source:$id:$sha256"
    }
    fun shouldRead(version:String,completed:Set<String>)=version !in completed
    fun acknowledge(expectedVersion:String,currentVersion:String,resultSavedAndReadBack:Boolean):String {
        require(expectedVersion==currentVersion){"자료가 변경되어 이전 분석을 완료 처리할 수 없습니다."}
        check(resultSavedAndReadBack){"분석 결과 저장 확인 전에는 원본을 완료 처리하지 않습니다."}
        return currentVersion
    }
}
