package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ConversationPrivacyTest {
    private val cloud="cloud"
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
        assertFalse(ConversationPrivacy.needsPrivateSource(setOf("search_youtube_videos","search_web","get_current_datetime")))
    }
}
