package com.user.localagent.workspace

import com.user.localagent.core.compactJsonData
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ProjectReadDeliveryTest {
    private val token="71b556ab-85ea-4c9c-b25a-f66c35014f76"
    private fun raw(text:String,truncated:Boolean=false)=buildJsonObject{
        put("path","source.txt");put("text",text);put("truncated",truncated)
        put("sha256",MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)})
        put("readToken",token);put("untrustedSourceData",true)
    }.toString()
    private fun rows(view:JsonElement)=listOf(buildJsonObject{put("tool","read_project_file");put("success",true);put("verification","VERIFIED");put("result",view)})
    @Test fun fullProjectWrapperPreserves301To8000CharactersWhileGenericCompactionDoesNotGrant(){
        for(size in listOf(301,8000)){
            val text="x".repeat(size)
            val generic=rows(Json.parseToJsonElement(compactJsonData(raw(text),1100)))
            assertTrue(ProjectReadDelivery.delivered(generic,JsonArray(generic).toString()).isEmpty())
            val full=rows(Json.parseToJsonElement(ProjectReadDelivery.modelResult(raw(text))))
            assertEquals(text,ProjectReadDelivery.delivered(full,JsonArray(full).toString()).single().text)
        }
    }
    @Test fun innerOuterPartialStringFlagsAndMissingActualQueryCannotGrant(){
        val view=Json.parseToJsonElement(ProjectReadDelivery.modelResult(raw("Synthetic source"))).jsonObject
        val candidates=listOf(
            JsonObject(view+("truncated" to JsonPrimitive(true))),
            JsonObject(view+("sourceScopePartial" to JsonPrimitive(true))),
            JsonObject(view+("truncated" to JsonPrimitive("false"))),
            JsonObject(view+("data" to JsonObject(view.getValue("data").jsonObject+("truncated" to JsonPrimitive(true)))))
        )
        for(candidate in candidates){val evidence=rows(candidate);assertTrue(ProjectReadDelivery.delivered(evidence,JsonArray(evidence).toString()).isEmpty())}
        val evidence=rows(view);assertTrue(ProjectReadDelivery.delivered(evidence,"Different model query").isEmpty())
    }
    @Test fun partialSourceAndMalformedAuditSuffixRemainPartialWithoutRepairingAuthority(){
        for(result in listOf(raw("prefix only",truncated=true),raw("full small source")+"\nAudit was not saved")){
            val evidence=rows(Json.parseToJsonElement(ProjectReadDelivery.modelResult(result)))
            assertTrue(ProjectReadDelivery.delivered(evidence,JsonArray(evidence).toString()).isEmpty())
        }
        val text=("한글🙂\n\"quoted\"\\file").repeat(100)
        val evidence=rows(Json.parseToJsonElement(ProjectReadDelivery.modelResult(raw(text))))
        assertEquals(text,ProjectReadDelivery.delivered(evidence,JsonArray(evidence).toString()).single().text)
    }
}
