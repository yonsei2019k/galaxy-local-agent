package com.user.localagent.core

import java.util.Locale

/** Translation quality only: preserves nonsexual educational terminology, never changes a request's intent. */
object ClinicalImagePrompt {
    fun translationInstruction(original:String):String=BASE+if(educationalTerms(original).isEmpty())""else
        " The request is a nonsexual medical/anatomy educational image. Anatomical or surgical terms alone are not erotic intent. Preserve structures and procedure names; do not replace them with euphemisms or add sexual content. Preserve negations, exclusions, orientation and relationships: vocabulary does not mean excluded structures should be included. "+
            (if(EXPLICIT_STYLE.containsMatchIn(original))"Preserve the user's explicitly requested visual style and level of realism exactly. "else "If no style was specified, default to a clear textbook schematic. ")+
            "This is an educational illustration, not a diagnosis or clinical instruction. Required source vocabulary: ${educationalTerms(original).joinToString(", ")}."

    fun validateTranslation(original:String,translated:String):String {
        val raw=translated.trim()
        val text=if(raw.startsWith("```")&&raw.endsWith("```")){
            val body=raw.removePrefix("```").removeSuffix("```")
            if(body.substringBefore('\n').trim() in setOf("text","english","en","plaintext"))body.substringAfter('\n',"").trim()else body.trim()
        }else raw
        require(text.isNotBlank()&&text.length<=3000){"장면 설명 번역이 비어 있거나 출력 한도를 넘었습니다."}
        require(!REFUSAL.containsMatchIn(text)){"장면 번역기가 그림 설명 대신 거절 문장을 반환했습니다. 이미지를 생성하지 않았습니다."}
        val missing=educationalTerms(original).filter {term->!text.lowercase(Locale.ROOT).contains(term)}
        require(missing.isEmpty()){"장면 번역에서 원문의 핵심 의학 용어가 누락됐습니다 (${missing.joinToString(", ")}). 장면을 변경해 생성하지 않았습니다."}
        return text
    }

    internal fun educationalTerms(prompt:String):List<String> {
        val text=prompt.lowercase(Locale.ROOT).replace(Regex("\\s+"),"")
        if(!EDUCATION.containsMatchIn(text)||SEXUAL_ACT.containsMatchIn(text))return emptyList()
        val selected=mutableListOf<String>();var position=0
        while(position<text.length){
            val match=TERMS.firstOrNull {(source,_)->text.startsWith(source,position)&&
                (source !in setOf("폐","뇌")||position+source.length==text.length||
                    Regex("^(?:의|와|과|를|는|가|에|위치|구조|모형|해부|단면|및|[,.;:])").containsMatchIn(text.substring(position+source.length)))}
            if(match==null)position++ else {selected+=match.second;position+=match.first.length}
        }
        return selected.distinct().take(10)
    }
    private const val BASE="Translate the user's visual scene description into a concise English image prompt. Preserve the requested subject, style and details. This is translation only; another local tool renders the image. Output only the translated prompt, without commentary or questions."
    private val EDUCATION=Regex("해부(?:학|도|모형)|의학교육|의학도해|간호교육|간호학|수술교육|수술도해|수술모식도|교육용.{0,20}(?:도해|도식|해부)|anatomy|anatomical|medical(?:education|illustration|diagram)|surgical(?:education|diagram)")
    private val EXPLICIT_STYLE=Regex("사진|실사|포토|수채화|유화|일러스트|3차원|스케치|도해|도식|도면|모식도|해부도|만화|카툰|애니|photograph|photoreal|photo|realistic|watercolor|oil.?painting|illustration|3d|sketch|schematic|diagram|cartoon|anime",RegexOption.IGNORE_CASE)
    private val SEXUAL_ACT=Regex("성관계|성행위|포르노|야동|섹스|자위|porn|erotic|intercourse|sexualacts?")
    private val REFUSAL=Regex("(?is)^(?:(?:i(?:'m| am) sorry|i apologize)[,.!:]?\\s*(?:but\\s+)?)?(?:i\\s+(?:can(?:not|'t)|won't|am unable to)\\b|sorry[,! ]+(?:i\\b|but\\b)|as an ai(?: language)? model\\b|죄송(?:하지만|합니다)|미안하지만|(?:해당|요청하신)\\s*.{0,80}(?:생성|제공|도움).{0,30}(?:없|어렵))")
    private val TERMS=listOf(
        "정맥주사" to "intravenous injection","근육주사" to "intramuscular injection","폐동맥" to "pulmonary artery",
        "폐정맥" to "pulmonary vein","좌심방" to "left atrium","우심방" to "right atrium","좌심실" to "left ventricle","우심실" to "right ventricle",
        "생식기관" to "reproductive anatomy","생식기" to "reproductive anatomy","요도카테터" to "urinary catheter",
        "신장해부" to "kidney anatomy","신장구조" to "kidney anatomy","신장수술" to "kidney surgery",
        "봉합" to "suturing","절개" to "incision","심장" to "heart","폐포" to "alveoli","콩팥" to "kidney",
        "골반" to "pelvis","회음부" to "perineal anatomy","유방" to "breast anatomy","자궁" to "uterus","고환" to "testicular anatomy",
        "뇌" to "brain","골격" to "skeleton","위장" to "stomach","폐" to "lung"
    ).sortedByDescending {it.first.length}
}
