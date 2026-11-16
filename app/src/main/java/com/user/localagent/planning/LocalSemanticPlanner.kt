package com.user.localagent.planning

import com.user.localagent.inference.loadTarget

import com.user.localagent.AppContainer
import com.user.localagent.FailureLog
import com.user.localagent.compute.PcBusy
import com.user.localagent.compute.PcUnavailable
import com.user.localagent.core.SemanticRequestPlan
import com.user.localagent.core.SemanticRequestPlanning
import com.user.localagent.core.ToolRegistry
import com.user.localagent.inference.GenerationEvent
import com.user.localagent.inference.GenerationStopReason
import com.user.localagent.inference.GenerationSummary
import com.user.localagent.inference.InferenceTurn
import com.user.localagent.inference.PcRequired
import com.user.localagent.models.ChosenInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

data class SemanticPlanningResult(
    val plan: SemanticRequestPlan,
    val modelId: String,
    val attempts: Int,
    val elapsedMs: Long,
    val constrainedDecoding: Boolean = false,
)
data class SemanticPlanningTelemetry(val stage:String,val elapsedMs:Long,val inputTokens:Int?=null,val visibleChars:Int=0,val attempt:Int=0,
    val nativeProgress:com.user.localagent.inference.NativeGenerationProgress?=null)

/** Safe diagnostics only: no request, history, generated plan or parser payload in errors. */
class SemanticPlanningException(val reasonCode: String, message: String) : IllegalStateException(message)

/**
 * The caller owns the existing inference gate and cancellation scope. This class only plans:
 * it executes no tools, stores no conversation payloads and never falls back to keyword routing.
 */
