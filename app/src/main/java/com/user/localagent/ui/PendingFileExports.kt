package com.user.localagent.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal data class PendingExportDetails(val messageCount:Int?=null,val truncated:Boolean?=null,val query:String?=null,
    val durationMillis:Long?=null,val width:Int?=null,val height:Int?=null)
internal data class PendingFileExport(val token:String,val owner:String,val format:String,val file:File,val bytes:Long,val sha256:ByteArray,val details:PendingExportDetails?=null)
internal data class PendingExportClaim(val request:PendingFileExport?=null,val duplicate:Boolean=false)

/** Process-owned immutable snapshots. Saved state contains only a token, never document text.
 * A missing process request fails before opening the selected destination. */
internal class PendingFileExportStore(private val directory:File,private val now:()->Long=System::currentTimeMillis) {
    private data class Entry(val owner:String,val format:String,val file:File,val createdAt:Long,var request:PendingFileExport?=null,var writing:Boolean=false)
    private val lock=Any()
    private val pending=mutableMapOf<String,Entry>()
    private val consumed=ArrayDeque<String>()
    init {
        // A new process cannot authorize an old snapshot; remove only our orphan filenames.
        directory.listFiles()?.filter{it.isFile}?.forEach(::deleteOwned)
    }
    fun activeToken(owner:String):String?=synchronized(lock){prune();pending.entries.firstOrNull{it.value.owner==owner}?.key}
    fun request(token:String?,owner:String,format:String):PendingFileExport?=synchronized(lock){prune();pending[token]?.takeIf{it.owner==owner&&it.format==format}?.request}
    suspend fun prepare(owner:String,format:String,details:()->PendingExportDetails?={null},produce:suspend (File)->Unit):PendingFileExport {
        var allocatedToken:String?=null
        try {
            return withContext(Dispatchers.IO){
                require(owner.isNotBlank());val limit=maximumBytes(format)
                val token=UUID.randomUUID().toString()
                val entry=synchronized(lock){
                    prune();check(pending.values.none{it.owner==owner}){"이전 파일 저장 선택·작업을 먼저 마쳐주세요."}
                    check(directory.isDirectory||directory.mkdirs())
                    Entry(owner,format,File(directory,"pending-$token.$format"),now()).also{pending[token]=it;allocatedToken=token}
                }
                produce(entry.file);currentCoroutineContext().ensureActive()
                check(entry.file.isFile&&entry.file.length() in 1..limit){"저장할 파일이 비어 있거나 ${limit/1024/1024}MiB를 넘습니다."}
                val metadata=details();require((metadata?.query?.length?:0)<=500)
                val request=PendingFileExport(token,owner,format,entry.file,entry.file.length(),entry.file.inputStream().use{exportDigest(it,limit)},metadata)
                synchronized(lock){check(pending[token]===entry);entry.request=request}
                request
            }
        }catch(failure:Throwable){withContext(NonCancellable+Dispatchers.IO){cancel(allocatedToken)};throw failure}
    }
    fun claim(token:String?,owner:String,format:String):PendingExportClaim=synchronized(lock){
        prune()
        if(token==null)return@synchronized PendingExportClaim()
        if(token in consumed)return@synchronized PendingExportClaim(duplicate=true)
        val entry=pending[token]?.takeIf{it.owner==owner&&it.format==format}?:return@synchronized PendingExportClaim()
        if(entry.writing)return@synchronized PendingExportClaim(duplicate=true)
        val request=entry.request?:return@synchronized PendingExportClaim()
        entry.writing=true;remember(token);PendingExportClaim(request)
    }
    fun cancel(token:String?)=synchronized(lock){
        val entry=pending[token]?:return@synchronized
        if(entry.writing)return@synchronized
        pending.remove(token);remember(requireNotNull(token));deleteOwned(entry.file)
    }
    fun finish(token:String)=synchronized(lock){pending.remove(token)?.let{deleteOwned(it.file)}}
    private fun remember(token:String){consumed.addLast(token);while(consumed.size>32)consumed.removeFirst()}
    private fun prune(){
        val cutoff=now()-30*60*1000L
        pending.filterValues{!it.writing&&it.createdAt<cutoff}.keys.toList().forEach{token->pending.remove(token)?.let{deleteOwned(it.file)}}
    }
    private fun deleteOwned(file:File){if(file.canonicalFile.parentFile==directory.canonicalFile&&file.name.matches(Regex("pending-[0-9a-f-]{36}\\.(?:txt|csv|pdf|docx|xlsx|json|mp4)")))file.delete()}
    companion object {
        const val MAX_BYTES=20L*1024*1024
        const val MAX_VIDEO_BYTES=256L*1024*1024
        fun maximumBytes(format:String):Long=when(format){"mp4"->MAX_VIDEO_BYTES;"txt","csv","pdf","docx","xlsx","json"->MAX_BYTES;else->error("지원하지 않는 저장 형식입니다.")}
    }
}

internal object PendingFileExports {
    @Volatile private var instance:PendingFileExportStore?=null
    fun get(context:Context):PendingFileExportStore=instance?:synchronized(this){
        instance?:PendingFileExportStore(File(context.applicationContext.cacheDir,"shared/pending-exports")).also{instance=it}
    }
}

internal fun exportDigest(input:InputStream,maximumBytes:Long=PendingFileExportStore.MAX_BYTES):ByteArray {
    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(16*1024);var bytes=0L
    while(true){val count=input.read(buffer);if(count<0)break;bytes+=count;check(bytes<=maximumBytes){"파일 검증 크기 한도를 넘었습니다."};digest.update(buffer,0,count)}
    return digest.digest()
}

internal fun verifyPendingExportSource(request:PendingFileExport) {
    val limit=PendingFileExportStore.maximumBytes(request.format)
    check(request.bytes in 1..limit&&request.file.isFile&&request.file.length()==request.bytes&&MessageDigest.isEqual(request.sha256,request.file.inputStream().use{exportDigest(it,limit)})){"저장할 원본 파일이 변경되었거나 없어졌습니다."}
}

/** No write is attempted for a missing, expired, mismatched, or already-consumed request. */
internal suspend fun savePendingExport(context:Context,store:PendingFileExportStore,token:String?,owner:String,format:String,uri:Uri,verify:suspend (Uri)->Unit):Boolean {
    val claim=store.claim(token,owner,format)
    if(claim.duplicate)return false
    val request=claim.request?:error("이전 저장 요청을 복원할 수 없습니다. 내용과 형식을 확인한 뒤 저장을 다시 눌러주세요.")
    try {
        withContext(Dispatchers.IO){
            verifyPendingExportSource(request)
            context.contentResolver.openOutputStream(uri,"wt")?.use{output->request.file.inputStream().use{input->
                val buffer=ByteArray(16*1024)
                while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count)}
            }}?:error("출력 파일을 열 수 없습니다.")
            val observed=context.contentResolver.openInputStream(uri)?.use{exportDigest(it,PendingFileExportStore.maximumBytes(format))}?:error("저장한 파일을 다시 열 수 없습니다.")
            check(MessageDigest.isEqual(request.sha256,observed)){"저장 후 파일 내용이 일치하지 않습니다."}
        }
        verify(uri)
        return true
    }finally{store.finish(request.token)}
}
