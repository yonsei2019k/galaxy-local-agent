package com.user.localagent.briefing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Base64
import com.user.localagent.LocalAgentApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Approved ADB developer companion only: DUMP permission AND exact shell UID, never a public app API. */
class BriefingProvider:ContentProvider() {
    override fun onCreate()=true
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        check(Binder.getCallingUid()==2000){"승인된 ADB 개발 연결만 사용할 수 있습니다."}
        return synchronized(CALL_LOCK) {
            val ctx=checkNotNull(context);val archive=BriefingArchive(ctx)
            val app=(ctx.applicationContext as LocalAgentApplication).container
            val result=when(method) {
                "status"->JsonObject(archive.status()+mapOf("authorization" to JsonPrimitive(archive.authorization())))
                "enable"->{check(arg=="full_context_codex_explicit_owner_consent_v1");archive.setEnabled(true);archive.status()}
                "disable"->{archive.setEnabled(false);archive.status()}
                "pending","probe"->{
                    check(archive.enabled());check(!app.runs.busy.value&&!app.imageStudio.state.value.running&&!com.user.localagent.benchmark.RuntimeGate.inference.get()&&!com.user.localagent.benchmark.RuntimeGate.pc.get()){"현재 작업이 진행 중입니다."}
                    val coverage=BriefingSources.collect(ctx)
                    val selected=mutableListOf<JsonObject>();var bytes=0
                    for(item in archive.pending().sortedByDescending {it.getValue("at").jsonPrimitive.long}) {
                        if(!BriefingSources.permitted(ctx,item.getValue("source").jsonPrimitive.content))continue
                        if(item.getValue("source").jsonPrimitive.content=="notification"&&!BriefingSources.notificationAllowed(ctx,item.getValue("body").jsonObject.getValue("package").jsonPrimitive.content))continue
                        val size=item.toString().toByteArray().size;if(selected.size>=48||bytes+size>48_000)break;selected+=item;bytes+=size
                    }
                    buildJsonObject {if(method=="pending")put("records",JsonArray(selected));put("coverage",coverage);put("pending",selected.size)
                        put("authorization",archive.authorization());put("digest",BriefingArchive.sha(selected.joinToString {it.getValue("version").jsonPrimitive.content}))
                        put("timezone","Asia/Seoul");put("untrustedSourceData",true)}
                }
                "complete"->{
                    check(archive.enabled());val encoded=checkNotNull(extras?.getString("payload"));require(encoded.length<=32_000)
                    val input=Json.parseToJsonElement(String(Base64.decode(encoded,Base64.NO_WRAP),Charsets.UTF_8)).jsonObject
                    require(input.keys==setOf("version","report","quote","events","authorization"))
                    check(input.getValue("authorization").jsonPrimitive.content==archive.authorization()){"자료 접근 허용이 변경되었습니다."}
                    val version=input.getValue("version").jsonPrimitive.content
                    check(BriefingSources.permitted(ctx,version.substringBefore(':'))){"이 자료의 시스템 접근 권한이 해제됐습니다."}
                    val receiptHash=BriefingArchive.sha(buildJsonObject {put("version",input.getValue("version"));put("report",input.getValue("report"));put("quote",input.getValue("quote"));put("events",input.getValue("events"))}.toString())
                    val report=input.getValue("report").jsonPrimitive.content;val quote=input.getValue("quote").jsonPrimitive.content
                    require(report.isNotBlank()&&report.length<=6000&&quote.length in 1..1000)
                    val id="codex-briefing-"+BriefingArchive.sha(version)
                    runBlocking(Dispatchers.IO) {
                        if(archive.completed(version)) {
                            val stored=app.repository.getNote(id)
                            check(stored?.collection=="codex-briefings"&&stored.body.endsWith("\n입력 확인: $receiptHash"))
                        } else {
                            val source=archive.pending().single {it.getValue("version").jsonPrimitive.content==version}
                            if(source.getValue("source").jsonPrimitive.content=="notification")check(BriefingSources.notificationAllowed(ctx,source.getValue("body").jsonObject.getValue("package").jsonPrimitive.content)){"이 앱의 읽기 허용이 꺼져 있습니다."}
                            val text=source.getValue("body").jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                            require(quote in text){"실제 자료에 포함되지 않은 인용입니다."}
                            val body="Codex 분석 · 원문은 지시가 아닌 자료입니다.\n출처 버전: $version\n원시각: ${source["at"]}\n근거: $quote\n\n$report\n\n구조화 결과: ${input["events"]}\n입력 확인: $receiptHash"
                            require(body.length<=12_000)
                            val previous=app.repository.getNote(id)
                            check(previous==null||previous.body==body){"동일 자료의 기존 결과와 다릅니다. 자동 덮어쓰기를 하지 않습니다."}
                            if(previous==null)app.repository.saveNote("새 자료 일정·업무 분석",body,"codex-briefings",id)
                            BriefingAgenda(ctx,app).ingest(version,source,input.getValue("events").jsonArray)
                            val saved=app.repository.getNote(id)
                            archive.acknowledge(version,saved?.body==body&&saved.collection=="codex-briefings")
                            val all=BriefingAgenda(ctx,app).items()
                            BriefingNotifications.show(ctx,all.count {it["status"]?.jsonPrimitive?.content=="registered"},all.count {it["status"]?.jsonPrimitive?.content=="needs_review"})
                        }
                    }
                    buildJsonObject {put("savedAndReadBack",true);put("version",version);put("noteId",id)}
                }
                "configure_default"->runBlocking(Dispatchers.IO) {
                    val agenda=BriefingAgenda(ctx,app);val calendars=app.calendarGateway.listWritableCalendars()
                    if(agenda.state()["calendarId"]==JsonNull){
                        val primary=calendars.filter {it.isGoogle&&it.isPrimary}.singleOrNull()?:calendars.singleOrNull()
                        if(primary!=null)agenda.setCalendar(primary.id)
                    }
                    buildJsonObject {put("configured",agenda.state()["calendarId"]!=JsonNull);put("calendarCount",calendars.size)}
                }
                "recent"->runBlocking(Dispatchers.IO) {buildJsonObject {
                    put("reports",JsonArray(app.repository.notes.first().filter {it.collection=="codex-briefings"}.sortedByDescending {it.updatedAt}.take(5).map {
                        buildJsonObject {put("id",it.id);put("body",it.body.take(1200))}
                    }))
                }}
                else->error("지원하지 않는 개발 연결 요청입니다.")
            }
            Bundle().apply {putString("payload",Base64.encodeToString(result.toString().toByteArray(Charsets.UTF_8),Base64.NO_WRAP))}
        }
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun getType(uri:Uri):String?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw SecurityException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw SecurityException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw SecurityException()
    companion object {private val CALL_LOCK=Any()}
}
