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
    fun setEnabled(on:Boolean){prefs.edit().putBoolean("owner_off",!on).apply()}
    /** Each PC request ID is executed at most once on this phone, even if the PC delivers it again. */
    @Synchronized fun markHandled(requestId:String):Boolean {
        val seen=prefs.getString("handled","").orEmpty().split(',').filter{it.isNotBlank()}
        if(requestId in seen)return false
        return prefs.edit().putString("handled",(seen+requestId).takeLast(40).joinToString(",")).commit()
    }
}

/** Validated shape of one PC test request; anything else is reported back as rejected without running. */
internal data class PcRemoteTestRequest(val requestId:String,val text:String,val model:String) {
    companion object {
        private val MODEL=Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")
        fun supportsReceipt(response:JsonObject?)=
            (response?.get("receiptVersion") as? JsonPrimitive)?.let{!it.isString&&it.intOrNull==2}==true
        fun parse(value:JsonObject):PcRemoteTestRequest {
            require(value.keys=="requestId text model".split(' ').toSet())
            val id=value.getValue("requestId").jsonPrimitive.content
            require(UUID.fromString(id).toString()==id)
            val text=value.getValue("text").jsonPrimitive.content.trim()
            require(text.isNotEmpty()&&text.length<=2000&&!text.startsWith("/"))
            val model=value.getValue("model").jsonPrimitive.content
            require(MODEL.matches(model))
            return PcRemoteTestRequest(id,text,model)
        }
    }
}

class PcRemoteTestInbox(private val context:Context,private val app:AppContainer) {
    private val settings=PcRemoteTestSettings.get(context)
    private val pairing=PcComputeSettings.get(context.applicationContext)
    private val transport=PcTransport(context)
    private var job:Job?=null

    @Synchronized fun start(scope:CoroutineScope){
        if(job?.isActive==true)return
        job=scope.launch(Dispatchers.IO){
            while(isActive){
                if(!settings.enabled()){delay(15_000);continue}
                val config=runCatching{transport.available()}.getOrNull()
                if(config==null){delay(20_000);continue}
                val next=runCatching{transport.json(config,"/v1/remote/next","POST",buildJsonObject{},timeout=10_000)}.getOrNull()
                val request=next?.get("request") as? JsonObject
                if(request==null){delay(8_000);continue}
                val receiptSupported=PcRemoteTestRequest.supportsReceipt(next)
                runCatching{handle(config,request,receiptSupported)}.onFailure{if(it is CancellationException)throw it}
            }
        }
    }

    private suspend fun handle(config:PcConnection,raw:JsonObject,receiptSupported:Boolean){
        val id=raw["requestId"]?.jsonPrimitive?.contentOrNull?:return
        val started=SystemClock.elapsedRealtime()
        val version=if(receiptSupported)context.packageManager.getPackageInfo(context.packageName,0).longVersionCode else null
        fun receipt()=version?.let{buildJsonObject {
            put("versionCode",it)
            put("elapsedMs",SystemClock.elapsedRealtime()-started)
        }}
        val request=runCatching{PcRemoteTestRequest.parse(raw)}.getOrNull()
        if(request==null||!settings.enabled()||!settings.markHandled(id)){report(config,id,"rejected","",emptyList(),null,"REQUEST_REFUSED",receipt());return}
        val thread=app.repository.chat.createThread(null,PcRemoteTestSettings.THREAD_TITLE)
        val runId=UUID.randomUUID().toString()
        val accepted=withContext(Dispatchers.Main){
            settings.enabled()&&pairing.snapshot()?.samePair(config)==true&&
                app.runs.submitRemoteTest(request.text,thread.id,request.model,runId)
        }
        if(!accepted){report(config,id,"rejected","",emptyList(),runId,"SUBMIT_REFUSED",receipt());return}
        var state:String?=null
        var reportedApproval=false
        while(SystemClock.elapsedRealtime()-started<25*60_000L){
            delay(3_000)
            val run=app.repository.chat.run(runId)
            if(run==null){if(SystemClock.elapsedRealtime()-started>60_000L){state="rejected";break};continue}
            if(run.status=="waiting_approval"&&!reportedApproval){reportedApproval=true;continue}
            if(run.status !in setOf("queued","running","waiting_approval")){state=run.status;break}
        }
        val messages=app.repository.chat.messages(thread.id).first()
        val answer=messages.lastOrNull{it.runId==runId&&it.role=="assistant"}?.text.orEmpty()
        val tools=app.repository.chat.toolRunsForThread(thread.id,runId).first()
        report(config,id,state?:"timeout",answer.take(20_000),tools.takeLast(64).map{Triple(it.name.take(96),it.success,it.summary.take(400))},runId,
            app.repository.chat.run(runId)?.errorCode?.take(96),receipt())
    }

    private suspend fun report(config:PcConnection,id:String,state:String,answer:String,tools:List<Triple<String,Boolean,String>>,runId:String?,error:String?,receipt:JsonObject?){
        val body=buildJsonObject{
            put("state",state);put("answer",answer)
            put("tools",buildJsonArray{tools.forEach{(name,ok,summary)->add(buildJsonObject{put("name",name);put("success",ok);put("summary",summary)})}})
            runId?.let{put("runId",it)};error?.let{put("errorCode",it)}
            receipt?.let{put("receipt",it)}
        }
        repeat(3){attempt->
            if(!settings.enabled()||pairing.snapshot()?.samePair(config)!=true)return
            val target=if(attempt==0)config else transport.available()?.takeIf{it.samePair(config)}?:config
            if(!settings.enabled()||pairing.snapshot()?.samePair(config)!=true)return
            try {
                transport.json(target,"/v1/remote/requests/$id/result","POST",body,timeout=15_000)
                return
            }catch(error:CancellationException){throw error}
            catch(_:Exception){/* Retry only a report to the original pairing. */}
            delay(10_000)
        }
    }
}
