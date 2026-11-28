package com.user.localagent

import com.user.localagent.compute.PairedMediaRead
import com.user.localagent.compute.classifyPairedMediaRead
import com.user.localagent.compute.matchesCompletedMediaWait
import com.user.localagent.core.BrowserMediaSample
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import com.user.localagent.core.WaitCondition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class GoalRecoveryReadbackTest {
    @Test fun readbackChecksTheExactFileAndDoesNotReclassifyKnownNoEffect() {
        val call=ToolCall("write","write_project_file",buildJsonObject {
            put("path","result.txt");put("content","확인")
        })
        val unknown=ToolResult(call.id,false,"",VerificationState.UNVERIFIED)
        val expected=checkNotNull(GoalFileReadback.from(call,unknown))
        assertNull(expected.confirmed(call,"other.txt",expected.sha256,expected.size))
        assertNull(expected.confirmed(call,expected.path,"1".repeat(64),expected.size))
        assertNull(expected.confirmed(call,expected.path,expected.sha256,expected.size-1))
        assertEquals(VerificationState.VERIFIED,expected.confirmed(call,expected.path,expected.sha256,expected.size)?.verification)
        assertNull(GoalFileReadback.from(call,unknown.copy(verification=VerificationState.FAILED,effectNotApplied=true)))
        assertNull(GoalFileReadback.from(call,unknown.copy(id="different-call")))
    }

    @Test fun mediaDistinguishesADeadSessionFromAReadError() {
        fun error(code:String)=buildJsonObject {put("error",code)}
        assertEquals(PairedMediaRead.SessionGone,classifyPairedMediaRead(409,error("BROWSER_WORK_SESSION_GONE")))
        assertEquals(PairedMediaRead.Unavailable,classifyPairedMediaRead(409,error("BROWSER_WORK_BUSY")))
        assertEquals(PairedMediaRead.Unavailable,classifyPairedMediaRead(403,error("ACCESS_DENIED")))
        assertEquals(PairedMediaRead.Unavailable,classifyPairedMediaRead(200,buildJsonObject {}))
        val login=classifyPairedMediaRead(200,buildJsonObject {put("loginRequired",true)}) as PairedMediaRead.Observed
        assertTrue(login.page.loginRequired)
    }

    @Test fun restartCheckRequiresADispatchedMarkerForTheSameUnfinishedCallAndScope() {
        val call=ToolCall("file-call","write_project_file",buildJsonObject {
            put("path","result.txt");put("content","private fixture content")
        })
        val file=checkNotNull(GoalFileReadback.intended(call))
        val binding=buildJsonObject {put("scopeId","captured-scope")}
        val native=buildJsonObject {
            put("inFlight",call.id);put("inFlightTool",call.name);put("closed",false);put("uncertain",false)
            put("binding",binding)
        }
        val begun=JsonObject(mapOf("sharedEngineState" to native))
        assertNull(InterruptedGoalFileCheck.load(begun,binding))
        val marker=InterruptedGoalFileCheck.marker(call,file)
        val dispatched=JsonObject(begun+(InterruptedGoalFileCheck.KEY to marker))
        val restored=checkNotNull(InterruptedGoalFileCheck.load(dispatched,binding))
        assertFalse(marker.toString().contains("private fixture content"))
        assertTrue(restored.matches(file.path,file.sha256,file.size))
        assertFalse(restored.matches(file.path,file.sha256,file.size+1))
        assertNull(InterruptedGoalFileCheck.load(dispatched,buildJsonObject {put("scopeId","other")}))
        val settled=JsonObject(dispatched+("sharedEngineState" to JsonObject(native+("inFlight" to JsonPrimitive("other-call")))))
        assertNull(InterruptedGoalFileCheck.load(settled,binding))
    }

    @Test fun aParkedEndCarriesForwardOnlyForTheSameFinishedPlayer() {
        val url="https://example.org/lesson?id=7"
        val player="${"a".repeat(32)}:1"
        val wait=WaitCondition.MediaEnd(url,"$player:0","lesson-7")
        val sample=BrowserMediaSample(player,0,60000,60000,true,true,false,4,1.0)
        assertTrue(matchesCompletedMediaWait(wait,url,sample))
        assertFalse(matchesCompletedMediaWait(wait,url,sample.copy(epoch=1)))
        assertFalse(matchesCompletedMediaWait(wait,url,sample.copy(ended=false)))
        assertFalse(matchesCompletedMediaWait(wait,url,sample.copy(positionMs=100)))
        assertFalse(matchesCompletedMediaWait(wait,"https://example.org/lesson?id=8",sample))
    }
}
