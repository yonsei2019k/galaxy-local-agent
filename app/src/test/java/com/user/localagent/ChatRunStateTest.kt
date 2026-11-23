package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.InferenceTurn

class ChatRunStateTest {
    @Test fun visualAccessIsCapturedAtSubmissionAndLegacyRequestsHaveNoGrant() {
        val request=QueuedChatRequest("r","t","b",null,"합성 요청","model",false,emptyList(),"u")
        assertNull(QueuedChatRequest.fromJson(request.toJson()).screenVisualBinding)
        val revision=java.util.UUID.randomUUID().toString()
        assertEquals(revision,QueuedChatRequest.fromJson(request.copy(screenVisualBinding=revision).toJson()).screenVisualBinding)
        assertThrows(IllegalArgumentException::class.java) {request.copy(screenVisualBinding="enabled")}
    }

    @Test fun cloudPhoneAuthorityIsExplicitAndSurvivesOnlyAnExactNewSnapshot(){
        val base=QueuedChatRequest("r","t","b",null,"합성 요청","paired-pc-cloud-agent-v1",false,emptyList(),"u")
        val old=Json.parseToJsonElement(base.toJson()).jsonObject
        val legacy=QueuedChatRequest.fromJson(JsonObject(old-filterSet()).toString())
        assertFalse(legacy.allowExternalAgent);assertNull(legacy.externalAgentRevision)
        val captured=base.copy(capturedPcRevision=4,allowExternalAgent=true,externalAgentRevision=8)
        val restored=QueuedChatRequest.fromJson(captured.toJson())
        assertTrue(restored.allowExternalAgent);assertEquals(8L,restored.externalAgentRevision)
        assertEquals(4L,restored.capturedPcRevision)
        assertTrue(runCatching{base.copy(allowExternalAgent=true)}.isFailure)
    }
    private fun filterSet()=setOf("allowExternalAgent","externalAgentRevision")

    @Test fun stringCloudConsentCannotBecomePermissionDuringRestore(){
        val base=QueuedChatRequest("r","t","b",null,"합성 요청","model",false,emptyList(),"u")
        val value=Json.parseToJsonElement(base.toJson()).jsonObject
        assertTrue(runCatching{QueuedChatRequest.fromJson(JsonObject(value+mapOf("allowExternalAgent" to JsonPrimitive("true"))).toString())}.isFailure)
    }
    @Test fun continuationKeepsDevicePlacementAndExecutionOptionsAcrossRestart(){
        val options=InferenceOptions(contextSize=8192,maxOutputTokens=2048,backend="GPU",thinking=true,cpuThreads=2,deviceOnly=true,audioEnabled=true,visionEnabled=true,projectorPath="private/projector.gguf")
        val snapshot=ContinuationSnapshot("answer-a","model-a","original 🙂","system",listOf(InferenceTurn("user","history")),options,3)
        val restored=ContinuationSnapshot.parse(snapshot.json())
        assertEquals(options.copy(thinking=false),restored.options)
        assertEquals(snapshot.copy(options=options.copy(thinking=false)),restored)
    }

    @Test fun legacyContinuationRestoresConservativeOriginalDefaults(){
        val old=Json.parseToJsonElement("""{"messageId":"a","modelId":"m","user":"u","system":"s","history":[],"nextPart":2,"context":4096,"cap":768}""").jsonObject
        val restored=ContinuationSnapshot.parse(old)
        assertEquals(InferenceOptions(maxOutputTokens=768),restored.options)
    }
    @Test fun queuedRequestPreservesUnicodeModeBindingsAndVerifiedPriorSteps(){
        val args=buildJsonObject {put("content","합성 메모");put("scope","default")}
        val request=QueuedChatRequest("run-a","thread-a","branch-a","project-a","긴 요청 🙂\n".repeat(200),"local-model",false,emptyList(),"user-a",
            mode="act",previousSteps=listOf(HarnessStep("save_note",args,StepEvidence("save_note",VerificationState.VERIFIED,"읽기 검증됨"))),queueOrder=7)
        val recovered=QueuedChatRequest.fromJson(request.toJson())
        assertEquals(request.text,recovered.text);assertEquals(request.threadId,recovered.threadId)
        assertEquals(request.branchId,recovered.branchId);assertEquals(7L,recovered.queueOrder)
        assertEquals(VerificationState.VERIFIED,recovered.previousSteps.single().evidence.state)
        assertEquals(args,recovered.previousSteps.single().arguments)
    }

    @Test fun unknownPriorToolCannotBeRestoredAsExecutionEvidence(){
        val request=QueuedChatRequest("r","t","b",null,"요청","model",false,emptyList(),"u")
        val o=Json.parseToJsonElement(request.toJson()).jsonObject
        val invalid=JsonObject(o+mapOf("previousSteps" to buildJsonArray {add(buildJsonObject {put("tool","unknown_tool");put("arguments",buildJsonObject {});put("state","VERIFIED");put("summary","untrusted")})}))
        assertTrue("Unknown tools must never become restored execution evidence",runCatching {QueuedChatRequest.fromJson(invalid.toString())}.isFailure)
    }

    @Test fun explicitSummaryIsBoundedAndKeepsLatestOriginalsUnmodified(){
        val messages=(1..40).map {"user" to "원문 $it · "+"긴 합성 문장 ".repeat(80)}
        val original=messages.map {it.second}
        val summary=extractiveChatSummaryWithSources(messages).text
        assertTrue(summary.length<=2200)
        assertTrue(summary.startsWith("발췌 요약"))
        assertTrue(summary.contains("원문 40"))
        assertEquals(original,messages.map {it.second})
    }

    @Test fun extractiveSummaryAttributesOnlyIncludedRowsAndKeepsLatestCorrectionTail(){
        val correction="열기 전에 확인. "+"가".repeat(500)+" 마지막 정정: 다른 사람에게 보내지 말 것."
        val messages=(1..20).map {"assistant" to "old-$it "+"가".repeat(400)}+
            listOf("user" to correction,"assistant" to " ")
        val result=extractiveChatSummaryWithSources(messages,256)
        assertEquals(listOf(20),result.sourceIndices)
        assertTrue(result.text.contains("마지막 정정: 다른 사람에게 보내지 말 것."))
        assertFalse(result.text.contains("old-20"))
        assertTrue(result.text.length<=256)
    }


    @Test fun boundedKnowledgeTermsFindNormalizedAndTailOnlyWords(){
        val text="첫단어 "+(1..110).joinToString(" ") {"합성$it"}+" 마지막표식 김 민"
        val terms=com.user.localagent.data.knowledgeSearchTerms(text,128)
        assertTrue(terms.size<=128)
        assertTrue("첫단어" in terms)
        assertTrue("마지막표식" in terms)
        assertTrue("김" in terms)
        assertTrue("민" in terms)
        assertEquals("김",com.user.localagent.data.normalizedKnowledgeText("김"))
    }


    @Test fun newestCorrectionAndItsSourcePrecedeUnrelatedResultsWithinBudget(){
        fun claim(id:String)=com.user.localagent.data.KnowledgeClaim(id=id,predicate="record",value=id)
        val old=claim("previous")
        val newest=claim("corrected")
        val ranked=listOf(claim("other"),old,newest)
        val pair=listOf(newest,old)
        assertEquals(listOf(newest),com.user.localagent.data.prioritizeKnowledgeCorrection(pair,ranked,1))
        assertEquals(pair,com.user.localagent.data.prioritizeKnowledgeCorrection(pair,ranked,2))
    }

}
