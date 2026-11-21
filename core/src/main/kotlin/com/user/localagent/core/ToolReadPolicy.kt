package com.user.localagent.core

/** Effects of registered tools, independent of natural-language intent or phrases. */
object ToolReadPolicy {
    fun isReadOnly(name:String):Boolean = ToolRegistry.definitions[name]?.risk==Risk.LOW&&(
        name in OfficeToolCatalog.names||name in EvidenceToolCatalog.names||name in PrivateAnalysisToolCatalog.privateReadNames||name in ProjectStorageToolCatalog.names||name in LocalSubagentToolCatalog.names||
            listOf("get_","list_","search_","read_").any {name.startsWith(it)}
    )
}
