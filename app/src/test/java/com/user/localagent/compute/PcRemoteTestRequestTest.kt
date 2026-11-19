package com.user.localagent.compute

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PcRemoteTestRequestTest {
    private val id="10000000-0000-4000-8000-000000000003"
    private fun body(text:String,model:String="paired-pc-cloud-agent-v1",extra:Boolean=false)=buildJsonObject{
        put("requestId",id);put("text",text);put("model",model);if(extra)put("tools","all")
    }
    @Test fun aPlainRequestIsAccepted(){
        val parsed=PcRemoteTestRequest.parse(body("  오늘 일정 알려줘 "))
        assertEquals("오늘 일정 알려줘",parsed.text)
        assertEquals("paired-pc-cloud-agent-v1",parsed.model)
    }
    @Test fun receiptRequiresAnExplicitSupportedVersion(){
        assertTrue(PcRemoteTestRequest.supportsReceipt(buildJsonObject{put("receiptVersion",2)}))
        assertFalse(PcRemoteTestRequest.supportsReceipt(buildJsonObject{}))
        assertFalse(PcRemoteTestRequest.supportsReceipt(buildJsonObject{put("receiptVersion","2")}))
        assertFalse(PcRemoteTestRequest.supportsReceipt(buildJsonObject{put("receiptVersion",3)}))
    }
    @Test fun commandsExtraFieldsAndBadModelsAreRejected(){
        listOf(body("/new"),body(""),body("x".repeat(2001)),body("hi","../x"),body("hi",extra=true)).forEach{
            assertThrows(IllegalArgumentException::class.java){PcRemoteTestRequest.parse(it)}
        }
    }
}
