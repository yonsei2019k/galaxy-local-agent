package com.user.localagent

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.inference.*
import com.user.localagent.workspace.ProjectWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual host producer + isolated encrypted Room + own synthetic files; no model or phone providers. */
@RunWith(AndroidJUnit4::class)
class ChatWorkTraceSessionValidation {
    @Test fun quotedLongCredentialIsMaskedBeforeOwnerGoalPreviewIsClipped()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val thread=repo.chat.createThread();val request=request(repo,thread)
        val missing=AtomicInteger()
        val session=checkNotNull(ChatWorkTraceSession.open(repo,request){missing.incrementAndGet()})
        val secretWords=(0..39).map{"synthetic-secret-word-$it"}
        val preview="합성 목표: \"api_key\":\""+secretWords.joinToString(" ")+"\"; 공개 계획 요약"
        assertTrue("The synthetic quoted value must extend beyond the preview boundary",preview.length>280)
        session.stage(WorkTraceCode.PLAN_READY,preview,WorkSummarySource.USER_GOAL)
        val event=repo.workTrace.events(thread.id,request.runId).single{it.event.code==WorkTraceCode.PLAN_READY}.event
        val stored=checkNotNull(event.publicSummary)
        assertTrue("Credential assignment must be masked before preview clipping",stored.contains("[비공개]"))
        assertFalse("A synthetic credential word remained in the persisted preview",secretWords.any{stored.contains(it)})
        assertEquals(WorkSummarySource.USER_GOAL,event.summarySource)
        assertEquals(0,missing.get())
    }}

    @Test fun repeatedModelCallIdProducesDistinctHostInvocationsAndActualNoteReceipts()=runBlocking(Dispatchers.IO){fixture{repo,db,_->
        val thread=repo.chat.createThread()
        val request=request(repo,thread)
        val missing=AtomicInteger()
        val session=checkNotNull(ChatWorkTraceSession.open(repo,request){missing.incrementAndGet()})
        session.stage(WorkTraceCode.RUN_STARTED)
        val scoped=repo.forThread(thread.id)
        val attempts=mutableListOf<ChatWorkTraceSession.Attempt>()
        val outcomes=mutableListOf<ToolResult>()
        val notes=mutableListOf<NoteRecord>()
        for(index in 1..2){
            val title="Synthetic trace note $index";val body="Synthetic independent body $index 한글🙂"
            val call=ToolCall("same-model-call-id","save_note",buildJsonObject{put("title",title);put("content",body);put("scope","session-qa")})
            val attempt=session.attempt(call).also{attempts+=it}
            assertEquals(call,attempt.actual)
            assertEquals(call.arguments,attempt.host.arguments)
            assertNotEquals(call.id,attempt.host.id)
            UUID.fromString(attempt.host.id)
            session.start(attempt)
            val note=scoped.saveNote(title,body,"session-qa").also{notes+=it}
            assertEquals(note,scoped.getNote(note.id))
            val result=ToolResult(call.id,true,buildJsonObject{put("noteId",note.id);put("verified",true)}.toString(),VerificationState.VERIFIED,"Synthetic note saved and read back")
            outcomes+=result
            assertTrue(session.outcome(attempt,result))
        }
        assertEquals(2,attempts.map{it.host.id}.distinct().size)
        assertEquals(2,notes.map{it.id}.distinct().size)
        val events=repo.workTrace.events(thread.id,request.runId)
        val starts=events.filter{it.event.code==WorkTraceCode.TOOL_STARTED}
        val results=events.filter{it.event.code==WorkTraceCode.TOOL_RESULT}
        assertEquals(2,starts.size);assertEquals(2,results.size)
        assertEquals(2,starts.map{it.event.argsHash}.distinct().size)
        assertEquals(attempts.map{it.host.id}.toSet(),results.map{it.event.invocationId}.toSet())
        assertEquals(2,results.map{it.event.auditId}.distinct().size)
        for(result in results){
            val audit=checkNotNull(db.dao().toolRunById(checkNotNull(result.event.auditId)))
            assertTrue(audit.success);assertEquals("save_note",audit.name)
            assertEquals(request.runId,audit.runId);assertEquals(thread.id,audit.threadId)
            val receipt=WorkTraceReceiptCodec.decode(checkNotNull(db.dao().toolRunById(WorkTraceReceiptCodec.receiptId(audit.id))))
            assertEquals(result.event.invocationId,receipt.invocationId)
            assertEquals(result.event.argsHash,receipt.argsHash)
            assertEquals(result.event.scopeHash,receipt.scopeHash)
            assertEquals(VerificationState.VERIFIED,receipt.state)
            assertEquals(starts.single{it.event.invocationId==receipt.invocationId}.id,receipt.startEventId)
        }
        // Journal retries may not run the real note action or create another receipt/event.
        assertTrue(session.outcome(attempts.first(),outcomes.first()))
        assertEquals(events,repo.workTrace.events(thread.id,request.runId))
        assertEquals(2,repo.toolAuditMetadata(0,Long.MAX_VALUE,100).size)
        assertEquals(0,missing.get())
    }}

    @Test fun actualPrivateFileCreateAndEditProduceBoundBeforeAfterHashesWithoutExecutionClaims()=runBlocking(Dispatchers.IO){fixture{repo,db,context->
        val project=repo.chat.createProject("Synthetic trace workspace")
        val thread=repo.chat.createThread(project.id)
        val workspace=ProjectWorkspace(context,project.workspaceKey)
        val directory=File(context.filesDir,"projects/${project.workspaceKey}").canonicalFile
        try{
            val fingerprint=workspace.bindingFingerprint()
            val request=request(repo,thread,fingerprint)
            val missing=AtomicInteger()
            val session=checkNotNull(ChatWorkTraceSession.open(repo,request){missing.incrementAndGet()})
            val contents=listOf("Synthetic initial file 한글🙂","Synthetic edited file 한글🙂")
            var before:String?=null
            for(content in contents){
                val call=ToolCall("repeated-file-call","write_project_file",buildJsonObject{
                    put("path","sample.txt");put("content",content);before?.let{put("expectedSha256",it)}
                })
                val attempt=session.attempt(call)
                session.start(attempt)
                val returned=workspace.write("sample.txt",content,before)
                val result=Json.parseToJsonElement(returned).jsonObject
                assertTrue(result.getValue("verified").jsonPrimitive.boolean)
                assertFalse(result.getValue("executed").jsonPrimitive.boolean)
                assertEquals(content,workspace.read("sample.txt"))
                assertTrue(session.outcome(attempt,ToolResult(call.id,true,returned,VerificationState.VERIFIED,"Synthetic private file readback verified")))
                before=result.getValue("sha256").jsonPrimitive.content
            }
            val changes=repo.workTrace.events(thread.id,request.runId).filter{it.event.code==WorkTraceCode.FILE_CHANGE}
            assertEquals(2,changes.size)
            val first=checkNotNull(changes[0].event.fileChange);val second=checkNotNull(changes[1].event.fileChange)
            assertEquals(WorkFileOperation.CREATED,first.operation);assertNull(first.beforeSha256)
            assertEquals(WorkFileOperation.MODIFIED,second.operation)
            assertEquals(ProjectWorkspace.sha256(contents[0]),first.afterSha256)
            assertEquals(first.afterSha256,second.beforeSha256)
            assertEquals(ProjectWorkspace.sha256(contents[1]),second.afterSha256)
            assertEquals(contents[1].toByteArray(Charsets.UTF_8).size.toLong(),second.bytes)
            assertEquals(fingerprint,second.workspaceBinding);assertNull(second.diffRef)
            for(change in changes){
                val receipt=WorkTraceReceiptCodec.decode(checkNotNull(db.dao().toolRunById(WorkTraceReceiptCodec.receiptId(checkNotNull(change.event.auditId)))))
                assertEquals(WorkTraceReceiptCodec.fileDigest(checkNotNull(change.event.fileChange)),receipt.fileDigest)
            }
            val invalid=session.attempt(ToolCall("forged-file-result","write_project_file",buildJsonObject{
                put("path","sample.txt");put("content","Synthetic unsaved replacement");put("expectedSha256",checkNotNull(before))
            }))
            session.start(invalid)
            val forged=buildJsonObject{
                put("path","sample.txt");put("sha256","0".repeat(64));put("bytes",29)
                put("verified",true);put("executed",false)
            }
            assertFalse(session.outcome(invalid,ToolResult(invalid.actual.id,true,forged.toString(),VerificationState.VERIFIED,"Synthetic invalid file result")))
            assertEquals(contents.last(),workspace.read("sample.txt"))
            assertEquals(2,repo.workTrace.events(thread.id,request.runId).count{it.event.code==WorkTraceCode.FILE_CHANGE})
            assertEquals(2,repo.toolAuditMetadata(0,Long.MAX_VALUE,100).size)
            assertEquals(1,missing.get())
        }finally{
            val projects=File(context.filesDir,"projects").canonicalFile
            check(directory.parentFile==projects&&directory.name==project.workspaceKey)
            val ownNames=setOf("sample.txt","sample.txt.bak","sample.txt.new")
            directory.listFiles().orEmpty().forEach{file->check(file.isFile&&file.name in ownNames);check(file.delete())}
            check(!directory.exists()||directory.delete())
            context.deleteSharedPreferences("project-workspace-v1-${project.workspaceKey}")
        }
    }}

    @Test fun failedToolWrongResultIdAndPausedGenerationCannotBecomeVerifiedCompletion()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val thread=repo.chat.createThread();val request=request(repo,thread)
        val missing=AtomicInteger()
        val session=checkNotNull(ChatWorkTraceSession.open(repo,request){missing.incrementAndGet()})
        session.stage(WorkTraceCode.RUN_STARTED)
        fun call(id:String)=ToolCall(id,"save_note",buildJsonObject{put("content","Synthetic unsaved body");put("title","Synthetic failure")})
        val failed=session.attempt(call("failed-model-call"));session.start(failed)
        assertTrue(session.outcome(failed,ToolResult(failed.actual.id,false,"Synthetic tool failure",VerificationState.FAILED,"Synthetic save did not occur")))
        val mismatched=session.attempt(call("expected-model-call"));session.start(mismatched)
        assertFalse(session.outcome(mismatched,ToolResult("wrong-result-id",true,"Synthetic unrelated result",VerificationState.VERIFIED,"Not this invocation")))
        session.beginGeneration("synthetic-no-engine",InferenceOptions(maxOutputTokens=32),visibleAnswer=true)
        repo.chat.updateRun(request.runId,"paused")
        session.finishGeneration(GenerationSummary("synthetic-stream",GenerationStopReason.SDK_DONE_UNKNOWN,GenerationEvidence.SDK,null,32,visibleChars=24),paused=true,visibleAnswer=true)
        session.finishFromActualState("paused")
        val events=repo.workTrace.events(thread.id,request.runId)
        assertEquals("paused",repo.chat.run(request.runId)?.status)
        assertEquals(1,events.count{it.event.code==WorkTraceCode.TOOL_RESULT})
        assertEquals(VerificationState.FAILED,events.single{it.event.code==WorkTraceCode.TOOL_RESULT}.event.evidence)
        assertTrue(events.none{it.event.evidence==VerificationState.VERIFIED})
        assertTrue(events.any{it.event.code==WorkTraceCode.ANSWER_PAUSED})
        assertTrue(events.none{it.event.code in setOf(WorkTraceCode.RUN_FINISHED,WorkTraceCode.ANSWER_FINISHED)})
        assertTrue(events.single{it.event.code==WorkTraceCode.INFERENCE_FINISHED}.event.publicSummary!!.contains("SDK_DONE_UNKNOWN"))
        assertTrue(repo.forThread(thread.id).notes.first().isEmpty())
        assertEquals(1,repo.toolAuditMetadata(0,Long.MAX_VALUE,100).size)
        assertEquals(1,missing.get())
    }}

    private suspend fun request(repo:LocalRepository,thread:ChatThreadRecord,workspace:String?=null):QueuedChatRequest{
        val user=repo.chat.addMessage(thread.id,thread.activeBranchId,"user","Synthetic host trace request")
        val run=repo.chat.createRun(thread.id,thread.activeBranchId,user.id)
        repo.chat.startRun(run.id)
        return QueuedChatRequest(run.id,thread.id,thread.activeBranchId,thread.projectId,user.text,"synthetic-no-engine",false,emptyList(),user.id,workspaceBinding=workspace)
    }
    private suspend fun fixture(block:suspend(LocalRepository,LocalDatabase,Context)->Unit){
        assumeTrue("workTraceSessionApproved=true required",InstrumentationRegistry.getArguments().getString("workTraceSessionApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="trace-session-qa-${UUID.randomUUID()}.db";val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try{block(LocalRepository(db),db,context)}finally{db.close();context.deleteDatabase(name);key.fill(0)}
    }
}
