package com.user.localagent

/**
 * Plain Korean names for tools in the owner's progress report. Uses the shared tool label table;
 * a tool without a plain label shows a generic step name, never its raw id.
 */
internal object ToolReportLabels {
    const val UNKNOWN="작업 단계"

    fun label(name:String):String {
        val shared=com.user.localagent.ui.toolLabel(name)
        return if(shared!=name && plain(shared))shared else UNKNOWN
    }

    /** A label must not look like a code identifier. */
    fun plain(label:String):Boolean = label.isNotBlank() && '_' !in label && !Regex("[a-z][A-Z]").containsMatchIn(label)
}
