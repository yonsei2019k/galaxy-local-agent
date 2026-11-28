package com.user.localagent

import com.user.localagent.core.ActionCompletionReport
import com.user.localagent.core.ObservedToolOutcome
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActionReceiptCardTest {
    private fun outcome(tool:String,state:VerificationState,args:JsonObject)=
        ObservedToolOutcome(ToolCall("call-1",tool,args),ToolResult("call-1",true,"ok",state))

    @Test fun storedReceiptKeepsExactValuesAndProofStateForTheCard(){
        val report=ActionCompletionReport.compose(setOf("prepare_email"),listOf(outcome("prepare_email",VerificationState.PREPARED,
            buildJsonObject{put("to","owner@example.org");put("subject","회의");put("body","3시\n2층")})))
        val card=checkNotNull(ActionReceiptCard.read(ActionReceiptCard.write(JsonObject(emptyMap()),report)))
        assertEquals(JsonPrimitive(false),card["complete"])
        val item=card["items"]!!.jsonArray.single().jsonObject
        assertEquals("메일 준비",item["title"]!!.jsonPrimitive.content)
        assertEquals("prepared",item["status"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false),item["verified"])
        assertEquals(listOf("받는 주소" to "owner@example.org","제목" to "회의","본문" to "3시\n2층"),
            item["fields"]!!.jsonArray.map{it.jsonObject["label"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content})
    }

    @Test fun untitledToolsUsePlainLabelsAndForgedFieldsAreDropped(){
        val context=buildJsonObject {
            put(ActionReceiptCard.KEY,buildJsonObject {
                put("complete",true)
                put("items",buildJsonArray {
                    add(buildJsonObject{put("tool","open_coupang");put("status","prepared");put("fields",buildJsonArray{})})
                    add(buildJsonObject{put("tool","save_note");put("status","done");put("fields",buildJsonArray{})})
                    add(buildJsonObject{put("tool","open_url");put("status","verified");put("link","javascript:alert(1)")
                        put("fields",buildJsonArray{add(buildJsonObject{put("label","주소\n");put("value","x")})})})
                })
            })
        }
        val items=checkNotNull(ActionReceiptCard.read(context))["items"]!!.jsonArray.map{it.jsonObject}
        assertEquals(2,items.size)
        assertEquals("쿠팡 열기",items[0]["title"]!!.jsonPrimitive.content)
        assertNull(items[1]["link"]);assertTrue(items[1]["fields"]!!.jsonArray.isEmpty())
        assertEquals(JsonPrimitive(true),items[1]["verified"])
    }

    @Test fun nothingIsStoredForAProvenRunWithoutReceipts(){
        val report=ActionCompletionReport.compose(setOf("get_current_datetime"),listOf(outcome("get_current_datetime",VerificationState.VERIFIED,JsonObject(emptyMap()))))
        assertTrue(report.complete);assertTrue(report.receipts.isEmpty())
        assertNull(ActionReceiptCard.read(ActionReceiptCard.write(JsonObject(emptyMap()),report)))
    }
}
