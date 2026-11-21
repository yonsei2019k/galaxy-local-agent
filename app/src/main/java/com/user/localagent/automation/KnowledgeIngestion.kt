package com.user.localagent.automation

import android.Manifest
import android.app.Notification
import android.service.notification.StatusBarNotification
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import androidx.work.*
import com.user.localagent.LocalAgentApplication
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.briefing.BriefingArchive
import com.user.localagent.core.ExtractedKnowledgeFact
import com.user.localagent.core.KnowledgeExtraction
import com.user.localagent.data.KnowledgeClaim
import com.user.localagent.data.DraftStore
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.MessageCursor
import com.user.localagent.data.NoteRecord
import com.user.localagent.integrations.MessengerInbox
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class KnowledgeIngestionState(val enabled:Boolean,val generation:Long,val lastSuccessAt:Long,val lastStatus:String,val processedSources:Long,val savedClaims:Long,val hasMore:Boolean)
data class KnowledgeIngestionReport(val processedSources:Int,val savedClaims:Int,val hasMore:Boolean,val status:String,
    val analyzedSegments:Int=0,val evidenceRecords:Int=0)
private class IngestionInterrupted:CancellationException("Knowledge ingestion generation changed")

/** Source-linked extraction and checkpointing for manual and scheduled local learning. */
class KnowledgeIngestionController(context:Context,private val repository:LocalRepository) {
    private val context=context.applicationContext
    private val prefs=this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private val learning=ConnectedRecordLearning(this.context,repository)
    private val activity=DeviceActivityLearning(this.context,repository)
    private val notificationStore=DraftStore(this.context,"knowledge-kakao-observations-v1",900_000)
    private val archiveVersions=DraftStore(this.context,"knowledge-kakao-archive-versions-v2",100_000)
    fun state()=KnowledgeIngestionState(prefs.getBoolean("enabled",false),prefs.getLong("generation",0),prefs.getLong("last_success",0),prefs.getString("status","disabled")?:"disabled",prefs.getLong("processed",0),prefs.getLong("claims",0),prefs.getBoolean("has_more",false))

