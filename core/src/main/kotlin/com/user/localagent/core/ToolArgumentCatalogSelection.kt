package com.user.localagent.core

import java.util.Collections

/** Prompt argument coverage only. This never registers, authorizes or executes a tool. */
object ToolArgumentCatalogSelection {
    const val MAX_FALLBACK_TOOLS = 6
    const val MAX_CATALOG_UTF16 = 6000

    fun select(directLoop:Boolean, selected:Set<String>, allowed:Set<String>,
               maxUtf16:Int=MAX_CATALOG_UTF16):Set<String> {
        require(maxUtf16>=0)
        require(selected.all {it in allowed}) { "Selected argument definitions must stay inside the request's allowed set." }
        fun snapshot(names:Set<String>):Set<String> = Collections.unmodifiableSet(LinkedHashSet(names))
        val original=snapshot(selected)
        if(!directLoop||selected.isNotEmpty()||allowed.isEmpty()||allowed.size>MAX_FALLBACK_TOOLS)return original
        if(allowed.any {it !in ToolRegistry.definitions})return original
        val candidates=snapshot(allowed)
        val catalog=ToolUsePolicy.compactCatalog(candidates)
        return if(catalog.length<minOf(maxUtf16,MAX_CATALOG_UTF16))candidates else original
    }
}
