package com.user.localagent.core

import java.util.Locale

data class DocumentTaskPlan(val formats:Set<String>,val usePreviousReply:Boolean=false) {
    init {require(formats.isNotEmpty()&&formats.size<=5&&formats.all {it in setOf("docx","xlsx","pdf","txt","csv")})}
}

/** Owner request intent only. Document text, quotes and prior requests never grant creation authority. */
object DocumentTaskPlanner {
    fun plan(text:String,@Suppress("UNUSED_PARAMETER") previousUserRequests:List<String> = emptyList()):DocumentTaskPlan? {
        var direct=ownerText(text.take(6000)).lowercase(Locale.ROOT)
        if(direct.isBlank())return null
        // "PDF 말고 DOCX로" changes the target rather than creating the rejected format too.
        Regex("(?:하지\\s*말고|지\\s*말고|말고)").findAll(direct).lastOrNull()?.let {direct=direct.substring(it.range.last+1)}
        val compact=direct.replace(Regex("\\s+"),"")
        if(compact.isBlank()||NEGATED_ACTION.containsMatchIn(compact)||Regex("\\b(?:do not|don't|never|avoid)\\s+(?:create|make|generate|save|export|convert|write)\\b").containsMatchIn(direct))return null
        val mentions=FORMATS.flatMap {(format,regex)->regex.findAll(direct).map {Mention(format,it.range.first,it.range.last+1)}.toList()}.sortedBy {it.start}
        val hasAction=ACTION.containsMatchIn(compact)||ENGLISH_ACTION.containsMatchIn(direct)||
            (mentions.isNotEmpty()&&Regex("(?:으)?로(?:도|각각)?(?:해줘|해주세요|해줄래|부탁해)").containsMatchIn(compact))
        if(!hasAction)return null
        val reference=PREVIOUS_REPLY.containsMatchIn(compact)
        val imperative=IMPERATIVE.containsMatchIn(compact)||ENGLISH_IMPERATIVE.containsMatchIn(direct)
        val asksExplanation=EXPLANATION.containsMatchIn(compact)
        val contextualPoliteRequest=reference&&!asksExplanation&&(POLITE_REQUEST.containsMatchIn(compact)||Regex("\\bcan you\\b").containsMatchIn(direct))
        if((asksExplanation||CAPABILITY_QUESTION.containsMatchIn(compact)||ENGLISH_QUESTION.containsMatchIn(direct))&&
            !imperative&&!contextualPoliteRequest)return null

        // In "DOCX 문서를 PDF로 변환", DOCX names the source. Conjoined output formats stay together.
        val targets=mentions.filter {mention->
            val after=direct.substring(mention.end).replace(Regex("^\\s+"),"")
            val source=Regex("^(?:(?:파일|문서|형식)\\s*)?(?:을|를|에서|은|는)(?:\\s|$)").containsMatchIn(after)||
                Regex("^(?:(?:파일|문서|형식))?(?:을|를|에서|은|는)").containsMatchIn(after)
            val hasLaterTarget=mentions.any {later->later.start>mention.start&&
                Regex("^\\s*(?:(?:파일|문서|형식)\\s*)?(?:으)?로").containsMatchIn(direct.substring(later.end))}
            val englishTo=Regex("\\b(?:to|into|as)\\b").find(direct)?.range?.last
            !(source&&hasLaterTarget)&&!(englishTo!=null&&mention.end<=englishTo&&mentions.any {it.start>englishTo})
        }.map {it.format}.toCollection(linkedSetOf())
        if(targets.isEmpty()){
            // A named unsupported format is not silently replaced with a DOCX.
            if(Regex("(?<![a-z0-9_])(?:hwp|hwpx|pptx?|odt|ods|rtf|xls|json|html|xml|zip)(?![a-z0-9_])|한글파일|파워포인트").containsMatchIn(direct))return null
            if(Regex("(?:보고서|문서|리포트|report|document)").containsMatchIn(direct)&&Regex("(?:파일|문서|보고서|리포트|report|document)").containsMatchIn(direct))targets+="docx"
            else return null
        }
        val newSubject=Regex("(?:새로운|다른(?:주제|내용|보고서|문서|표)|새로(?:작성|만들)|대신)").containsMatchIn(compact)
        val transform=Regex("(?:요약해서|번역해서|고쳐서|수정해서|다시써서|재작성해서)").containsMatchIn(compact)
        return DocumentTaskPlan(targets.toSet(),reference&&!newSubject&&!transform)
    }

