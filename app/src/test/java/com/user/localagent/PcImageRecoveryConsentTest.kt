package com.user.localagent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PcImageRecoveryConsentTest {
    private fun request()=QueuedChatRequest("run","thread","branch",null,"synthetic request","model",false,emptyList(),"user")
    @Test fun legacyQueuedAndGoalRequestsNeverGainPersistentPcStorage(){
        val original=request();val json=Json.parseToJsonElement(original.toJson()).jsonObject
        val legacy=QueuedChatRequest.fromJson(JsonObject(json.filterKeys{it!="pcImageRecoveryAllowed"}).toString())
        assertFalse(legacy.pcImageRecoveryAllowed)
        assertFalse(legacy.copy(text="steer old work").pcImageRecoveryAllowed)
        val readOnly=original.copy(allowTools=false,pcImageRecoveryAllowed=true)
        assertFalse(QueuedChatRequest.fromJson(readOnly.toJson()).allowTools)
    }
    @Test fun explicitCapturedConsentRoundTripsButStringCoercionIsRejected(){
        val allowed=request().copy(pcImageRecoveryAllowed=true)
        assertTrue(QueuedChatRequest.fromJson(allowed.toJson()).pcImageRecoveryAllowed)
        val json=Json.parseToJsonElement(allowed.toJson()).jsonObject
        assertThrows(IllegalArgumentException::class.java){QueuedChatRequest.fromJson(JsonObject(json+("pcImageRecoveryAllowed" to JsonPrimitive("true"))).toString())}
    }
}
