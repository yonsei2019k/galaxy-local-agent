package com.user.localagent.core

/** Relative logical paths only; a model cannot replace the owner-selected workspace root. */
object ProjectPaths {
    fun isBoundFileRequest(text:String):Boolean {
        val normalized=text.lowercase().replace(Regex("\\s+"),"")
        return ("프로젝트" in normalized||"project" in normalized)&&
            (listOf("현재프로젝트","선택한프로젝트","프로젝트폴더","프로젝트에","프로젝트안","프로젝트의").any {it in normalized}||
                Regex("[\\p{L}\\p{N}_-]+\\.(?:txt|md|csv|json|js|ts|tsx|jsx|html|css|py|kt|kts|java|cpp|c|h|luau|lua|yaml|yml|toml)(?:파일|을|를|에|$)").containsMatchIn(normalized))
    }
    fun segments(path:String):List<String> {
        require(path.length<=500 && !path.startsWith('/') && !path.contains('\\') && !path.contains(':')) {"프로젝트 안의 상대 경로를 사용해주세요."}
        val parts=path.split('/')
        require(parts.size<=12 && parts.all {it.isNotBlank()&&it !in setOf(".","..")&&it.length<=100&&it.none {ch->ch.isISOControl()||ch in "<>|?*"}}) {"올바르지 않은 프로젝트 경로입니다."}
        return parts
    }
}
