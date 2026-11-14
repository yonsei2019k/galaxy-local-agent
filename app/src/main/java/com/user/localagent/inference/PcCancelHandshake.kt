package com.user.localagent.inference

import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal data class PcCancelKey(val requestId:String,val epoch:Long,val owner:String?,val authorityRevision:Long) {
    init {
        require(UUID.fromString(requestId).toString()==requestId&&epoch>=0&&authorityRevision>=0)
        require(owner==null||UUID.fromString(owner).toString()==owner)
    }
}

internal enum class PcCancelStage {
    ACK_CLEANED,ACK_CLEANUP_PENDING,NOT_ACCEPTED,NOT_ACTIVE,OWNER_MISMATCH,
    HTTP_ERROR,INVALID_REPLY,TRANSPORT_ERROR,TIMEOUT,OBSERVATION_STOPPED
}

/** Only fixed classifications and actual wire fields survive parsing; no response/error text. */
internal data class PcCancelReply(
    val stage:PcCancelStage,val httpStatus:Int?=null,val acknowledged:Boolean=false,
    val cancelled:Boolean?=null,val cleanupComplete:Boolean?=null,
) {
    companion object {
        private const val MAX_BYTES=4096
        private val ACK_BODY=Regex("""\s*\{\s*"cancelled"\s*:\s*(true|false)\s*,\s*"cleanup_complete"\s*:\s*(true|false)\s*\}\s*""")
        private val ACK_REVERSE=Regex("""\s*\{\s*"cleanup_complete"\s*:\s*(true|false)\s*,\s*"cancelled"\s*:\s*(true|false)\s*\}\s*""")
        fun read(status:Int,input:InputStream?):PcCancelReply {
            val code=status.takeIf{it in 100..599}
            if(code==null||input==null)return PcCancelReply(PcCancelStage.INVALID_REPLY,code)
            val bytes=ByteArray(MAX_BYTES+1);var size=0
            try {
                while(size<bytes.size){
                    val n=input.read(bytes,size,bytes.size-size)
                    if(n<0)break
                    if(n==0)return PcCancelReply(PcCancelStage.INVALID_REPLY,code)
                    size+=n
                }
            }catch(_:IOException){return PcCancelReply(PcCancelStage.TRANSPORT_ERROR,code)}
            if(size>MAX_BYTES)return PcCancelReply(PcCancelStage.INVALID_REPLY,code)
            return parse(code,bytes.copyOf(size).toString(Charsets.UTF_8))
        }
        fun parse(status:Int,body:String):PcCancelReply {
            val code=status.takeIf{it in 100..599}
            if(code==null||body.toByteArray(Charsets.UTF_8).size>MAX_BYTES)return PcCancelReply(PcCancelStage.INVALID_REPLY,code)
            val value=try{Json.parseToJsonElement(body) as? JsonObject}catch(_:IllegalArgumentException){null}
                ?:return PcCancelReply(PcCancelStage.INVALID_REPLY,code)
            if(status !in 200..299){
                val error=(value["error"] as? JsonPrimitive)?.takeIf{it.isString}?.content
                val stage=when {
                    status==409&&error=="COMPUTE_REQUEST_NOT_ACTIVE"->PcCancelStage.NOT_ACTIVE
                    status==409&&error=="COMPUTE_OWNER_MISMATCH"->PcCancelStage.OWNER_MISMATCH
                    else->PcCancelStage.HTTP_ERROR
                }
                return PcCancelReply(stage,code)
            }
            // The current wire contract has exactly two literal boolean fields, never duplicates.
            if(!ACK_BODY.matches(body)&&!ACK_REVERSE.matches(body))return PcCancelReply(PcCancelStage.INVALID_REPLY,code)
            fun boolean(name:String)=(value[name] as? JsonPrimitive)?.takeUnless{it.isString}?.booleanOrNull
            val cancelled=boolean("cancelled");val complete=boolean("cleanup_complete")
            if(value.keys!=setOf("cancelled","cleanup_complete")||cancelled==null||complete==null||
                complete&&!cancelled||status!=(if(complete)200 else 202))
                return PcCancelReply(PcCancelStage.INVALID_REPLY,code,cancelled=cancelled,cleanupComplete=complete)
            val stage=if(complete)PcCancelStage.ACK_CLEANED else if(cancelled)PcCancelStage.ACK_CLEANUP_PENDING else PcCancelStage.NOT_ACCEPTED
            return PcCancelReply(stage,code,true,cancelled,complete)
        }
    }
}

internal data class PcCancelOutcome(val key:PcCancelKey,val reply:PcCancelReply) {
    val confirmed:Boolean get()=reply.stage==PcCancelStage.ACK_CLEANED&&reply.acknowledged&&
        reply.cancelled==true&&reply.cleanupComplete==true
}