class LocalSemanticPlanner internal constructor(
    private val requestEngine:com.user.localagent.inference.InferenceEngine,
    private val progress:(SemanticPlanningTelemetry)->Unit={},
    private val budget:PlanningBudget=PlanningBudget(),
    private val recordEngineFailure:(stage:String,error:Throwable,code:String)->Unit={_,_,_->}) {
    constructor(app:AppContainer,requestEngine:com.user.localagent.inference.InferenceEngine=app.engine,
        progress:(SemanticPlanningTelemetry)->Unit={}) : this(requestEngine,progress,PlanningBudget(),
            {stage,error,code->FailureLog.record(app.applicationContext,"semantic_$stage",error,code)})
    suspend fun plan(
        ownerRequest: String,
        history: List<InferenceTurn>,
        chosen: ChosenInference,
        availableTools: Set<String> = ToolRegistry.definitions.keys,
        localContext: JsonObject = buildJsonObject {},
    ): SemanticPlanningResult {
        if (ownerRequest.isBlank()) fail("empty_request", "작업을 계획할 현재 요청이 비어 있습니다.")
        if ((chosen.model as? com.user.localagent.models.LocalWeight)?.installed?.verified == false)
            fail("unverified_model", "검증된 기기 내 모델로 작업 계획을 만들어야 합니다.")
        chosen.model.requireSelectable()
        if (availableTools.any { it !in ToolRegistry.definitions }) {
            fail("unknown_available_tool", "등록되지 않은 도구가 작업 계획 범위에 포함되어 있습니다.")
        }
        val started = System.nanoTime()
        var inputTokens:Int?=null
        var visibleChars=0
        var activeAttempt=0
        var lastProgress=0L
        // Track the operation, not presentation/heartbeat events such as budget_checked or repairing.
        var engineStage="loading"
        fun emit(stage:String,force:Boolean=true){
            val elapsed=(System.nanoTime()-started)/1_000_000L
            if(force||elapsed-lastProgress>=1000){lastProgress=elapsed;progress(SemanticPlanningTelemetry(stage,elapsed,inputTokens,visibleChars,activeAttempt,
                if(stage=="generating")requestEngine.nativeProgressSnapshot else null))}
        }
        val schema = com.user.localagent.core.ModelDecisionContract.planningSchemaJson(availableTools)
        val options = chosen.options.copy(
            thinking = false,
            maxOutputTokens = OUTPUT_TOKENS,
            audioEnabled = false,
            visionEnabled = false,
            projectorPath = null,
        )
        try {
            return withTimeout(budget.totalMs) {
                emit("loading")
                planningStage("loading",budget.loadMs,budget.heartbeatMs,{emit("loading")}){requestEngine.loadTarget(chosen.model, options);Unit}
                var constrained = requestEngine.supportsStructuredOutput
                var retainedHistory = history.toList()
                var retainedContext = localContext
                fun reduceContext():Boolean {
                    if(retainedHistory.size>4){retainedHistory=retainedHistory.drop(1);return true}
                    if(retainedContext.containsKey("approvedProfileContext")){
                        retainedContext=JsonObject(retainedContext.filterKeys {it!="approvedProfileContext"}+mapOf("profileOmittedForBudget" to kotlinx.serialization.json.JsonPrimitive(true)))
                        return true
                    }
                    if(retainedHistory.isNotEmpty()){retainedHistory=retainedHistory.drop(1);return true}
                    return false
                }
                var failureCode: String? = null
                for (attempt in 1..MAX_ATTEMPTS) {
                    activeAttempt=attempt;visibleChars=0
                    currentCoroutineContext().ensureActive()
                    val system = instruction(failureCode, availableTools)
                    var input = envelope(ownerRequest, retainedHistory, retainedContext)
                    while (true) {
                        engineStage="tokenizing"
                        emit("tokenizing")
                        // Respect the runtime transport limit before asking its tokenizer.
                        // This removes whole old turns, never bytes from the current request.
                        if (input.toByteArray(Charsets.UTF_8).size.toLong() + system.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) {
                            if (!reduceContext()) {
                                fail("current_request_exceeds_input_limit", "현재 요청과 필수 작업 지침이 실행 엔진의 입력 한도를 넘습니다. 원문을 자르지 않고 중단했습니다.")
                            }
                            input = envelope(ownerRequest, retainedHistory, retainedContext)
                            continue
                        }
                        val tokens = planningStage("tokenizing",budget.tokenizeMs,budget.heartbeatMs,{emit("tokenizing")}) {
                            // The grammar-rendered prompt may differ from plain chat. Never
                            // silently let native trimming change the owner's recent context.
                            var counted = if(constrained) requestEngine.countStructuredChatTokens(input,system,schema,emptyList())
                                else requestEngine.countChatTokens(input,system,emptyList())
                            if(counted==null&&constrained){
                                // Older paired runtimes lack exact schema counting. Use a
                                // counted plain generation instead, with the same strict parser.
                                constrained=false
                                counted=requestEngine.countChatTokens(input,system,emptyList())
                            }
                            counted ?: fail(
                                "token_budget_unverified",
                                "이 실행 엔진은 작업 계획의 문맥 토큰 수를 확인할 수 없습니다. 현재 요청 원문을 자르지 않고 중단했습니다.",
                            )
                        }
                        inputTokens=tokens;emit("budget_checked")
                        if (tokens >= 0 && tokens.toLong() + OUTPUT_TOKENS + CONTEXT_MARGIN <= options.contextSize) break
                        if (!reduceContext()) {
                            fail(
                                "current_request_exceeds_context",
                                "현재 요청과 필수 작업 지침이 모델 문맥 한도를 넘습니다. 원문을 자르지 않았습니다. 자료를 나누어 요청해주세요.",
                            )
                        }
                        // All history lives in the envelope: engine-specific takeLast() cannot
                        // silently discard turns or change which user message is authoritative.
                        input = envelope(ownerRequest, retainedHistory, retainedContext)
                    }
                    try {
                        engineStage="generating"
                        emit("generating")
                        val raw = planningStage("generating",if(attempt==1)budget.firstGenerationMs else budget.retryGenerationMs,
                            budget.heartbeatMs,{emit("generating")}) {
                            generate(input, system, schema, constrained){count->val first=visibleChars==0&&count>0;visibleChars=count;emit("generating",first)}
                        }
                        engineStage="validating"
                        emit("validating")
                        val parsed = try {
                            com.user.localagent.core.ModelDecisionContract.parse(raw, availableTools)
                        } catch (failure:com.user.localagent.core.ModelDecisionException) {
                            throw InvalidOutput(failure.code,failure.hint)
                        } catch (_: IllegalArgumentException) {
                            throw InvalidOutput("invalid_plan")
                        }
                        emit("planned")
                        return@withTimeout SemanticPlanningResult(
                            parsed, chosen.model.id, attempt,
                            (System.nanoTime() - started) / 1_000_000L, constrained,
                        )
                    } catch (timeout:PlanningStageTimeout) {
                        requestEngine.cancel()
                        currentCoroutineContext().ensureActive()
                        if(attempt==MAX_ATTEMPTS)throw timeout
                        // A fresh model decision, never a partial JSON/tool execution. Preserve
                        // the full current request, explicit goal and all authority bindings.
                        retainedHistory=retainedHistory.takeLast(4)
                        retainedContext=JsonObject(retainedContext.filterKeys {it!="approvedProfileContext"}+
                            mapOf("profileOmittedForBudget" to kotlinx.serialization.json.JsonPrimitive(true)))
                        failureCode="generation_deadline; return only the minimum valid decision"
                        emit("retrying_timeout")
                    } catch (invalid: InvalidOutput) {
                        emit("repairing_${invalid.code}")
                        requestEngine.cancel()
                        failureCode = invalid.code+(invalid.hint?.let {": $it"}?:"")
                        // One new semantic generation repairs format/termination failures.
                        // Rejected output is not promoted into user authority or execution.
                        if (attempt == MAX_ATTEMPTS) {
                            fail(
                                "plan_rejected_${invalid.code}",
                                "기기 내 작업 계획이 두 번의 형식·완결성 검증을 통과하지 못했습니다. 이전 고정 응답으로 대체하거나 도구를 실행하지 않았습니다.",
                            )
                        }
                    }
                }
                fail("plan_unavailable", "기기 내 작업 계획을 확인하지 못해 실행하지 않았습니다.")
            }
        } catch (timeout:PlanningStageTimeout) {
            emit("timeout_${timeout.stage}")
            requestEngine.cancel()
            currentCoroutineContext().ensureActive()
            val label=when(timeout.stage){"loading"->"모델 준비";"tokenizing"->"입력 확인";else->"계획 생성"}
            fail("planning_${timeout.stage}_timeout", "$label 단계가 제한 시간 안에 끝나지 않았습니다. 요청을 보존해 일시 정지했으며 도구는 실행하지 않았습니다.")
        } catch (timeout: TimeoutCancellationException) {
            emit("timeout")
            requestEngine.cancel()
            if (!currentCoroutineContext().isActive) throw timeout
            fail("planning_total_timeout", "계획의 전체 시간 한도에 도달했습니다. 요청을 보존해 일시 정지했으며 도구는 실행하지 않았습니다.")
        } catch (cancel: CancellationException) {
            requestEngine.cancel()
            throw cancel
        } catch (failure: SemanticPlanningException) {
            throw failure
        } catch (failure: Exception) {
            requestEngine.cancel()
            currentCoroutineContext().ensureActive()
            val code:String
            val message:String
            when {
                failure is PcBusy || (failure is PcRequired && generateSequence(failure.cause){it.cause}.take(4).any{it is PcBusy})->{
                    code="planning_pc_busy"
                    message="PC가 이미지 생성 등 다른 작업을 처리 중이어서 이번 요청을 시작하지 못했습니다. 그 작업이 끝나거나 이미지 작업실에서 일시정지한 뒤 다시 보내주세요. 요청은 보존했고 도구는 실행하지 않았습니다."
                }
                failure is PcRequired->{
                    code="planning_pc_required"
                    message="PC 모델 계산을 사용할 수 없어 작업 계획을 완료하지 못했습니다. PC 연결과 실행 상태를 확인한 뒤 다시 요청해주세요. 도구는 실행하지 않았습니다."
                }
                failure is PcUnavailable->{
                    code="planning_pc_unavailable"
                    message="PC 계산 응답을 확인하지 못해 작업 계획을 완료하지 못했습니다. PC 연결과 실행 상태를 확인한 뒤 다시 요청해주세요. 도구는 실행하지 않았습니다."
                }
                else->{
                    code="planning_engine_failed"
                    message="작업 계획 단계의 모델 계산을 완료하지 못해 실행하지 않았습니다. 실행 기록에서 준비 상태를 확인해주세요."
                }
            }
            // The existing private sink records class/cause names and app source frames only.
            // Keep arbitrary engine messages, prompts and addresses out of the public exception.
            // Diagnostics must never replace the original failure or cause another inference attempt.
            runCatching {recordEngineFailure(engineStage,failure,code)}
            fail(code,message)
        }
    }

    private suspend fun generate(input: String, system: String, schema: String, constrained: Boolean,onDelta:(Int)->Unit): String {
        val output = StringBuilder()
        var terminal: GenerationSummary? = null
        val events = if (constrained) requestEngine.generateStructuredEvents(input, system, schema, emptyList())
            else requestEngine.generateChatEvents(input, system, emptyList())
        events.collect { event ->
            currentCoroutineContext().ensureActive()
            when (event) {
                is GenerationEvent.Delta -> {
                    if (terminal != null) throw InvalidOutput("text_after_finish")
                    if (event.text.length > MAX_OUTPUT_CHARS - output.length) throw InvalidOutput("output_too_large")
                    output.append(event.text)
                    onDelta(output.length)
                }
                is GenerationEvent.Finished -> {
                    if (terminal != null) throw InvalidOutput("multiple_finishes")
                    terminal = event.summary
                }
            }
        }
        val summary = terminal ?: throw InvalidOutput("missing_finish")
        if (summary.mayBeTruncated) throw InvalidOutput("truncated")
        if (summary.reason != GenerationStopReason.EOG &&
            !(summary.reason == GenerationStopReason.SDK_DONE_UNKNOWN && summary.capReached == false)) {
            throw InvalidOutput("unverified_finish")
        }
        if (summary.historyTurnsDropped != 0) throw InvalidOutput("context_changed")
        if (output.isBlank()) throw InvalidOutput("empty_output")
        return output.toString()
    }

    private fun envelope(ownerRequest: String, history: List<InferenceTurn>, localContext: JsonObject): String = buildJsonObject {
        put("type", "semantic_planning_input")
        put("recentConversation", buildJsonArray {
            history.forEach { turn -> add(buildJsonObject { put("role", turn.role); put("text", turn.text) }) }
        })
        put("currentOwnerRequest", ownerRequest)
        put("localContextData", localContext)
    }.toString()

    private fun instruction(repairCode:String?,availableTools:Set<String>):String =
        com.user.localagent.core.SemanticPlannerInstructions.build(availableTools,repairCode)

    private class InvalidOutput(val code: String,val hint:String?=null) : IllegalStateException()

    private fun fail(code: String, message: String): Nothing = throw SemanticPlanningException(code, message)

    companion object {
        private const val OUTPUT_TOKENS = 384
        private const val CONTEXT_MARGIN = 64
        private const val MAX_OUTPUT_CHARS = 8192
        private const val MAX_INPUT_BYTES = 256 * 1024
        private const val MAX_ATTEMPTS = 2
    }
}
