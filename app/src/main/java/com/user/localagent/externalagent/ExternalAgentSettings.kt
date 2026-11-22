package com.user.localagent.externalagent

import android.content.Context

/** Separate opt-in for uploading this request and scoped tool results to the cloud. */
class ExternalAgentSettings private constructor(context:Context, modelId:String) {
    data class Authority(val revision:Long,val enabled:Boolean,val allowsPersonalData:Boolean=false) {
        fun permitsPersonalData(capturedRevision:Long?):Boolean =
            enabled && allowsPersonalData && capturedRevision==revision
    }
    private val preferences=context.getSharedPreferences(if(modelId==CLAUDE_MODEL_ID)"claude-agent-authority" else "external-agent-authority",Context.MODE_PRIVATE)
    private val lock=Any()
    // A consent given for an older, narrower tool scope does not silently cover new data categories.
    private var authority=readAuthority(
        preferences.getLong("revision",0),preferences.getBoolean("enabled",false),preferences.getInt("scope",1))
    fun capture():Authority=synchronized(lock){authority}
    fun setEnabled(enabled:Boolean):Authority=synchronized(lock){
        val old=capture()
        if(old.enabled==enabled&&(!enabled||old.allowsPersonalData))return@synchronized old
        val next=Math.addExact(old.revision,1)
        authority=Authority(next,false) // A failed persistence must still revoke the old in-memory grant.
        check(preferences.edit().putLong("revision",next).putBoolean("enabled",enabled).putInt("scope",CONSENT_SCOPE).commit()){"외부 에이전트 권한을 저장하지 못했습니다."}
        readAuthority(next,enabled,CONSENT_SCOPE).also{authority=it}
    }
    companion object {
        const val MODEL_ID="paired-pc-cloud-agent-v1"
        const val CLAUDE_MODEL_ID="paired-pc-claude-agent-v1"
        fun isCloud(modelId:String)=modelId==MODEL_ID||modelId==CLAUDE_MODEL_ID
        fun automaticModel(claudeAuthorized:Boolean)=if(claudeAuthorized)CLAUDE_MODEL_ID else MODEL_ID
        fun provider(modelId:String)=if(modelId==CLAUDE_MODEL_ID)"claude" else "codex"
        fun label(modelId:String)=if(modelId==CLAUDE_MODEL_ID)"Claude" else "Codex"
        const val TITLE="클라우드 · 휴대폰 도구"
        const val DISABLE_ID="disable-cloud-phone-agent"
        /** Scope 2 retains the old cloud grant. Scope 3 also allows personal data for newly captured runs. */
        const val CONSENT_SCOPE=3
        internal fun readAuthority(revision:Long,enabled:Boolean,scope:Int):Authority =
            Authority(revision,enabled&&(scope==2||scope==CONSENT_SCOPE),enabled&&scope==CONSENT_SCOPE)

        const val CONSENT_TEXT="새 요청과 요청에 필요해 읽은 개인 기억, 대화 기록, 분석 결과, 문자·연락처·일정 등 허용된 휴대폰 자료가 연결된 PC의 Codex를 통해 클라우드(ChatGPT)로 전송될 수 있습니다. "+
            "현재 대화의 최근 클라우드 문맥도 함께 사용할 수 있습니다. 휴대폰이나 연결된 PC에서 처리하려면 모델 메뉴에서 개인정보 · 로컬을 선택하세요. "+

            "문자·일정·메일은 작성 화면만 열고 직접 보내야 합니다. 글자 입력과 보내기·결제·삭제 같은 버튼은 실행 전에 확인을 받습니다. 비밀번호·결제·인증 화면은 다루지 않습니다. "+
            "연결을 해제하면 진행 중인 요청의 권한도 만료됩니다."
        fun consentText(modelId:String)=if(modelId==CLAUDE_MODEL_ID)
            CONSENT_TEXT.replace("Codex를 통해 클라우드(ChatGPT)로","Claude Code를 통해 Anthropic으로") else CONSENT_TEXT

        private val instances=mutableMapOf<String,ExternalAgentSettings>()
        fun get(context:Context,modelId:String=MODEL_ID):ExternalAgentSettings=synchronized(this){
            val key=if(modelId==CLAUDE_MODEL_ID)CLAUDE_MODEL_ID else MODEL_ID
            instances.getOrPut(key){ExternalAgentSettings(context.applicationContext,key)}
        }
    }
}
