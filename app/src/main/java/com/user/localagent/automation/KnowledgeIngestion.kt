package com.user.localagent.automation

import android.Manifest
import android.app.Notification
import android.service.notification.StatusBarNotification
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import android.os.BatteryManager
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
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalTime
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class KnowledgeIngestionState(val enabled:Boolean,val generation:Long,val lastSuccessAt:Long,val lastStatus:String,val processedSources:Long,val savedClaims:Long,val hasMore:Boolean,val nightlyEnabled:Boolean)
data class KnowledgeIngestionReport(val processedSources:Int,val savedClaims:Int,val hasMore:Boolean,val status:String)
private class IngestionInterrupted:CancellationException("Knowledge ingestion generation changed")

/** Incremental source-linked local extraction. No network, model loading, or weight training. */
class KnowledgeIngestionController(context:Context,private val repository:LocalRepository) {
    private val context=context.applicationContext
    private val prefs=this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private val notificationStore=DraftStore(this.context,"knowledge-kakao-observations-v1",200_000)
    private val archiveVersions=DraftStore(this.context,"knowledge-kakao-archive-versions-v1",100_000)
    fun state()=KnowledgeIngestionState(prefs.getBoolean("enabled",false),prefs.getLong("generation",0),prefs.getLong("last_success",0),prefs.getString("status","disabled")?:"disabled",prefs.getLong("processed",0),prefs.getLong("claims",0),prefs.getBoolean("has_more",false),prefs.getBoolean("nightly_enabled",false))