    private data class Mention(val format:String,val start:Int,val end:Int)
    private fun ownerText(raw:String):String {
        var text=raw.trim()
        if(Regex("^(?:선택한|가져온)\\s*(?:문서|파일|텍스트|자료)").containsMatchIn(text))return ""
        if(Regex("^(?:첨부한|업로드한)\\s*(?:문서|파일|텍스트|자료)\\s*(?:내용|원문)?\\s*[:：\\n]").containsMatchIn(text))return ""
        text=text.replace(Regex("(?s)```.*?```|\"\"\".*?\"\"\"|<document>.*?</document>|<untrusted>.*?</untrusted>")," ")
        listOf("```","\"\"\"","<document>","<untrusted>").forEach {marker->val at=text.indexOf(marker);if(at>=0)text=text.substring(0,at)}
        text=text.lineSequence().filterNot {it.trimStart().startsWith('>')}.joinToString(" ")
        text=text.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|“[^”]*”|‘[^’]*’|`[^`]*`")," [인용 자료] ")
        val source=Regex("(?:아래|다음)(?:의)?\\s*(?:문서|문장|텍스트|내용|인용|로그)\\s*(?:원문|내용)?\\s*[:：]").find(text)
        if(source!=null)text=text.substring(0,source.range.first)
        return text.trim()
    }
    private val FORMATS=linkedMapOf(
        "docx" to Regex("(?<![a-z0-9_])(?:docx|word)(?![a-z0-9_])|워드(?!프레스|클라우드)"),
        "xlsx" to Regex("(?<![a-z0-9_])(?:xlsx|excel)(?![a-z0-9_])|엑셀"),
        "pdf" to Regex("(?<![a-z0-9_])pdf(?![a-z0-9_])|피디에프"),
        "txt" to Regex("(?<![a-z0-9_])txt(?![a-z0-9_])|텍스트\\s*(?:파일|문서)|메모장\\s*파일"),
        "csv" to Regex("(?<![a-z0-9_])csv(?![a-z0-9_])"))
    private val ACTION=Regex("만들|생성|저장|내보내|변환|작성")
    private val ENGLISH_ACTION=Regex("\\b(?:create|make|generate|save|export|convert|write)\\b")
    private val IMPERATIVE=Regex("(?:만들(?:어(?:줘|주세요|주라|줄래|봐|라|주고|서)|자)|(?:생성|저장|변환|작성)(?:해(?:줘|주세요|주라|줄래|봐|라|주고|서)|하자)|내보내(?:줘|주세요|줄래|주고))(?=$|[.!?,]|그리고|그다음)|(?:만들어|생성해|저장해|변환해|작성해|내보내|생성|저장|변환|작성|만들기)(?:부탁|요청)?[.!?]?$")
    private val ENGLISH_IMPERATIVE=Regex("^(?:please\\s+)?(?:create|make|generate|save|export|convert|write)\\b")
    private val EXPLANATION=Regex("사용법|(?:만드는|저장하는|변환하는|내보내는|생성하는|작성하는|만들어주는|저장해주는|변환해주는)(?:법|방법)|(?:법|방법)(?:을|를)?(?:알려|설명|검색|배워)|(?:수있는|수있을)(?:법|방법)|저장방법|변환방법|만들어지는|생성되는|저장되는|변환되는|어떻게|설명|기능설명|뜻|뭐야|무엇이야|정의")
    private val CAPABILITY_QUESTION=Regex("만들(?:어줄|어주실)?수있|(?:생성|저장|변환|작성)(?:해줄|해주실|할)수있|내보낼수있|가능(?:해|하니|한가|할까)")
    private val POLITE_REQUEST=Regex("만들(?:어줄|어주실)?수있|(?:생성|저장|변환|작성)(?:해줄|해주실|할)수있|내보낼수있")
    private val ENGLISH_QUESTION=Regex("\\b(?:how|what|why|can you|can i|could you|is it possible)\\b")
    private val NEGATED_ACTION=Regex("(?:만들|생성|저장|내보내|변환|작성)(?:어|해)?(?:주|줘)?(?:은|는|을|를)?(?:하지는|하지|지는|지)?(?:마|말|않)|실행하지마")
    private val PREVIOUS_REPLY=Regex("(?:이|위|그|해당)내용|방금(?:답변|내용|결과)|(?:이전|위|지금)답변|(?:네|너의)답변|방금쓴|작성한내용|(?:이|위|그)표|(?:이|그)보고서|thiscontent|previousreply|aboveanswer|yourlastreply")
}
