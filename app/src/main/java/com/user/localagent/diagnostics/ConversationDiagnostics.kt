package com.user.localagent.diagnostics

import com.user.localagent.data.LocalRepository
import com.user.localagent.data.MessageCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.OutputStream
import java.time.Instant

enum class ConversationExportFormat { JSON, TEXT }
data class ConversationExportMetadata(val schemaVersion:Int=1,val messageCount:Int,val toolRunCount:Int,
    val oldestAt:Long?,val newestAt:Long?,val truncated:Boolean,val byteCount:Long)

/** Explicit local/SAF export only. No network, logcat, drafts, external-app files or memory export. */
class ConversationDiagnostics(private val repository:LocalRepository) {
    suspend fun exportSnapshot(output:OutputStream,limit:Int=2000,includeText:Boolean=true,
        includeToolMetadata:Boolean=false,query:String="",format:ConversationExportFormat=ConversationExportFormat.JSON):ConversationExportMetadata = withContext(Dispatchers.IO) {
        require(limit in 1..MAX_MESSAGES);require(query.length<=500 && '\u0000' !in query)
        var bytes=0L;var count=0;var tools=0;var oldest:Long?=null;var newest:Long?=null;var truncated=false
        var cursor:MessageCursor?=null;var done=false
        fun write(text:String){val encoded=text.toByteArray(Charsets.UTF_8);check(bytes+encoded.size<=MAX_BYTES);output.write(encoded);bytes+=encoded.size}
        fun fits(text:String)=bytes+text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES-FOOTER_RESERVE
        if(format==ConversationExportFormat.JSON)write("{\"schemaVersion\":1,\"scope\":\"ziphap_stored_app_messages_only\",\"order\":\"createdAt_desc_id_desc\",\"exportStartedAt\":${JsonPrimitive(Instant.now().toString())},\"includesText\":$includeText,\"messages\":[")
        else write("ZipHap.Agent 대화 기록 · 앱에 저장된 대화만 포함\n최신 기록부터 · 개인 자료가 포함될 수 있습니다.\n\n")
        while(!done && count<limit){
            currentCoroutineContext().ensureActive()
            val page=repository.messagePage(cursor,minOf(200,limit-count),query)
            if(page.messages.isEmpty())break
            for(message in page.messages){
                currentCoroutineContext().ensureActive()
                val encoded=if(format==ConversationExportFormat.JSON){
                    (if(count>0)"," else "")+buildJsonObject {put("id",message.id);put("role",message.role);put("createdAt",message.createdAt);if(includeText)put("text",message.text)}
                }else "[${Instant.ofEpochMilli(message.createdAt)}] ${message.role}\n"+(if(includeText)message.text else "[본문 제외]")+"\n\n"
                if(!fits(encoded)){truncated=true;done=true;break}
                write(encoded);count++;oldest=message.createdAt;if(newest==null)newest=message.createdAt
            }
            cursor=page.nextCursor
            if(cursor==null)done=true
            if(count==limit && cursor!=null)truncated=true
        }
        if(format==ConversationExportFormat.JSON)write("],\"toolMetadata\":[")
        if(includeToolMetadata && oldest!=null && newest!=null){
            val records=repository.toolAuditMetadata(oldest!!,newest!!,1001)
            if(records.size>1000)truncated=true
            for(record in records.take(1000)){
                currentCoroutineContext().ensureActive()
                val encoded=if(format==ConversationExportFormat.JSON)(if(tools>0)"," else "")+buildJsonObject {put("id",record.id);put("tool",record.name);put("success",record.success);put("at",record.at)}
                    else "[도구 메타데이터 ${Instant.ofEpochMilli(record.at)}] ${record.name} · ${if(record.success)"성공" else "실패"}\n"
                if(!fits(encoded)){truncated=true;break};write(encoded);tools++
            }
        }
        if(format==ConversationExportFormat.JSON)write("],\"auditAssociation\":\"time_range_only\",\"messageCount\":$count,\"toolRunCount\":$tools,\"oldestAt\":${oldest?:"null"},\"newestAt\":${newest?:"null"},\"truncated\":$truncated,\"maxMessages\":$limit,\"maxBytes\":$MAX_BYTES}")
        else write("\n대화 ${count}개 · 도구 메타데이터 ${tools}개 · 일부 내보내기: $truncated\n도구는 시간 범위만 연결되며 특정 메시지와의 관계를 확정하지 않습니다.\n")
        output.flush()
        ConversationExportMetadata(messageCount=count,toolRunCount=tools,oldestAt=oldest,newestAt=newest,truncated=truncated,byteCount=bytes)
    }
    companion object {const val MAX_MESSAGES=10_000;const val MAX_BYTES=20L*1024*1024;private const val FOOTER_RESERVE=4096}
}
