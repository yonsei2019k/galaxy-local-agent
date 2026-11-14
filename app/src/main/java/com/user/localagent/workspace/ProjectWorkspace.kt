package com.user.localagent.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import com.user.localagent.core.ProjectPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class ProjectFile(val path:String,val directory:Boolean,val bytes:Long?)
data class ProjectSelection(val name:String,val external:Boolean,val writable:Boolean)

/** Private default project, or a persisted SAF tree chosen by the owner. No shell or app-private bypass. */
class ProjectWorkspace(context:Context,private val workspaceId:String="default") {
    private val context=context.applicationContext
    init {require(workspaceId.matches(Regex("[a-zA-Z0-9-]{1,80}")))}
    private val resolver=this.context.contentResolver
    private val prefs=this.context.getSharedPreferences("project-workspace-v1-$workspaceId",0)
    private val operation=Mutex()
    private val leases=java.util.concurrent.atomic.AtomicInteger()
    private data class ReadEvidence(val hash:String,val complete:Boolean,val binding:String,val token:String?=null)
    private val reads=mutableMapOf<Pair<String,String>,ReadEvidence>()
    private val ownerReader="owner-ui"
    suspend fun acquireLease():java.io.Closeable=operation.withLock {
        leases.incrementAndGet();val open=java.util.concurrent.atomic.AtomicBoolean(true)
        java.io.Closeable {if(open.compareAndSet(true,false))leases.decrementAndGet()}
    }
    suspend fun bindingFingerprint():String=operation.withLock {sha256("$workspaceId|${tree()?.toString()?:privateRoot.path}")}
    private fun bindingUnlocked()=sha256("$workspaceId|${tree()?.toString()?:privateRoot.path}")
    private fun readKey(reader:String,path:String)=reader to ProjectPaths.segments(path).joinToString("/")
    private fun releaseIfUnreferenced(uri:Uri){
        val prefsDir=File(context.applicationInfo.dataDir,"shared_prefs")
        val referenced=prefsDir.listFiles().orEmpty().filter {it.name.startsWith("project-workspace-v1-")&&it.extension=="xml"}.any {file->
            context.getSharedPreferences(file.nameWithoutExtension,0).getString("tree",null)==uri.toString()
        }
        if(!referenced)runCatching {resolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
    }
    private val privateRoot=File(this.context.filesDir,"projects/$workspaceId").apply {check(isDirectory||mkdirs())}.canonicalFile
    private val mutableSelection=MutableStateFlow(ProjectSelection(if(tree()==null)"내 프로젝트"else"선택한 프로젝트",tree()!=null,tree()==null))
    val state=mutableSelection.asStateFlow()
    init {if(tree()!=null)CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {operation.withLock {runCatching {mutableSelection.value=selection()}}}}
    private fun tree():Uri?=prefs.getString("tree",null)?.let(Uri::parse)
    private fun selection():ProjectSelection {
        val uri=tree()?:return ProjectSelection("내 프로젝트",false,true)
        val permission=resolver.persistedUriPermissions.firstOrNull {it.uri==uri}
        val name=runCatching {metadata(rootUri(uri)).first}.getOrDefault("선택한 프로젝트")
        return ProjectSelection(name,true,permission?.isWritePermission==true)
    }
    /**
     * For a deleted project: forget the chosen phone folder (its files stay; the persisted access grant is
     * released unless another project still uses it) and delete this project's app-private folder.
     */
    suspend fun discard()=withContext(Dispatchers.IO) {operation.withLock {
        check(leases.get()==0){"실행 중인 프로젝트 폴더는 작업이 끝난 뒤 삭제할 수 있습니다."}
        val previous=tree()
        check(prefs.edit().clear().commit()){"프로젝트 폴더 연결 정보를 지우지 못했습니다."}
        previous?.let(::releaseIfUnreferenced)
        reads.clear()
        check(!privateRoot.exists()||privateRoot.deleteRecursively()){"앱 전용 프로젝트 폴더를 삭제하지 못했습니다."}
        mutableSelection.value=ProjectSelection("내 프로젝트",false,true)
    }}
    suspend fun select(uri:Uri)=withContext(Dispatchers.IO) {operation.withLock {
        check(leases.get()==0){"실행 중인 프로젝트 폴더는 작업이 끝난 뒤 변경할 수 있습니다."}
        require(uri.scheme=="content"&&DocumentsContract.isTreeUri(uri))
        val previous=tree()
        val held=resolver.persistedUriPermissions.firstOrNull {it.uri==uri}
        val oldFlags=(if(held?.isReadPermission==true)Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if(held?.isWritePermission==true)Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        var acquired=0
        try {
            val requested=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {resolver.takePersistableUriPermission(uri,requested);acquired=requested}
            catch(_:SecurityException){resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);acquired=Intent.FLAG_GRANT_READ_URI_PERMISSION}
            val root=rootUri(uri)
            val details=metadata(root)
            require(details.second==DocumentsContract.Document.MIME_TYPE_DIR)
            val writable=resolver.persistedUriPermissions.any {it.uri==uri&&it.isWritePermission}
            check(prefs.edit().putString("tree",uri.toString()).commit())
            reads.clear()
            mutableSelection.value=ProjectSelection(details.first,true,writable)
        }catch(error:Throwable){
            val newlyAcquired=acquired and oldFlags.inv()
            if(newlyAcquired!=0)runCatching {resolver.releasePersistableUriPermission(uri,newlyAcquired)}
            throw error
        }
        if(previous!=null&&previous!=uri)releaseIfUnreferenced(previous)
    }}
    suspend fun usePrivateProject()=withContext(Dispatchers.IO) {operation.withLock {
        check(leases.get()==0){"실행 중인 프로젝트 폴더는 작업이 끝난 뒤 변경할 수 있습니다."}
        val previous=tree();check(prefs.edit().remove("tree").commit());reads.clear();mutableSelection.value=selection()
        previous?.let {releaseIfUnreferenced(it)}
    }}
    suspend fun list(path:String=""):List<ProjectFile> = withContext(Dispatchers.IO) {operation.withLock {
        val parts=if(path.isBlank())emptyList()else ProjectPaths.segments(path)
        val selected=tree()
        if(selected==null) {
            val directory=local(parts);require(directory.isDirectory){"프로젝트 폴더를 찾을 수 없습니다."}
            directory.listFiles().orEmpty().sortedWith(compareBy<File>{!it.isDirectory}.thenBy {it.name}).take(100).map {ProjectFile((parts+it.name).joinToString("/"),it.isDirectory,if(it.isFile)it.length()else null)}
        } else {
            val directory=resolve(selected,parts,false)
            require(metadata(directory).second==DocumentsContract.Document.MIME_TYPE_DIR)
            children(selected,directory).take(100).map {(name,mime,size)->ProjectFile((parts+name).joinToString("/"),mime==DocumentsContract.Document.MIME_TYPE_DIR,size)}
        }
    }}
    /** Binary observation only: never records full-text delivery or authorizes an overwrite. */
    suspend fun hashForAudit(path:String,maxBytes:Long):ProjectFileDigest=withContext(Dispatchers.IO){operation.withLock{WorkspaceMutationLocks.withBinding(mutationBindingUnlocked()){
        require(maxBytes in 0..16L*1024*1024){"파일 해시 관찰 한도를 초과했습니다."}
        val parts=ProjectPaths.segments(path);val selected=tree()
        val target=if(selected==null)local(parts)else null
        val uri=selected?.let{resolve(it,parts,false)}
        fun version():Pair<Long?,Long?> = if(target!=null){require(target.isFile){"프로젝트 파일이 아닙니다."};target.length() to target.lastModified()}else{
            resolver.query(checkNotNull(uri),arrayOf(DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use{cursor->
                check(cursor.moveToFirst());require(cursor.getString(2)!=DocumentsContract.Document.MIME_TYPE_DIR)
                (if(cursor.isNull(0))null else cursor.getLong(0)) to (if(cursor.isNull(1))null else cursor.getLong(1))
            }?:error("프로젝트 파일 정보를 읽지 못했습니다.")
        }
        val before=version();require(before.first==null||before.first!!<=maxBytes){"파일이 해시 관찰 한도보다 큽니다."}
        val digest=MessageDigest.getInstance("SHA-256");var count=0L
        val input=target?.inputStream()?:resolver.openInputStream(checkNotNull(uri))?:error("파일을 읽지 못했습니다.")
        input.use{stream->val buffer=ByteArray(8192);while(true){currentCoroutineContext().ensureActive();val n=stream.read(buffer,0,minOf(buffer.size.toLong(),maxBytes-count+1).toInt());if(n<0)break;count+=n;require(count<=maxBytes){"파일이 해시 관찰 한도를 초과했습니다."};digest.update(buffer,0,n)}}
        val after=version();require(before==after&&(after.first==null||after.first==count)){"관찰 중 파일이 변경되어 중복 근거로 사용하지 않았습니다."}
        ProjectFileDigest(parts.joinToString("/"),count,digest.digest().joinToString(""){"%02x".format(it)})
    }}}
    suspend fun read(path:String):String=withContext(Dispatchers.IO) {operation.withLock {WorkspaceMutationLocks.withBinding(mutationBindingUnlocked()) {
        readUnlocked(ProjectPaths.segments(path)).also{reads[readKey(ownerReader,path)]=ReadEvidence(sha256(it),true,bindingUnlocked())}
    }}}
    suspend fun readForTool(path:String,reader:String):ProjectToolRead=withContext(Dispatchers.IO){operation.withLock{WorkspaceMutationLocks.withBinding(mutationBindingUnlocked()){
        require(reader!=ownerReader&&reader.isNotBlank())
        val content=readUnlocked(ProjectPaths.segments(path));val hash=sha256(content);val token=java.util.UUID.randomUUID().toString()
        reads[readKey(reader,path)]=ReadEvidence(hash,false,bindingUnlocked(),token)
        ProjectToolRead(path,content,hash,token)
    }}}
    suspend fun acceptToolReadContext(reader:String,delivered:List<DeliveredProjectRead>)=withContext(Dispatchers.IO){operation.withLock{
        require(reader!=ownerReader&&reader.isNotBlank())
        reads.keys.filter{it.first==reader}.forEach{key->reads[key]?.let{reads[key]=it.copy(complete=false)}}
        val binding=bindingUnlocked()
        delivered.forEach{item->
            val key=readKey(reader,item.path);val pending=reads[key]?:return@forEach
            if(pending.binding==binding&&pending.token==item.token&&pending.hash==item.sha256&&sha256(item.text)==pending.hash)reads[key]=pending.copy(complete=true)
        }
    }}
    suspend fun releaseToolReadContext(reader:String)=withContext(Dispatchers.IO){operation.withLock{reads.keys.removeAll{it.first==reader}}}
    suspend fun write(path:String,content:String,expectedSha256:String?=null):String=writeScoped(path,content,expectedSha256,ownerReader)
    suspend fun writeForTool(path:String,content:String,expectedSha256:String?=null,reader:String):String{
        require(reader!=ownerReader&&reader.isNotBlank());return writeScoped(path,content,expectedSha256,reader)
    }
    private suspend fun writeScoped(path:String,content:String,expectedSha256:String?,reader:String):String=withContext(Dispatchers.IO) {operation.withLock {WorkspaceMutationLocks.withBinding(mutationBindingUnlocked()) {
        require(content.length<=12_000&&'\u0000' !in content)
        val parts=ProjectPaths.segments(path);val selected=tree()
        val previous=runCatching {readUnlocked(parts)}.getOrElse {error->
            if(exists(parts))throw error else null
        }
        if(previous!=null&&!(expectedSha256!=null&&expectedSha256==sha256(previous)))throw com.user.localagent.core.ToolPreconditionRejected("기존 파일은 먼저 읽고 확인한 SHA-256으로 수정해야 합니다.")
        if(previous!=null){val evidence=reads[readKey(reader,path)];if(!(evidence?.hash==sha256(previous)&&evidence.complete&&evidence.binding==bindingUnlocked()))throw com.user.localagent.core.ToolPreconditionRejected("현재 실행에 파일 전체가 전달되지 않아 덮어쓰지 않았습니다. 발췌문으로 원본을 교체하지 않았습니다.")}
        else if(expectedSha256!=null)throw com.user.localagent.core.ToolPreconditionRejected("수정할 기존 파일이 없습니다.")
        currentCoroutineContext().ensureActive()
        // A bounded write+readback finishes before cancellation is reported; it cannot claim an unverified save.
        withContext(NonCancellable) {
            if(selected==null) {
                val target=local(parts);check(target.parentFile!!.isDirectory||target.parentFile!!.mkdirs())
                val atomic=AtomicFile(target);val stream=atomic.startWrite()
                try {stream.write(content.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}catch(error:Throwable){atomic.failWrite(stream);throw error}
            } else {
                check(selection().writable){"선택한 폴더의 쓰기 권한이 필요합니다."}
                val target=resolve(selected,parts,true)
                try {writeUri(target,content);check(sha256(readUnlocked(parts))==sha256(content)){"저장된 파일 검증에 실패했습니다."}}
                catch(error:Throwable){
                    val restored=runCatching {if(previous!=null){writeUri(target,previous);check(sha256(readUnlocked(parts))==sha256(previous))}else check(DocumentsContract.deleteDocument(resolver,target))}.isSuccess
                    throw IllegalStateException(if(restored)"저장 검증 실패 · 변경을 복구했습니다."else"저장 검증과 복구에 실패했습니다. 원본 상태를 확인해야 합니다.",error)
                }
            }
            val verified=readUnlocked(parts)
            check(sha256(verified)==sha256(content)){"저장된 파일 검증에 실패했습니다."}
            reads[readKey(reader,path)]=ReadEvidence(sha256(verified),reader==ownerReader,bindingUnlocked())
            buildJsonObject {put("path",path);put("bytes",verified.toByteArray(Charsets.UTF_8).size);put("sha256",sha256(verified));put("verified",true);put("executed",false)}.toString()
        }
    }}}
    // Provider-level exclusion also covers a parent tree and an alias/descendant tree in another project.
    private fun mutationBindingUnlocked()=tree()?.let {"saf-provider:${it.authority}"}?:"private:${privateRoot.path}"
    private fun exists(parts:List<String>):Boolean=tree()?.let {runCatching {resolve(it,parts,false)}.isSuccess}?:local(parts).exists()
    private fun readUnlocked(parts:List<String>):String {
        val selected=tree()
        val input=if(selected!=null)resolver.openInputStream(resolve(selected,parts,false))else local(parts).inputStream()
        val bytes=input?.use {stream->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val count=stream.read(buffer);if(count<0)break;require(output.size()+count<=256*1024){"프로젝트 파일은 256 KiB 이하의 텍스트만 읽을 수 있습니다."};output.write(buffer,0,count)};output.toByteArray()}?:error("파일을 열 수 없습니다.")
        val value=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in value){"텍스트 파일만 읽을 수 있습니다."};return value
    }
    private fun local(parts:List<String>):File=parts.fold(privateRoot){file,part->File(file,part)}.canonicalFile.also {require(it==privateRoot||it.path.startsWith(privateRoot.path+File.separator))}
    private fun rootUri(tree:Uri)=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
    private fun metadata(uri:Uri):Pair<String,String> = resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use {cursor->check(cursor.moveToFirst());cursor.getString(0) to cursor.getString(1)}?:error("폴더 연결을 확인해주세요.")
    private fun children(tree:Uri,parent:Uri):List<Triple<String,String,Long?>> {
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
        return resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE),null,null,null)?.use {cursor->buildList {while(size<100&&cursor.moveToNext())add(Triple(cursor.getString(0),cursor.getString(1),if(cursor.isNull(2))null else cursor.getLong(2)))}}?:error("폴더 내용을 읽지 못했습니다.")
    }
    private fun resolve(tree:Uri,parts:List<String>,create:Boolean):Uri {
        var current=rootUri(tree)
        parts.forEachIndexed {index,part->
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(current))
            val matches=resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {cursor->buildList {while(cursor.moveToNext())if(cursor.getString(1)==part)add(cursor.getString(0))}}.orEmpty()
            require(matches.size<=1){"이름이 중복된 파일입니다."}
            current=matches.singleOrNull()?.let {DocumentsContract.buildDocumentUriUsingTree(tree,it)}?:if(create)DocumentsContract.createDocument(resolver,current,if(index==parts.lastIndex)"text/plain"else DocumentsContract.Document.MIME_TYPE_DIR,part)?:error("파일을 만들지 못했습니다.")else error("프로젝트 파일을 찾을 수 없습니다.")
        }
        return current
    }
    private fun writeUri(uri:Uri,content:String){resolver.openOutputStream(uri,"wt")?.use {it.write(content.toByteArray(Charsets.UTF_8))}?:error("파일을 저장하지 못했습니다.")}
    companion object {fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}}
}
