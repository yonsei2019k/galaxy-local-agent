package com.user.localagent.connections

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.user.localagent.LocalAgentApplication
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.compute.PcConnection
import com.user.localagent.compute.PcTransport
import com.user.localagent.core.*
import com.user.localagent.core.connections.PcToolCatalog
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Selected file + selected chat + explicit paired-PC grant. No arbitrary URI/path tool argument. */
class PcToolClient(private val context:Context,private val store:ConnectionStore) {
    private val settings=PcComputeSettings.get(context)
    private fun thread()=(context.applicationContext as LocalAgentApplication).container.runs.selectedThreadId.value
    private fun selectedData(threadId:String):PcDataSelection? {
        store.read(PcDataSelection.storageKey(threadId))?.let{return PcDataSelection.decode(it).also{require(it.threadId==threadId)}}
        // Old global metadata is read-only migration input. New selections never overwrite it.
        if(store.read("pc.data.thread")!=threadId)return null
        return PcDataSelection(threadId,store.read("pc.data.uri")?:return null,store.read("pc.data.name")?:return null,
            store.read("pc.data.sha")?:return null,"legacy")
    }
    fun selectionBinding(threadId:String):String?=runCatching{selectedData(threadId)?.binding}.getOrNull()
    fun enabled()=store.read("pc.tools.enabled")=="true"
    fun allow(enabled:Boolean){store.write("pc.tools.enabled",enabled.toString());store.write("pc.tools.verified",null)}
    fun authorized()=enabled()&&settings.snapshot()!=null
    fun verified()=authorized()&&settings.snapshot()?.let {store.read("pc.tools.verified")==identity(it)}==true
    private fun identity(value:PcConnection)="${value.serverId}:${value.certificateSha256}"
    private fun configuration():PcConnection {
        check(enabled()){"먼저 PC 분석·NotebookLM 사용을 허용해주세요."}
        val base=settings.snapshot()?:error("도구 화면의 PC 보조에서 연결 파일을 등록해주세요.")
        val host=java.net.URI(base.endpoint).host?:error("등록한 PC 주소를 확인해주세요.")
        return base.copy(endpoint="https://$host:18436")
    }
    suspend fun verify():String=withContext(Dispatchers.IO) {
        val config=configuration()
        val result=PcTransport(context).json(config,"/v1/tools/health",timeout=5000)
        check(result["service"]?.jsonPrimitive?.content=="ZipHapTools"&&result["serverId"]?.jsonPrimitive?.content==config.serverId)
        check(enabled()&&settings.snapshot()?.let {identity(it)}==identity(config))
        store.write("pc.tools.verified",identity(config))
        "PC 분석·NotebookLM 작업기에 연결했습니다. NotebookLM 계정 로그인 상태는 실제 조회로 따로 확인합니다."
    }
    suspend fun select(uri:Uri):String=withContext(Dispatchers.IO) {
        require(uri.scheme=="content")
        val selected=thread();require(selected.isNotBlank()){"먼저 앱에서 대화를 선택해주세요."}
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->
            if(cursor.moveToFirst())cursor.getString(0)else null
        }?:error("선택한 파일 이름을 확인할 수 없습니다.")
        require(name.length in 1..200&&name.none {it=='/'||it=='\\'||it=='\u0000'})
        require(name.substringAfterLast('.').lowercase() in setOf("csv","tsv","xlsx","xlsm","xls","xlsb","ods","parquet"))
        val content=read(uri)
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(thread()==selected)
        val selection=PcDataSelection(selected,uri.toString(),name,sha(content),UUID.randomUUID().toString())
        store.write(PcDataSelection.storageKey(selected),selection.encode())
        check(selectionBinding(selected)==selection.binding)
        "현재 대화의 분석 파일을 선택했습니다: $name · ${content.size}바이트"
    }
    private suspend fun read(uri:Uri):ByteArray {
        val output=java.io.ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use {input->val buffer=ByteArray(8192);while(true){
            currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break
            require(output.size()+count<=6*1024*1024){"앱 전송용 분석 파일은6MiB이하여야 합니다."};output.write(buffer,0,count)
        }}?:error("선택한 파일의 읽기 권한을 확인해주세요.")
        require(output.size()>0);return output.toByteArray()
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    suspend fun execute(call:ToolCall,hostThreadId:String?=null,expectedSourceBinding:String?=null,verifyAccess:suspend()->Unit={},computeOwner:String?=null):ToolResult=withContext(Dispatchers.IO) {
        try {
            require(call.name in PcToolCatalog.names);ToolRegistry.validate(call)
            verifyAccess()
            val config=configuration();val selected=hostThreadId?:thread();require(selected.isNotBlank())
            val transport=PcTransport(context)
            if(hostThreadId!=null){
                require(computeOwner!=null&&runCatching{UUID.fromString(computeOwner).toString()==computeOwner}.getOrDefault(false))
                val health=transport.json(config,"/v1/tools/health",timeout=1500,computeOwner=computeOwner)
                check(health["compute_owner_binding"]?.jsonPrimitive?.intOrNull==1){"PC 도구 작업기에 실행별 격리 지원이 필요합니다."}
            }
            val requestId=UUID.randomUUID().toString()
            var sourceHash:String?=null
            val notebook=call.name in setOf("list_notebooklm_notebooks","query_notebooklm")
            val selection=if(notebook)null else checkNotNull(selectedData(selected)){"이 대화에서 분석할 파일을 먼저 선택해주세요."}
            if(selection!=null&&hostThreadId!=null)check(selection.binding==expectedSourceBinding){"요청 이후 선택한 파일이 달라졌습니다. 새 요청으로 분석해주세요."}
            suspend fun verifyOriginalSource(){
                verifyAccess()
                check(enabled()&&settings.snapshot()?.let{identity(it)}==identity(config))
                if(hostThreadId==null)check(thread()==selected)
                if(selection!=null)check(selectedData(selected)?.binding==selection.binding){"원래 분석 파일 선택이 바뀌었습니다."}
            }
            val body=buildJsonObject {
                put("requestId",requestId)
                if(notebook){
                    put("operation",if(call.name=="query_notebooklm")"query"else"list")
                    if(call.name=="query_notebooklm"){
                        val id=call.arguments.getValue("notebookId").jsonPrimitive.content
                        require(UUID.fromString(id).toString()==id)
                        put("notebookId",id);put("question",call.arguments.getValue("question"))
                    }
                }else{
                    val original=checkNotNull(selection)
                    val uri=Uri.parse(original.uri)
                    require(uri.scheme=="content")
                    val bytes=read(uri);sourceHash=sha(bytes)
                    check(sourceHash==original.sha256){"선택 후 파일이 바뀌었습니다. 파일을 다시 선택해주세요."}
                    put("filename",original.name);put("sha256",sourceHash)
                    put("contentBase64",Base64.encodeToString(bytes,Base64.NO_WRAP))
                    val mode=if(call.name=="query_selected_data")"query"else call.arguments["mode"]?.jsonPrimitive?.content?:"profile"
                    require(mode in setOf("profile","query","statistics","regression","cluster","chart"));put("mode",mode)
                    if(mode=="query")put("sql",call.arguments.getValue("sql"))
                    put("options",call.arguments["optionsJson"]?.jsonPrimitive?.content?.let {Json.parseToJsonElement(it).jsonObject}?:buildJsonObject {})
                }
            }
            verifyOriginalSource()
            val result=transport.json(config,if(notebook)"/v1/tools/notebooklm"else"/v1/tools/analyze","POST",body,80_000,computeOwner=computeOwner)
            currentCoroutineContext().ensureActive()
            verifyOriginalSource()
            check(result["requestId"]?.jsonPrimitive?.content==requestId&&result["serverId"]?.jsonPrimitive?.content==config.serverId)
            val success=result["ok"]?.jsonPrimitive?.boolean==true
            if(success&&sourceHash!=null)check(result["sourceSha256"]?.jsonPrimitive?.content==sourceHash)
            val shown=result.toMutableMap()
            shown.remove("pngBase64")?.jsonPrimitive?.content?.let {encoded->
                check(success);val image=Base64.decode(encoded,Base64.NO_WRAP);require(image.size<=512*1024)
                check(sha(image)==result["pngSha256"]?.jsonPrimitive?.content)
                val dimensions=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(image,0,image.size,dimensions)
                require(dimensions.outWidth in 1..4096&&dimensions.outHeight in 1..4096)
                val directory=File(context.filesDir,"shared/data-analysis").apply {check(isDirectory||mkdirs())}
                val output=File(directory,"$requestId.png");output.writeBytes(image);check(sha(output.readBytes())==sha(image))
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",output)
                shown["chartUri"]=JsonPrimitive(uri.toString());store.write("pc.chart."+PcDataSelection.storageKey(selected),uri.toString())
            }
            if(success)store.write("pc.tools.verified",identity(config))
            var content=JsonObject(shown).toString()
            if(content.length>24_000)content=buildJsonObject {put("ok",success);put("partial",true);put("resultExcerpt",content.take(18000));put("sourceSha256",sourceHash);put("untrustedSource",true)}.toString()
            ToolResult(call.id,success,content,if(success)VerificationState.VERIFIED else VerificationState.FAILED,
                if(success)"paired_pc_readback_verified" else result["code"]?.jsonPrimitive?.content?:"pc_tool_failed")
        }catch(cancel:CancellationException){throw cancel}catch(error:Exception){
            ToolResult(call.id,false,"PC 도구 연결·현재 대화의 파일 선택·계정 로그인을 확인해주세요.",VerificationState.FAILED,"pc_connection_required")
        }
    }
}
