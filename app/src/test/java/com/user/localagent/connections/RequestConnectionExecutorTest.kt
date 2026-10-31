package com.user.localagent.connections

import com.user.localagent.core.*
import com.user.localagent.core.connections.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class RequestConnectionExecutorTest {
    private fun call(id:String)=ToolCall(id,"send_gmail_message",buildJsonObject{})
    private fun preview(call:ToolCall,run:String)=GmailSendPreview(call.name,ConnectionExecutionScope.bind(call,run).id,
        "public-test-operation","public-test-account","from@example.invalid","to@example.invalid","Public test","Public invented body",
        null,null,"<public-test@example.invalid>",null,emptyList(),"a".repeat(64),"b".repeat(64),1L)
    @Test fun twoConcurrentApprovalsKeepTheirOriginalRunAndCall()=runBlocking {
        val a=call("public-call-a");val b=call("public-call-b");val seen=mutableListOf<Pair<String,String>>()
        val aReview=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        fun host(owner:String)=RequestConnectionExecutor(owner,{true},approve={run,actual,_ ->
            seen+=run to actual.id
            if(run=="public-run-a"){aReview.complete(Unit);release.await();true}else false
        },execute={actual,run,approve ->ToolResult(actual.id,approve(preview(actual,run)),"synthetic only")})
        val first=async{host("public-run-a").execute(a)};aReview.await()
        val second=host("public-run-b").execute(b);assertFalse(second.success)
        release.complete(Unit);assertTrue(first.await().success)
        assertEquals(listOf("public-run-a" to a.id,"public-run-b" to b.id),seen)
    }
    @Test fun completedRunNeverTouchesTheConnector()=runBlocking {
        var touches=0
        val host=RequestConnectionExecutor("public-run",{false},approve={_,_,_->true},execute={actual,_,_->touches++;ToolResult(actual.id,true,"")})
        assertTrue(runCatching{host.execute(call("public-call"))}.exceptionOrNull() is IllegalStateException)
        assertEquals(0,touches)
    }
    @Test fun cancellationOrMismatchedPreviewNeverOpensTheApproval()=runBlocking {
        var live=true;var reviews=0;val original=call("public-call")
        val host=RequestConnectionExecutor("public-run",{live},approve={_,_,_->reviews++;true},execute={actual,run,approve->
            val foreign=approve(preview(actual,"different-run"));assertFalse(foreign)
            live=false;val cancelled=approve(preview(actual,run));assertFalse(cancelled)
            ToolResult(actual.id,false,"synthetic only")
        })
        assertFalse(host.execute(original).success);assertEquals(0,reviews)
    }
}
