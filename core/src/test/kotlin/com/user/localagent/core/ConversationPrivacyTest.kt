package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ConversationPrivacyTest {
    private val cloud="cloud"

    @Test fun privacyDiagnosticsExposeOnlyFixedMatchKinds() {
        val cases = mapOf(
            "비밀번호 안내" to ConversationPrivacy.MatchKind.CREDENTIAL,
            "fixture@example.invalid" to ConversationPrivacy.MatchKind.IDENTIFIER,
            "내 메모를 읽어줘" to ConversationPrivacy.MatchKind.PRIVATE_RECORD,
            "제주행 항공편 2027년 1월 1일" to null,
        )
        for ((text, kind) in cases) {
            assertEquals(kind, ConversationPrivacy.matchKind(text))
            assertEquals(kind != null, ConversationPrivacy.sensitiveText(text))
        }
    }

    @Test fun freshGeneralRequestsUseCloudAndOldLocalSelectionsCannotExport(){
        assertEquals(cloud,ConversationPrivacy.modelForNewRequest("auto",cloud,"두 산의 높이를 비교해줘",false))
        assertEquals("old-local",ConversationPrivacy.modelForNewRequest("old-local",cloud,"요약해줘",false))
    }

    @Test fun privateSelectionImagesAndIdentifiablePrivateDataRemainLocal(){
        for(text in listOf("내 계정 확인","암호는 TEST_ONLY","비번을 바꿨어","복구 키 확인","메일 fixture@example.invalid","연락처 010-0000-0000","내 메모를 수정해줘")){
            assertEquals(ConversationPrivacy.LOCAL_SELECTION,ConversationPrivacy.modelForNewRequest(cloud,cloud,text,false))
        }
        assertEquals(ConversationPrivacy.LOCAL_SELECTION,ConversationPrivacy.modelForNewRequest("auto",cloud,"사진 설명",true))
        assertEquals(ConversationPrivacy.LOCAL_SELECTION,ConversationPrivacy.modelForNewRequest(ConversationPrivacy.LOCAL_SELECTION,cloud,"확인해줘",false))
    }

    @Test fun theSelectedSourceCanRequireAHandoffBeforeTheFirstRead(){
        assertTrue(ConversationPrivacy.needsPrivateSource(setOf("search_contacts","calculate")))
        assertTrue(ConversationPrivacy.needsPrivateSource(setOf("patch_note")))
        assertTrue(ConversationPrivacy.needsPrivateSource(setOf("search_memories")))
        assertTrue(ConversationPrivacy.needsPrivateSource(setOf("save_memory")))
        assertTrue(ConversationPrivacy.needsPrivateSource(setOf("delete_memory")))
        assertFalse(ConversationPrivacy.needsPrivateSource(setOf("search_youtube_videos","search_web","get_current_datetime")))
    }

    @Test fun memoryOverviewUsesTheExistingScopedToolWithoutAnInventedSearchWord(){
        val overview="""{"type":"tool_call","tool":"search_memories","arguments":{"limit":3}}"""
        val call=ToolRegistry.parseAndValidate(overview)
        assertEquals("search_memories",call.name)
        assertFalse("query" in call.arguments)
        assertTrue(AssistantPolicy.buildToolSelection(setOf("search_memories"),"2026-11-21").contains("전반적인 질문은 검색어를 생략"))
        assertFalse(AssistantPolicy.buildToolSelection(setOf("calculate"),"2026-11-21").contains("저장된 기억부터 조회"))
    }
}
