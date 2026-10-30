package com.user.localagent.core

import java.time.ZonedDateTime
import java.util.Locale

/** A read/analysis plan only. It never authorizes sending or calendar mutation. */
sealed interface AssistantTaskPlan {
    data class SmsAnalysis(val year:Int?,val extractSchedules:Boolean,val messengerPackages:Set<String> = emptySet()):AssistantTaskPlan
    data class MessengerAnalysis(val packageNames:Set<String>,val year:Int?=null,val extractSchedules:Boolean=false):AssistantTaskPlan
    data object NoPlan:AssistantTaskPlan
}

/** Deterministic host routing for clear owner requests; quoted source text has no authority. */
object AssistantTaskPlanner {
    private val kakao=setOf("com.kakao.talk")
    private val telegram=setOf("org.telegram.messenger","org.telegram.messenger.web")
    private val med=setOf("com.tionsoft.meettalk.eun","com.meditong.gw")
    private val nativeWords=Regex("문자|(?<![a-z])sms(?![a-z])")
    private val genericWords=Regex("메시지|메세지|(?<![a-z])messages?(?![a-z])")
    private val analysisWords=Regex("분석|요약|정리|추출|조회|검색|분류|살펴|검토|파악|읽어|읽고|읽어서|읽을|보여|모아|찾아|뽑아|analy[sz]e|summari[sz]e|extract|read|review")
    private val schedules=Regex("일정|캘린더|약속|예약|스케줄|calendar|schedule|appointment")
    private val calendarActions=Regex("등록|추가|옮겨|넣어|만들|추출|찾아|정리|뽑아")
    private val stopAnalysis=Regex("(?:분석|요약|정리|추출|조회|검색)(?:은|는)?(?:하지마|하지말|하지않|안해|안할|할필요없)|읽(?:지마|지말)|don'?tanaly[sz]e|donot(?:read|analy[sz]e)")
    private val reset=Regex("다른주제|다른얘기|다른이야기|그건됐고|새주제|새로운주제")
    private val noPhoneReadTopic=Regex("문자열|유니코드|문자코드|정규식|인코딩|특수문자|파이썬|python|java(?:script)?|코드작성|함수작성")
    private val explanation=Regex("무슨뜻|무엇을뜻|이란|원리|기능설명|사용법|방법.*(?:설명|알려)|(?:앱|모델)(?:을|좀)?추천|어떤(?:앱|모델)")
    private val sourceList=Regex("(?:문자(?:메시지)?|sms|메시지|메세지|모든대화|전체대화|카카오톡|카카오|카톡|kakaotalk|kakao|텔레그램|telegram|메디통링크메신저|메디통링크|메디통|meditong|메신저|messenger|[\\s,、/·+]|그리고|하고|이랑|랑|와|과|및|도|만|전부|전체|모든|이요|이야|야)+")
    private val bareFollowup=Regex("(?:(?:그럼|그거|그것|이거|이것|그자료|해당자료|그러면|다시|계속|이어서|전부|전체|모든|다|좀|한번|올해|금년도|금년|작년|지난해|내년|것|거|만|도|을|를|은|는|으로|에서|대화|일정|약속|캘린더|예약|스케줄|분석|요약|정리|추출|조회|검색|검토|분류|읽어|읽고|읽어서|찾아|뽑아|보여|모아|해줘|해봐|해주세요|해보세요|해|줘|봐|주세요|해줄래|해줄수있어|할수있어|알려줘)|[0-9년!?.,\\s])+")

    /** previousUserRequests are chronological, oldest first. An intervening topic resets context. */
    fun plan(userText:String,previousUserRequests:List<String> = emptyList(),now:ZonedDateTime):AssistantTaskPlan {
        val year=now.withZoneSameInstant(ToolRegistry.defaultZone).year
        var inherited:AssistantTaskPlan=AssistantTaskPlan.NoPlan
        previousUserRequests.takeLast(4).forEach {inherited=resolve(it,inherited,year)}
        return resolve(userText,inherited,year)
    }

