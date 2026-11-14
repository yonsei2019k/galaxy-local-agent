package com.user.localagent.briefing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.provider.CallLog
import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Official providers and notifications only; no other app's database or UI automation. */
object BriefingSources {
    private val captureScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val notifications=Channel<Pair<Context,StatusBarNotification>>(64)
    init {captureScope.launch {for((context,event) in notifications)runCatching {notification(context,event)}.onFailure {
        context.getSharedPreferences("briefing-capture-status",Context.MODE_PRIVATE).edit().putBoolean("captureIncomplete",true).apply()
    }}}
    fun enqueue(context:Context,event:StatusBarNotification) {
        if(!notifications.trySend(context.applicationContext to event).isSuccess)
            context.getSharedPreferences("briefing-capture-status",Context.MODE_PRIVATE).edit().putBoolean("captureIncomplete",true).apply()
    }
    fun notificationAllowed(context:Context,pkg:String):Boolean = pkg in notificationApps &&
        (pkg !in com.user.localagent.integrations.MessengerInbox.supported || com.user.localagent.integrations.MessengerInbox.allowed(context,pkg))
    fun permitted(context:Context,source:String):Boolean=when(source) {
        "sms"->ContextCompat.checkSelfPermission(context,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED
        "call_log"->ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED
        "notification"->com.user.localagent.automation.AssistantNotificationListener.granted(context)
        else->false
    }
    val notificationApps=setOf("com.kakao.talk","com.tionsoft.meettalk.eun","com.meditong.gw",
        "org.telegram.messenger","org.telegram.messenger.web","com.google.android.gm","com.nhn.android.mail","com.microsoft.office.outlook")
    fun notification(context:Context,event:StatusBarNotification) {
        val archive=BriefingArchive(context)
        if(!archive.enabled()||!notificationAllowed(context,event.packageName)||event.isOngoing||event.notification.flags and Notification.FLAG_GROUP_SUMMARY!=0)return
        val extras=event.notification.extras
        val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val style=NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(event.notification)
        val messages=style?.messages.orEmpty()
        if(messages.isNotEmpty())messages.forEach {message->
            val text=message.text?.toString().orEmpty();if(text.isNotBlank())archive.capture("notification",
                BriefingArchive.sha("${event.packageName}:${event.key}:${message.timestamp}:${message.person?.key}:${text}"),
                message.timestamp.coerceAtLeast(0),buildJsonObject {
                    put("package",event.packageName);put("title",title.take(500));put("text",text.take(8000))
                    put("sender",message.person?.name?.toString()?.take(300));put("partial",text.length>8000);put("fullTextSha256",BriefingArchive.sha(text))
                    put("coverage","notification_only_not_full_conversation")
                })
        } else {
            val text=(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?:extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if(text.isNotBlank())archive.capture("notification",BriefingArchive.sha("${event.packageName}:${event.key}:${event.postTime}:$text"),
                event.postTime.coerceAtLeast(0),buildJsonObject {
                    put("package",event.packageName);put("title",title.take(500));put("text",text.take(8000))
                    put("partial",true);put("fullTextSha256",BriefingArchive.sha(text));put("coverage","notification_excerpt_not_full_message")
                })
        }
    }
    /** Bounded scan pages restart from the beginning after exhaustion so changed versions are revisited. */
    fun collect(context:Context):JsonObject {
        val archive=BriefingArchive(context);check(archive.enabled())
        val cursorStore=com.user.localagent.data.DraftStore(context,"codex-briefing-source-cursors-v1",2000)
        val cursors=cursorStore.readStrict().takeIf {it.isNotBlank()}?.let {Json.parseToJsonElement(it).jsonObject}?:JsonObject(emptyMap())
        val next=cursors.toMutableMap();val coverage=mutableMapOf<String,JsonElement>()
        fun scan(name:String,permission:String,uri:android.net.Uri,columns:Array<String>,body:(android.database.Cursor)->JsonObject) {
            if(ContextCompat.checkSelfPermission(context,permission)!=PackageManager.PERMISSION_GRANTED){coverage[name]=JsonPrimitive("permission_missing");return}
            // New arrivals must not wait behind the initial historical queue. Completed versions dedupe here.
            context.contentResolver.query(uri,columns,"date >= ?",arrayOf(archive.startEpochMillis().toString()),"_id DESC")?.use {recent->
                var count=0
                while(count<12&&recent.moveToNext()) {
                    check(ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED)
                    archive.capture(name,recent.getLong(0).toString(),recent.getLong(1).coerceAtLeast(0),body(recent));count++
                }
            }?:error("최근 자료 제공자가 조회 결과를 반환하지 않았습니다.")
            if(archive.pending().size>=48){coverage[name]=JsonPrimitive("pending_batch_first");return}
            val after=cursors[name]?.jsonPrimitive?.long?:-1L
            var visited=0;var last=after;var exhausted=true
            context.contentResolver.query(uri,columns,"_id > ? AND date >= ?",arrayOf(after.toString(),archive.startEpochMillis().toString()),"_id ASC")?.use {c->
                while(c.moveToNext()) {
                    if(visited>=128||archive.pending().size>=48){exhausted=false;break}
                    check(ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED)
                    val id=c.getLong(0);val at=c.getLong(1).coerceAtLeast(0)
                    archive.capture(name,id.toString(),at,body(c));last=id;visited++
                }
            }?:error("자료 제공자가 조회 결과를 반환하지 않았습니다.")
            next[name]=JsonPrimitive(if(exhausted)-1L else last)
            cursorStore.write(JsonObject(next).toString())
            coverage[name]=buildJsonObject {put("scanned",visited);put("pageExhausted",exhausted);put("wholeHistoryVerified",false)}
        }
        synchronized(BriefingArchive.LOCK) {
            scan("sms",Manifest.permission.READ_SMS,Telephony.Sms.CONTENT_URI,arrayOf("_id","date","address","type","thread_id","body")) {c->
                val text=c.getString(5).orEmpty();buildJsonObject {
                    put("address",c.getString(2));put("type",c.getInt(3));put("thread",c.getLong(4));put("text",text.take(8000))
                    put("partial",text.length>8000);put("fullTextSha256",BriefingArchive.sha(text));put("coverage","sms_provider_mms_excluded")
                }
            }
            scan("call_log",Manifest.permission.READ_CALL_LOG,CallLog.Calls.CONTENT_URI,arrayOf("_id","date","number","type","duration")) {c->buildJsonObject {
                put("number",c.getString(2));put("type",c.getInt(3));put("durationSeconds",c.getLong(4))
                put("text","통화기록만 확보. 통화 내용은 확보하지 않았습니다.");put("partial",true);put("coverage","call_metadata_only")
            }}
        }
        return JsonObject(coverage)
    }
}
