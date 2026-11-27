package com.user.localagent.engine

import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import android.os.SystemClock
import uniffi.ziphap_android.NativeRunJournal
import uniffi.ziphap_android.protocolVersion

/** Actual Android dispatch metadata only. This never grants permission or marks a task complete. */
internal class RustExecutionJournal(binding:String,profile:String,checkpoint:String?=null,private val nowMs:()->Long=SystemClock::elapsedRealtime) : com.user.localagent.GoalExecutionJournal {
    private val native=if(checkpoint==null)NativeRunJournal(binding,profile,nowMs().toULong()) else NativeRunJournal.restore(binding,checkpoint)
    init { check(protocolVersion()==2u){"공통 실행 엔진 버전이 일치하지 않습니다."} }
    override fun begin(binding:String,call:ToolCall){
        native.beginCall(binding,call.id,call.name,nowMs().toULong())
    }
    override fun outcome(call: ToolCall,result: ToolResult) {
        require(call.id==result.id)
        outcome(result)
    }
    fun outcome(result:ToolResult) {
        native.finishCall(
            result.id,
            result.success,
            result.verification.name,
            result.effectNotApplied,
            nowMs().toULong(),
        )
    }
    fun snapshot():String=native.events(0u)
    override fun checkpoint():String=native.checkpoint()
    override fun sealCheckpoint():String { native.close(nowMs().toULong());return native.checkpoint() }
    override fun close(){try{native.close(nowMs().toULong())}finally{native.destroy()}}
}
