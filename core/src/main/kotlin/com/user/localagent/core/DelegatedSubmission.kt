package com.user.localagent.core

import java.net.URI
import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class DelegatedAction {
    @SerialName("lesson_complete") LESSON_COMPLETE,
    @SerialName("quiz_submit") QUIZ_SUBMIT,
    @SerialName("survey_submit") SURVEY_SUBMIT
}

/** Owner-delegated scope: origins must already be normalized (`https://host`), 1..4 entries. */
@Serializable
data class DelegationScope(val origins:Set<String>,val actions:Set<DelegatedAction>) {
    init {
        require(origins.size in 1..4){"위임 출처는 1개에서 4개여야 합니다."}
        require(origins.all{DelegatedSubmission.normalizeOrigin(it)==it}){"위임 출처는 https://호스트 형식이어야 합니다."}
        require(actions.isNotEmpty()){"위임 작업이 비어 있습니다."}
    }
}

/** Decides whether a click on a risky control may skip the per-click owner approval. */
object DelegatedSubmission {
    const val CONSTRAINT_PREFIX = "delegate:v1 "

    private val json=Json{ignoreUnknownKeys=false}
    private val submitLabel=Regex("제출|응시|확인|submit|confirm",RegexOption.IGNORE_CASE)
    private val progressLabel=Regex("완료|다음|finish|next",RegexOption.IGNORE_CASE)
    private val formatChars=Regex("\\p{Cf}")
    private val spaces=Regex("\\s+")
    private val forbiddenLabel=Regex("결제|구매|주문|송금|이체|보내기|전송|삭제|탈퇴|해지|가입|pay|buy|purchase|send|transfer|delete|unsubscribe",
        RegexOption.IGNORE_CASE)

    internal fun normalizeOrigin(url:String):String? {
        if(!WebAgentPolicy.urlAllowed(url))return null
        val uri=runCatching{URI(url)}.getOrNull()?:return null
        if(uri.scheme?.lowercase()!="https"||uri.port !in setOf(-1,443))return null
        val host=uri.host?.lowercase()?:return null
        return "https://$host"
    }

    fun fromConstraints(constraints:List<String>):DelegationScope? {
        val lines=constraints.filter{it.startsWith(CONSTRAINT_PREFIX)}
        if(lines.size!=1)return null
        return runCatching{json.decodeFromString<DelegationScope>(lines[0].removePrefix(CONSTRAINT_PREFIX))}.getOrNull()
    }

    fun toConstraint(scope:DelegationScope):String = CONSTRAINT_PREFIX+json.encodeToString(scope)

    /** The DOM must show a same-origin assessment form. A generic Submit label proves no delegation. */
    fun permitsAssessmentForm(scope:DelegationScope?,pageUrl:String,formUrl:String,controlLabel:String):Boolean {
        if(!permits(scope,pageUrl,controlLabel))return false
        if(normalizeOrigin(pageUrl)!=normalizeOrigin(formUrl))return false
        fun assessment(url:String):DelegatedAction? {
            val path=runCatching{URI(url).path.lowercase(java.util.Locale.ROOT)}.getOrNull()?:return null
            if(forbiddenLabel.containsMatchIn(path))return null
            return when {
                Regex("(?:^|[/_.-])(?:survey|questionnaire)(?:[/_.-]|$)").containsMatchIn(path)->DelegatedAction.SURVEY_SUBMIT
                Regex("(?:^|[/_.-])(?:quiz|exam|assessment)(?:[/_.-]|$)").containsMatchIn(path)->DelegatedAction.QUIZ_SUBMIT
                else->null
            }
        }
        val action=assessment(pageUrl)?:return false
        return action==assessment(formUrl)&&scope?.actions?.contains(action)==true
    }

    fun permits(scope:DelegationScope?,pageUrl:String,controlLabel:String):Boolean {
        if(scope==null)return false
        val origin=normalizeOrigin(pageUrl)?:return false
        if(origin !in scope.origins)return false
        val label=Normalizer.normalize(formatChars.replace(controlLabel,""),Normalizer.Form.NFKC)
            .replace(spaces," ").trim()
        if(label.length>120)return false
        if(forbiddenLabel.containsMatchIn(label))return false
        val submit=DelegatedAction.QUIZ_SUBMIT in scope.actions||DelegatedAction.SURVEY_SUBMIT in scope.actions
        if(submitLabel.containsMatchIn(label))return submit
        return progressLabel.containsMatchIn(label)&&(submit||DelegatedAction.LESSON_COMPLETE in scope.actions)
    }
}
