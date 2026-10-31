package com.user.localagent.analysis

import com.user.localagent.core.ToolCall
import com.user.localagent.data.NoteRecord
import java.util.Locale
import kotlinx.serialization.json.*

/** Resolves the exact saved source on resume; metadata discovery limits never change its identity. */
object PrivateAnalysisSourceBinding {
    suspend fun importedHash(call:ToolCall,scopeId:String,read:suspend(String)->NoteRecord?):String {
        call.arguments["contentSha256"]?.jsonPrimitive?.content?.let {hash->
            require(SHA.matches(hash));return hash.lowercase(Locale.ROOT)
        }
        require(call.name=="analyze_conversation_import") {"요청한 메신저의 실제 가져오기 해시가 필요합니다."}
        val id=call.arguments["analysisId"]?.jsonPrimitive?.content?:error("요청한 메신저의 자료 목록에서 출처를 먼저 선택해야 합니다.")
        require(UUID.matches(id))
        val note=read(id)?:error("현재 범위의 저장된 분석을 찾지 못했습니다.")
        require(note.id==id&&note.collection==RepositoryAnalysisJournal.COLLECTION)
        val root=Json.parseToJsonElement(note.body).jsonObject
        require(root["kind"]?.jsonPrimitive?.content==RepositoryAnalysisJournal.ROOT_KIND&&
            root["id"]?.jsonPrimitive?.content==id&&root["scopeId"]?.jsonPrimitive?.content==scopeId)
        val source=root.getValue("source").jsonObject
        require(source["scopeId"]?.jsonPrimitive?.content==scopeId&&source["kind"]?.jsonPrimitive?.content=="IMPORTED_CONVERSATION")
        val locator=root.getValue("sourceLocator").jsonObject
        require(locator["kind"]?.jsonPrimitive?.content=="conversation_import")
        return locator.getValue("contentSha256").jsonPrimitive.content.also {require(SHA.matches(it))}.lowercase(Locale.ROOT)
    }
    private val SHA=Regex("[a-fA-F0-9]{64}")
    private val UUID=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
}
