package com.user.localagent.inference

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM cancellation regressions; Android transport and actual PC effects require device proof. */
class PcCancelHandshakeTest {
    private val owner=UUID.randomUUID().toString()
    private fun key(epoch:Long=1)=PcCancelKey(UUID.randomUUID().toString(),epoch,owner,7)
    private fun clean()=PcCancelReply.parse(200,"""{"cancelled":true,"cleanup_complete":true}""")
    private fun scope()=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)

    @Test fun completeAckRequiresActualBooleanAcceptanceAndCleanup(){
        assertTrue(PcCancelOutcome(key(),clean()).confirmed)
        assertTrue(PcCancelOutcome(key(),PcCancelReply.parse(200,
            """ { "cleanup_complete" : true, "cancelled" : true } """)).confirmed)
        for(body in listOf("""{"cancelled":"true","cleanup_complete":true}""",
            """{"cancelled":true}""","""{"cancelled":false,"cleanup_complete":true}""",
            """{"cancelled":true,"cleanup_complete":null}""","""{"released":true,"cleanup_complete":true}""",
            """{"cancelled":true,"cleanup_complete":true,"extra":1}""",
            """{"cancelled":false,"cancelled":true,"cleanup_complete":true}""","[true]","broken")){
            val reply=PcCancelReply.parse(200,body)
            assertEquals(PcCancelStage.INVALID_REPLY,reply.stage)
            assertFalse(PcCancelOutcome(key(),reply).confirmed)
        }
    }
    @Test fun incompleteAndNotAcceptedRepliesCannotBecomeConfirmed(){
        val pending=PcCancelReply.parse(202,"""{"cancelled":true,"cleanup_complete":false}""")
        val refused=PcCancelReply.parse(202,"""{"cancelled":false,"cleanup_complete":false}""")
        assertEquals(PcCancelStage.ACK_CLEANUP_PENDING,pending.stage)
        assertEquals(PcCancelStage.NOT_ACCEPTED,refused.stage)
        assertFalse(PcCancelOutcome(key(),pending).confirmed);assertFalse(PcCancelOutcome(key(),refused).confirmed)
        assertEquals(PcCancelStage.INVALID_REPLY,PcCancelReply.parse(202,"""{"cancelled":true,"cleanup_complete":true}""").stage)
        assertEquals(PcCancelStage.INVALID_REPLY,PcCancelReply.parse(200,"""{"cancelled":true,"cleanup_complete":false}""").stage)
    }
    @Test fun ownerMismatchAndAlreadyFinishedRemainDistinctFailures(){
        assertEquals(PcCancelStage.NOT_ACTIVE,PcCancelReply.parse(409,"""{"error":"COMPUTE_REQUEST_NOT_ACTIVE"}""").stage)
        assertEquals(PcCancelStage.OWNER_MISMATCH,PcCancelReply.parse(409,"""{"error":"COMPUTE_OWNER_MISMATCH"}""").stage)
        assertEquals(PcCancelStage.HTTP_ERROR,PcCancelReply.parse(500,"""{"error":"COMPUTE_REQUEST_NOT_ACTIVE"}""").stage)
    }
    @Test fun replyReadsAreBoundedAndDoNotRetainErrorBodies(){
        val huge=ByteArrayInputStream(ByteArray(4097){'x'.code.toByte()})
        assertEquals(PcCancelStage.INVALID_REPLY,PcCancelReply.read(200,huge).stage)
        val broken=object:InputStream(){override fun read():Int=throw IOException("synthetic-only")}
        val failed=PcCancelReply.read(503,broken)
        assertEquals(PcCancelStage.TRANSPORT_ERROR,failed.stage);assertEquals(503,failed.httpStatus)
        assertFalse(failed.toString().contains("synthetic-only"))
        assertEquals(PcCancelStage.INVALID_REPLY,PcCancelReply.read(200,null).stage)
        val stalled=object:InputStream(){
            override fun read():Int=0
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int=0
        }
        assertEquals(PcCancelStage.INVALID_REPLY,PcCancelReply.read(200,stalled).stage)
    }
    @Test fun repeatedStopAndUnloadShareTheOriginalSnapshotAndSinglePost()=runBlocking {
        val scope=scope();val gate=CompletableDeferred<PcCancelReply>();val sent=mutableListOf<String>()
        try {
            val h=PcCancelHandshake<String>(scope,send={session,_->sent+=session.binding;gate.await()})
            val k=key();val session=h.activate(k,"original-snapshot")
            val ticket=checkNotNull(h.requestCancel(1))
            assertSame(ticket,h.requestCancel(1));h.end(session);assertSame(ticket,h.requestCancel(1))
            gate.complete(clean())
            assertTrue(checkNotNull(h.awaitPending(1)).confirmed)
            assertEquals(listOf("original-snapshot"),sent)
            assertEquals(k,h.outcome(k.requestId,owner)?.key)
        }finally{scope.cancel()}
    }
    @Test fun noActiveSessionAndWrongEpochNeverInventATarget()=runBlocking {
        val scope=scope();var posts=0
        try {
            val h=PcCancelHandshake<String>(scope,send={_,_->posts++;clean()})
            assertNull(h.requestCancel(1));assertNull(h.awaitPending(1))
            val s=h.activate(key(),"bound");assertNull(h.requestCancel(2));h.end(s)
            assertNull(h.requestCancel(1));assertEquals(0,posts)
        }finally{scope.cancel()}
    }
    @Test fun responseBeyondOldTwoSecondWindowIsRetainedWithinOriginalDeadline()=runBlocking {
        val scope=scope();var clock=0L;val gate=CompletableDeferred<PcCancelReply>()
        try {
            val h=PcCancelHandshake<String>(scope,send={_,_->gate.await()},nowMillis={clock})
            val k=key();val s=h.activate(k,"bound");h.requestCancel(1);h.end(s)
            clock=2500;gate.complete(clean());val result=checkNotNull(h.awaitPending(1))
            assertTrue(result.confirmed);h.releaseEpoch(1)
            assertEquals(result,h.outcome(k.requestId,owner))
            assertNull(h.outcome(k.requestId,UUID.randomUUID().toString()))
        }finally{scope.cancel()}
    }
    @Test fun repeatedCancellationDoesNotRestartDeadlineOrUpgradeLateSuccess()=runBlocking {
        val scope=scope();var clock=0L;var posts=0;val gate=CompletableDeferred<PcCancelReply>()
        try {
            val h=PcCancelHandshake<String>(scope,send={_,_->posts++;withContext(NonCancellable){gate.await()}},nowMillis={clock})
            val k=key();val s=h.activate(k,"bound");val ticket=h.requestCancel(1)
            clock=11_900;assertSame(ticket,h.requestCancel(1));h.end(s)
            clock=12_001
            assertEquals(PcCancelStage.TIMEOUT,h.awaitPending(1)?.reply?.stage)
            gate.complete(clean());yield()
            assertEquals(PcCancelStage.TIMEOUT,h.outcome(k.requestId,owner)?.reply?.stage)
            assertEquals(1,posts)
        }finally{gate.complete(clean());scope.cancel()}
    }
    @Test fun waitingForPendingDoesNotRequireTheGenerationMutex()=runBlocking {
        val scope=scope();val mutex=Mutex();val gate=CompletableDeferred<PcCancelReply>()
        try {
            val h=PcCancelHandshake<String>(scope,send={_,_->gate.await()})
            h.activate(key(),"bound");h.requestCancel(1)
            withTimeout(2000){mutex.withLock {
                val completer=launch{gate.complete(clean())}
                assertTrue(checkNotNull(h.awaitPending(1)).confirmed);completer.join()
            }}
        }finally{scope.cancel()}
    }
    @Test fun cancellingAWaiterDoesNotCancelTheIndependentPost()=runBlocking {
        val scope=scope();val gate=CompletableDeferred<PcCancelReply>()
        try {
            val h=PcCancelHandshake<String>(scope,send={_,_->gate.await()})
            val k=key();h.activate(k,"bound");h.requestCancel(1)
            val waiter=launch(start=CoroutineStart.UNDISPATCHED){h.awaitPending(1)}
            waiter.cancelAndJoin();assertNull(h.outcome(k.requestId,owner))
            gate.complete(clean());assertTrue(checkNotNull(h.awaitPending(1)).confirmed)
        }finally{scope.cancel()}
    }
    @Test fun stalledPostHasABoundedWaitAndCancellationSignal()=runBlocking {
        val scope=scope();var closed=false
        try {
            val h=PcCancelHandshake<String>(scope,timeoutMillis=20,send={_,_->
                try{awaitCancellation()}finally{closed=true}
            })
            val k=key();h.activate(k,"bound");val ticket=checkNotNull(h.requestCancel(1))
            val result=withTimeout(2000){h.awaitPending(1)}
            withTimeout(2000){ticket.job?.join()}
            assertEquals(PcCancelStage.TIMEOUT,result?.reply?.stage)
            assertTrue(closed);assertFalse(checkNotNull(result).confirmed)
        }finally{scope.cancel()}
    }
    @Test fun schedulingDelayConsumesTheSameFirstRegistrationDeadline()=runBlocking {
        class Manual:CoroutineDispatcher(){
            val queued=java.util.ArrayDeque<Runnable>()
            override fun dispatch(context:CoroutineContext,block:Runnable){queued.addLast(block)}
            fun drain(){while(queued.isNotEmpty())queued.removeFirst().run()}
        }
        val dispatcher=Manual();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        var clock=0L;var posts=0
        try {
            val h=PcCancelHandshake<String>(scope,send={_,_->posts++;clean()},nowMillis={clock})
            val k=key();h.activate(k,"bound");h.requestCancel(1)
            clock=12_001;dispatcher.drain()
            assertEquals(PcCancelStage.TIMEOUT,h.awaitPending(1)?.reply?.stage)
            assertEquals(0,posts)
        }finally{scope.cancel();dispatcher.drain()}
    }
    @Test fun oldReplyCannotCompleteANewerTicket()=runBlocking {
        val scope=scope();val old=CompletableDeferred<PcCancelReply>();val fresh=CompletableDeferred<PcCancelReply>()
        val first=key(1);val second=key(2)
        try {
            val h=PcCancelHandshake<String>(scope,send={s,_->if(s.key==first)old.await()else fresh.await()})
            val s=h.activate(first,"old");h.requestCancel(1);h.end(s)
            h.activate(second,"new");val current=checkNotNull(h.requestCancel(2))
            old.complete(clean());assertFalse(current.result.isCompleted)
            assertTrue(checkNotNull(h.outcome(first.requestId,owner)).confirmed)
            assertNull(h.outcome(second.requestId,owner))
            fresh.complete(PcCancelReply.parse(409,"""{"error":"COMPUTE_REQUEST_NOT_ACTIVE"}"""))
            assertEquals(PcCancelStage.NOT_ACTIVE,h.awaitPending(2)?.reply?.stage)
        }finally{scope.cancel()}
    }
    @Test fun boundedRequestHistorySurvivesReleaseWithoutConflatingResults()=runBlocking {
        val scope=scope()
        try {
            val h=PcCancelHandshake<String>(scope,send={_,_->clean()});val keys=mutableListOf<PcCancelKey>()
            repeat(5){n->
                val k=key(n.toLong());keys+=k;val s=h.activate(k,"bound-$n")
                h.requestCancel(k.epoch);assertTrue(checkNotNull(h.awaitPending(k.epoch)).confirmed)
                h.end(s);h.releaseEpoch(k.epoch)
            }
            assertNull(h.outcome(keys.first().requestId,owner))
            keys.drop(1).forEach{assertTrue(checkNotNull(h.outcome(it.requestId,owner)).confirmed)}
        }finally{scope.cancel()}
    }
    @Test fun invalidKeysAndUnboundedPoliciesAreRejected(){
        assertTrue(runCatching{PcCancelKey("not-a-request",1,owner,7)}.isFailure)
        assertTrue(runCatching{PcCancelKey(UUID.randomUUID().toString(),-1,owner,7)}.isFailure)
        val scope=scope()
        try{assertTrue(runCatching{PcCancelHandshake<String>(scope,timeoutMillis=12_001,send={_,_->clean()})}.isFailure)}
        finally{scope.cancel()}
    }
}
