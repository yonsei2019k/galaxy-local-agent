package com.user.localagent.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test

class ContactCountArgumentTest {
    private fun call(query:String)=ToolCall("c1","search_contacts",buildJsonObject{put("query",query)})
    @Test fun anEmptyContactQueryMeansCountOnly(){
        ToolRegistry.validate(call(""))
        ToolRegistry.validate(call("QA"))
    }
    @Test fun whitespaceOrMissingQueryIsStillRejected(){
        assertThrows(IllegalArgumentException::class.java){ToolRegistry.validate(call("   "))}
        assertThrows(IllegalArgumentException::class.java){ToolRegistry.validate(ToolCall("c2","search_contacts",buildJsonObject{}))}
    }
}
