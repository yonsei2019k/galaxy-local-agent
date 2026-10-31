package com.user.localagent.core

import java.util.Locale

/** Owner intent only. The host must separately require a selected image before executing an edit. */
object ImageReferenceIntent {
    fun isReferenceEditRequest(ownerText:String):Boolean {
        if(ownerText.isBlank()||ownerText.length>6000)return false
        var text=ownerText.trim().lowercase(Locale.ROOT)
        if(Regex("^(?:선택한|가져온|첨부한)\\s*(?:문서|파일|텍스트|메시지)").containsMatchIn(text))return false
        if(text.startsWith("{")||text.startsWith("[")||Regex("<(?:document|untrusted|message|user|assistant|system)(?:\\s|>)").containsMatchIn(text))return false
        listOf("```","<document>","<untrusted>").map {text.indexOf(it)}.filter {it>=0}.minOrNull()?.let {text=text.substring(0,it)}
        text=text.lineSequence().filterNot {it.trimStart().startsWith('>')}.joinToString(" ")
            .replace(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|“[^”]*”|‘[^’]*’|`[^`]*`")," ")
        if(ProjectPaths.isBoundFileRequest(text)||DocumentTaskPlanner.plan(text)!=null)return false
        val compact=text.replace(Regex("\\s+"),"")
        val rename=Regex("(?:파일명|파일이름|(?:사진|이미지)(?:파일)?(?:의)?이름)(?:을|를)?.{0,60}(?:바꿔|바꾸|변경|수정|편집)|이름(?:을|를).{0,60}(?:바꿔|바꾸|변경|수정|편집)|\\brename\\b")
        if(rename.containsMatchIn(compact))return false
        if(("프로젝트" in compact||Regex("\\bproject\\b").containsMatchIn(text))&&Regex("에셋|asset|파일|[a-z0-9_-]+\\.(?:png|jpe?g|webp|gif|bmp|avif)").containsMatchIn(compact))return false
        if(Regex("방법|사용법|어떻게|설명|원리|무슨뜻|검색|찾아|추천|비교").containsMatchIn(compact))return false
        if(Regex("(?:프로그램|코드|플러그인|스킬|도구|프로젝트).{0,20}(?:만들|설치|개발|수정|편집)").containsMatchIn(compact))return false
        if(Regex("(?:바꾸|변환|편집|리터치|수정|보정)(?:는|은|을|를|해|하|지)?(?:하지마|하지말|지마|지말|마세요)").containsMatchIn(compact))return false
        val image=Regex("사진|이미지|그림|\\b(?:photo|image|picture)\\b").containsMatchIn(text)
        val edit=Regex("바꿔|바꾸|변환|리터치|편집|보정|수정|\\b(?:edit|retouch|transform|convert|restyle)\\b").containsMatchIn(text)
        return image&&edit
    }
}
