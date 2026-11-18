package com.user.localagent.externalagent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderProgressTest {
    private fun value()=buildJsonObject{put("sequence",1);put("stage","tool_returned");put("elapsedMs",900);put("calls",1);put("toolName","read_screen")}
    @Test fun validMetadataHasHostLabelAndLegacyAbsenceIsAllowed(){
        val parsed=ProviderProgress.parse(value())!!
        assertEquals("도구 응답 확인 중",parsed.label);assertEquals("read_screen",parsed.toolName)
        assertNull(ProviderProgress.parse(null));assertNull(ProviderProgress.parse(JsonNull))
    }
    @Test fun unknownStagesTextAndOutOfBoundsMetadataNeverBecomeDisplayProgress(){
        for((key,bad) in listOf("stage" to JsonPrimitive("private reasoning"),"toolName" to JsonPrimitive("arbitrary text"),
            "sequence" to JsonPrimitive(-1),"elapsedMs" to JsonPrimitive(3_600_001),"calls" to JsonPrimitive("1"),"thought" to JsonPrimitive("private")))
            assertNull(ProviderProgress.parse(JsonObject(value()+mapOf(key to bad))))
    }
}