    /** Authorized host/UI action. Disabling waits until any in-flight publication has stopped. */
    suspend fun setEnabled(enabled:Boolean):KnowledgeIngestionState=withContext(Dispatchers.IO){
        synchronized(SETTINGS_LOCK){
            check(prefs.edit().putBoolean("enabled",enabled).putLong("generation",state().generation+1)
                .putString("status",if(enabled)"scheduled" else "disabled").commit())
            if(!enabled){
                check(prefs.edit().putBoolean("nightly_enabled",false).commit())
                notificationStore.write("")
            }
        }
        activeScan?.cancel(IngestionInterrupted())
        WorkManager.getInstance(context).cancelUniqueWork(EVENT)
        if(enabled){schedule();requestInitialScan()}else WorkManager.getInstance(context).apply { cancelUniqueWork(PERIODIC); cancelUniqueWork(LEGACY_PERIODIC) }
        SCAN_MUTEX.withLock {state()}
    }
    suspend fun setNightlyEnabled(enabled:Boolean):KnowledgeIngestionState=withContext(Dispatchers.IO){
        synchronized(SETTINGS_LOCK){
            val current=state()
            check(current.enabled)
            check(prefs.edit().putBoolean("nightly_enabled",enabled).putLong("generation",current.generation+1)
                .remove("last_night_complete").commit())
            if(!enabled)notificationStore.write("")
        }
        activeScan?.cancel(IngestionInterrupted())
        schedule()
        SCAN_MUTEX.withLock {state()}
    }
    fun schedule(){
        val current=state()
        if(!current.enabled)return
        val work=WorkManager.getInstance(context)
        if(!current.nightlyEnabled){
            work.cancelUniqueWork(PERIODIC)
            work.enqueueUniquePeriodicWork(LEGACY_PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<KnowledgeIngestionWorker>(4,TimeUnit.HOURS).setConstraints(constraints()).build())
            return
        }
        work.cancelUniqueWork(LEGACY_PERIODIC)
        val now=ZonedDateTime.now(NIGHT_ZONE)
        val target=now.toLocalDate().atTime(NIGHT_START).atZone(NIGHT_ZONE)
        val next=if(now.isBefore(target))target else if(now.toLocalTime().isBefore(NIGHT_END))now else target.plusDays(1)
        work.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<KnowledgeIngestionWorker>(1,TimeUnit.HOURS)
                .setInputData(workDataOf("nightly" to true)).setConstraints(constraints())
                .setInitialDelay(Duration.between(now,next).toMillis(),TimeUnit.MILLISECONDS).build())
    }
    fun requestInitialScan(append:Boolean=false){
        if(!state().enabled)return
        WorkManager.getInstance(context).enqueueUniqueWork(EVENT,if(append)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<KnowledgeIngestionWorker>().setConstraints(constraints()).build())
    }
    /** Collect bounded observations from newly received, consented notifications for the next night. */
    fun captureKakaoNotification(notification:StatusBarNotification) {
        if(notification.packageName!=KAKAO||notification.isOngoing||
            notification.notification.flags and Notification.FLAG_GROUP_SUMMARY!=0)return
        if(!canCaptureKakao())return
        val values=notification.notification.extras
        val body=(values.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?:values.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val facts=KnowledgeExtraction.observedTopics(body)
        if(facts.isEmpty())return
        val at=notification.postTime
        val id=digest("${notification.key}|$at|$body")
        synchronized(SETTINGS_LOCK){
            if(!canCaptureKakao()||at<=prefs.getLong("erased_through",0))return
            val pending=notificationRows()
            if(pending.any {it.getValue("id").jsonPrimitive.content==id})return
            if(pending.size>=512){
                check(prefs.edit().putBoolean("kakao_capture_gap",true).commit())
                return
            }
            notificationStore.write(JsonArray(pending+buildJsonObject {
                put("id",id);put("at",at);put("topics",JsonArray(facts.map {JsonPrimitive(it.value)}))
            }).toString())
        }
    }
    fun recordKakaoCaptureGap() {
        synchronized(SETTINGS_LOCK){if(canCaptureKakao())prefs.edit().putBoolean("kakao_capture_gap",true).apply()}
    }
    fun canCaptureKakao():Boolean {
        val current=state()
        return current.enabled&&current.nightlyEnabled&&kakaoAllowed()
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
    }
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
        SCAN_MUTEX.withLock {repository.clearKnowledge()}
    }

    /** Explicit settings action, using the same source cursors as the scheduled scan. */
    suspend fun scanConnectedNow(onProgress:(KnowledgeIngestionReport)->Unit={}):KnowledgeIngestionReport {
        var total=KnowledgeIngestionReport(0,0,true,"started")
        repeat(80){
            val pass=scanNow(manual=true,maxSources=200,includeDeviceSources=true)
            total=pass.copy(processedSources=total.processedSources+pass.processedSources,
                savedClaims=total.savedClaims+pass.savedClaims)
            onProgress(total)
            if(!pass.hasMore||pass.status!="incremental_checkpoint_saved"||pass.processedSources==0)return total
            yield()
        }
        return total
    }

    /** Explicit initial/manual scan API; background workers additionally require charging/idle constraints. */
    suspend fun scanNow(manual:Boolean=true,maxSources:Int=100,nightly:Boolean=false,includeDeviceSources:Boolean=nightly):KnowledgeIngestionReport=withContext(Dispatchers.IO){
        require(maxSources in 1..200&&(manual||nightly||!includeDeviceSources))
        if(!SCAN_MUTEX.tryLock())return@withContext KnowledgeIngestionReport(0,0,true,"busy")
        val generation=state().generation
        var processed=0;var claims=0;var more=false
        try {
            activeScan=currentCoroutineContext()[Job]
            if(!state().enabled)return@withContext KnowledgeIngestionReport(0,0,false,"disabled")
            if(!deviceSafe(manual))return@withContext statusReport(generation,0,0,false,"deferred_device_state")
            val today=LocalDate.now(NIGHT_ZONE).toString()
            if(nightly&&(!state().nightlyEnabled||ZonedDateTime.now(NIGHT_ZONE).toLocalTime().let {it<NIGHT_START||it>=NIGHT_END}||
                    prefs.getString("last_night_complete",null)==today))
                return@withContext KnowledgeIngestionReport(0,0,false,"outside_night_window_or_current")
            val epoch=repository.knowledgeEpoch();val floor=prefs.getLong("erased_through",0)
            val deadline=SystemClock.elapsedRealtime()+20000
            suspend fun eligible(){currentCoroutineContext().ensureActive();if(!state().enabled||state().generation!=generation||repository.knowledgeEpoch()!=epoch)throw IngestionInterrupted()}
            suspend fun persist(kind:String,id:String,at:Long,facts:List<ExtractedKnowledgeFact>){
                fun permitted()=when(kind){"sms_provider"->smsAllowed();"kakao_notification"->kakaoAllowed();else->true}
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
            var smsMissing=false
            if(includeDeviceSources){
                smsMissing=!smsAllowed()
                if(!smsMissing){
                    val after=prefs.getLong("nightly_sms_id",-1)
                    val limit=minOf(if(manual)120 else 40,maxSources/2)
                    if(limit>0)context.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf("_id","date","body"),
                        "_id > ? AND date > ?",arrayOf(after.toString(),floor.toString()),"_id ASC")?.use {rows->
                        var read=0
                        while(rows.moveToNext()){
                            if(read>=limit||SystemClock.elapsedRealtime()>deadline){more=true;break}
                            eligible()
                            check(smsAllowed())
                            val id=rows.getLong(0)
                            val at=rows.getLong(1)
                            val body=rows.getString(2).orEmpty()
                            persist("sms_provider","sms:"+digest("$id|$at"),at,KnowledgeExtraction.observedTopics(body))
                            checkpoint(prefs.edit().putLong("nightly_sms_id",id))
                            read++;processed++
                        }
                    }?:error("SMS provider returned no cursor")
                }
            }
            if(includeDeviceSources&&kakaoAllowed()){
                val pending=synchronized(SETTINGS_LOCK){notificationRows()}
                val handled=mutableSetOf<String>()
                for(row in pending.take(20)){
                    if(processed>=maxSources||SystemClock.elapsedRealtime()>deadline||!kakaoAllowed()){more=true;break}
                    val id=row.getValue("id").jsonPrimitive.content
                    val at=row.getValue("at").jsonPrimitive.long
                    val topics=row.getValue("topics").jsonArray.joinToString(" "){it.jsonPrimitive.content}
                    persist("kakao_notification","kakao:$id",at,KnowledgeExtraction.observedTopics(topics))
                    handled+=id
                    processed++
                }
                if(handled.isNotEmpty()){
                    eligible()
                    synchronized(SETTINGS_LOCK){
                        if(state().enabled&&state().generation==generation&&kakaoAllowed()){
                            val remaining=notificationRows().filterNot {it.getValue("id").jsonPrimitive.content in handled}
                            notificationStore.write(if(remaining.isEmpty())""else JsonArray(remaining).toString())
                            more=more||remaining.isNotEmpty()
                        }
                    }
                }
                more=more||pending.size>handled.size
                val archive=BriefingArchive(context)
                val authority=if(archive.enabled())archive.authorization() else ""
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
                if((message.packageName==KAKAO&&state().nightlyEnabled)||message.id in seen||!MessengerInbox.allowed(context,message.packageName))continue
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
                val notes=repository.knowledgeNotePage(prefs.getLong("note_at",floor),prefs.getString("note_id","").orEmpty(),limit)
                more=more||notes.size>=limit
                for(note in notes){
                    if(processed>=maxSources||SystemClock.elapsedRealtime()>deadline){more=true;break}
                    eligible()
                    val current=repository.getNote(note.id)
                    if(current!=null&&current.updatedAt==note.updatedAt){
                        val kind=when(note.collection){"sms-schedule-candidates","native-sms"->"phone_sms";"imported-chats"->"conversation_import";else->"audio_transcript"}
                        persist(kind,note.id,note.updatedAt,observedNote(note,floor))
                    }
                    processed++;checkpoint(prefs.edit().putLong("note_at",note.updatedAt).putString("note_id",note.id))
                }
            }
            eligible()
            val outcome=when {more->"incremental_checkpoint_saved";smsMissing->"sms_permission_missing";includeDeviceSources&&prefs.getBoolean("kakao_capture_gap",false)->"nightly_kakao_capture_partial";else->"up_to_date"}
            val update=prefs.edit().putLong("last_success",System.currentTimeMillis())
                .putLong("processed",state().processedSources+processed).putLong("claims",state().savedClaims+claims)
                .putBoolean("has_more",more).putString("status",outcome)
            if(nightly&&!more)update.putString("last_night_complete",today)
            checkpoint(update)
            KnowledgeIngestionReport(processed,claims,more,outcome)
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){statusReport(generation,processed,claims,true,"source_or_generation_changed")}
        finally {activeScan=null;SCAN_MUTEX.unlock()}
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
        val battery=context.getSystemService(BatteryManager::class.java)
        return battery.isCharging&&!power.isPowerSaveMode&&!RuntimeGate.foreground.get()&&!RuntimeGate.benchmark.get()&&!RuntimeGate.inference.get()
    }
    private fun constraints()=Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).setRequiresDeviceIdle(true).build()
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    companion object {
        private const val KAKAO="com.kakao.talk"
        private const val PREFS="local_knowledge_ingestion_v1"
        private const val LEGACY_PERIODIC="knowledge-ingestion-4h-v1"
        private const val PERIODIC="knowledge-ingestion-nightly-v1"
        private val NIGHT_ZONE=ZoneId.of("Asia/Seoul")
        private val NIGHT_START=LocalTime.of(3,0)
        private val NIGHT_END=LocalTime.of(9,0)
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
            val nightly=inputData.getBoolean("nightly",false)
            val report=controller.scanNow(manual=false,nightly=nightly)
            if(!nightly&&report.hasMore&&report.status=="incremental_checkpoint_saved")controller.requestInitialScan(append=true)
            Result.success()
        }
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){Result.retry()}
    }
}
