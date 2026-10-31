package com.user.localagent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QueuedPcDataBindingTest {
    private fun request(binding:String?=null)=QueuedChatRequest("synthetic-run","synthetic-thread","synthetic-branch","synthetic-project",
        "원래 읽기 전용 조건과 본문을 보존", "synthetic-model",false,emptyList(),"synthetic-message",allowTools=false,mode="plan",pcDataBinding=binding)

    @Test fun encryptedQueueEnvelopeRetainsExactSelectedSourceBindingWithoutChangingOldRestrictions() {
        val original=request("a".repeat(64))
        val restored=QueuedChatRequest.fromJson(original.toJson())
        assertEquals(original,restored)
        assertEquals("a".repeat(64),restored.pcDataBinding)
        assertFalse(restored.allowTools);assertEquals("plan",restored.mode)
    }
    @Test fun anOldEnvelopeHasNoAuthorityToWhateverPcDataIsSelectedLater() {
        val old=JsonObject(Json.parseToJsonElement(request().toJson()).jsonObject.filterKeys {it!="pcDataBinding"}).toString()
        val restored=QueuedChatRequest.fromJson(old)
        assertNull(restored.pcDataBinding)
        assertFalse(restored.allowTools);assertEquals("plan",restored.mode)
        assertEquals(request().text,restored.text)
    }
    @Test fun malformedSourceBindingsAreRejectedBeforeQueueExecution() {
        assertThrows(IllegalArgumentException::class.java){request("../not-a-source")}
        assertThrows(IllegalArgumentException::class.java){request("a".repeat(63))}
    }
}
