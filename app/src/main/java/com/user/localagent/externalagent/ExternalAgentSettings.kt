package com.user.localagent.externalagent

import android.content.Context

/** Separate opt-in for uploading this request and scoped tool results to the cloud. */
class ExternalAgentSettings private constructor(context:Context) {
    data class Authority(val revision:Long,val enabled:Boolean)
    private val preferences=context.getSharedPreferences("external-agent-authority",Context.MODE_PRIVATE)
    private val lock=Any()
    // A consent given for an older, narrower tool scope does not silently cover new data categories.
    private var authority=Authority(preferences.getLong("revision",0),
        preferences.getBoolean("enabled",false)&&preferences.getInt("scope",1)==CONSENT_SCOPE)
    fun capture():Authority=synchronized(lock){authority}
    fun setEnabled(enabled:Boolean):Authority=synchronized(lock){
        val old=capture()
        if(old.enabled==enabled)return@synchronized old
        val next=Math.addExact(old.revision,1)
        authority=Authority(next,false) // A failed persistence must still revoke the old in-memory grant.
        check(preferences.edit().putLong("revision",next).putBoolean("enabled",enabled).putInt("scope",CONSENT_SCOPE).commit()){"외부 에이전트 권한을 저장하지 못했습니다."}
        Authority(next,enabled).also{authority=it}
    }
    companion object {
        const val MODEL_ID="paired-pc-cloud-agent-v1"
        const val TITLE="클라우드 · 휴대폰 도구"
        const val DISABLE_ID="disable-cloud-phone-agent"
        /** 1: time/storage/notes. 2: adds SMS, calls, contacts, calendar, web and on-screen content. */
        const val CONSENT_SCOPE=2
        const val CONSENT_TEXT="이 모델로 새로 보내는 요청과, 요청에 필요한 휴대폰 도구의 결과가 연결된 개인 PC의 Codex를 통해 클라우드(ChatGPT)로 전달됩니다. "+
            "대화를 이어가기 위해 같은 대화·분기에서 같은 권한으로 이미 클라우드에 전달한 최근 대화와 작업 상태를 제한된 크기로 함께 보냅니다. 로컬 전용 대화·첨부 자료·이미지 작업실 기록은 이 대화 맥락에 포함하지 않습니다. "+
            "현재 시각, 웹·쇼핑·영상 검색 결과, 화면 연결을 켠 경우 허용된 화면 정보가 전달될 수 있습니다. 계정 정보나 개인 자료를 다룰 때는 개인정보 · 로컬을 선택할 수 있습니다. 메모와 연락처 등 개인 자료 조회는 기기 또는 페어링 PC에서 처리합니다. "+
            "문자·일정·메일은 작성 화면만 열고 직접 보내야 합니다. 글자 입력과 보내기·결제·삭제 같은 버튼은 실행 전에 확인을 받습니다. 비밀번호·결제·인증 화면은 다루지 않습니다. "+
            "연결을 해제하면 진행 중인 요청의 권한도 만료됩니다."
        @Volatile private var instance:ExternalAgentSettings?=null
        fun get(context:Context):ExternalAgentSettings=instance?:synchronized(this){instance?:ExternalAgentSettings(context.applicationContext).also{instance=it}}
    }
}
