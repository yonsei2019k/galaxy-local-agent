package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.VerificationState
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

// Explicitly gated synthetic SQLCipher databases/files only; no owner data or model calls.
@RunWith(AndroidJUnit4::class)
class WorkTraceValidation {
    private val args="1".repeat(64);private val scope="2".repeat(64)
    private fun started(tool:String="save_note",call:String="synthetic-call")=WorkTraceDraft("tool.$call.start",WorkTraceKind.TOOL,WorkTraceCode.TOOL_STARTED,tool,call,argsHash=args,scopeHash=scope)

    @Test fun actualReadbackAuditRequiredAndProgressIdsAreIdempotent()=runBlocking(Dispatchers.IO){fixture{repo,db,_,_->
        val thread=repo.chat.createThread();val run=repo.chat.createRun(thread.id,thread.activeBranchId);repo.chat.startRun(run.id)
        val traces=WorkTraceStore(WorkTraceRoomStorage(db));val binding=traces.open(run.id,thread.id)
        val start=traces.append(binding,started())
        assertEquals(start,traces.append(binding,started()))
        val proposed=WorkTraceDraft("tool.synthetic-call.result",WorkTraceKind.TOOL,WorkTraceCode.TOOL_RESULT,"save_note","synthetic-call",VerificationState.VERIFIED,"missing-audit",args,scope)
        assertTrue(runCatching{traces.append(binding,proposed)}.isFailure)
        val scoped=repo.forThread(thread.id);val note=scoped.saveNote("Synthetic trace note","SYNTHETIC_READBACK_BODY")
        assertEquals(note,scoped.getNote(note.id))
        val audit=traces.recordOutcome(binding,start.id,true,VerificationState.VERIFIED,"synthetic note readback").audit
        val secondStart=traces.append(binding,started(call="synthetic-b").copy(argsHash="3".repeat(64)))
        assertNotEquals(start.id,secondStart.id)
        assertTrue("An A receipt must not verify B even before A's result trace is recorded",runCatching{traces.append(binding,proposed.copy(operationKey="tool.synthetic-b.result",invocationId="synthetic-b",argsHash="3".repeat(64),auditId=audit.id))}.isFailure)
        val result=traces.append(binding,proposed.copy(auditId=audit.id))
        assertEquals(VerificationState.VERIFIED,result.event.evidence)
        assertEquals(3,traces.events(thread.id,run.id).size)
        assertFalse(db.dao().toolRunById(result.id)!!.success)
        assertEquals(listOf(audit.id),repo.toolAuditMetadata(0,Long.MAX_VALUE,100).map{it.id})
        assertTrue(runCatching{traces.append(binding,started().copy(publicSummary="different same event"))}.isFailure)
        val prepared=ToolRunRecord(name="save_note",success=true,summary="PREPARED · synthetic draft only",runId=run.id,threadId=thread.id);db.dao().toolRun(prepared)
        assertTrue(runCatching{traces.append(binding,proposed.copy(operationKey="forged.verification",auditId=prepared.id))}.isFailure)
    }}

    @Test fun otherRunAuditAndLateEventsAfterClearCannotForgeOrResurrectProgress()=runBlocking(Dispatchers.IO){fixture{repo,db,_,_->
        val a=repo.chat.createThread();val b=repo.chat.createThread()
        val ra=repo.chat.createRun(a.id,a.activeBranchId);val rb=repo.chat.createRun(b.id,b.activeBranchId);repo.chat.startRun(ra.id);repo.chat.startRun(rb.id)
        val traces=WorkTraceStore(WorkTraceRoomStorage(db));val binding=traces.open(ra.id,a.id);traces.append(binding,started())
        val foreign=ToolRunRecord(name="save_note",success=true,summary="VERIFIED · synthetic other run",runId=rb.id,threadId=b.id);db.dao().toolRun(foreign)
        assertTrue(runCatching{traces.append(binding,WorkTraceDraft("foreign.result",WorkTraceKind.TOOL,WorkTraceCode.TOOL_RESULT,"save_note","synthetic-call",VerificationState.VERIFIED,foreign.id,args,scope))}.isFailure)
        assertTrue(runCatching{traces.open(ra.id,b.id)}.isFailure)
        repo.chat.clearThread(a.id)
        assertTrue(traces.observe(a.id,listOf(ra.id)).first().isEmpty())
        assertTrue(runCatching{traces.append(binding,started().copy(operationKey="late.start"))}.isFailure)
        assertTrue(traces.events(a.id,ra.id).isEmpty())
        assertEquals(foreign,db.dao().toolRunById(foreign.id))
    }}

