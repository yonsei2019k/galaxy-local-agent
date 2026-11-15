package com.user.localagent

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.core.RequiredReadbackObligations as Ledger
import com.user.localagent.data.*
import com.user.localagent.imports.ConversationImport
import com.user.localagent.imports.PhoneRecords
import com.user.localagent.notes.*
import com.user.localagent.tools.ToolExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** CANDIDATE, NOT RUN. Real isolated encrypted Room + actual ToolExecutor. No model/provider calls.
 * confirmNoteOwner is an explicit fixture consent seam, NOT evidence of the real approval UI/coordinator.
 * Never opens local-agent.db. Explicit gate is required even for these synthetic-only databases. */
@RunWith(AndroidJUnit4::class)
class NoteReadPatchDeviceValidation {
    private class Fixture(val repo:LocalRepository,val scoped:LocalRepository,val db:LocalDatabase,
        val thread:ChatThreadRecord,val run:ChatRunRecord,val seed:NoteRecord,val binding:NoteReadBinding) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        fun host(transactions:NotePatchTransactions=scoped.notePatchTransactions(binding))=NoteReadPatchHost(binding,NotePatchStore(binding,transactions)) {
            currentCoroutineContext().ensureActive();check(repo.chat.run(run.id)?.status=="running")
            check(repo.chat.accessScope(thread.id).scopeKey==binding.scopeKey)
        }
        fun tools(host:NoteReadPatchHost)=ToolExecutor(context,scoped,app.scheduler,ConversationImport(context,scoped),PhoneRecords(context),noteReadPatch=host)
            .also{it.bindAvailableTools(setOf("read_note","patch_note"))}
        fun read(note:NoteRecord=seed,offset:Int=0,limit:Int=900)=ToolCall("qa-read-$offset","read_note",buildJsonObject{
            put("id",note.id);put("scope",note.collection);put("expectedUpdatedAt",note.updatedAt.toString());put("offset",offset);put("limit",limit)
        })
        fun patch(old:String="needle",new:String="changed",note:NoteRecord=seed)=ToolCall("qa-patch","patch_note",buildJsonObject{
            put("id",note.id);put("scope",note.collection);put("expectedUpdatedAt",note.updatedAt.toString());put("oldText",old);put("newText",new)
        })
        suspend fun deliver(tools:ToolExecutor,call:ToolCall=read()):ToolResult {
            val r=tools.execute(call);assertTrue(r.content,r.success);assertEquals(VerificationState.VERIFIED,r.verification)
            val entry=buildJsonObject{put("tool","read_note");put("success",true);put("verification","VERIFIED");put("result",Json.parseToJsonElement(NoteReadModelDelivery.modelResult(r.content)))}
            tools.acceptNoteReadContext(listOf(entry),JsonArray(listOf(entry)).toString());return r
        }
        suspend fun approve(tools:ToolExecutor,call:ToolCall=patch()) {
            deliver(tools);val preview=checkNotNull(tools.reviewForAction(call))
            assertTrue(preview.contains(seed.title));assertTrue(preview.contains("beforeSha256"));assertTrue(preview.contains("newText"))
            tools.confirmNoteOwner(call)
        }
    }

    private suspend fun fixture(body:String="needle\r\n"+"x".repeat(1600)+"\r\n😀 tail e\u0301",block:suspend(Fixture,suspend()->LocalRepository)->Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("notePatchValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="note-patch-qa-${UUID.randomUUID()}.db";val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher");var database:LocalDatabase?=null
        fun open():LocalDatabase=Room.databaseBuilder(context,LocalDatabase::class.java,name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .addMigrations(KnowledgeMigrations.MIGRATION_1_2,ChatMigrations.MIGRATION_2_3)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val db=open().also{database=it};db.openHelper.writableDatabase
            val repo=LocalRepository(db);val p=repo.chat.createProject("QA isolated note patch",ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false))
            val thread=repo.chat.createThread(p.id,"QA isolated note patch")
            val user=repo.chat.addMessage(thread.id,thread.activeBranchId,"user","QA synthetic literal edit")
            val run=repo.chat.createRun(thread.id,thread.activeBranchId,user.id);repo.chat.startRun(run.id)
            val scoped=repo.forThread(thread.id);val seed=scoped.saveNote("QA isolated note",body,"qa-note-patch")
            val binding=NoteReadBinding(run.id,thread.id,thread.activeBranchId,repo.chat.accessScope(thread.id).scopeKey)
            block(Fixture(repo,scoped,db,thread,run,seed,binding)) {
                database?.close();val reopened=open().also{database=it};reopened.openHelper.writableDatabase;LocalRepository(reopened)
            }
        }finally{database?.close();key.fill(0);check(name.startsWith("note-patch-qa-"));check(context.deleteDatabase(name)){"QA_DATABASE_CLEANUP_FAILED"}}
    }

    @Test fun exactPartialSpanEditPreservesActualUnreadTailAndDurableReceiptAcrossReopen()=runBlocking(Dispatchers.IO){fixture{f,reopen->
        val tools=f.tools(f.host());val before=f.seed;val patch=f.patch();f.approve(tools,patch)
        val r=tools.execute(patch);assertTrue(r.content,r.success);assertEquals(VerificationState.VERIFIED,r.verification)
        val actual=checkNotNull(f.scoped.getNote(before.id));assertEquals(before.body.replaceFirst("needle","changed"),actual.body)
        assertEquals(before.title,actual.title);assertEquals(before.collection,actual.collection);assertTrue(actual.updatedAt>before.updatedAt)
        val receipt=NotePatchReceipt.parse(r.content);assertEquals(actual.updatedAt,receipt.updatedAt)
        val reopened=reopen();val scoped=reopened.forThread(f.thread.id)
        assertEquals(actual,scoped.getNote(before.id))
        val cold=NoteReadPatchHost(f.binding,NotePatchStore(f.binding,scoped.notePatchTransactions(f.binding))){}
        assertEquals(r.content,checkNotNull(cold.recover(patch)).content)
        assertEquals(1,scoped.notes.first().size)
    }}
    @Test fun actualExecutorRequiresCurrentDeliveredTextAndSeparateConfirmation()=runBlocking(Dispatchers.IO){fixture{f,_->
        val tools=f.tools(f.host());val patch=f.patch();assertTrue(tools.execute(f.read()).success)
        assertTrue(runCatching{tools.reviewForAction(patch)}.isFailure) // raw storage read was not delivered to a model.
        f.deliver(tools);assertNotNull(tools.reviewForAction(patch));assertFalse(tools.execute(patch).success)
        assertEquals(f.seed,f.scoped.getNote(f.seed.id));assertEquals(1,f.scoped.notes.first().size)
    }}
    @Test fun actualTimestampAndHashConflictAfterConfirmationDoesNotOverwrite()=runBlocking(Dispatchers.IO){fixture{f,_->
        val tools=f.tools(f.host());val patch=f.patch();f.approve(tools,patch)
        val other=f.scoped.saveNote(f.seed.title,f.seed.body+" changed elsewhere",f.seed.collection,f.seed.id,f.seed.updatedAt)
        assertFalse(tools.execute(patch).success);assertEquals(other,f.scoped.getNote(f.seed.id))
    }}
    @Test fun actualRoomRollsBackBodyWhenReceiptPersistenceFails()=runBlocking(Dispatchers.IO){fixture{f,_->
        val real=f.scoped.notePatchTransactions(f.binding)
        val fault=object:NotePatchTransactions {
            override suspend fun <T> transaction(requireRunning:Boolean,block:suspend NotePatchTransaction.()->T):T=real.transaction(requireRunning){
                val actual=this
                block(object:NotePatchTransaction by actual {
                    override suspend fun putReceipt(operationId:String,json:String){error("QA_RECEIPT_WRITE_FAULT")}
                })
            }
        }
        val tools=f.tools(f.host(fault));f.approve(tools)
        assertFalse(tools.execute(f.patch()).success);assertEquals(f.seed,f.scoped.getNote(f.seed.id))
        f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tool_runs WHERE runId=? AND name=?",arrayOf(f.run.id,NOTE_PATCH_RECEIPT)).use{cursor->
            assertTrue(cursor.moveToFirst());assertEquals(0L,cursor.getLong(0))
        }
    }}
    @Test fun actualProjectScopeAndProtectedCollectionAreNotPatchAuthority()=runBlocking(Dispatchers.IO){fixture{f,_->
        val p=f.repo.chat.createProject("QA other project");val other=f.repo.chat.createThread(p.id)
        val foreign=checkNotNull(f.repo.forThread(other.id)).saveNote("QA foreign","needle","qa-note-patch")
        val tools=f.tools(f.host());assertFalse(tools.execute(f.read(foreign)).success)
        val protected=f.seed.copy(collection=InternalNoteCollections.protected.first());f.db.dao().note(protected)
        assertFalse(tools.execute(f.read(protected)).success);assertEquals(protected,f.scoped.getNote(protected.id))
    }}
    @Test fun actualCommitAfterCancellationStillHasRecoverableReceipt()=runBlocking(Dispatchers.IO){fixture{f,_->
        val real=f.scoped.notePatchTransactions(f.binding);val job=Job()
        val cancelAfterWrite=object:NotePatchTransactions {
            override suspend fun <T> transaction(requireRunning:Boolean,block:suspend NotePatchTransaction.()->T):T=real.transaction(requireRunning){
                val actual=this
                block(object:NotePatchTransaction by actual {
                    override suspend fun putPreservingMetadata(before:NoteSource,body:String)=actual.putPreservingMetadata(before,body).also{job.cancel()}
                })
            }
        }
        val tools=f.tools(f.host(cancelAfterWrite));val patch=f.patch();f.approve(tools,patch)
        try{withContext(job){tools.execute(patch)};fail("cancellation expected")}catch(_:CancellationException){}
        val receipt=checkNotNull(tools.recoverNotePatch(patch));assertEquals(VerificationState.VERIFIED,receipt.verification)
        assertEquals(f.seed.body.replaceFirst("needle","changed"),f.scoped.getNote(f.seed.id)?.body)
    }}
    @Test fun actualQueueRoundtripPreservesPendingPairButDoesNotRestoreReadOrWritePermission()=runBlocking(Dispatchers.IO){fixture{f,_->
        val tools=f.tools(f.host());val patch=f.patch();f.approve(tools,patch);val result=tools.execute(patch);assertTrue(result.success)
        val outcomes=listOf(ObservedToolOutcome(patch,result));val steps=listOf(HarnessStep(patch,StepEvidence(patch.name,result.verification,"QA actual Room receipt")))
        val context=Ledger.Context(f.run.id,f.thread.id,f.thread.projectId,f.binding.scopeKey,null)
        val pair=setOf("patch_note","read_note");val collected=ChatReadbackEvidence.collect(context.proofBinding(),pair,steps,outcomes)
        val selected=checkNotNull(Ledger.select(null,context,pair));val ledger=checkNotNull(Ledger.capture(selected,context,steps,collected.mutations,collected.reads,collected.aligned))
        val queued=QueuedChatRequest(f.run.id,f.thread.id,f.thread.activeBranchId,f.thread.projectId,"QA read-only resume","synthetic-no-model",false,emptyList(),"qa-user",allowTools=false,mode="plan",previousSteps=steps,readbackObligations=ledger)
        val restored=QueuedChatRequest.fromJson(queued.toJson());assertFalse(restored.allowTools);assertEquals("plan",restored.mode)
        assertEquals(ledger,restored.readbackObligations);assertFalse(Ledger.assess(restored.readbackObligations,context,steps,1,setOf("read_note"),17,3).readbackSatisfied)
        assertFalse(AgentWorkModePolicy.allows(AgentWorkMode.PLAN,"patch_note"))
        val cold=f.tools(f.host());val latest=checkNotNull(f.scoped.getNote(f.seed.id))
        assertTrue(runCatching{cold.reviewForAction(f.patch(old="changed",new="unapproved",note=latest))}.isFailure)
    }}
    /** Actual isolated providers, with synthetic input/return acknowledgement; no actual inference claim. */
    @Test fun actualReadbackNeedsAllAfterPagesDeliveredAndAcknowledged()=runBlocking(Dispatchers.IO){fixture{f,_->
        val tools=f.tools(f.host());val patch=f.patch();f.approve(tools,patch)
        val written=tools.execute(patch);assertTrue(written.success)
        val latest=checkNotNull(f.scoped.getNote(f.seed.id));val outcomes=mutableListOf(ObservedToolOutcome(patch,written))
        val proof=RequiredReadbackPolicy.Binding(f.run.id,f.binding.scopeKey,null)
        fun complete():Boolean {
            val steps=outcomes.map{HarnessStep(it.call,StepEvidence(it.call.name,it.result.verification,"QA actual isolated provider"))}
            return ChatReadbackEvidence.assess(proof,setOf("patch_note","read_note"),setOf("patch_note","read_note"),steps,outcomes,3,
                deliveredNoteReadKeys=tools.deliveredNoteReadKeys()).readbackSatisfied
        }
        var offset=0
        while(true){
            val call=f.read(latest,offset);val observed=tools.execute(call);assertTrue(observed.success)
            outcomes+=ObservedToolOutcome(call,observed)
            assertFalse(complete()) // This newly received page has not reached a model input/return yet.
            val entry=buildJsonObject{put("tool","read_note");put("success",true);put("verification","VERIFIED");put("result",Json.parseToJsonElement(NoteReadModelDelivery.modelResult(observed.content)))}
            val batch=checkNotNull(tools.acceptNoteReadContext(listOf(entry),JsonArray(listOf(entry)).toString()))
            assertTrue(batch.hasPages);tools.acknowledgeNoteReadContext(batch)
            val page=Json.parseToJsonElement(observed.content).jsonObject
            if(!page.getValue("hasMore").jsonPrimitive.boolean)break
            assertFalse(complete());offset=page.getValue("nextOffset").jsonPrimitive.int
        }
        assertTrue(complete());assertEquals(latest,f.scoped.getNote(latest.id))
    }}
    @Test fun actualEmptyLiteralReplacementPreservesUnreadTail()=runBlocking(Dispatchers.IO){fixture{f,_->
        val tools=f.tools(f.host());val call=f.patch(new="");f.approve(tools,call)
        val result=tools.execute(call);assertTrue(result.success)
        val actual=checkNotNull(f.scoped.getNote(f.seed.id))
        assertEquals(f.seed.body.replaceFirst("needle",""),actual.body)
        assertEquals(f.seed.title,actual.title);assertEquals(f.seed.collection,actual.collection)
        assertTrue(actual.updatedAt>f.seed.updatedAt)
    }}
    @Test fun actualEmptySearchScopeMatchesOmittedWithinCapturedProjectWithoutProtectedNotes()=runBlocking(Dispatchers.IO){fixture{f,_->
        val query="QA scoped lookup ${UUID.randomUUID()}"
        val first=f.scoped.saveNote("$query first","QA first body","qa-search-first")
        val second=f.scoped.saveNote("$query second","QA second body","qa-search-second")
        val protected=f.scoped.saveNote("$query protected","QA protected body",InternalNoteCollections.protected.first())
        val project=f.repo.chat.createProject("QA other lookup project")
        val thread=f.repo.chat.createThread(project.id,"QA other lookup thread")
        val foreignRepo=f.repo.forThread(thread.id)
        val foreign=foreignRepo.saveNote("$query foreign","QA foreign body",first.collection)
        val owner=f.repo.saveNote("$query owner","QA owner body",first.collection)
        val tools=f.tools(f.host()).also{it.bindAvailableTools(setOf("search_notes","read_note","patch_note"))}
        fun lookup(scope:String?)=ToolCall("qa-search","search_notes",buildJsonObject{
            put("query",query);put("limit",5);if(scope!=null)put("scope",scope)
        })
        suspend fun search(scope:String?):ToolResult {
            val call=lookup(scope);ToolRegistry.validate(call)
            return tools.execute(call).also{assertTrue(it.content,it.success);assertEquals(VerificationState.VERIFIED,it.verification)}
        }
        fun details(result:ToolResult)=Json.parseToJsonElement(result.content).jsonObject.getValue("details").jsonObject
        fun ids(result:ToolResult)=details(result).getValue("notes").jsonArray.map{it.jsonObject.getValue("id").jsonPrimitive.content}.toSet()
        val omitted=search(null);val empty=search("")
        assertEquals(omitted.content,empty.content)
        assertEquals(setOf(first.id,second.id),ids(empty))
        assertEquals(2,details(empty).getValue("returnedCount").jsonPrimitive.int)
        assertEquals("",details(empty).getValue("collectionFilter").jsonPrimitive.content)
        assertTrue(ids(empty).intersect(setOf(protected.id,foreign.id,owner.id)).isEmpty())
        val filtered=search(first.collection)
        assertEquals(setOf(first.id),ids(filtered))
        assertEquals(first.collection,details(filtered).getValue("collectionFilter").jsonPrimitive.content)
        assertTrue(ids(search("qa-nonexistent-collection")).isEmpty()) // No fallback for an invented nonempty scope.
        assertTrue(ids(search(protected.collection)).isEmpty())
        for(blank in listOf(" ","\t\n")){
            assertTrue(runCatching{ToolRegistry.validate(lookup(blank))}.isFailure)
            assertFalse(tools.execute(lookup(blank)).success)
        }
        for(call in listOf(f.read(),f.patch()))for(blank in listOf(""," ","\t\n")){
            val invalid=call.copy(arguments=JsonObject(call.arguments+mapOf("scope" to JsonPrimitive(blank))))
            assertTrue(runCatching{ToolRegistry.validate(invalid)}.isFailure)
            assertFalse(tools.execute(invalid).success)
        }
        for(note in listOf(first,second,protected,f.seed))assertEquals(note,f.scoped.getNote(note.id))
        assertEquals(foreign,foreignRepo.getNote(foreign.id));assertEquals(owner,f.repo.getNote(owner.id))
    }}
    @Test fun actualPreviewRejectionIsUnappliedButReceiptFailureRemainsUncertain()=runBlocking(Dispatchers.IO){fixture{f,_->
        val tools=f.tools(f.host());f.deliver(tools)
        val rejected=f.patch(old="QA missing literal")
        val error=runCatching{tools.reviewForAction(rejected)}.exceptionOrNull()
        assertTrue(error is NotePatchPreviewRejected)
        assertEquals("OLD_TEXT_NOT_FOUND",error?.message)
        val result=NotePatchFailureEvidence.result(rejected,error as Exception)
        assertFalse(result.success);assertEquals(VerificationState.FAILED,result.verification);assertTrue(result.effectNotApplied)
        val failed=HarnessStep(rejected,StepEvidence(rejected.name,result.verification,result.verificationSummary,effectNotApplied=result.effectNotApplied))
        val goal=TaskGoal("QA exact observed note correction",listOf("Exact approved edit and readback"),6,setOf("patch_note"))
        assertTrue(TaskHarness.decide(goal,listOf(failed),true) is HarnessDecision.Stop)
        assertTrue(TaskHarness.beforeStep(goal,listOf(failed),f.patch()) is HarnessDecision.Advance)
        assertTrue(TaskHarness.beforeStep(goal,listOf(failed),rejected) is HarnessDecision.Stop) // Same-call limit remains.
        assertEquals(f.seed,f.scoped.getNote(f.seed.id))
        val real=f.scoped.notePatchTransactions(f.binding)
        val reconcileFault=object:NotePatchTransactions {
            override suspend fun <T> transaction(requireRunning:Boolean,block:suspend NotePatchTransaction.()->T):T {
                throw NotePatchRejected("QA_RECONCILE_FAULT")
            }
        }
        val reconcileError=runCatching{f.tools(f.host(reconcileFault)).reviewForAction(f.patch())}.exceptionOrNull()
        assertTrue(reconcileError is NotePatchRejected);assertFalse(reconcileError is NotePatchPreviewRejected)
        assertFalse(NotePatchFailureEvidence.result(f.patch(),reconcileError as Exception).effectNotApplied)
        val receiptFault=object:NotePatchTransactions {
            override suspend fun <T> transaction(requireRunning:Boolean,block:suspend NotePatchTransaction.()->T):T=real.transaction(requireRunning){
                val actual=this
                block(object:NotePatchTransaction by actual {
                    override suspend fun putReceipt(operationId:String,json:String){throw NotePatchRejected("QA_RECEIPT_FAULT")}
                })
            }
        }
        val uncertainTools=f.tools(f.host(receiptFault));f.approve(uncertainTools)
        val uncertain=uncertainTools.execute(f.patch())
        assertFalse(uncertain.success);assertEquals(VerificationState.FAILED,uncertain.verification);assertFalse(uncertain.effectNotApplied)
        val uncertainStep=HarnessStep(f.patch(),StepEvidence("patch_note",uncertain.verification,uncertain.verificationSummary,effectNotApplied=uncertain.effectNotApplied))
        assertTrue(TaskHarness.beforeStep(goal,listOf(uncertainStep),f.patch(new="other correction")) is HarnessDecision.Stop)
        assertTrue(TaskHarness.decide(goal,listOf(uncertainStep),true) is HarnessDecision.Stop)
        assertEquals(f.seed,f.scoped.getNote(f.seed.id)) // Actual encrypted transaction rolls back; classification stays conservative.
    }}
    @Test fun actualNoOpDoesNotIncrementNoteRevision()=runBlocking(Dispatchers.IO){fixture{f,_->
        val tools=f.tools(f.host());val call=f.patch(new="needle");f.approve(tools,call)
        val result=tools.execute(call);assertTrue(result.success);assertFalse(NotePatchReceipt.parse(result.content).changed)
        assertEquals(f.seed,f.scoped.getNote(f.seed.id))
    }}
}
