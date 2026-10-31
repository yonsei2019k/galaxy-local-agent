package com.user.localagent.core

/** A source assertion, never a behavior rule, permission, diagnosis, or inferred identity. */
data class ExtractedKnowledgeFact(val predicate:String,val value:String,val evidence:String,val ownerProfile:Boolean,val confidence:Double,val tags:List<String>)
object KnowledgeExtraction {
    private val quoted=Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|“[^”]*”|‘[^’]*’|`[^`]*`")
    private val sensitive=Regex("직업|직장|회사원|의사|간호사|약사|변호사|종교|신앙|기독|천주|불교|이슬람|정당|정치|투표|병원|의료|환자|수술|검진|진단|질환|질병|치료|복용|처방|우울|암환|당뇨|혈압|알레르기|임신|장애|성적|성생활|주민번호|계좌|카드번호|비밀번호|비밀키",RegexOption.IGNORE_CASE)
    private val authority=Regex("권한|승인|보안|시스템|프롬프트|개발자|명령|지시|정책|규칙|도구|자동(?:으로)?(?:실행|발송|전송|삭제|결제|설치)|검사.{0,8}(?:무시|생략|건너)|제약.{0,8}(?:없애|해제)|api.?key|secret",RegexOption.IGNORE_CASE)
    private val reported=Regex("라고(?:했|하|말|적|쓰)|라는(?:말|문장|내용)|메시지내용|메일내용|문자내용|다음(?:문장|텍스트|문서|내용)|아래(?:문장|텍스트|문서|내용)|선택한문서|가져온문서")
    private val relations="아들|딸|아내|남편|배우자|어머니|아버지|엄마|아빠"
    private val count=Regex("^(?:나는|난|저는|전|내게는|나에게는|저에게는)\\s*(아들|딸)(?:이|은|는)?\\s*(한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|[1-9][0-9]?)\\s*명(?:이|은|는)?\\s*(?:있어|있어요|있다|있습니다|이야|입니다)$")
    private val presence=Regex("^(?:나는|난|저는|전|내게는|나에게는|저에게는)\\s*($relations)(?:이|가|은|는)?\\s*(있어|있어요|있다|있습니다|없어|없어요|없다|없습니다)$")
    private val name=Regex("^(?:내|제|나의|저의|우리)\\s*($relations)(?:의)?\\s*이름(?:은|는)\\s*([가-힣A-Za-z][가-힣A-Za-z· ]{0,30}?)(?:이야|야|예요|이에요|입니다|이다)$")
    private val preference=Regex("^(?:나는|난|저는|전)\\s*(.{1,100}?)(?:을|를)?\\s*(좋아해|좋아해요|좋아한다|좋아합니다|선호해|선호해요|선호한다|선호합니다|싫어해|싫어해요|싫어한다|싫어합니다)$")
    private val preferredValue=Regex("^(?:내가|제가)\\s*선호하는\\s*(언어|답변방식|답변 방식|음식|색상|스타일)(?:은|는)\\s*(.{1,80}?)(?:이야|야|예요|이에요|입니다|이다)$")
    private val sourceHeader=Regex("(?im)^[^\\n:：]{0,100}(?:카카오톡|카톡|텔레그램|메디통|메신저|문자|sms|메시지|메세지|메일|이메일|대화|발신자|수신자|sender|from|subject|chat|message)[^\\n:：]{0,40}[:：]")
    private val speakerHeader=Regex("(?m)^\\s*[^\\s:：]{1,30}\\s*[:：]\\s*")
    private val structuredRole=Regex("(?i)\"(?:role|messages|speaker|content)\"\\s*:|</?(?:document|untrusted|message|messages|conversation|user|assistant|system|tool)(?:\\s|>|/)|<\\|(?:user|assistant|system|tool)\\|>")

    /** Only the host may classify an actual saved app USER message as this source. */
    fun directOwnerStatements(text:String,role:String="user"):List<ExtractedKnowledgeFact> {
        if(role!="user"||text.length>20000)return emptyList()
        // A chat role belongs to the saved app message, not to nested documents or pasted speakers.
        val original=text.trimStart()
        if(original.firstOrNull() in setOf('{','[')||sourceHeader.containsMatchIn(text)||speakerHeader.containsMatchIn(text)||structuredRole.containsMatchIn(text))return emptyList()
        var direct=text.take(4000).trim()
        if(Regex("^(?:선택한|가져온|첨부한)\\s*(?:문서|파일|텍스트|메시지)").containsMatchIn(direct))return emptyList()
        listOf("```","\"\"\"","<document>","<untrusted>").map {direct.indexOf(it)}.filter {it>=0}.minOrNull()?.let {direct=direct.substring(0,it)}
        direct=direct.lineSequence().filterNot {it.trimStart().startsWith('>')}.joinToString(" ").replace(quoted," ")
        if(reported.containsMatchIn(direct.replace(Regex("\\s+"),""))||Regex("전달받은|메신저내용|카톡내용|알림내용|인용문|기사내용|역할극|아래예시").containsMatchIn(direct.replace(Regex("\\s+"),"")))return emptyList()
        val facts=mutableListOf<ExtractedKnowledgeFact>()
        for(raw in direct.split(Regex("(?<=[.!?。！？])|\\n")).take(12)){
            if(raw.trimEnd().lastOrNull() in setOf('?','？'))continue
            val sentence=raw.trim().trimEnd('.','。','!','！').trim();val compact=sentence.replace(Regex("\\s+"),"")
            if(sentence.isBlank()||reported.containsMatchIn(compact)||sensitive.containsMatchIn(compact)||authority.containsMatchIn(compact))continue
            if(Regex("아니야|아니에요|아닙니다|아닌|안좋아|안선호|좋아하지|선호하지").containsMatchIn(compact))continue
            fun add(predicate:String,value:String,tag:String){facts+=ExtractedKnowledgeFact(predicate,value,sentence.take(200),true,1.0,listOf("owner_direct",tag,"assertion_only"))}
            val counted=count.matchEntire(sentence)
            if(counted!=null){
                val numbers=mapOf("한" to 1,"두" to 2,"세" to 3,"네" to 4,"다섯" to 5,"여섯" to 6,"일곱" to 7,"여덟" to 8,"아홉" to 9,"열" to 10)
                val amount=numbers[counted.groupValues[2]]?:counted.groupValues[2].toInt()
                if(amount in 1..20)add("family.${if(counted.groupValues[1]=="아들")"sons" else "daughters"}.count",amount.toString(),"family")
                continue
            }
            val named=name.matchEntire(sentence)
            if(named!=null){add("family.${relation(named.groupValues[1])}.name",named.groupValues[2].trim(),"family");continue}
            val present=presence.matchEntire(sentence)
            if(present!=null){add("family.${relation(present.groupValues[1])}.present",(!present.groupValues[2].startsWith("없")).toString(),"family");continue}
            val preferred=preferredValue.matchEntire(sentence)
            if(preferred!=null){add("preference.${preferred.groupValues[1].replace(" ","")}",preferred.groupValues[2].trim(),"preference");continue}
            val likes=preference.matchEntire(sentence)
            if(likes!=null){
                val value=likes.groupValues[1].trim().removeSuffix("을").removeSuffix("를").trim()
                if(value.isNotBlank())add("preference.item.${fingerprint(value)}",(if(likes.groupValues[2].startsWith("싫어"))"비선호: " else "선호: ")+value,"preference")
            }
            if(facts.size>=6)break
        }
        return facts.distinctBy {it.predicate to it.value}.take(6)
    }

    /** Third-party text yields only a record-scoped topic assertion, never an owner profile fact. */
    fun observedTopics(text:String):List<ExtractedKnowledgeFact> {
        val bounded=text.take(4000)
        val vocabulary=linkedMapOf("회의" to listOf("회의","미팅"),"일정" to listOf("일정","스케줄","스케쥴"),"서류" to listOf("서류","문서","자료","파일"),"배송" to listOf("배송","택배"),"마감" to listOf("마감","기한"),"예약" to listOf("예약"))
        return vocabulary.filterValues {words->words.any {it in bounded}}.keys.take(4).map {topic->
            ExtractedKnowledgeFact("record.mentions_topic",topic,"연결된 자료에 '$topic' 관련 표현이 있음; 화자·사용자 사실로 확정하지 않음",false,.8,listOf("source_observation",topic,"not_owner_identity"))
        }
    }
    /** A deletion watermark excludes old records even before the first successful ingestion. */
    fun afterHistoricalErasure(sourceCreatedAt:Long,erasedThrough:Long)=sourceCreatedAt>erasedThrough
    private fun fingerprint(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"),"").toByteArray()).take(8).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun relation(value:String)=when(value){"아들"->"son";"딸"->"daughter";"아내"->"wife";"남편"->"husband";"배우자"->"spouse";"어머니","엄마"->"mother";else->"father"}
}