    @Test fun latestTraceSurvivesLegacyAuditVolumeTenMinutesAndDatabaseReopen()=runBlocking(Dispatchers.IO){fixture{repo,db,traces,reopen->
        val thread=repo.chat.createThread();val run=repo.chat.createRun(thread.id,thread.activeBranchId);repo.chat.startRun(run.id)
        val binding=traces.open(run.id,thread.id)
        val first=traces.append(binding,WorkTraceDraft("run.started",WorkTraceKind.RUN,WorkTraceCode.RUN_STARTED))
        db.withTransaction{repeat(1001){index->db.dao().toolRun(ToolRunRecord(name="legacy_qa",success=true,summary="Synthetic legacy audit",at=first.atEpochMillis+index,runId=run.id,threadId=thread.id))}}
        db.openHelper.writableDatabase.execSQL("UPDATE tool_runs SET at=at-600001 WHERE id=?",arrayOf<Any?>(first.id))
        val second=traces.append(binding,WorkTraceDraft("semantic.started",WorkTraceKind.RATIONALE,WorkTraceCode.SEMANTIC_STARTED))
        val restored=reopen();val actual=restored.events(thread.id,run.id)
        assertEquals(listOf(first.id,second.id),actual.map{it.id});assertTrue(actual.last().atEpochMillis-actual.first().atEpochMillis>=600000)
        assertEquals(actual,restored.observe(thread.id,listOf(run.id)).first())
    }}

    @Test fun summaryPreviewMasksPrivateTokensAndUnknownDuplicateOrWrongTypeFieldsFail()=runBlocking(Dispatchers.IO){fixture{repo,db,traces,_->
        val thread=repo.chat.createThread();val run=repo.chat.createRun(thread.id,thread.activeBranchId);repo.chat.startRun(run.id)
        val binding=traces.open(run.id,thread.id)
        val event=WorkTraceDraft("plan.ready",WorkTraceKind.PLAN,WorkTraceCode.PLAN_READY,publicSummary="계획 password=synthetic-secret tester@example.invalid https://example.invalid/?token=synthetic-token 010-1234-5678 \"api_key\":\"synthetic quoted secret\" token='another multi word secret' Authorization: Bearer abcdefgh",summarySource=WorkSummarySource.MODEL_PLAN)
        val saved=traces.append(binding,event);val summary=saved.event.publicSummary!!
        for(secret in listOf("synthetic-secret","tester@example.invalid","synthetic-token","010-1234-5678","synthetic quoted secret","another multi word secret","abcdefgh"))assertFalse(summary.contains(secret))
        assertEquals(summary,WorkTraceCodec.maskPreview(summary))
        assertTrue(runCatching{traces.append(binding,event.copy(operationKey="bad.thought",publicSummary="<think>hidden reasoning</think>"))}.isFailure)
        val row=db.dao().toolRunById(saved.id)!!
        for(body in listOf(row.summary.replace("\"kind\":\"PLAN\"","\"kind\":\"UNKNOWN\""),row.summary.replace("\"v\":1","\"v\":\"1\""),row.summary.replaceFirst("{","{\"v\":1,"))){
            assertTrue(runCatching{WorkTraceCodec.decode(row.copy(summary=body))}.isFailure)
        }
    }}

