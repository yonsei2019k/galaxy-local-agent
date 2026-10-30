package com.user.localagent.integrations

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class InboxMessage(
    val id:String,
    val packageName:String,
    val title:String,
    val text:String,
    val at:Long,
    val canReply:Boolean,
    val stableThread:Boolean,
    val conversation:String?=null,
    val sender:String?=null
)

/** Session-only inbox. Reading and replying are separately enabled by the phone user. */
object MessengerInbox {
    val supported=linkedMapOf("com.tionsoft.meettalk.eun" to "메디통 링크","com.meditong.gw" to "메디통","com.kakao.talk" to "카카오톡","org.telegram.messenger" to "텔레그램","org.telegram.messenger.web" to "텔레그램 Web")
    private data class Entry(val message:InboxMessage,val notificationKey:String,val thread:String?,val autoEvent:String?,val action:Notification.Action?,val input:RemoteInput?)
    private val entries=linkedMapOf<String,Entry>()
    private val mutableMessages=MutableStateFlow<List<InboxMessage>>(emptyList())
    val messages:StateFlow<List<InboxMessage>> = mutableMessages
    private const val ENTRY_TTL_MS=10*60_000L
    private const val MAX_RULES=12
    private const val MAX_HANDLED_PER_RULE=16
    private val expiryHandler=Handler(Looper.getMainLooper())
    private var expiryScheduled=false
    private val expiryTask=object:Runnable {
        override fun run() {
            synchronized(this@MessengerInbox) {
                pruneExpiredLocked(System.currentTimeMillis())
                if(entries.isNotEmpty()) expiryHandler.postDelayed(this,60_000L) else expiryScheduled=false
            }
        }
    }
    private fun prefs(context:Context)=context.getSharedPreferences("messenger-consent",Context.MODE_PRIVATE)
    fun allowed(context:Context,packageName:String)=packageName in supported && prefs(context).getBoolean(packageName,false)
    @Synchronized fun setAllowed(context:Context,packageName:String,enabled:Boolean){
        require(packageName in supported);prefs(context).edit().putBoolean(packageName,enabled).apply()
        if(!enabled){entries.entries.removeAll {it.value.message.packageName==packageName};publish();if(entries.isEmpty()){expiryHandler.removeCallbacks(expiryTask);expiryScheduled=false};val rules=readRules(context);rules.keys().asSequence().toList().filter {it.startsWith("$packageName:")}.forEach {rules.remove(it)};writeRules(context,rules)}
    }
    @Synchronized fun receive(context:Context,notification:StatusBarNotification){
        val pkg=notification.packageName
        pruneExpiredLocked(System.currentTimeMillis())
        if(!allowed(context,pkg)||notification.isOngoing||notification.notification.flags and Notification.FLAG_GROUP_SUMMARY!=0)return
        val n=notification.notification
        val title=n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(200).orEmpty()
        val text=n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(2000).orEmpty()
        if(title.isBlank()||text.isBlank())return
        val id=hash("$pkg:${notification.key}:${notification.postTime}:$text")
        if(entries.containsKey(id))return
        val actions=n.actions.orEmpty().flatMap {action->action.remoteInputs.orEmpty().filter {it.allowFreeFormInput}.map {action to it}}
            .filter {it.first.actionIntent.creatorPackage==pkg && !it.first.actionIntent.isImmutable}
        val reply=actions.singleOrNull()
        val auto=autoReplyMetadata(n,pkg)
        val thread=auto?.shortcutId?.let {"$pkg:"+hash(it)}
        val now=System.currentTimeMillis()
        val message=InboxMessage(id,pkg,title,text,now,reply!=null,thread!=null,auto?.conversation,auto?.sender)
        entries[id]=Entry(message,notification.key,thread,auto?.eventHash,reply?.first,reply?.second)
        while(entries.size>30)entries.remove(entries.keys.first())
        scheduleExpiryLocked()
        publish()
        // A rule is a fixed, user-approved acknowledgement, never model-authored free-form sending.
        if(thread!=null&&reply!=null&&auto!=null&&com.user.localagent.automation.AssistantNotificationListener.granted(context)){
            val rules=readRules(context);val rule=rules.optJSONObject(thread)?:return
            val day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()
            val count=if(rule.optString("day")==day)rule.optInt("count")else 0
            if(now>rule.optLong("expires")||now-rule.optLong("last")<60*60_000||count>=3)return
            val handled=rule.optJSONArray("handled")?:JSONArray()
            if((0 until handled.length()).any {handled.optString(it)==auto.eventHash})return
            // Reserve BEFORE sending. Failure never retries an external send automatically.
            handled.put(auto.eventHash)
            while(handled.length()>MAX_HANDLED_PER_RULE)handled.remove(0)
            rule.put("handled",handled).put("last",now).put("day",day).put("count",count+1);writeRules(context,rules)
            runCatching {dispatch(context,entries.getValue(id),rule.getString("text"))}
        }
    }
    private data class AutoReplyMetadata(val shortcutId:String,val conversation:String,val sender:String,val eventHash:String)

