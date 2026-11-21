package com.user.localagent.core

/** Local egress check. A positive match selects private processing, never a tool or a permission. */
object ConversationPrivacy {
    const val LOCAL_SELECTION = "private-local"
    const val LOCAL_LABEL = "개인정보 · 로컬"

    private val credential = Regex(
        """비밀\s*번호|비번|패스워드|암호(?:는|가|를|\s*[:=])|복구\s*(?:키|코드)|인증\s*(?:번호|코드)|개인\s*정보|주민\s*(?:등록)?\s*번호|계좌\s*번호|카드\s*번호|\b(?:password|passwd|passphrase|otp|api[_ -]?key|access[_ -]?token|bearer)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val identifier = Regex("""(?<![\w.+%-])[\w.+%-]{1,64}@[\w.-]{1,253}\.[A-Za-z]{2,63}|(?<!\d)(?:01[016789][ -]?\d{3,4}[ -]?\d{4}|\d{6}[ -]?[1-8]\d{6})(?!\d)""")
    private val privateRecord = Regex("""(?:내|나의|제|개인)\s*(?:계정|메모|연락처|문자|메일|이메일|일정|녹음|주소|전화번호|건강|진료)|(?:제목|본문).{0,150}메모|통화\s*(?:기록|녹음)""")

    enum class MatchKind { CREDENTIAL, IDENTIFIER, PRIVATE_RECORD }

    fun matchKind(text: String): MatchKind? = when {
        credential.containsMatchIn(text) -> MatchKind.CREDENTIAL
        identifier.containsMatchIn(text) -> MatchKind.IDENTIFIER
        privateRecord.containsMatchIn(text) -> MatchKind.PRIVATE_RECORD
        else -> null
    }

    fun sensitiveText(text:String):Boolean = matchKind(text) != null

    fun needsPrivateSource(tools:Set<String>):Boolean = tools.any {
        SemanticRequestPlanning.accessesPrivateRecords(it) ||
            it in setOf("save_note","update_note","delete_note","save_memory","delete_memory")
    }

    fun modelForNewRequest(selection:String,cloud:String,text:String,imageOnly:Boolean):String {
        if(selection==LOCAL_SELECTION || imageOnly || sensitiveText(text))
            return if(selection=="auto" || selection==cloud) LOCAL_SELECTION else selection
        // An existing explicit device-model preference is never consent to export text.
        return if(selection=="auto") cloud else selection
    }
}