    @Test fun fileChangeReferencesOnlyActualVerifiedWorkspaceHashAndCannotInventDiff()=runBlocking(Dispatchers.IO){fixture{repo,db,traces,_->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val workspaceId="traceqa-${UUID.randomUUID()}";val workspace=ProjectWorkspace(context,workspaceId)
        try {
            val before=Json.parseToJsonElement(workspace.write("sample.txt","Synthetic before")).jsonObject
            workspace.read("sample.txt")
            val thread=repo.chat.createThread();val run=repo.chat.createRun(thread.id,thread.activeBranchId);repo.chat.startRun(run.id)
            val binding=traces.open(run.id,thread.id);val start=traces.append(binding,started("write_project_file"))
            val after=Json.parseToJsonElement(workspace.write("sample.txt","Synthetic after",before.getValue("sha256").jsonPrimitive.content)).jsonObject
            assertTrue(after.getValue("verified").jsonPrimitive.boolean);assertFalse(after.getValue("executed").jsonPrimitive.boolean)
            val file=WorkFileChange("sample.txt",WorkFileOperation.MODIFIED,before.getValue("sha256").jsonPrimitive.content,after.getValue("sha256").jsonPrimitive.content,after.getValue("bytes").jsonPrimitive.long,workspace.bindingFingerprint())
            val audit=traces.recordOutcome(binding,start.id,true,VerificationState.VERIFIED,"synthetic file readback",file).audit
            val draft=WorkTraceDraft("file.change",WorkTraceKind.FILE,WorkTraceCode.FILE_CHANGE,"write_project_file","synthetic-call",VerificationState.VERIFIED,audit.id,args,scope,fileChange=file)
            assertEquals(file,traces.append(binding,draft).event.fileChange)
            assertTrue("A verified audit cannot acquire a different file payload",runCatching{traces.append(binding,draft.copy(operationKey="file.forged.bytes",fileChange=file.copy(bytes=file.bytes+1)))}.isFailure)
            assertTrue(runCatching{traces.append(binding,draft.copy(operationKey="file.fake.diff",fileChange=file.copy(diffRef="not-an-actual-snapshot")))}.isFailure)
        } finally {
            val directory=File(context.filesDir,"projects/$workspaceId").canonicalFile
            check(directory.parentFile==File(context.filesDir,"projects").canonicalFile&&directory.name==workspaceId)
            File(directory,"sample.txt").delete();directory.delete()
        }
    }}

    @Test fun uncappedRunUsesIndependentRecentWindowsPagedHistoryAndActualTerminalState()=runBlocking(Dispatchers.IO){fixture{repo,_,traces,_->
        val thread=repo.chat.createThread();val hot=repo.chat.createRun(thread.id,thread.activeBranchId);val quiet=repo.chat.createRun(thread.id,thread.activeBranchId)
        repo.chat.startRun(hot.id);repo.chat.startRun(quiet.id)
        val hb=traces.open(hot.id,thread.id);val qb=traces.open(quiet.id,thread.id)
        repeat(141){index->traces.append(hb,WorkTraceDraft("context.$index",WorkTraceKind.CONTEXT,WorkTraceCode.CONTEXT_READY,count=index))}
        traces.append(qb,WorkTraceDraft("quiet.context",WorkTraceKind.CONTEXT,WorkTraceCode.CONTEXT_READY))
        val windows=traces.observeWindows(thread.id,listOf(hot.id,quiet.id),16).first{it.sumOf{window->window.events.size}==17}
        assertEquals(16,windows.first{it.runId==hot.id}.events.size);assertEquals(1,windows.first{it.runId==quiet.id}.events.size)
        assertTrue(windows.first{it.runId==hot.id}.hasOlder);assertEquals(141L,windows.first{it.runId==hot.id}.totalCount)
        val first=traces.page(thread.id,hot.id);val second=traces.page(thread.id,hot.id,first.nextCursor)
        assertEquals(141,(first.events+second.events).map{it.id}.distinct().size);assertNull(second.nextCursor)
        assertTrue(first.events.zipWithNext().all{(a,b)->b.sequence>a.sequence&&b.atEpochMillis>a.atEpochMillis})
        assertTrue(runCatching{traces.append(hb,WorkTraceDraft("false.finished",WorkTraceKind.RUN,WorkTraceCode.RUN_FINISHED))}.isFailure)
        repo.chat.updateRun(hot.id,"completed")
        traces.append(hb,WorkTraceDraft("actual.finished",WorkTraceKind.RUN,WorkTraceCode.RUN_FINISHED))
        assertTrue(runCatching{traces.append(hb,WorkTraceDraft("false.interrupted",WorkTraceKind.RUN,WorkTraceCode.RUN_INTERRUPTED))}.isFailure)
        assertEquals(setOf(hot.id,quiet.id),traces.observeRunRows(thread.id,listOf(hot.id,quiet.id)).first().map{it.id}.toSet())
    }}

    private suspend fun fixture(block:suspend(LocalRepository,LocalDatabase,WorkTraceStore,suspend()->WorkTraceStore)->Unit){
        assumeTrue("workTraceValidationApproved=true required",InstrumentationRegistry.getArguments().getString("workTraceValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="work-trace-qa-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also{SecureRandom().nextBytes(it)};System.loadLibrary("sqlcipher")
        fun open()=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone())).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        var database=open()
        try {block(LocalRepository(database),database,WorkTraceStore(WorkTraceRoomStorage(database))){database.close();database=open();WorkTraceStore(WorkTraceRoomStorage(database))}}
        finally {database.close();key.fill(0);context.deleteDatabase(name)}
    }
}