    /** Authorized host/UI action. Disabling waits until any in-flight publication has stopped. */
    suspend fun setEnabled(enabled:Boolean):KnowledgeIngestionState=withContext(Dispatchers.IO){
        synchronized(SETTINGS_LOCK){
            check(prefs.edit().putBoolean("enabled",enabled).putLong("generation",state().generation+1)
                .putString("status",if(enabled)"scheduled" else "disabled").commit())
            if(!enabled){
                prefs.edit().remove("nightly_enabled").apply()
                notificationStore.write("")
            }
        }
        activeScan?.cancel(IngestionInterrupted())
        WorkManager.getInstance(context).cancelUniqueWork(EVENT)
        if(enabled){schedule();requestInitialScan()}else WorkManager.getInstance(context).apply { cancelUniqueWork(PERIODIC); cancelUniqueWork(SMS_WATCH) }
        SCAN_MUTEX.withLock {if(!state().enabled)learning.clear();state()}
    }
    fun schedule(){
        val work=WorkManager.getInstance(context)
        work.cancelUniqueWork("knowledge-ingestion-nightly-v1")
        work.cancelUniqueWork(LEGACY_PERIODIC)
        if(!state().enabled)return
        scheduleSmsWatch()
        // Reconcile missed Android signals and collect the system's retained app usage events.
        work.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<KnowledgeIngestionWorker>(30,TimeUnit.MINUTES)
                .setConstraints(constraints()).build())
        requestInitialScan()
    }
    internal fun scheduleSmsWatch(append:Boolean=false){
        if(!state().enabled||!smsAllowed()&&!activity.canReadCalls())return
        val watch=Constraints.Builder()
        if(smsAllowed())watch.addContentUriTrigger(Telephony.Sms.CONTENT_URI,true)
        if(activity.canReadCalls())watch.addContentUriTrigger(android.provider.CallLog.Calls.CONTENT_URI,true)
        WorkManager.getInstance(context).enqueueUniqueWork(SMS_WATCH,
            if(append)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<KnowledgeSmsWatchWorker>().setConstraints(watch.build()).build())
    }
    fun requestInitialScan(append:Boolean=false){
        if(!state().enabled)return
        WorkManager.getInstance(context).enqueueUniqueWork(EVENT,if(append)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<KnowledgeIngestionWorker>().setConstraints(constraints()).build())
    }
    /** Capture bounded observations on receipt and enqueue the same incremental processor. */
    fun captureIncomingNotification(notification:StatusBarNotification) {
        val pkg=notification.packageName
        if(!canCaptureNotification(pkg)||notification.isOngoing||
            notification.notification.flags and Notification.FLAG_GROUP_SUMMARY!=0)return
        val values=notification.notification.extras
        val body=(values.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?:values.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val facts=KnowledgeExtraction.observedTopics(body)
        if(body.isBlank())return
        val at=notification.postTime
        val id=digest("$pkg|${notification.key}|$at|$body")
        synchronized(SETTINGS_LOCK){
            if(!canCaptureNotification(pkg)||at<=prefs.getLong("erased_through",0))return
            val pending=notificationRows()
            if(pending.any {it.getValue("id").jsonPrimitive.content==id})return
            if(pending.size>=512){
                check(prefs.edit().putBoolean("kakao_capture_gap",true).commit())
                return
            }
            notificationStore.write(JsonArray(pending+buildJsonObject {
                put("id",id);put("at",at);put("package",pkg);put("topics",JsonArray(facts.map {JsonPrimitive(it.value)}))
                values.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf(String::isNotBlank)
                    ?.let { put("group",digest("$pkg:$it")) }
                if(com.user.localagent.core.ConversationPrivacy.matchKind(body)!=com.user.localagent.core.ConversationPrivacy.MatchKind.CREDENTIAL)
                    put("body",body.take(960))
            }).toString())
        }
        requestInitialScan()
    }
    fun recordNotificationCaptureGap() {
        synchronized(SETTINGS_LOCK){if(state().enabled)prefs.edit().putBoolean("kakao_capture_gap",true).apply()}
    }
    fun canCaptureNotification(pkg:String):Boolean = state().enabled&&when(pkg) {
        KAKAO->kakaoAllowed()
        in EMAIL_APPS->AssistantNotificationListener.granted(context)
        else->false
    }
    private fun smsAllowed()=ContextCompat.checkSelfPermission(context,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED
    private fun kakaoAllowed()=AssistantNotificationListener.granted(context)&&MessengerInbox.allowed(context,KAKAO)
    private fun notificationRows()=notificationStore.readStrict().takeIf(String::isNotBlank)
        ?.let {Json.parseToJsonElement(it).jsonArray.map {row->row.jsonObject}}.orEmpty()
    /** Immediate direct-owner capture from an actually saved user message; does not skip historical cursors. */
    suspend fun ingestDirectMessage(id:String):KnowledgeIngestionReport=ingestOne {
        val message=repository.getMessage(id)?:return@ingestOne null
        if(message.role!="user")return@ingestOne null
        SingleSource("user_message",message.id,message.createdAt,KnowledgeExtraction.directOwnerStatements(message.text,message.role))
    }
    /** Targeted host hook for a newly saved/imported record; it never promotes third-party identity. */
    suspend fun ingestStoredNote(id:String):KnowledgeIngestionReport=ingestOne {
        val note=repository.getNote(id)?:return@ingestOne null
        if(note.collection !in setOf("sms-schedule-candidates","native-sms","imported-chats","audio-transcripts","rawaudio-transcripts"))return@ingestOne null
        SingleSource(when(note.collection){"sms-schedule-candidates","native-sms"->"phone_sms";"imported-chats"->"conversation_import";else->"audio_transcript"},note.id,note.updatedAt,observedNote(note,prefs.getLong("erased_through",0)))
    }.also { if(it.processedSources>0)requestInitialScan() }
    private data class SingleSource(val kind:String,val id:String,val at:Long,val facts:List<ExtractedKnowledgeFact>)
    private suspend fun ingestOne(load:suspend ()->SingleSource?):KnowledgeIngestionReport=withContext(Dispatchers.IO){
        if(!SCAN_MUTEX.tryLock()){requestInitialScan();return@withContext KnowledgeIngestionReport(0,0,true,"busy")}
        val generation=state().generation;var saved=0
        try {
            activeScan=currentCoroutineContext()[Job]
            if(!state().enabled)return@withContext KnowledgeIngestionReport(0,0,false,"disabled")
            if(!deviceSafe(true))return@withContext statusReport(generation,0,0,false,"deferred_device_state")
            val epoch=repository.knowledgeEpoch();val source=load()?:return@withContext KnowledgeIngestionReport(0,0,false,"source_missing")
            if(!KnowledgeExtraction.afterHistoricalErasure(source.at,prefs.getLong("erased_through",0))||!repository.knowledgeSourceAllowed(source.kind,source.id,source.at))return@withContext KnowledgeIngestionReport(0,0,false,"source_erased")
            for(fact in source.facts.take(6)){
                currentCoroutineContext().ensureActive()
                if(!state().enabled||state().generation!=generation||repository.knowledgeEpoch()!=epoch)throw IngestionInterrupted()
                repository.saveKnowledgeClaim(KnowledgeClaim(subject=if(fact.ownerProfile)"owner" else "record:${digest("${source.kind}|${source.id}").take(24)}",predicate=if(fact.ownerProfile)fact.predicate else fact.predicate+"."+digest(fact.value).take(16),value=fact.value,
                    status=if(fact.ownerProfile)"confirmed" else "observed",confidence=fact.confidence,sourceKind=source.kind,sourceId=source.id,sourceExcerpt=fact.evidence,updatedAt=source.at,tags=fact.tags),expectedEpoch=epoch)
                saved++
            }
            synchronized(SETTINGS_LOCK){if(state().enabled&&state().generation==generation)prefs.edit().putLong("processed",state().processedSources+1).putLong("claims",state().savedClaims+saved).putLong("last_success",System.currentTimeMillis()).putString("status","direct_source_checked").commit()}
            KnowledgeIngestionReport(1,saved,false,"direct_source_checked")
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){statusReport(generation,0,saved,true,"source_or_generation_changed")}
        finally {activeScan=null;SCAN_MUTEX.unlock()}
    }
    /** Erase graph knowledge and place all older sources behind a persistent no-reingestion boundary. */
    suspend fun clearHistory():Int=withContext(Dispatchers.IO){
        val erased=System.currentTimeMillis()
        synchronized(SETTINGS_LOCK){
            check(prefs.edit().putLong("generation",state().generation+1).putLong("erased_through",erased)
                .putLong("message_at",erased).putString("message_id","\uFFFF").putLong("note_at",erased).putString("note_id","\uFFFF")
                .remove("notification_seen").remove("kakao_capture_gap").remove("last_night_complete")
                .putLong("processed",0).putLong("claims",0).putBoolean("has_more",false).putString("status","history_erased").commit())
            notificationStore.write("")
            archiveVersions.write("")
        }
        activeScan?.cancel(IngestionInterrupted());WorkManager.getInstance(context).cancelUniqueWork(EVENT)
        SCAN_MUTEX.withLock {learning.clear();repository.clearKnowledge()}
    }

    /** Explicit settings action, using the same source cursors as the scheduled scan. */
    suspend fun scanConnectedNow(onProgress:(KnowledgeIngestionReport)->Unit={}):KnowledgeIngestionReport {
        var total=KnowledgeIngestionReport(0,0,true,"started")
        repeat(6){
            val pass=scanNow(manual=true,maxSources=200,includeDeviceSources=true)
            total=pass.copy(processedSources=total.processedSources+pass.processedSources,
                savedClaims=total.savedClaims+pass.savedClaims,analyzedSegments=total.analyzedSegments+pass.analyzedSegments,
                evidenceRecords=total.evidenceRecords+pass.evidenceRecords)
            onProgress(total)
            if(!pass.hasMore||pass.status!="incremental_checkpoint_saved")return total
            yield()
        }
        return total
    }

    /** Explicit initial/manual scan API; background workers additionally require charging/idle constraints. */
    suspend fun scanNow(manual:Boolean=true,maxSources:Int=100,includeDeviceSources:Boolean=false):KnowledgeIngestionReport=withContext(Dispatchers.IO){
        require(maxSources in 1..200)
        if(!SCAN_MUTEX.tryLock())return@withContext KnowledgeIngestionReport(0,0,true,"busy")
        val generation=state().generation
        var processed=0;var claims=0;var more=false
        var drainOne: (suspend () -> LearningPass)? = null
        var learningStatus: String? = null
        var learningPass: LearningPass? = null
        try {
            activeScan=currentCoroutineContext()[Job]
            if(!state().enabled)return@withContext KnowledgeIngestionReport(0,0,false,"disabled")
            if(!deviceSafe(manual))return@withContext statusReport(generation,0,0,false,"deferred_device_state")
            val epoch=repository.knowledgeEpoch();val floor=prefs.getLong("erased_through",0)
            if(includeDeviceSources){
                learning.begin()
                drainOne = { learning.drain(epoch) { source ->
                    val current=state()
                    current.enabled&&current.generation==generation&&
                        source.at>floor&&repository.knowledgeSourceAllowed(source.kind,source.id,source.at)&&
                        when(source.kind){
                            "sms_provider"->currentSmsSource(source)
                            "email_notification"->AssistantNotificationListener.granted(context)
                            "kakao_notification"->kakaoAllowed()&&source.archiveAuthority.let { original ->
                                original==null||BriefingArchive(context).let { it.enabled()&&it.authorization()==original }
                            }
                            else->source.noteId?.let { repository.getNote(it) }?.let { note ->
                                note.updatedAt==source.noteVersionAt&&note.body.contains(source.text)
                            }==true
                        }
                } }
            }
            val deadline=SystemClock.elapsedRealtime()+20000
            suspend fun eligible(){currentCoroutineContext().ensureActive();if(!state().enabled||state().generation!=generation||repository.knowledgeEpoch()!=epoch)throw IngestionInterrupted()}
            suspend fun persist(kind:String,id:String,at:Long,facts:List<ExtractedKnowledgeFact>){
                fun permitted()=when(kind){"sms_provider"->smsAllowed();"kakao_notification"->kakaoAllowed();"email_notification"->AssistantNotificationListener.granted(context);else->true}
                eligible()
                check(permitted())
                if(!KnowledgeExtraction.afterHistoricalErasure(at,floor)||!repository.knowledgeSourceAllowed(kind,id,at))return
                for(fact in facts.take(6)){
                    eligible()
                    val predicate=if(fact.ownerProfile)fact.predicate else fact.predicate+"."+digest(fact.value).take(16)
                    val saved=repository.saveKnowledgeClaim(KnowledgeClaim(subject=if(fact.ownerProfile)"owner" else "record:${digest("$kind|$id").take(24)}",predicate=predicate,value=fact.value,
                        status=if(fact.ownerProfile)"confirmed" else "observed",confidence=fact.confidence,sourceKind=kind,sourceId=id,sourceExcerpt=fact.evidence,
                        updatedAt=at,tags=fact.tags),expectedEpoch=epoch)
                    if(kind=="sms_provider"||kind=="kakao_notification"){
                        check(permitted())
                        check(repository.getKnowledge(saved.id)?.let{it.sourceKind==kind&&it.sourceId==id&&it.value==saved.value}==true)
                    }
                    claims++
                }
            }
            suspend fun checkpoint(edit:android.content.SharedPreferences.Editor){eligible();synchronized(SETTINGS_LOCK){if(state().enabled&&state().generation==generation)check(edit.commit())}}
            suspend fun stage(block:suspend ()->Unit):Boolean=try {block();true}
            catch(_:LearningQueueFull){more=true;false}
            var smsMissing=false
            if(includeDeviceSources){
                claims+=activity.capture(floor,epoch,::eligible)
                smsMissing=!smsAllowed()
                if(!smsMissing){
                    val smsCursor=if(manual)"learning_sms_id_v1" else "learning_recent_sms_id_v1"
                    val after=prefs.getLong(smsCursor,-1)
                    val readSince=if(after>=0||manual)floor else maxOf(floor,System.currentTimeMillis()-TimeUnit.DAYS.toMillis(1))
                    val limit=minOf(if(manual)120 else 40,maxSources/2)
                    if(limit>0)context.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf("_id","date","body","thread_id"),
                        "_id > ? AND date > ?",arrayOf(after.toString(),readSince.toString()),"_id ASC")?.use {rows->
                        var read=0
                        while(rows.moveToNext()){
                            if(read>=limit||SystemClock.elapsedRealtime()>deadline){more=true;break}
                            eligible()
                            check(smsAllowed())
                            val id=rows.getLong(0)
                            val at=rows.getLong(1)
                            val body=rows.getString(2).orEmpty()
                            val sourceId="sms:"+digest("$id|$at")
                            if(repository.knowledgeSourceAllowed("sms_provider",sourceId,at)&&!stage {
                                learning.stage("sms_provider",sourceId,at,body,smsId=id,group=digest("sms:"+rows.getLong(3)))
                            })break
                            persist("sms_provider",sourceId,at,KnowledgeExtraction.observedTopics(body))
                            checkpoint(prefs.edit().putLong(smsCursor,id))
                            read++;processed++
                        }
                    }?:error("SMS provider returned no cursor")
                }
            }
            if(includeDeviceSources&&AssistantNotificationListener.granted(context)){
                val pending=synchronized(SETTINGS_LOCK){notificationRows()}
                val handled=mutableSetOf<String>()
                for(row in pending.take(20)){
                    if(processed>=maxSources||SystemClock.elapsedRealtime()>deadline){more=true;break}
                    val id=row.getValue("id").jsonPrimitive.content
                    val pkg=row["package"]?.jsonPrimitive?.contentOrNull?:KAKAO
                    if(!canCaptureNotification(pkg)){handled+=id;continue}
                    val kind=if(pkg==KAKAO)"kakao_notification" else "email_notification"
                    val sourceId=if(pkg==KAKAO)"kakao:$id" else "email:$id"
                    val at=row.getValue("at").jsonPrimitive.long
                    val topics=row.getValue("topics").jsonArray.joinToString(" "){it.jsonPrimitive.content}
                    if(at>floor&&repository.knowledgeSourceAllowed(kind,sourceId,at)&&!stage {
                        learning.stage(kind,sourceId,at,row["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            group=row["group"]?.jsonPrimitive?.contentOrNull)
                    }){continue}
                    persist(kind,sourceId,at,KnowledgeExtraction.observedTopics(topics))
                    handled+=id
                    processed++
                }
                if(handled.isNotEmpty()){
                    eligible()
                    synchronized(SETTINGS_LOCK){
                        if(state().enabled&&state().generation==generation&&AssistantNotificationListener.granted(context)){
                            val remaining=notificationRows().filterNot {it.getValue("id").jsonPrimitive.content in handled}
                            notificationStore.write(if(remaining.isEmpty())""else JsonArray(remaining).toString())
                            more=more||remaining.isNotEmpty()
                        }
                    }
                }
                more=more||pending.size>handled.size
                val archive=BriefingArchive(context)
                val authority=if(kakaoAllowed()&&archive.enabled())archive.authorization() else ""
                if(authority.isNotBlank()){
                    val archived=archive.pending().mapNotNull {row->
                        if(row["source"]?.jsonPrimitive?.content!="notification")return@mapNotNull null
                        val body=row["body"] as? JsonObject?:return@mapNotNull null
                        if(body["package"]?.jsonPrimitive?.content!=KAKAO)return@mapNotNull null
                        val version=row.getValue("version").jsonPrimitive.content
                        Triple(digest(version),row.getValue("at").jsonPrimitive.long,body["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                    val liveVersions=archived.mapTo(mutableSetOf()){it.first}
                    val checked=synchronized(SETTINGS_LOCK){archiveVersions.readStrict().takeIf(String::isNotEmpty)
                        ?.let{Json.parseToJsonElement(it).jsonArray.mapTo(mutableSetOf()){item->item.jsonPrimitive.content}}
                        ?:mutableSetOf()}
                    checked.retainAll(liveVersions)
                    val unprocessed=archived.filter {it.first !in checked}
                    for((version,at,sourceText) in unprocessed.take(20)){
                        if(processed>=maxSources||SystemClock.elapsedRealtime()>deadline){more=true;break}
                        eligible()
                        check(kakaoAllowed()&&archive.authorization()==authority)
                        if(at>floor&&repository.knowledgeSourceAllowed("kakao_notification","kakao:$version",at)&&!stage {
                            learning.stage("kakao_notification","kakao:$version",at,sourceText,archiveAuthority=authority)
                        })break
                        persist("kakao_notification","kakao:$version",at,KnowledgeExtraction.observedTopics(sourceText))
                        checked+=version
                        processed++
                    }
                    eligible()
                    synchronized(SETTINGS_LOCK){
                        if(state().enabled&&state().generation==generation&&kakaoAllowed()&&archive.authorization()==authority)
                            archiveVersions.write(JsonArray(checked.map(::JsonPrimitive)).toString())
                    }
                    more=more||checked.size<liveVersions.size
                }
            }
            val seen=runCatching {Json.parseToJsonElement(prefs.getString("notification_seen","[]")!!).jsonArray.map {it.jsonPrimitive.content}.toMutableSet()}.getOrDefault(mutableSetOf())
            val notificationLimit=minOf(processed+10,maxSources)
            if(AssistantNotificationListener.granted(context))for(message in MessengerInbox.messages.value.take(30)){
                if(message.packageName==KAKAO||message.id in seen||!MessengerInbox.allowed(context,message.packageName))continue
                if(processed>=notificationLimit||SystemClock.elapsedRealtime()>deadline){more=true;break}
                eligible()
                persist("notification",message.id,message.at,KnowledgeExtraction.observedTopics(message.text))
                processed++;seen+=message.id
                checkpoint(prefs.edit().putString("notification_seen",JsonArray(seen.toList().takeLast(256).map(::JsonPrimitive)).toString()))
            }
            if(processed<maxSources&&SystemClock.elapsedRealtime()<=deadline){
                val at=prefs.getLong("message_at",floor);val id=prefs.getString("message_id","").orEmpty()
                val cursor=if(id.isBlank())null else MessageCursor(at,id)
                val page=repository.knowledgeMessagePage(cursor,minOf(50,maxSources-processed))
                more=more||page.nextCursor!=null
                for(message in page.messages){
                    if(processed>=maxSources||SystemClock.elapsedRealtime()>deadline){more=true;break}
                    eligible()
                    persist("user_message",message.id,message.createdAt,KnowledgeExtraction.directOwnerStatements(message.text,message.role))
                    processed++;checkpoint(prefs.edit().putLong("message_at",message.createdAt).putString("message_id",message.id))
                }
            }
            if(processed<maxSources&&SystemClock.elapsedRealtime()<=deadline){
                val limit=maxSources-processed
                val noteCursor=if(includeDeviceSources)"learning_note" else "note"
                val notes=repository.knowledgeNotePage(prefs.getLong("${noteCursor}_at",floor),prefs.getString("${noteCursor}_id","").orEmpty(),limit)
                more=more||notes.size>=limit
                for(note in notes){
                    if(processed>=maxSources||SystemClock.elapsedRealtime()>deadline){more=true;break}
                    eligible()
                    val current=repository.getNote(note.id)
                    if(current!=null&&current.updatedAt==note.updatedAt){
                        val kind=when(note.collection){"sms-schedule-candidates","native-sms"->"phone_sms";"imported-chats"->"conversation_import";else->"audio_transcript"}
                        if(includeDeviceSources&&note.updatedAt>floor&&note.collection in setOf("imported-chats","audio-transcripts","rawaudio-transcripts","native-sms")&&
                            repository.knowledgeSourceAllowed(kind,note.id,note.updatedAt)){
                            val material=if(note.collection=="imported-chats")note.body.substringAfter('\n',"") else note.body
                            if(!stage {learning.stage(kind,note.id,note.updatedAt,material,note.updatedAt,group=digest("note:"+note.id))})break
                        }
                        persist(kind,note.id,note.updatedAt,observedNote(note,floor))
                    }
                    processed++;checkpoint(prefs.edit().putLong("${noteCursor}_at",note.updatedAt).putString("${noteCursor}_id",note.id))
                }
            }
            eligible()
            drainOne?.let { drain ->
                val pass=drain();claims+=pass.saved;learningPass=pass
                more=more||learning.hasPending()
                learningStatus=pass.status.takeIf { it !in setOf("learning_idle","learning_batch_saved") }
            }
            val outcome=when {learningStatus!=null->checkNotNull(learningStatus);more->"incremental_checkpoint_saved";smsMissing->"sms_permission_missing";includeDeviceSources&&prefs.getBoolean("kakao_capture_gap",false)->"notification_capture_partial";else->"up_to_date"}
            val update=prefs.edit().putLong("last_success",System.currentTimeMillis())
                .putLong("processed",state().processedSources+processed).putLong("claims",state().savedClaims+claims)
                .putBoolean("has_more",more).putString("status",outcome)
            checkpoint(update)
            KnowledgeIngestionReport(processed,claims,more,outcome,learningPass?.checked?:0,learningPass?.saved?:0)
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){statusReport(generation,processed,claims,true,"source_or_generation_changed")}
        finally {activeScan=null;SCAN_MUTEX.unlock()}
    }
    private fun currentSmsSource(source: LearningSource): Boolean {
        val id=source.smsId?:return false
        if(!smsAllowed())return false
        return context.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf("body"),"_id = ? AND date = ?",
            arrayOf(id.toString(),source.at.toString()),null)?.use { result ->
            result.moveToFirst()&&result.getString(0).orEmpty().contains(source.text)
        }==true
    }

    private fun observedNote(note:NoteRecord,floor:Long):List<ExtractedKnowledgeFact> {
        if(note.collection=="sms-schedule-candidates"){
            val data=runCatching {Json.parseToJsonElement(note.body).jsonObject}.getOrNull()?:return emptyList()
            val originalAt=data["sourceTimestampMillis"]?.jsonPrimitive?.longOrNull
            if(originalAt!=null&&!KnowledgeExtraction.afterHistoricalErasure(originalAt,floor))return emptyList()
            val title=data["title"]?.jsonPrimitive?.content?.take(150)?:return emptyList()
            val start=data["startIso"]?.jsonPrimitive?.content?:return KnowledgeExtraction.observedTopics(title);val end=data["endIso"]?.jsonPrimitive?.content?:return KnowledgeExtraction.observedTopics(title)
            if(runCatching {check(OffsetDateTime.parse(end).isAfter(OffsetDateTime.parse(start)))}.isFailure)return KnowledgeExtraction.observedTopics(title)
            return listOf(ExtractedKnowledgeFact("record.schedule_candidate","$title · $start ~ $end","저장된 SMS 일정 후보의 제목·시간; 사용자 참석·건강·직업을 확정하지 않음",false,.8,listOf("schedule_candidate","not_owner_identity","requires_review")))
        }
        return KnowledgeExtraction.observedTopics(if(note.collection=="imported-chats")note.body.substringAfter('\n',"") else note.body)
    }
    private fun statusReport(generation:Long,processed:Int,claims:Int,more:Boolean,status:String):KnowledgeIngestionReport {
        synchronized(SETTINGS_LOCK){if(state().generation==generation&&state().enabled)prefs.edit().putString("status",status).putBoolean("has_more",more).commit()}
        return KnowledgeIngestionReport(processed,claims,more,status)
    }
    private fun deviceSafe(manual:Boolean):Boolean {
        if(!context.getSystemService(UserManager::class.java).isUserUnlocked)return false
        val power=context.getSystemService(PowerManager::class.java)
        if(power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE)return false
        if(manual)return true
        return !power.isPowerSaveMode&&!RuntimeGate.foreground.get()&&!RuntimeGate.benchmark.get()&&!RuntimeGate.inference.get()
    }
    private fun constraints()=Constraints.Builder().setRequiresBatteryNotLow(true).build()
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    companion object {
        private const val KAKAO="com.kakao.talk"
        private const val PREFS="local_knowledge_ingestion_v1"
        private const val LEGACY_PERIODIC="knowledge-ingestion-4h-v1"
        private const val PERIODIC="knowledge-arrival-reconcile-v1"
        private const val SMS_WATCH="knowledge-sms-arrival-v1"
        private val EMAIL_APPS=setOf("com.google.android.gm","com.nhn.android.mail","com.microsoft.office.outlook")
        private const val EVENT="knowledge-ingestion-incremental-v1"
        private val SETTINGS_LOCK=Any()
        private val SCAN_MUTEX=Mutex()
        @Volatile private var activeScan:Job?=null
    }
}

class KnowledgeIngestionWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters){
    override suspend fun doWork():Result {
        val app=(applicationContext as? LocalAgentApplication)?.container?:return Result.failure()
        val controller=KnowledgeIngestionController(applicationContext,app.repository)
        if(!controller.state().enabled)return Result.success()
        return try {
            val report=controller.scanNow(manual=false,includeDeviceSources=true)
            if(report.hasMore&&report.status=="incremental_checkpoint_saved")controller.requestInitialScan(append=true)
            else if(report.status in setOf("busy","learning_device_busy","deferred_device_state"))return Result.retry()
            Result.success()
        }
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){Result.retry()}
    }
}

/** JobScheduler observes granted providers and rearms after each notification. */
class KnowledgeSmsWatchWorker(context:Context, parameters:WorkerParameters):CoroutineWorker(context,parameters){
    override suspend fun doWork():Result {
        val controller=(applicationContext as? LocalAgentApplication)?.container?.knowledgeIngestion?:return Result.failure()
        controller.scheduleSmsWatch(append=true)
        controller.requestInitialScan()
        return Result.success()
    }
}
