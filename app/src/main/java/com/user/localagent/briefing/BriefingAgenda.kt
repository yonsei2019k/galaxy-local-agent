package com.user.localagent.briefing

import android.content.Context
import android.content.ContentUris
import android.provider.CalendarContract
import com.user.localagent.AppContainer
import com.user.localagent.core.BriefingEventPolicy
import com.user.localagent.core.ScheduleCandidate
import com.user.localagent.data.DraftStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.OffsetDateTime

/** Encrypted durable review queue. Stable item IDs plus CalendarGateway markers recover uncertain inserts. */
class BriefingAgenda(private val context:Context,private val app:AppContainer,
    private val storageKey:String="codex-briefing-agenda-v1",private val isEnabled:()->Boolean={BriefingArchive(context).enabled()}) {
    private val store=DraftStore(context,storageKey,2_000_000)
    fun state():JsonObject=store.readStrict().takeIf {it.isNotBlank()}?.let {Json.parseToJsonElement(it).jsonObject}
        ?:buildJsonObject {put("calendarId",JsonNull);put("items",JsonArray(emptyList()))}
    fun items():List<JsonObject> = (state()["items"] as? JsonArray).orEmpty().map {it.jsonObject}
    private fun save(value:JsonObject){store.write(value.toString());check(state()==value)}
    suspend fun setCalendar(id:Long)=LOCK.withLock {
        check(app.calendarGateway.listWritableCalendars().any {it.id==id})
        save(JsonObject(state()+mapOf("calendarId" to JsonPrimitive(id))))
    }
    suspend fun ingest(version:String,source:JsonObject,events:JsonArray):List<JsonObject> = LOCK.withLock {
        require(events.size<=8)
        val before=state();val list=items().toMutableList();val result=mutableListOf<JsonObject>()
        check(list.size+events.size<=1000){"일정 검토함이 가득 찼습니다."}
        for((index,raw) in events.withIndex()) {
            val event=raw.jsonObject
            val allowed=setOf("title","startIso","endIso","confirmed","ownerRelevant","changed","quote","reason","location","kind","recipient","deliveryMethod")
            require(event.keys==allowed)
            for(key in setOf("title","quote","reason","location","kind","recipient","deliveryMethod"))require(event.getValue(key).jsonPrimitive.content.length<=1000)
            require(event.getValue("title").jsonPrimitive.content.length in 1..300)
            val text=source.getValue("body").jsonObject.getValue("text").jsonPrimitive.content
            val quote=event.getValue("quote").jsonPrimitive.content;require(quote.isNotBlank()&&quote in text)
            val id=BriefingArchive.sha("$version:$index")
            val existing=list.firstOrNull {it["id"]?.jsonPrimitive?.content==id}
            if(existing!=null){result+=existing;continue}
            val partial=source.getValue("body").jsonObject["partial"]?.jsonPrimitive?.boolean!=false
            val block=BriefingEventPolicy.automaticBlockReason(event.getValue("confirmed").jsonPrimitive.boolean,
                event.getValue("ownerRelevant").jsonPrimitive.boolean,event.getValue("changed").jsonPrimitive.boolean,partial,
                event.getValue("title").jsonPrimitive.content,event["startIso"]?.jsonPrimitive?.contentOrNull,event["endIso"]?.jsonPrimitive?.contentOrNull,
                quote,text,System.currentTimeMillis())
            var item=JsonObject(event+mapOf("id" to JsonPrimitive(id),"version" to JsonPrimitive(version),"sourceAt" to source.getValue("at"),
                "sourceSha256" to source.getValue("sha256"),"status" to JsonPrimitive("needs_review"),"reviewReason" to JsonPrimitive(block?:"등록할 캘린더를 선택해주세요.")))
            list+=item;save(JsonObject(before+mapOf("items" to JsonArray(list)))) // Persist before any calendar side effect.
            val calendar=before["calendarId"]?.jsonPrimitive?.longOrNull
            if(block==null&&calendar!=null&&isEnabled()) {
                item=try {register(item,calendar,false)}catch(error:Exception){JsonObject(item+mapOf("reviewReason" to JsonPrimitive(error.message?.take(400)?:"캘린더 저장 확인 필요")))}
                list[list.lastIndex]=item;save(JsonObject(before+mapOf("items" to JsonArray(list))))
            }
            result+=item
        }
        result
    }
    suspend fun review(id:String,title:String,start:String,end:String,location:String,reject:Boolean=false):JsonObject=LOCK.withLock {
        val before=state();val list=items().toMutableList();val index=list.indexOfFirst {it["id"]?.jsonPrimitive?.content==id};check(index>=0)
        val old=list[index];check(old["status"]?.jsonPrimitive?.content!="registered"){"이미 등록된 일정은 캘린더에서 수정해주세요."}
        val item=if(reject)JsonObject(old+mapOf("status" to JsonPrimitive("dismissed")))else {
            require(title.isNotBlank()&&title.length<=300&&location.length<=1000)
            val a=OffsetDateTime.parse(start);val b=OffsetDateTime.parse(end);require(b.isAfter(a))
            val calendar=before["calendarId"]?.jsonPrimitive?.longOrNull?:error("먼저 저장할 캘린더를 선택해주세요.")
            val updated=JsonObject(old+mapOf("title" to JsonPrimitive(title),"startIso" to JsonPrimitive(start),"endIso" to JsonPrimitive(end),"location" to JsonPrimitive(location)))
            // Persist the owner-reviewed values before a recoverable calendar write.
            list[index]=updated;save(JsonObject(before+mapOf("items" to JsonArray(list))))
            register(updated,calendar,true)
        }
        list[index]=item;save(JsonObject(before+mapOf("items" to JsonArray(list))));BriefingWidget.updateAll(context);item
    }
    private suspend fun register(item:JsonObject,calendarId:Long,reviewed:Boolean):JsonObject {
        val start=item.getValue("startIso").jsonPrimitive.content;val end=item.getValue("endIso").jsonPrimitive.content
        if(!reviewed&&conflicts(start,end))return JsonObject(item+mapOf("reviewReason" to JsonPrimitive("기존 일정과 시간이 겹칩니다. 확인 후 등록해주세요.")))
        val details=listOf("장소: "+item.getValue("location").jsonPrimitive.content,"대상/제출처: "+item.getValue("recipient").jsonPrimitive.content,
            "전달 방법: "+item.getValue("deliveryMethod").jsonPrimitive.content,"근거: "+item.getValue("quote").jsonPrimitive.content).joinToString("\n")
        val candidate=ScheduleCandidate(item.getValue("id").jsonPrimitive.content,item.getValue("version").jsonPrimitive.content,
            item.getValue("sourceAt").jsonPrimitive.long,item.getValue("sourceSha256").jsonPrimitive.content,item.getValue("title").jsonPrimitive.content,
            start,end,false,null,details)
        val saved=app.calendarGateway.saveApproved(listOf(candidate),calendarId)
        check(saved.failures.isEmpty()){saved.failures.first().reason}
        val result=saved.events.single()
        return JsonObject(item+mapOf("status" to JsonPrimitive("registered"),"eventUri" to JsonPrimitive(result.eventUri),
            "eventId" to JsonPrimitive(result.eventId),"calendarId" to JsonPrimitive(calendarId),"reviewReason" to JsonPrimitive("저장 후 캘린더 재조회 완료")))
    }
    private fun conflicts(start:String,end:String):Boolean {
        val a=OffsetDateTime.parse(start).toInstant().toEpochMilli();val b=OffsetDateTime.parse(end).toInstant().toEpochMilli()
        val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().also {ContentUris.appendId(it,a);ContentUris.appendId(it,b)}.build()
        return context.contentResolver.query(uri,arrayOf(CalendarContract.Instances.EVENT_ID),null,null,null)?.use {it.moveToFirst()}
            ?:error("기존 일정과의 충돌을 확인하지 못했습니다.")
    }
    companion object {private val LOCK=Mutex()}
}
