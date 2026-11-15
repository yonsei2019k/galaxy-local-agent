package com.user.localagent.externalagent

import android.content.Context

/** Separate opt-in for uploading this request and scoped tool results to the cloud. */
class ExternalAgentSettings private constructor(context:Context) {
    data class Authority(val revision:Long,val enabled:Boolean)
    private val preferences=context.getSharedPreferences("external-agent-authority",Context.MODE_PRIVATE)
    private val lock=Any()
    private var authority=Authority(preferences.getLong("revision",0),preferences.getBoolean("enabled",false))
    fun capture():Authority=synchronized(lock){authority}
    fun setEnabled(enabled:Boolean):Authority=synchronized(lock){
        val old=capture()
        if(old.enabled==enabled)return@synchronized old
        val next=Math.addExact(old.revision,1)
        authority=Authority(next,false) // A failed persistence must still revoke the old in-memory grant.
        check(preferences.edit().putLong("revision",next).putBoolean("enabled",enabled).commit()){"외부 에이전트 권한을 저장하지 못했습니다."}
        Authority(next,enabled).also{authority=it}
    }
    companion object {
        const val MODEL_ID="paired-pc-cloud-agent-v1"
        const val TITLE="클라우드 · 휴대폰 도구"
        const val DISABLE_ID="disable-cloud-phone-agent"
        @Volatile private var instance:ExternalAgentSettings?=null
        fun get(context:Context):ExternalAgentSettings=instance?:synchronized(this){instance?:ExternalAgentSettings(context.applicationContext).also{instance=it}}
    }
}
