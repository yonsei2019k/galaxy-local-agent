package com.user.localagent.compute

import android.content.Context
import android.os.SystemClock
import com.user.localagent.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Owner switch for test requests the paired PC queues for this phone. On while a PC is paired (the owner asked
 * for hands-off remote testing); turning it off persists until the owner turns it on again. It only lets the PC
 * put a request into its own visible thread; tools, approvals and consent are unchanged.
 */
class PcRemoteTestSettings private constructor(context:Context) {
    companion object {
        const val THREAD_TITLE="PC 원격 시험"
        @Volatile private var instance:PcRemoteTestSettings?=null
        fun get(context:Context)=instance?:synchronized(this){instance?:PcRemoteTestSettings(context.applicationContext).also{instance=it}}
    }
    private val prefs=context.getSharedPreferences("pc_remote_test_v1",Context.MODE_PRIVATE)
    fun enabled():Boolean=!prefs.getBoolean("owner_off",false)
    @Volatile internal var epoch=0L
        private set
    @Synchronized fun setEnabled(on:Boolean){
        if(enabled()!=on)epoch++
        prefs.edit().putBoolean("owner_off",!on).apply()
    }
    /** Each PC request ID is executed at most once on this phone, even if the PC delivers it again. */
    @Synchronized fun markHandled(requestId:String):Boolean {
        val seen=prefs.getString("handled","").orEmpty().split(',').filter{it.isNotBlank()}
        if(requestId in seen)return false
        return prefs.edit().putString("handled",(seen+requestId).takeLast(40).joinToString(",")).commit()
    }
}

/** Validated shape of one PC test request; anything else is reported back as rejected without running. */
internal data class PcRemoteTestRequest(val requestId:String,val text:String,val model:String,val afterRequestId:String?) {
    companion object {
        private val MODEL=Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")
        fun supportsReceipt(response:JsonObject?)=
            (response?.get("receiptVersion") as? JsonPrimitive)?.let{!it.isString&&it.intOrNull==2}==true
        fun supportsConversation(response:JsonObject?)=
            (response?.get("conversationVersion") as? JsonPrimitive)?.let{!it.isString&&it.intOrNull==1}==true
        fun parse(value:JsonObject):PcRemoteTestRequest {
            val required=setOf("requestId","text","model")
            require(value.keys.containsAll(required)&&value.keys.all{it in required||it=="afterRequestId"})
            fun string(key:String)=value.getValue(key).jsonPrimitive.also{require(it.isString)}.content
            fun id(key:String)=string(key).also{require(UUID.fromString(it).toString()==it)}
            val id=id("requestId")
            val text=string("text").trim()
            require(text.isNotEmpty()&&text.length<=2000&&!text.startsWith("/"))
            val model=string("model")
            require(MODEL.matches(model))
            val previous=if("afterRequestId" in value)id("afterRequestId") else null
            require(previous!=id)
            return PcRemoteTestRequest(id,text,model,previous)
        }
    }
}

class PcRemoteTestInbox(private val context:Context,private val app:AppContainer) {
    private val settings=PcRemoteTestSettings.get(context)
    private val pairing=PcComputeSettings.get(context.applicationContext)
    private val transport=PcTransport(context)
    private var job:Job?=null
    private data class Continuation(val threadId:String,val branchId:String,val threadRevision:Long,val runId:String,val model:String,
        val connection:PcConnection,val epoch:Long,val expiresAt:Long)
    private val continuations=linkedMapOf<String,Continuation>()

    @Synchronized fun start(scope:CoroutineScope){
        if(job?.isActive==true)return
        job=scope.launch(Dispatchers.IO){
            var nextArchiveAt=0L
            while(isActive){
                val elapsed=SystemClock.elapsedRealtime()
                if(elapsed>=nextArchiveAt){
                    nextArchiveAt=elapsed+60_000L
                    try {app.repository.chat.archiveFinishedRemoteTests(System.currentTimeMillis()-30*60_000L)}
                    catch(error:CancellationException){throw error}
                    catch(_:Exception){/* A later sweep can retry; the test history is preserved. */}
                }
                if(!settings.enabled()){delay(15_000);continue}
                val epoch=settings.epoch
                val config=runCatching{transport.available()}.getOrNull()
                if(config==null){delay(20_000);continue}
                val next=runCatching{transport.json(config,"/v1/remote/next","POST",buildJsonObject{},timeout=10_000)}.getOrNull()
                val request=next?.get("request") as? JsonObject
                if(request==null){delay(8_000);continue}
                val receiptSupported=PcRemoteTestRequest.supportsReceipt(next)
                val conversationSupported=PcRemoteTestRequest.supportsConversation(next)
                runCatching{handle(config,request,receiptSupported,conversationSupported,epoch)}.onFailure{
                    if(it is CancellationException)throw it
                    com.user.localagent.FailureLog.record(context,"remote_qa_handler",it)
                }
            }
        }
    }

