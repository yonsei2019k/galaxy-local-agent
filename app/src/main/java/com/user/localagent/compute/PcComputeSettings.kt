package com.user.localagent.compute

import com.user.localagent.core.PcImageModelCatalog
import android.content.Context
import android.net.Uri
import com.user.localagent.core.PcComputeContract
import com.user.localagent.data.DraftStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class PcConnection(val serverId:String,val endpoint:String,val token:String,val certificateSha256:String,val discoveryPort:Int=18435,val overlayEndpoint:String?=null){override fun toString()="PcConnection(paired local compute)"}
data class PcComputeState(val paired:Boolean=false,val automaticText:Boolean=true,val preferPcImages:Boolean=true,val status:String="PC 연결 등록 필요",val imageModelId:String=PcImageModelCatalog.QUALITY_ID,val claudeText:Boolean=false)

/** Pairing credentials stay in the phone's Keystore-backed encrypted store. */
class PcComputeSettings private constructor(private val context:Context) {
    private val store=DraftStore(context,"paired-pc-compute-v1",8192)
    private val lock=Any()
    private val authorizationPreferences=context.getSharedPreferences("pc-compute-authorization-v1",Context.MODE_PRIVATE)
    @Volatile private var revision=authorizationPreferences.getLong("revision",0L).also {require(it>=0L)}
    val authorizationRevision:Long get()=revision
    @Volatile private var connection:PcConnection?=null
    private val mutable=MutableStateFlow(PcComputeState())
    val state:StateFlow<PcComputeState> = mutable.asStateFlow()
    init {synchronized(lock){
        val raw=store.read()
        if(raw.isNotEmpty())runCatching {
            val json=Json.parseToJsonElement(raw).jsonObject
            connection=parse(json.getValue("connection").jsonObject)
            mutable.value=PcComputeState(true,json["automaticText"]?.jsonPrimitive?.boolean?:true,json["preferPcImages"]?.jsonPrimitive?.boolean?:true,"등록한 PC를 작업 전에 확인합니다.").copy(imageModelId=runCatching {PcImageModelCatalog.selection(json["imageModelId"]?.jsonPrimitive?.content?:PcImageModelCatalog.QUALITY_ID).id}.getOrDefault(PcImageModelCatalog.QUALITY_ID),claudeText=json["claudeText"]?.jsonPrimitive?.boolean?:false)
        }.onFailure{mutable.value=PcComputeState(status="PC 연결 설정을 다시 등록해주세요.")}
    }}
    fun snapshot():PcConnection?=connection
    fun captureTextAuthority():PcTextAuthority=synchronized(lock){PcTextAuthority(revision,mutable.value.claudeText)}
    suspend fun importPairing(uri:Uri)=withContext(Dispatchers.IO){
        require(uri.scheme=="content")
        val raw=context.contentResolver.openInputStream(uri)?.use {input->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024);while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=8192);output.write(buffer,0,count)};String(output.toByteArray(),Charsets.UTF_8)}?:error("PC 연결 파일을 열 수 없습니다.")
        importPairing(raw)
    }
    fun importPairing(raw:String)=synchronized(lock){
        require(raw.toByteArray(Charsets.UTF_8).size<=8192)
        val root=Json.parseToJsonElement(raw).jsonObject
        require(root.keys.all {it in setOf("version","serverId","endpoint","token","certificateSha256","discoveryPort")})
        require(root["version"]?.jsonPrimitive?.int==1)
        val next=parse(root)
        advanceAuthorization();connection=next
        mutable.value=PcComputeState(true,true,true,"PC 연결 등록됨 · 사용 가능 여부 확인 중")
        persist()
    }
    private fun parse(json:JsonObject):PcConnection {
        val id=json.getValue("serverId").jsonPrimitive.content;require(UUID.fromString(id).toString()==id)
        val endpoint=json.getValue("endpoint").jsonPrimitive.content.trimEnd('/');PcComputeContract.endpoint(endpoint)
        val token=json.getValue("token").jsonPrimitive.content;require(Regex("[a-f0-9]{64}").matches(token))
        val certificate=json.getValue("certificateSha256").jsonPrimitive.content;require(Regex("[a-f0-9]{64}").matches(certificate))
        val port=json["discoveryPort"]?.jsonPrimitive?.int?:18435;require(port in 1024..65535)
        val overlay=json["overlayEndpoint"]?.jsonPrimitive?.contentOrNull?.also{PcComputeContract.endpoint(it)}
        return PcConnection(id,endpoint,token,certificate,port,overlay)
    }
    fun updateEndpoint(endpoint:String)=synchronized(lock){
        PcComputeContract.endpoint(endpoint)
        connection=connection?.copy(endpoint=endpoint);persist()
    }
    /** An old request may remember a route only while the same pairing is still selected. */
    internal fun updateEndpointIfSamePair(expected:PcConnection,endpoint:String):Boolean=synchronized(lock){
        val current=connection?:return@synchronized false
        if(!expected.samePair(current))return@synchronized false
        PcComputeContract.endpoint(endpoint)
        connection=current.copy(endpoint=endpoint);persist();true
    }
    /** Learned from the authenticated PC while reachable, so the phone can reach it away from home. */
    fun updateOverlay(endpoint:String)=synchronized(lock){
        PcComputeContract.endpoint(endpoint)
        if(connection?.overlayEndpoint!=endpoint){connection=connection?.copy(overlayEndpoint=endpoint);persist()}
    }
    fun automaticText(enabled:Boolean)=synchronized(lock){if(mutable.value.automaticText!=enabled)advanceAuthorization();mutable.value=mutable.value.copy(automaticText=enabled);persist()}
    fun preferPcImages(enabled:Boolean)=synchronized(lock){if(mutable.value.preferPcImages!=enabled)advanceAuthorization();mutable.value=mutable.value.copy(preferPcImages=enabled);persist()}
    /** Apply/restore a scoped routing override atomically; return its exact revision, or preserve owner edits. */
    internal fun updateRoutingIfUnchanged(expectedRevision:Long,automaticText:Boolean,preferPcImages:Boolean):Long?=synchronized(lock){
        if(revision!=expectedRevision)return@synchronized null
        if(mutable.value.automaticText!=automaticText||mutable.value.preferPcImages!=preferPcImages){
            advanceAuthorization()
            mutable.value=mutable.value.copy(automaticText=automaticText,preferPcImages=preferPcImages)
            persist()
        }
        revision
    }
    /** Choice applies to the next image client; changing it does not revoke an active run. */
    fun imageModel(id:String)=synchronized(lock){
        val pin=PcImageModelCatalog.selection(id)
        mutable.value=mutable.value.copy(imageModelId=pin.id);persist()
    }
    /** Changing local/cloud authority revokes captured requests; only newly submitted requests inherit the choice. */
    fun claudeText(enabled:Boolean)=synchronized(lock){updateTextEngine(enabled)}
    internal fun updateTextEngineIfUnchanged(expectedRevision:Long,enabled:Boolean):Long?=synchronized(lock){
        if(revision!=expectedRevision)return@synchronized null
        updateTextEngine(enabled);revision
    }
    private fun updateTextEngine(enabled:Boolean){
        if(mutable.value.claudeText==enabled)return
        advanceAuthorization()
        mutable.value=mutable.value.copy(claudeText=enabled);persist()
    }
    fun report(status:String){mutable.update {it.copy(status=status)}}
    fun forget()=synchronized(lock){advanceAuthorization();connection=null;store.write("");mutable.value=PcComputeState()}
    private fun advanceAuthorization(){
        revision=Math.addExact(revision,1L) // Fail closed for existing requests even if persistence fails.
        check(authorizationPreferences.edit().putLong("revision",revision).commit()){"PC 사용 권한의 변경 기록을 저장하지 못했습니다."}
    }
    private fun persist(){
        val config=connection?:return
        store.write(buildJsonObject {
            put("automaticText",mutable.value.automaticText);put("preferPcImages",mutable.value.preferPcImages);put("imageModelId",mutable.value.imageModelId);put("claudeText",mutable.value.claudeText)
            put("connection",buildJsonObject {put("serverId",config.serverId);put("endpoint",config.endpoint);put("token",config.token);put("certificateSha256",config.certificateSha256);put("discoveryPort",config.discoveryPort);config.overlayEndpoint?.let{put("overlayEndpoint",it)}})
        }.toString())
    }
    companion object {
        @Volatile private var instance:PcComputeSettings?=null
        fun get(context:Context):PcComputeSettings=instance?:synchronized(this){instance?:PcComputeSettings(context.applicationContext).also {instance=it}}
        /** The caller supplies an isolated files/preferences context; never replaces the application singleton. */
        internal fun isolatedForValidation(context:Context)=PcComputeSettings(context)
    }
}
