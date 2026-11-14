package com.user.localagent

import kotlinx.serialization.json.*

/** Test-only accounting. No provider/model authority, retries, or cleanup execution. */
class QaBatchIntegrity(kinds:List<String>,repeats:Int,private val version:Long,private val token:String) {
    data class Attempt(val index:Int,val kind:String,val round:Int)
    val plan:List<Attempt> = (0 until repeats).flatMap {round->kinds.map {it to round}}.mapIndexed {index,pair->Attempt(index,pair.first,pair.second)}
    private val recorded=mutableListOf<JsonObject>()
    val rows:List<JsonObject> get()=recorded.toList()
    var current:JsonObject?=null;private set
    var terminalFailure:Throwable?=null;private set
    private var active:Attempt?=null
    private var started=0
    private var caseFailure:Throwable?=null
    private var lastCaseFailure:Throwable?=null
    private var cleanupKnown:Boolean?=null
    private var storageObservation:JsonElement?=null
    private var reason:String?=null
    private var abortRequested=false

    init {require(kinds.isNotEmpty()&&kinds.distinct().size==kinds.size&&repeats in 1..2)}
    fun begin(attempt:Attempt) {
        check(!abortRequested&&reason==null&&active==null&&attempt.index==started&&recorded.size==started&&plan.getOrNull(started)==attempt)
        active=attempt;started++;caseFailure=null;lastCaseFailure=null;cleanupKnown=null;storageObservation=null
        current=buildJsonObject {put("kind",attempt.kind);put("round",attempt.round);put("qaStage","case_boundary_entered")}
    }
    fun observe(stage:JsonObject) {
        val attempt=checkNotNull(active)
        check(stage["kind"]?.jsonPrimitive?.content==attempt.kind&&stage["round"]?.jsonPrimitive?.intOrNull==attempt.round)
        current=stage
        if(stage["qaStage"]?.jsonPrimitive?.content=="case_cleanup_finished")cleanupKnown=stage["cleanupVerified"]?.jsonPrimitive?.booleanOrNull
        stage["storageStateObservation"]?.let {storageObservation=it}
    }
    fun rememberCaseFailure(error:Throwable) {
        val prior=caseFailure
        if(prior==null)caseFailure=error else attach(prior,error)
    }
    /** A separate small suspend boundary around the entire case, including its finally and row builder. */
    suspend fun capture(action:suspend()->JsonObject):JsonObject {
        val row=try {action()}catch(error:Throwable){escapedRow(error)}
        record(row);return row
    }
    fun abort(error:Throwable) {
        abortRequested=true
        if(active!=null)record(escapedRow(error))else rememberBoundary(error,lastCaseFailure)
    }
    private fun escapedRow(error:Throwable):JsonObject {
        val attempt=checkNotNull(active)
        rememberBoundary(error,caseFailure)
        val primary=checkNotNull(terminalFailure)
        return buildJsonObject {
            put("kind",attempt.kind);put("round",attempt.round);put("versionCode",version);put("batchToken",token)
            put("passed",false);put("abortBatch",true);put("caseRecordComplete",false);put("caughtBy","outer_case_boundary")
            put("error",primary.javaClass.simpleName);put("message",primary.message);put("escapedError",error.javaClass.simpleName)
            put("cleanupVerified",cleanupKnown==true);put("cleanupState",when(cleanupKnown){true->"verified";false->"failed";null->"unknown"})
            put("lastCheckpoint",current?:JsonNull);put("storageStateObservation",storageObservation?:JsonNull)
        }
    }
    private fun record(row:JsonObject) {
        val attempt=checkNotNull(active)
        check(row["kind"]?.jsonPrimitive?.content==attempt.kind&&row["round"]?.jsonPrimitive?.intOrNull==attempt.round)
        val passed=checkNotNull(row["passed"]?.jsonPrimitive?.booleanOrNull)
        val cleanup=checkNotNull(row["cleanupVerified"]?.jsonPrimitive?.booleanOrNull)
        val abort=checkNotNull(row["abortBatch"]?.jsonPrimitive?.booleanOrNull)
        check(!passed||(cleanup&&!abort));check(cleanup||abort)
        recorded+=JsonObject(row+mapOf("attemptIndex" to JsonPrimitive(attempt.index)))
        if(row["abortBatch"]?.jsonPrimitive?.booleanOrNull==true)abortRequested=true
        lastCaseFailure=caseFailure;active=null;current=null
    }
    fun finish(terminationReason:String){check(active==null);reason=terminationReason}
    fun summary():JsonObject=buildJsonObject {
        put("plannedAttempts",plan.size);put("startedAttempts",started);put("recordedAttempts",recorded.size)
        put("startedWithoutRow",started-recorded.size);put("unstartedAttempts",plan.size-started)
        put("terminationReason",reason?:"running");put("boundaryFailure",terminalFailure!=null);put("abortRequested",abortRequested)
        put("unstartedPlan",buildJsonArray {plan.drop(started).forEach {add(buildJsonObject{put("kind",it.kind);put("round",it.round);put("attemptIndex",it.index)})}})
        put("attemptLedger",buildJsonArray {plan.forEach {attempt->add(buildJsonObject {
            put("kind",attempt.kind);put("round",attempt.round);put("attemptIndex",attempt.index)
            put("recordState",if(attempt.index<recorded.size)"recorded"else if(attempt.index<started)"started_without_row"else"not_started")
        })}})
    }
    private fun rememberBoundary(error:Throwable,preferred:Throwable?) {
        val primary=terminalFailure?:preferred?:error
        terminalFailure=primary;abortRequested=true;attach(primary,error)
    }
    private fun attach(primary:Throwable,other:Throwable) {
        if(primary!==other&&primary.suppressed.none{it===other})primary.addSuppressed(other)
    }
}
