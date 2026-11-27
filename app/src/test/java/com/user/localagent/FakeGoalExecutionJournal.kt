package com.user.localagent

import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolReadPolicy
import com.user.localagent.core.ToolResult
import kotlinx.serialization.json.*

/** Test-only double of the native checkpoint wire format (rust/crates/ziphap-android). Never used by production code. */
internal class FakeGoalExecutionJournal(private val binding:String,private val budget:Int=18,private var now:Long=1000) : GoalExecutionJournal {
    private val events=mutableListOf<JsonObject>()
    private val calls=linkedSetOf<String>()
    private var inFlight:Pair<String,String>?=null
    private var uncertain=false
    private var closed=false
    init {event(buildJsonObject {put("type","created")});event(buildJsonObject {put("type","started")})}
    private fun event(kind:JsonObject) {events+=buildJsonObject {put("sequence",events.size+1);put("timestampMs",now++);put("kind",kind)}}
    override fun begin(binding:String,call:ToolCall) {
        check(!closed&&!uncertain&&inFlight==null&&binding==this.binding&&calls.size<budget&&call.id !in calls)
        event(buildJsonObject {put("type","tool_started");put("effect_id",call.id);put("tool",call.name)})
        calls+=call.id;inFlight=call.id to call.name
    }
    override fun outcome(call:ToolCall,result:ToolResult) {
        val (id,tool)=checkNotNull(inFlight);check(!closed&&id==call.id)
        val state=when(result.verification.name) {
            "VERIFIED"->"verified";"PREPARED"->"handed_off"
            "FAILED","UNVERIFIED"->if(result.effectNotApplied||ToolReadPolicy.isReadOnly(tool))"not_applied" else "unknown"
            else->error("rejected")
        }
        event(buildJsonObject {put("type","tool_finished");put("effect_id",id);put("state",state)})
        if(state=="unknown")uncertain=true
        inFlight=null
    }
    override fun checkpoint():String=buildJsonObject {
        put("binding",Json.parseToJsonElement(binding));put("calls",buildJsonArray {calls.forEach {add(it)}})
        put("inFlight",inFlight?.first);put("inFlightTool",inFlight?.second);put("uncertain",uncertain);put("closed",closed)
        put("budget",budget);put("startedAtMs",1000);put("deadlineAtMs",1000+GoalSegmentBinding.MAX_DURATION_MS)
        put("events",JsonArray(events.toList()))
    }.toString()
    override fun sealCheckpoint():String {
        if(!closed){if(inFlight!=null||uncertain)event(buildJsonObject {put("type","uncertain")});closed=true}
        return checkpoint()
    }
    override fun close() {sealCheckpoint()}
}