    /** Only message-style inbound events with distinct sender identity are eligible for unattended replies. */
    private fun autoReplyMetadata(notification:Notification,packageName:String):AutoReplyMetadata? {
        if(notification.category!=Notification.CATEGORY_MESSAGE)return null
        val style=NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?:return null
        val shortcutId=notification.shortcutId?.takeIf {it.isNotBlank()}?:return null
        val conversation=(style.conversationTitle?.toString()
            ?:notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString())
            ?.trim()?.take(200)?.takeIf {it.isNotBlank()}?:return null
        val self=style.user?:return null
        val latest=style.messages.lastOrNull()?:return null
        val sender=latest.person?:return null // null identifies the local user's outgoing message
        val senderName=sender.name?.toString()?.trim()?.take(200)?.takeIf {it.isNotBlank()}?:return null
        val senderKey=sender.key?.takeIf {it.isNotBlank()}?:return null
        val selfKey=self.key?.takeIf {it.isNotBlank()}?:return null
        if(senderKey==selfKey)return null
        val body=latest.text?.toString()?.take(2000)?.takeIf {it.isNotBlank()}?:return null
        val event=hash("$packageName|$shortcutId|${latest.timestamp}|$senderKey|$body")
        return AutoReplyMetadata(shortcutId,conversation,senderName,event)
    }
    /** Caller must display recipient+text and obtain approval immediately before this call. */
    @Synchronized fun replyConfirmed(context:Context,id:String,text:String){
        val entry=entries[id]?:error("알림이 만료되었습니다.");dispatch(context,entry,text)
        entries.remove(id);publish()
    }
    /** Explicit per-thread template approval. Stable Android conversation ID is mandatory. */
    @Synchronized fun approveAcknowledgementRule(context:Context,id:String,text:String){
        require(text.isNotBlank()&&text.length<=500)
        val entry=entries[id]?:error("알림이 만료되었습니다.")
        require(allowed(context,entry.message.packageName)&&entry.message.canReply)
        val thread=entry.thread?:error("이 앱 알림은 안정적인 대화방 ID가 없어 자동 답장을 허용하지 않습니다.")
        require(com.user.localagent.automation.AssistantNotificationListener.granted(context)) {"Android 알림 접근을 허용해야 자동 답장을 사용할 수 있습니다."}
        require(entry.message.stableThread&&entry.message.conversation!=null&&entry.message.sender!=null) {"안전한 대화방·보낸 사람 정보를 확인할 수 없어 자동 답장을 허용하지 않습니다."}
        val rules=readRules(context)
        val now=System.currentTimeMillis()
        rules.keys().asSequence().toList().filter {rules.optJSONObject(it)?.optLong("expires",0L)?.let {expires->expires<now}==true}.forEach {rules.remove(it)}
        require(rules.has(thread)||rules.length()<MAX_RULES) {"자동 답장 규칙이 가득 찼습니다. 기존 규칙을 먼저 지워주세요."}
        rules.put(thread,JSONObject().put("text",text).put("expires",now+24*60*60_000L).put("last",0).put("count",0).put("handled",JSONArray()));writeRules(context,rules)
    }
    @Synchronized fun clear(context:Context){entries.clear();publish();expiryHandler.removeCallbacks(expiryTask);expiryScheduled=false;writeRules(context,JSONObject())}
    @Synchronized fun clearSession(){entries.clear();publish();expiryHandler.removeCallbacks(expiryTask);expiryScheduled=false}
    @Synchronized fun removeNotification(notificationKey:String){
        entries.entries.removeAll {it.value.notificationKey==notificationKey}
        publish()
        if(entries.isEmpty()){expiryHandler.removeCallbacks(expiryTask);expiryScheduled=false}
    }
    private fun dispatch(context:Context,entry:Entry,text:String){
        require(allowed(context,entry.message.packageName));require(com.user.localagent.automation.AssistantNotificationListener.granted(context)) {"Android 알림 접근이 꺼져 있습니다."}
        require(System.currentTimeMillis()-entry.message.at<ENTRY_TTL_MS)
        require(text.isNotBlank()&&text.length<=2000)
        val action=entry.action?:error("이 알림은 답장 기능을 제공하지 않습니다.")
        val input=entry.input?:error("답장 입력이 없습니다.")
        require(action.actionIntent.creatorPackage==entry.message.packageName)
        val fill=Intent();RemoteInput.addResultsToIntent(arrayOf(input),fill,Bundle().apply {putCharSequence(input.resultKey,text)})
        action.actionIntent.send(context,0,fill)
    }
    private fun publish(){mutableMessages.value=entries.values.map {it.message}.reversed()}
    private fun scheduleExpiryLocked(){if(!expiryScheduled){expiryScheduled=true;expiryHandler.postDelayed(expiryTask,ENTRY_TTL_MS)}}
    private fun pruneExpiredLocked(now:Long){
        entries.entries.removeAll {now-it.value.message.at>=ENTRY_TTL_MS}
        publish()
        if(entries.isEmpty()){expiryHandler.removeCallbacks(expiryTask);expiryScheduled=false}
    }
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun key():SecretKey{val alias="ziphap-reply-rules-v1";val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)};if(!store.containsAlias(alias))KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey();return store.getKey(alias,null) as SecretKey}
    private fun readRules(context:Context):JSONObject{val file=java.io.File(context.filesDir,"reply-rules.enc");if(!file.exists())return JSONObject();return runCatching {val bytes=file.readBytes();require(bytes.size in 29..65536);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));JSONObject(cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8))}.getOrDefault(JSONObject())}
    private fun writeRules(context:Context,rules:JSONObject){val bytes=rules.toString().toByteArray();require(bytes.size<60000);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val file=java.io.File(context.filesDir,"reply-rules.enc");val temp=java.io.File(context.filesDir,"reply-rules.tmp");temp.outputStream().use {it.write(cipher.iv);it.write(cipher.doFinal(bytes))};check(temp.renameTo(file))}
}
