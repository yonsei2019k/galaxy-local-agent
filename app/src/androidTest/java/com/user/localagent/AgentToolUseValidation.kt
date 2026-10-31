package com.user.localagent

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.AssistantPolicy
import com.user.localagent.core.HarnessDecision
import com.user.localagent.core.HarnessStep
import com.user.localagent.core.StepEvidence
import com.user.localagent.core.TaskHarness
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolCallSchema
import com.user.localagent.core.ToolRegistry
import com.user.localagent.core.VerificationState
import com.user.localagent.inference.InferenceOptions
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real cached local model -> typed tool -> DOCX readback. Synthetic inputs only; no host fallback. */
@RunWith(AndroidJUnit4::class)
class AgentToolUseValidation {
    @Test fun localE2bUsesInstalledDocumentToolAndVerifiesActualFile()=runBlocking<Unit> {
        assumeTrue("Explicit agentToolUseApproved=true is required",InstrumentationRegistry.getArguments().getString("agentToolUseApproved")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val reports=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").apply {check(isDirectory||mkdir())}
        val reportFile=File(reports,"agent-tool-use.json")
        val attempts=mutableListOf<JsonObject>()
        val started=SystemClock.elapsedRealtime()
        var stage="starting";var failure:Throwable?=null
        var ownsBenchmark=false;var ownsInference=false;var busy=false
        var modelId:String?=null;var modelSha:String?=null
        var artifactFile:File?=null;var actualFileVerified=false;var cleanupVerified=false;var validJson=false
        var toolVerified=false;var harnessFinished=false;var unloadVerified=false

        fun checkpoint(next:String){stage=next;instrumentation.sendStatus(0,Bundle().apply {putString("stream","Local model tool QA: $next\n")})}
        try {
            ownsBenchmark=RuntimeGate.benchmark.compareAndSet(false,true);check(ownsBenchmark){"Another benchmark is active"}
            ownsInference=RuntimeGate.inference.compareAndSet(false,true);check(ownsInference){"Another inference is active"}
            app.updates.setBusy(true);busy=true;app.cancelIdleUnload()
            app.engine.unload()
            checkpoint("verifying_cached_e2b")
            app.models.refresh()
            val mediaCandidateIds=app.models.candidates.filter {it.runtime=="litertlm"&&it.repository.equals("litert-community/gemma-4-E2B-it-litert-lm",ignoreCase=true)}.map {it.id}.toSet()
            val model=app.models.models.value.firstOrNull {it.runtime=="litertlm"&&it.verified&&it.id in mediaCandidateIds}
                ?:error("A cached SHA256-verified official LiteRT E2B media model is required; this test never downloads or replaces models")
            modelId=model.id;modelSha=model.sha256
            val options=InferenceOptions(contextSize=4096,backend="CPU",thinking=false,maxOutputTokens=1536)
            checkpoint("loading_local_cpu_model")
            withTimeout(180000){app.engine.load(model.path,options)}
            assertTrue("Selected runtime must expose native JSON-schema decoding",app.engine.supportsStructuredOutput)
            val allowed=setOf("create_document")
            val outputSchema=ToolCallSchema.forTools(allowed)
            val goal=TaskHarness.inferGoal(OWNER_REQUEST)
            assertEquals(setOf("create_document"),goal.requiredTools)
            val system=AssistantPolicy.build(allowed,ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toString())+
                "\n도구 인자 정의: "+ToolRegistry.schemaJson(allowed)
            var query=OWNER_REQUEST
            var validated:ToolCall?=null
            for(attempt in 1..2){
                checkpoint("model_attempt_$attempt")
                val output=StringBuilder();val generationStarted=SystemClock.elapsedRealtime();var firstVisibleMs:Long?=null
                withTimeout(180000){app.engine.generateStructured(query,system,outputSchema,emptyList()).collect {token->
                    currentCoroutineContext().ensureActive()
                    if(token.isNotEmpty()&&firstVisibleMs==null)firstVisibleMs=SystemClock.elapsedRealtime()-generationStarted
                    require(output.length+token.length<=16000){"Synthetic model visible output exceeded16k characters"}
                    output.append(token)
                }}
                val raw=output.toString().trim()
                val checked=runCatching {
                    val call=ToolRegistry.validateForRequest(ToolRegistry.parseAndValidate(raw),allowed)
                    require(call.name=="create_document")
                    require(call.arguments.getValue("format").jsonPrimitive.content=="docx")
                    require(call.arguments.getValue("title").jsonPrimitive.content==TITLE){"Synthetic title must match the owner request"}
                    require(normalize(call.arguments.getValue("content").jsonPrimitive.content).contains(normalize(BODY))){"Synthetic body must preserve the owner-provided sentence"}
                    require(call.arguments.getValue("content").jsonPrimitive.content.length<=2000)
                    require(TaskHarness.beforeStep(goal,emptyList(),call) is HarnessDecision.Advance)
                    call
                }
                val native=app.engine.lastMetrics.value
                attempts+=buildJsonObject {
                    put("attempt",attempt);put("valid_goal_tool_json",checked.isSuccess)
                    put("visible_output_chars",raw.length);put("visible_ttft_ms",firstVisibleMs)
                    // This fixed synthetic request has no private context. Read only the public
                    // structured visible stream, never native thought/analysis channel diagnostics.
                    put("synthetic_visible_output_prefix",diagnostic(raw,600))
                    checked.exceptionOrNull()?.let {error->
                        put("validation_error_class",error.javaClass.simpleName.take(80))
                        put("validation_error_message",diagnostic(error.message.orEmpty(),400))
                    }
                    native?.timeToFirstTokenSeconds?.takeIf {it.isFinite()}?.let {put("native_ttft_seconds",it)}
                    // Keep accepted canonical JSON separate from the bounded synthetic-only prefix.
                    checked.getOrNull()?.let {call->put("model_output",buildJsonObject {put("type","tool_call");put("tool",call.name);put("arguments",call.arguments)})}
                }
                if(checked.isSuccess){validated=checked.getOrThrow();validJson=true;break}
                if(attempt==1)query=OWNER_REQUEST+"\n이전 응답은 실행 가능한 도구 인자 검증을 통과하지 못했다. 파일을 만들려면 허용된 create_document의 JSON 객체 하나를 반환하라. 사용자가 지정한 DOCX 형식, 제목과 본문을 보존하고 설명·코드펜스를 넣지 마라."
            }
            val call=checkNotNull(validated){"Local model did not produce an executable goal-matching tool request after one repair; no document tool was executed"}
            checkpoint("executing_model_selected_tool")
            val result=app.tools.execute(call)
            toolVerified=result.success&&result.verification==VerificationState.VERIFIED
            assertTrue("Actual host execution and postcondition must be VERIFIED",toolVerified)
            val data=Json.parseToJsonElement(result.content).jsonObject
            assertTrue(data.getValue("privateArtifact").jsonPrimitive.boolean)
            assertEquals("docx",data.getValue("format").jsonPrimitive.content)
            val fileName=data.getValue("fileName").jsonPrimitive.content
            require(Regex("artifact-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.docx").matches(fileName))
            val root=File(context.filesDir,"exports").canonicalFile
            val file=File(root,fileName).canonicalFile
            check(file.parentFile==root);artifactFile=file
            checkpoint("reading_back_actual_docx")
            check(file.isFile&&file.length()>0)
            assertEquals(data.getValue("bytes").jsonPrimitive.long,file.length())
            val returned=app.officeTools.readDocx(file.inputStream())
            val expected=listOf(call.arguments.getValue("title").jsonPrimitive.content)+call.arguments.getValue("content").jsonPrimitive.content.replace("\r\n","\n").replace('\r','\n').split('\n')
            assertFalse(returned.truncated);assertEquals(expected,returned.paragraphs)
            actualFileVerified=true
            val decision=TaskHarness.decide(goal,listOf(HarnessStep(call,StepEvidence(call.name,result.verification,result.verificationSummary))),requestedFinish=true)
            harnessFinished=decision is HarnessDecision.Finish
            assertTrue("Goal completion must be supported by actual verified document evidence",harnessFinished)
            checkpoint("verified")
        }catch(error:Throwable){failure=error;throw error}
        finally {
            try {
                withContext(NonCancellable+Dispatchers.IO){
                    artifactFile?.let {file->
                        check(file.canonicalFile.parentFile==File(context.filesDir,"exports").canonicalFile)
                        check(Regex("artifact-[0-9a-f-]{36}\\.docx").matches(file.name))
                        check(!file.exists()||file.delete()){"Synthetic artifact cleanup failed"};check(!file.exists())
                    }
                    cleanupVerified=artifactFile==null||!artifactFile!!.exists()
                }
            }finally {
                try {if(ownsInference)withContext(NonCancellable){app.engine.unload();unloadVerified=app.engine.residentModelPath==null}}
                finally {if(busy)app.updates.setBusy(false);if(ownsInference)RuntimeGate.inference.set(false);if(ownsBenchmark)RuntimeGate.benchmark.set(false)}
                withContext(NonCancellable+Dispatchers.IO){reportFile.writeText(buildJsonObject {
                    put("stage",stage);put("success",failure==null&&validJson&&toolVerified&&actualFileVerified&&cleanupVerified&&harnessFinished&&unloadVerified)
                    put("synthetic_inputs_only",true);put("private_records_read",false);put("network_used",false)
                    put("model",modelId);put("model_sha256",modelSha);put("backend","CPU");put("context_size",4096);put("thinking",false);put("max_output_tokens",1536)
                    put("native_json_schema",true);put("speculative_decoding",false);put("automatic_tool_calling",false)
                    put("request",OWNER_REQUEST);put("attempts",JsonArray(attempts));put("valid_json",validJson)
                    put("tool_verified",toolVerified);put("actual_docx_readback_verified",actualFileVerified);put("goal_verified",harnessFinished);put("cleanup_verified",cleanupVerified)
                    put("model_unloaded",unloadVerified)
                    put("elapsed_ms",SystemClock.elapsedRealtime()-started);failure?.let {put("failure_class",it.javaClass.simpleName)}
                }.toString())}
            }
        }
    }
    private fun normalize(value:String)=value.replace(Regex("[\\s.]"),"")
    private fun diagnostic(value:String,limit:Int):String=value
        .replace(Regex("(?i)[a-z]:\\\\[^\\s\"<>]+|/data/(?:user|data)/[^\\s\"<>]+"),"[private path]")
        .filterNot {it.isISOControl()}.take(limit)
    companion object {
        private const val TITLE="합성검증"
        private const val BODY="오늘 회의에서 테스트 일정을 확인했다"
        private const val OWNER_REQUEST="회의록 DOCX 파일을 만들어줘. 제목은 합성검증, 본문은 오늘 회의에서 테스트 일정을 확인했다"
    }
}
