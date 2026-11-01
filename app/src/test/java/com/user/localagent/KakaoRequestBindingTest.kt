package com.user.localagent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KakaoRequestBindingTest {
    private fun request()=QueuedChatRequest("run","thread","branch",null,"합성 요청","model",false,emptyList(),"user")
    @Test fun oldQueuedRequestCannotGainNewContentConsent() {
        val legacy=Json.parseToJsonElement(request().toJson()).jsonObject
        val recovered=QueuedChatRequest.fromJson(JsonObject(legacy.filterKeys{it!="kakaoReadBinding"}).toString())
        assertNull(recovered.kakaoReadBinding)
        assertNull(recovered.copy(text="추가 지시").kakaoReadBinding)
    }
    @Test fun capturedGrantSurvivesQueueStorageButCannotBeMalformed() {
        val token="0d93349d-087b-4cd4-830f-97fa67701309"
        assertEquals(token,QueuedChatRequest.fromJson(request().copy(kakaoReadBinding=token).toJson()).kakaoReadBinding)
        assertThrows(IllegalArgumentException::class.java){request().copy(kakaoReadBinding="all_rooms")}
    }
}