    private suspend fun handle(config:PcConnection,raw:JsonObject,receiptSupported:Boolean,conversationSupported:Boolean,epoch:Long){
        val id=raw["requestId"]?.jsonPrimitive?.contentOrNull?:return
        val started=SystemClock.elapsedRealtime()
        if(!settings.enabled()||settings.epoch!=epoch||pairing.snapshot()?.samePair(config)!=true)return
        val version=if(receiptSupported)context.packageManager.getPackageInfo(context.packageName,0).longVersionCode else null
        fun receipt()=version?.let{buildJsonObject {
            put("versionCode",it)
            put("elapsedMs",SystemClock.elapsedRealtime()-started)
        }}
        val request=runCatching{PcRemoteTestRequest.parse(raw)}.getOrNull()
        if(request==null||!settings.enabled()||!settings.markHandled(id)){report(config,id,"rejected","",emptyList(),null,"REQUEST_REFUSED",receipt(),epoch);return}
        continuations.entries.removeAll{it.value.expiresAt<=started||it.value.epoch!=epoch||!it.value.connection.samePair(config)}
        val previous=request.afterRequestId?.let{continuations.remove(it)}
        val thread=if(request.afterRequestId==null)app.repository.chat.createRemoteTestThread()
        else previous?.takeIf{conversationSupported&&it.model==request.model}?.let { link ->
            app.repository.chat.thread(link.threadId)?.takeIf { candidate ->
                !candidate.archived&&candidate.projectId==null&&candidate.revision==link.threadRevision&&candidate.activeBranchId==link.branchId&&
                    app.repository.chat.messages(candidate.id,link.branchId).first().lastOrNull()?.runId==link.runId
            }
        }
        if(thread==null){report(config,id,"rejected","",emptyList(),null,"FOLLOW_UP_UNAVAILABLE",receipt(),epoch);return}
        val runId=UUID.randomUUID().toString()
        val accepted=withContext(Dispatchers.Main){
            settings.enabled()&&settings.epoch==epoch&&pairing.snapshot()?.samePair(config)==true&&
                app.runs.submitRemoteTest(request.text,thread.id,request.model,runId,thread.activeBranchId,thread.revision,previous?.runId){
                    settings.enabled()&&settings.epoch==epoch&&pairing.snapshot()?.samePair(config)==true
                }
        }
        if(!accepted){report(config,id,"rejected","",emptyList(),runId,"SUBMIT_REFUSED",receipt(),epoch);return}
        var state:String?=null
        var reportedApproval=false
        while(SystemClock.elapsedRealtime()-started<25*60_000L){
            delay(3_000)
            val run=app.repository.chat.run(runId)
            if(run==null){if(SystemClock.elapsedRealtime()-started>60_000L){state="rejected";break};continue}
            if(run.status=="waiting_approval"&&!reportedApproval){reportedApproval=true;continue}
            if(run.status !in setOf("queued","running","waiting_approval")){state=run.status;break}
        }
        suspend fun scopeUnchanged():Boolean=app.repository.chat.thread(thread.id)?.let{
            it.projectId==null&&it.revision==thread.revision&&it.activeBranchId==thread.activeBranchId
        }==true
        if(!scopeUnchanged()){
            report(config,id,"interrupted","",emptyList(),runId,"REMOTE_TEST_SCOPE_CHANGED",receipt(),epoch)
            return
        }
        val messages=app.repository.chat.messages(thread.id,thread.activeBranchId).first()
        val answer=messages.lastOrNull{it.runId==runId&&it.role=="assistant"}?.text.orEmpty()
        val tools=app.repository.chat.toolRunsForThread(thread.id,runId).first()
        if(!scopeUnchanged()){
            report(config,id,"interrupted","",emptyList(),runId,"REMOTE_TEST_SCOPE_CHANGED",receipt(),epoch)
            return
        }
        val finalCode=app.repository.chat.run(runId)?.errorCode
        val canFollowUp=conversationSupported&&(state=="completed"||
            state=="paused"&&finalCode=="cloud_clarification_read_only")&&settings.epoch==epoch&&
            messages.lastOrNull()?.let{it.role=="assistant"&&it.runId==runId}==true
        if(canFollowUp){
            if(continuations.size>=16)continuations.remove(continuations.keys.first())
            continuations[id]=Continuation(thread.id,thread.activeBranchId,thread.revision,runId,request.model,config,epoch,SystemClock.elapsedRealtime()+30*60_000L)
        }
        report(config,id,state?:"timeout",answer.take(20_000),tools.takeLast(64).map{Triple(it.name.take(96),it.success,it.summary.take(400))},runId,
            finalCode?.take(96),receipt(),epoch,canFollowUp)
    }

    private suspend fun report(config:PcConnection,id:String,state:String,answer:String,tools:List<Triple<String,Boolean,String>>,runId:String?,error:String?,receipt:JsonObject?,epoch:Long,canFollowUp:Boolean=false){
        if(state in setOf("failed","rejected","interrupted","timeout")){
            com.user.localagent.FailureLog.record(context,"remote_qa_terminal",null,error?:state)
        }
        val body=buildJsonObject{
            put("state",state);put("answer",answer)
            put("tools",buildJsonArray{tools.forEach{(name,ok,summary)->add(buildJsonObject{put("name",name);put("success",ok);put("summary",summary)})}})
            runId?.let{put("runId",it)};error?.let{put("errorCode",it)}
            receipt?.let{put("receipt",it)}
            if(canFollowUp)put("canFollowUp",true)
        }
        fun authorized()=settings.enabled()&&settings.epoch==epoch&&pairing.snapshot()?.samePair(config)==true
        repeat(3){attempt->
            if(!authorized())return
            val target=if(attempt==0)config else transport.available()?.takeIf{it.samePair(config)}?:config
            if(!authorized())return
            try {
                transport.json(target,"/v1/remote/requests/$id/result","POST",body,timeout=15_000)
                return
            }catch(error:CancellationException){throw error}
            catch(error:Exception){
                val code=if(attempt==2)"retry_exhausted" else "retry_${attempt+1}"
                com.user.localagent.FailureLog.record(context,"remote_qa_result_delivery",error,code)
            }
            delay(10_000)
        }
    }
}