    private fun resolve(raw:String,inherited:AssistantTaskPlan,currentYear:Int):AssistantTaskPlan {
        val trusted=directRequest(raw)
        if(trusted.isBlank())return AssistantTaskPlan.NoPlan
        var text=trusted.lowercase(Locale.ROOT).replace(Regex("\\s+"),"")
        // A correction such as "문자 말고 카카오톡만" excludes the old source.
        val excludedSources=Regex("(?:문자(?:메시지)?|sms|메시지|메세지|카카오톡|카톡|텔레그램|메디통)(?:은|는|을|를)?(?:분석|요약|조회)?(?:하지)?(?:말고|제외하고|빼고)")
        val excludedSms=excludedSources.findAll(text).any {nativeWords.containsMatchIn(it.value)||genericWords.containsMatchIn(it.value)}
        text=text.replace(excludedSources,"")
        if(text.isBlank()||stopAnalysis.containsMatchIn(text)||noPhoneReadTopic.containsMatchIn(text)||explanation.containsMatchIn(text))return AssistantTaskPlan.NoPlan
        if(Regex("(?:코드|스크립트|예제|프로그램)(?:를|을)?(?:작성|만들|구현)|앱(?:을|를)?(?:개발|만들|제작)").containsMatchIn(text))return AssistantTaskPlan.NoPlan
        if(text.contains("우리대화")||text.contains("너와나의대화")||text.contains("이앱대화"))return AssistantTaskPlan.NoPlan
        val explicitSms=nativeWords.containsMatchIn(text)
        val genericMessage=genericWords.containsMatchIn(text)
        val packages=linkedSetOf<String>()
        if(Regex("카카오톡|카카오|카톡|kakaotalk|kakao").containsMatchIn(text))packages+=kakao
        if(Regex("텔레그램|telegram").containsMatchIn(text))packages+=telegram
        if(Regex("메디통|meditong").containsMatchIn(text))packages+=med
        val allMessengers=Regex("메신저|messenger").containsMatchIn(text)&&packages.isEmpty()
        if(allMessengers)packages+=ToolRegistry.supportedMessengerPackages
        val explicitlyListedGeneric=Regex("(?:메시지|메세지)(?:[,、/·+]|랑|와|하고|및|그리고)|(?:카카오톡|카톡|텔레그램|메디통)(?:[,、/·+]|과|랑|및|그리고)(?:메시지|메세지)").containsMatchIn(text)
        val sms=explicitSms||(genericMessage&&(packages.isEmpty()||explicitlyListedGeneric)&&!allMessengers)
        if(excludedSms&&!sms&&packages.isEmpty())return AssistantTaskPlan.NoPlan
        val clearAnalysis=analysisWords.containsMatchIn(text)||(schedules.containsMatchIn(text)&&calendarActions.containsMatchIn(text))
        val continuationWithSource=Regex("^(그럼|그러면|이어서|추가로)").containsMatchIn(text)&&(sms||packages.isNotEmpty())
        val followup=(sourceList.matches(text)||bareFollowup.matches(text)||continuationWithSource)&&!reset.containsMatchIn(text)
        if(!clearAnalysis && !(followup&&inherited!=AssistantTaskPlan.NoPlan))return AssistantTaskPlan.NoPlan
        val yearChoice=parseYear(text,currentYear)
        if(yearChoice.ambiguous)return AssistantTaskPlan.NoPlan
        val scheduleRequested=schedules.containsMatchIn(text)
        val scheduleExcluded=Regex("(?:일정|캘린더|약속|예약)(?:은|는|을|를)?(?:빼고|제외|말고|안해|하지마)").containsMatchIn(text)
        val inheritDetails=followup&&inherited!=AssistantTaskPlan.NoPlan
        val inheritedYear=when(inherited){is AssistantTaskPlan.SmsAnalysis->inherited.year;is AssistantTaskPlan.MessengerAnalysis->inherited.year;else->null}
        val inheritedSchedules=when(inherited){is AssistantTaskPlan.SmsAnalysis->inherited.extractSchedules;is AssistantTaskPlan.MessengerAnalysis->inherited.extractSchedules;else->false}
        val wantedYear=if(yearChoice.specified)yearChoice.year else if(inheritDetails)inheritedYear else null
        val extract=!scheduleExcluded&&(scheduleRequested||(inheritDetails&&inheritedSchedules))
        if(sms)return AssistantTaskPlan.SmsAnalysis(wantedYear,extract,packages.toSet())
        if(packages.isNotEmpty())return AssistantTaskPlan.MessengerAnalysis(packages.toSet(),wantedYear,extract)
        if(inheritDetails)return when(inherited){
            is AssistantTaskPlan.SmsAnalysis->inherited.copy(year=wantedYear,extractSchedules=extract)
            is AssistantTaskPlan.MessengerAnalysis->inherited.copy(year=wantedYear,extractSchedules=extract)
            else->AssistantTaskPlan.NoPlan
        }
        return AssistantTaskPlan.NoPlan
    }

    private data class YearChoice(val year:Int?=null,val specified:Boolean=false,val ambiguous:Boolean=false)
    private fun parseYear(text:String,current:Int):YearChoice {
        // This planner represents one year or all years, never a silently widened month/range.
        if(Regex("(?:19|20)\\d{2}(?:[-./]\\d{1,2}|년\\d{1,2}월)|\\d{1,2}월(?:문자|메시지)").containsMatchIn(text))return YearChoice(ambiguous=true)
        val years=linkedSetOf<Int>()
        Regex("(?<!\\d)(19\\d{2}|20\\d{2}|2100)(?:년)?(?!\\d)").findAll(text).forEach {years+=it.groupValues[1].toInt()}
        if(text.contains("올해")||text.contains("금년")||text.contains("이번해"))years+=current
        if(text.contains("작년")||text.contains("지난해")||text.contains("전년도"))years+=current-1
        if(text.contains("내년"))years+=current+1
        if(years.size>1)return YearChoice(ambiguous=true)
        return if(years.isEmpty())YearChoice()else YearChoice(years.single(),specified=true)
    }

    private fun directRequest(raw:String):String {
        var text=raw.take(6000).trim()
        if(Regex("^(선택한|가져온)\\s*(문서|파일|텍스트|대화\\s*자료)").containsMatchIn(text))return ""
        listOf("```","\"\"\"","<document>","<untrusted>").forEach {marker->val at=text.indexOf(marker);if(at>=0)text=text.substring(0,at)}
        text=text.lineSequence().filterNot {it.trimStart().startsWith('>')}.joinToString(" ")
        val sourceStart=Regex("(?:아래|다음)(?:의)?\\s*(?:문장|텍스트|문서|내용|대화|메일|이메일|인용|로그|메시지)(?:를|을|는|은|:|\\s)").find(text)?.range?.first
        if(sourceStart!=null)text=text.substring(0,sourceStart)
        text=text.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|“[^”]*”|‘[^’]*’|「[^」]*」|`[^`]*`")," ")
        return text.replace(Regex("https?://\\S+")," ").trim()
    }
}
