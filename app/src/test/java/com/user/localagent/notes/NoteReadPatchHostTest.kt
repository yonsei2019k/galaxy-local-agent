package com.user.localagent.notes

import com.user.localagent.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteReadPatchHostTest {
    private val binding=NoteReadBinding("run-demo","thread-demo","branch-demo","scope-demo")
    private fun source(body:String="prefix\r\nneedle 😀\r\nTAIL e\u0301")=NoteSource("note-demo",binding.scopeKey,"team","제목",body,7)
    private class Database(var current:NoteSource):NotePatchTransactions {
        var receipts=mutableMapOf<String,String>();var writes=0;var fail:String?=null
        var beforeTransaction:(()->Unit)?=null;var afterWrite:(()->Unit)?=null
        override suspend fun <T> transaction(requireRunning:Boolean,block:suspend NotePatchTransaction.()->T):T {
            if(requireRunning){beforeTransaction?.invoke();beforeTransaction=null}
            val saved=current;val previous=receipts.toMutableMap();val count=writes
            var wrote=false
            try {return block(object:NotePatchTransaction {
                override suspend fun note(id:String):NoteSource? {
                    if(fail=="readback"&&wrote)return current.copy(body="corrupt")
                    return current.takeIf{it.id==id}
                }
                override suspend fun putPreservingMetadata(before:NoteSource,body:String):NoteSource {
                    check(before==current)
                    if(fail=="write")error("injected write failure")
                    current=current.copy(body=body,updatedAt=current.updatedAt+1);writes++;wrote=true;afterWrite?.invoke();return current
                }
                override suspend fun receipt(operationId:String):String?=receipts[operationId]?.let{if(fail=="receipt-readback"&&wrote)"bad"else it}
                override suspend fun putReceipt(operationId:String,json:String){if(fail=="receipt")error("injected receipt failure");check(operationId !in receipts);receipts[operationId]=json}
            })}catch(e:Throwable){current=saved;receipts=previous;writes=count;throw e}
        }
    }
    private class Fixture(val db:Database,val host:NoteReadPatchHost)
    private fun fixture(body:String=source().body,live:suspend()->Unit={}):Fixture {
        val db=Database(source(body));return Fixture(db,NoteReadPatchHost(binding,NotePatchStore(binding,db),live))
    }
    private fun read(n:NoteSource,offset:Int=0,limit:Int=900)=ToolCall("read-demo","read_note",buildJsonObject {
        put("id",n.id);put("scope",n.collection);put("expectedUpdatedAt",n.updatedAt.toString());put("offset",offset);put("limit",limit)
    })
    private fun patch(n:NoteSource,old:String="needle",new:String="changed")=ToolCall("patch-demo","patch_note",buildJsonObject {
        put("id",n.id);put("scope",n.collection);put("expectedUpdatedAt",n.updatedAt.toString());put("oldText",old);put("newText",new)
    })
    private suspend fun deliver(f:Fixture,call:ToolCall=read(f.db.current)):JsonObject {
        val r=f.host.execute(call);val entry=buildJsonObject {
            put("tool",call.name);put("success",r.success);put("verification",r.verification.name);put("result",Json.parseToJsonElement(NoteReadModelDelivery.modelResult(r.content)))
        }
        f.host.accept(listOf(entry),JsonArray(listOf(entry)).toString());return entry
    }
    private suspend fun approved(f:Fixture,c:ToolCall=patch(f.db.current)):ToolCall {
        deliver(f);val review=checkNotNull(f.host.review(c));assertTrue(review.contains("needle"));assertTrue(review.contains("제목"));f.host.ownerConfirmed(c);return c
    }
    private suspend fun rejected(block:suspend()->Unit){try{block();fail("must reject")}catch(e:Exception){if(e is CancellationException)throw e}}

    @Test fun realDeliveryExactApprovalCasReadbackAndReceiptPreserveTail()=runBlocking {
        val f=fixture("needle "+"x".repeat(4000)+"\r\n😀 e\u0301");val c=approved(f)
        val result=f.host.execute(c);assertEquals(VerificationState.VERIFIED,result.verification)
        assertEquals("changed "+"x".repeat(4000)+"\r\n😀 e\u0301",f.db.current.body)
        assertEquals("제목",f.db.current.title);assertEquals("team",f.db.current.collection)
        assertEquals(1,f.db.writes);assertEquals(1,f.db.receipts.size)
        val receipt=NotePatchReceipt.parse(result.content);assertTrue(receipt.changed);assertEquals(noteSha(f.db.current.body),receipt.afterSha256)
        assertFalse(result.content.contains("x".repeat(30))) // No full body or source excerpt in the durable receipt.
    }
    @Test fun previewAndMatchingGenericGrantCannotApprove()=runBlocking {
        val f=fixture();deliver(f);val c=patch(f.db.current);f.host.review(c)
        rejected{f.host.execute(c)};assertEquals(0,f.db.writes)
    }
    @Test fun confirmationWithoutPreviewCannotApprove()=runBlocking {
        val f=fixture();deliver(f);val c=patch(f.db.current);f.host.ownerConfirmed(c)
        rejected{f.host.execute(c)};assertEquals(0,f.db.writes)
    }
    @Test fun differentReplacementDoesNotInheritApproval()=runBlocking {
        val f=fixture();approved(f);rejected{f.host.execute(patch(f.db.current,new="unapproved"))};assertEquals(0,f.db.writes)
    }
    @Test fun omittedAndTruncatedFittedQueryProofFailClosed()=runBlocking {
        for(truncate in listOf(false,true)){
            val f=fixture();val entry=deliver(f)
            f.host.accept(listOf(entry),if(truncate)entry.toString().take(70)else "unrelated query")
            rejected{f.host.review(patch(f.db.current))};assertEquals(0,f.db.writes)
        }
    }
    @Test fun laterGenerationWithoutReadInvalidatesOldApprovalProof()=runBlocking {
        val f=fixture();val c=approved(f);f.host.accept(emptyList(),"new generation")
        rejected{f.host.execute(c)};assertEquals(0,f.db.writes)
    }
    @Test fun titleVersionAndSameVersionBodyChangeRejectAfterApproval()=runBlocking {
        for(change in listOf<(NoteSource)->NoteSource>({it.copy(title="new")},{it.copy(updatedAt=8)},{it.copy(body=it.body+" tail")})){
            val f=fixture();val c=approved(f);f.db.current=change(f.db.current)
            rejected{f.host.execute(c)};assertEquals(0,f.db.writes)
        }
    }
    @Test fun transactionTimeRaceHasNoOverwrite()=runBlocking {
        val f=fixture();val c=approved(f);f.db.beforeTransaction={f.db.current=f.db.current.copy(body="changed elsewhere",updatedAt=8)}
        rejected{f.host.execute(c)};assertEquals("changed elsewhere",f.db.current.body);assertEquals(0,f.db.writes)
    }
    @Test fun writeReadbackAndReceiptFaultsRollbackTogether()=runBlocking {
        for(fault in listOf("write","readback","receipt","receipt-readback")){
            val f=fixture();val before=f.db.current;val c=approved(f);f.db.fail=fault
            rejected{f.host.execute(c)};assertEquals(before,f.db.current);assertEquals(0,f.db.writes);assertTrue(f.db.receipts.isEmpty())
        }
    }
    @Test fun sameOperationDifferentModelCallIdAndNewHostReconcileWithoutNewEffect()=runBlocking {
        val f=fixture();val c=approved(f);val result=f.host.execute(c)
        val fresh=NoteReadPatchHost(binding,NotePatchStore(binding,f.db)){}
        assertEquals(result.content,fresh.execute(c.copy(id="retry-demo")).content)
        assertEquals(1,f.db.writes);assertEquals(1,f.db.receipts.size)
    }
    @Test fun replayAfterAnotherMutationRejectsWithoutRepair()=runBlocking {
        val f=fixture();val c=approved(f);f.host.execute(c);f.db.current=f.db.current.copy(body="later edit",updatedAt=9)
        rejected{f.host.execute(c)};assertEquals("later edit",f.db.current.body);assertEquals(1,f.db.writes)
    }
    @Test fun freshHostWithoutReceiptCannotRestoreProofOrApproval()=runBlocking {
        val f=fixture();val c=approved(f);val fresh=NoteReadPatchHost(binding,NotePatchStore(binding,f.db)){}
        rejected{fresh.execute(c)};rejected{fresh.review(c)};assertEquals(0,f.db.writes)
    }
    @Test fun noOpDoesNotChangeVersionAndStillPersistsReadbackReceipt()=runBlocking {
        val f=fixture();val c=patch(f.db.current,new="needle");deliver(f);f.host.review(c);f.host.ownerConfirmed(c)
        val before=f.db.current;val r=f.host.execute(c);assertEquals(before,f.db.current);assertEquals(0,f.db.writes)
        assertFalse(NotePatchReceipt.parse(r.content).changed);assertEquals(1,f.db.receipts.size)
    }
    @Test fun protectedCollectionAndDifferentScopeNeverProducePage()=runBlocking {
        for(n in listOf(source().copy(collection=InternalNoteCollections.protected.first()),source().copy(scopeKey="other-scope"))){
            val db=Database(n);val host=NoteReadPatchHost(binding,NotePatchStore(binding,db)){}
            rejected{host.execute(read(n))};assertEquals(0,db.writes)
        }
    }
    @Test fun cancelBeforeAtomicEffectDoesNotWrite()=runBlocking {
        val f=fixture();val c=approved(f)
        val job=Job();f.db.beforeTransaction={job.cancel()}
        try{withContext(job){f.host.execute(c)};fail()}catch(_:CancellationException){}
        assertEquals(0,f.db.writes);assertTrue(f.db.receipts.isEmpty())
    }
    @Test fun cancelAfterWriteFinishesAtomicReceiptAndRecoveryObservesActualEffect()=runBlocking {
        val f=fixture();val c=approved(f);val job=Job();f.db.afterWrite={job.cancel()}
        try{withContext(job){f.host.execute(c)};fail()}catch(_:CancellationException){}
        val recovered=checkNotNull(f.host.recover(c));assertEquals(VerificationState.VERIFIED,recovered.verification)
        assertEquals(1,f.db.writes);assertEquals(1,f.db.receipts.size);assertEquals("changed",f.db.current.body.substringAfter("\r\n").substringBefore(" 😀"))
    }
    @Test fun revokedLiveRequestRejectsBeforeAnyEffect()=runBlocking {
        var live=true;val f=fixture(live={check(live)});val c=approved(f);live=false
        rejected{f.host.execute(c)};assertEquals(0,f.db.writes)
    }
    @Test fun whitespaceLiteralAndEmptyReplacementPreserveCrlfEmoji()=runBlocking {
        val f=fixture("head\r\n  😀 tail");val c=patch(f.db.current,"  ","");deliver(f);f.host.review(c);f.host.ownerConfirmed(c)
        f.host.execute(c);assertEquals("head\r\n😀 tail",f.db.current.body)
    }
}
