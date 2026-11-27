package com.user.localagent.engine

import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import android.os.SystemClock
import kotlinx.serialization.json.*
import uniffi.ziphap_android.NativeRunJournal
import uniffi.ziphap_android.protocolVersion

/** Actual Android dispatch metadata only. This never grants permission or marks a task complete. */
internal class RustExecutionJournal(binding:String,profile:String,checkpoint:String?=null) : com.user.localagent.GoalExecutionJournal {
    private val native=if(checkpoint==null)NativeRunJournal(binding,profile,SystemClock.elapsedRealtime().toULong()) else NativeRunJournal.restore(binding,checkpoint)
    init { check(protocolVersion()==2u){"공통 실행 엔진 버전이 일치하지 않습니다."} }
    override fun begin(binding:String,call:ToolCall){
        native.beginCall(binding,call.id,call.name,SystemClock.elapsedRealtime().toULong())
    }
    override fun outcome(call:ToolCall,result:ToolResult){
        native.finishCall(call.id,result.verification.name,result.effectNotApplied,SystemClock.elapsedRealtime().toULong())
    }
    fun snapshot():String=native.events(0u)
    override fun checkpoint():String=native.checkpoint()
    override fun sealCheckpoint():String { native.close(SystemClock.elapsedRealtime().toULong());return native.checkpoint() }
    override fun close(){try{native.close(SystemClock.elapsedRealtime().toULong())}finally{native.destroy()}}
}