/** One POST per captured session, independent of the UI's conflated latest cleanup value. */
internal class PcCancelHandshake<B:Any>(
    private val scope:CoroutineScope,
    private val send:suspend(Session<B>,Long)->PcCancelReply,
    private val nowMillis:()->Long={System.nanoTime()/1_000_000},
    private val timeoutMillis:Long=DEADLINE_MILLIS,
    private val onOutcome:(PcCancelOutcome)->Unit={},
) {
    companion object {const val DEADLINE_MILLIS=12_000L;private const val RETAINED=4}
    internal class Session<B:Any>(val key:PcCancelKey,val binding:B)
    internal class Ticket<B:Any>(val session:Session<B>,val deadline:Long) {
        val result=CompletableDeferred<PcCancelOutcome>()
        @Volatile var terminal:PcCancelOutcome?=null
        var job:Job?=null
    }
    private val lock=Any()
    private var active:Session<B>?=null
    private var pending:Ticket<B>?=null
    private val outcomes=mutableListOf<PcCancelOutcome>()
    init {require(timeoutMillis in 1..DEADLINE_MILLIS)}

    fun activate(key:PcCancelKey,binding:B):Session<B> = synchronized(lock){
        check(active==null){"PC_CANCEL_SESSION_ALREADY_ACTIVE"}
        Session(key,binding).also{active=it}
    }
    fun end(session:Session<B>){synchronized(lock){if(active===session)active=null}}
    fun requestCancel(epoch:Long):Ticket<B>? {
        var start:Ticket<B>?=null
        val ticket=synchronized(lock){
            val session=active?.takeIf{it.key.epoch==epoch}
            if(session==null)pending?.takeIf{it.session.key.epoch==epoch}
            else pending?.takeIf{it.session===session}?:Ticket(session,nowMillis()+timeoutMillis).also{
                pending=it
                it.job=scope.launch(start=CoroutineStart.LAZY){perform(it)}
                start=it
            }
        }
        // Registration is complete before the independent I/O coroutine is scheduled.
        start?.let { begun->
            begun.job?.invokeOnCompletion {cause->if(cause!=null)finish(begun,PcCancelReply(PcCancelStage.OBSERVATION_STOPPED))}
            begun.job?.start()
        }
        return ticket
    }
    private fun remaining(ticket:Ticket<B>)=(ticket.deadline-nowMillis()).coerceAtLeast(0L)
    private suspend fun perform(ticket:Ticket<B>) {
        val reply=try {
            val remaining=remaining(ticket)
            if(remaining==0L)PcCancelReply(PcCancelStage.TIMEOUT)
            else withTimeout(remaining){send(ticket.session,remaining)}
        }catch(_:TimeoutCancellationException){PcCancelReply(PcCancelStage.TIMEOUT)}
        catch(cancel:CancellationException){finish(ticket,PcCancelReply(PcCancelStage.OBSERVATION_STOPPED));throw cancel}
        catch(_:Exception){PcCancelReply(PcCancelStage.TRANSPORT_ERROR)}
        finish(ticket,reply)
    }
    private fun finish(ticket:Ticket<B>,reply:PcCancelReply):PcCancelOutcome {
        var first=false
        val outcome=synchronized(lock){
            ticket.terminal?:PcCancelOutcome(ticket.session.key,
                if(remaining(ticket)==0L)PcCancelReply(PcCancelStage.TIMEOUT) else reply).also {
                ticket.terminal=it;outcomes.removeAll{old->old.key==it.key};outcomes+=it
                while(outcomes.size>RETAINED)outcomes.removeAt(0)
                first=true
            }
        }
        if(first){ticket.result.complete(outcome);onOutcome(outcome)}
        return outcome
    }
    suspend fun awaitPending(epoch:Long):PcCancelOutcome? {
        val ticket=synchronized(lock){pending?.takeIf{it.session.key.epoch==epoch}}?:return null
        ticket.terminal?.let{return it}
        val remaining=remaining(ticket)
        val result=if(remaining>0)withTimeoutOrNull(remaining){ticket.result.await()}else null
        if(result!=null)return result
        val expired=finish(ticket,PcCancelReply(PcCancelStage.TIMEOUT))
        ticket.job?.cancel()
        return expired
    }
    fun outcome(requestId:String,owner:String?):PcCancelOutcome? = synchronized(lock){
        outcomes.lastOrNull{it.key.requestId==requestId&&it.key.owner==owner}
    }
    fun releaseEpoch(epoch:Long){synchronized(lock){
        if(pending?.session?.key?.epoch==epoch&&pending?.terminal!=null)pending=null
    }}
}
