package com.user.localagent.images

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class ImageModelState(
    val available:Boolean=false,
    val downloading:Boolean=false,
    val downloadedBytes:Long=0,
    val totalBytes:Long=ImageModelManager.DEFAULT_BYTES,
    val status:String="이미지 모델을 내려받아주세요 · 약 2.13GB",
    val modelPath:String?=null,
    val error:String?=null,
)

/** Only weights are imported; archive, pickle, program and plugin execution are not supported. */
class ImageModelManager(context:Context) {
    private val context=context.applicationContext
    private val directory=File(this.context.filesDir,"image-models").apply {check(isDirectory||mkdirs())}
    private val index=AtomicFile(File(directory,"active.json"))
    private val mutex=Mutex()
    private val mutableState=MutableStateFlow(ImageModelState())
    val state:StateFlow<ImageModelState> = mutableState.asStateFlow()
    private data class Descriptor(val name:String,val hash:String,val size:Long)
    private data class Verified(val file:File,val hash:String,val size:Long,val modified:Long)
    @Volatile private var verified:Verified?=null
    private val cache=mutableMapOf<String,Verified>()
    private var lastProgress=0L

    /** File identity is rechecked synchronously; cold refresh always computes the complete SHA256. */
    fun verifiedModelFile():File? {
        val proof=verified?:return null
        return proof.file.takeIf {it.isFile&&it.length()==proof.size&&it.lastModified()==proof.modified&&
            it.canonicalFile.parentFile==directory.canonicalFile&&mutableState.value.available}
    }

    suspend fun refresh()=withContext(Dispatchers.IO){mutex.withLock {
        try {
            val descriptor=readIndex()
            if(descriptor==null){verified=null;mutableState.value=ImageModelState();return@withLock}
            val file=managed(descriptor.name)
            require(file.isFile&&file.length()==descriptor.size){"이미지 모델 파일이 없거나 크기가 달라졌습니다."}
            verifyFile(file,descriptor.hash)
            publishReady(file,descriptor.hash)
        }catch(cancel:CancellationException){restoreAfterCancellation();throw cancel}
        catch(error:Exception){verified=null;mutableState.value=ImageModelState(status="이미지 모델을 확인할 수 없습니다.",error=error.message?.take(300))}
    }}

    suspend fun downloadDefault():File=withContext(Dispatchers.IO){mutex.withLock {
        val target=managed(DEFAULT_FILENAME)
        val partial=managed("$DEFAULT_FILENAME.part")
        try {
            if(target.isFile&&target.length()==DEFAULT_BYTES){
                try {verifyFile(target,DEFAULT_SHA256);activate(target,DEFAULT_SHA256);return@withLock target}
                catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){cache.remove(target.canonicalPath)}
            }
            require(!target.exists()||target.isFile){"모델 저장 경로가 일반 파일이 아닙니다."}
            var offset=if(partial.isFile)partial.length()else 0L
            require(!partial.exists()||partial.isFile){"다운로드 임시 경로가 일반 파일이 아닙니다."}
            if(offset>DEFAULT_BYTES){deleteOwned(partial);offset=0}
            // A complete partial is verified locally, without an invalid bytes=EOF- request.
            if(offset<DEFAULT_BYTES){
                ensureSpace(DEFAULT_BYTES-offset)
                val connection=connect(DEFAULT_URL,offset)
                try {
                    if(connection.responseCode==200&&offset>0){ensureSpace(DEFAULT_BYTES);offset=0}
                    if(connection.responseCode==206){
                        val range=CONTENT_RANGE.matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                            ?:error("다운로드 재개 범위를 확인할 수 없습니다.")
                        require(range.groupValues[1].toLongOrNull()==offset&&
                            range.groupValues[2].toLongOrNull()==DEFAULT_BYTES-1&&
                            range.groupValues[3].toLongOrNull()==DEFAULT_BYTES){"다운로드 재개 범위가 일치하지 않습니다."}
                    }
                    require(connection.contentLengthLong<0||connection.contentLengthLong==DEFAULT_BYTES-offset){"모델 다운로드 응답 크기가 일치하지 않습니다."}
                    require(connection.contentEncoding==null||connection.contentEncoding.equals("identity",true)){"압축된 모델 응답은 지원하지 않습니다."}
                    progress(offset,DEFAULT_BYTES,"이미지 모델 다운로드 중",true)
                    connection.inputStream.use {input->FileOutputStream(partial,offset>0).use {output->
                        val buffer=ByteArray(BUFFER_BYTES);var bytes=offset;var spaceAt=offset
                        while(true){
                            currentCoroutineContext().ensureActive()
                            val count=input.read(buffer);if(count<0)break;if(count==0)continue
                            require(bytes<=DEFAULT_BYTES-count){"다운로드 파일이 예상 크기를 넘었습니다."}
                            if(bytes>=spaceAt){ensureSpace(BUFFER_BYTES.toLong());spaceAt=bytes+8L*1024*1024}
                            output.write(buffer,0,count);bytes+=count
                            progress(bytes,DEFAULT_BYTES,"이미지 모델 다운로드 중")
                        }
                        output.fd.sync()
                    }}
                }finally{connection.disconnect()}
            }
            require(partial.isFile&&partial.length()==DEFAULT_BYTES){"다운로드가 중단되었습니다. 다시 누르면 이어서 받습니다."}
            try {verifyFile(partial,DEFAULT_SHA256)}catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){deleteOwned(partial);throw error}
            currentCoroutineContext().ensureActive()
            // Existing default weights are app-owned. Replace only after the new file passed verification.
            if(target.exists())deleteOwned(target)
            check(partial.renameTo(target)){"검증된 이미지 모델 파일을 확정할 수 없습니다."}
            cache.remove(partial.canonicalPath)
            remember(target,DEFAULT_SHA256)
            activate(target,DEFAULT_SHA256)
            target
        }catch(cancel:CancellationException){restoreAfterCancellation();throw cancel}
        catch(error:Exception){publishError(error);throw error}
    }}

    /** A separately obtained publisher digest is mandatory; an observed local hash does not grant trust. */
    suspend fun importModel(uri:Uri,expectedSha256:String?=null):File=withContext(Dispatchers.IO){mutex.withLock {
        val expected=expectedSha256?.trim()?.lowercase(Locale.ROOT)
        require(expected!=null&&SHA256.matches(expected)){"배포자가 제공한 SHA-256 64자리를 입력해야 모델을 활성화할 수 있습니다."}
        require(uri.scheme=="content"&&!uri.authority.isNullOrBlank()){ "문서 선택기로 고른 모델 파일이 필요합니다." }
        val id=UUID.randomUUID().toString()
        val temporary=managed("$id.part")
        var committed=false
        var output:File?=null
        try {
            val hint=runCatching {context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use {cursor->
                val column=cursor.getColumnIndex(OpenableColumns.SIZE)
                if(cursor.moveToFirst()&&column>=0&&!cursor.isNull(column))cursor.getLong(column).takeIf {it>=0}else null
            }}.getOrNull()
            require(hint==null||hint in 1..MAX_BYTES){"가져올 모델은 8GiB 이하여야 합니다."}
            ensureSpace(hint?:BUFFER_BYTES.toLong())
            progress(0,hint?:0,"이미지 모델 가져오는 중",true)
            context.contentResolver.openInputStream(uri)?.use {input->FileOutputStream(temporary).use {target->
                val buffer=ByteArray(BUFFER_BYTES);var bytes=0L;var spaceAt=0L
                while(true){
                    currentCoroutineContext().ensureActive()
                    val count=input.read(buffer);if(count<0)break;if(count==0)continue
                    require(bytes<=MAX_BYTES-count){"가져올 모델은 8GiB 이하여야 합니다."}
                    if(bytes>=spaceAt){ensureSpace(BUFFER_BYTES.toLong());spaceAt=bytes+8L*1024*1024}
                    target.write(buffer,0,count);bytes+=count
                    progress(bytes,hint?:0,"이미지 모델 가져오는 중")
                }
                require(bytes>0){"모델 파일이 비어 있습니다."};target.fd.sync()
            }}?:error("선택한 모델 파일을 열 수 없습니다.")
            verifyFile(temporary,expected)
            val extension=validateFormat(temporary)
            currentCoroutineContext().ensureActive()
            val finalized=managed("$id.$extension");output=finalized
            check(temporary.renameTo(finalized)){"가져온 모델 파일을 확정할 수 없습니다."}
            cache.remove(temporary.canonicalPath);remember(finalized,expected)
            activate(finalized,expected);committed=true
            finalized
        }catch(cancel:CancellationException){restoreAfterCancellation();throw cancel}
        catch(error:Exception){publishError(error);throw error}
        finally {if(!committed){deleteOwned(temporary);output?.let {deleteOwned(it)}}}
    }}

    suspend fun deleteModel()=withContext(Dispatchers.IO){mutex.withLock {
        val descriptor=readIndex()
        descriptor?.let {deleteOwned(managed(it.name))}
        deleteOwned(managed("$DEFAULT_FILENAME.part"))
        index.delete();verified=null;cache.clear();mutableState.value=ImageModelState(status="이미지 모델을 삭제했습니다.")
    }}

    private fun readIndex():Descriptor? {
        if(!index.baseFile.exists())return null
        require(index.baseFile.length() in 1..4096){"이미지 모델 목록 크기가 유효하지 않습니다."}
        val data=Json.parseToJsonElement(utf8(index.readFully())) as? JsonObject?:error("이미지 모델 목록 형식 오류")
        require(data.keys==setOf("schema","filename","sha256","size")&&data["schema"]?.jsonPrimitive?.intOrNull==1)
        val name=data["filename"]?.jsonPrimitive?.takeIf {it.isString}?.content?:error("모델 파일명 누락")
        val hash=data["sha256"]?.jsonPrimitive?.takeIf {it.isString}?.content?:error("모델 해시 누락")
        val size=data["size"]?.jsonPrimitive?.longOrNull?:error("모델 크기 누락")
        require(SHA256.matches(hash)&&size in 1..MAX_BYTES&&validModelName(name)){"이미지 모델 목록이 유효하지 않습니다."}
        return Descriptor(name,hash,size)
    }
    private fun activate(file:File,hash:String){
        require(validModelName(file.name)&&file.parentFile?.canonicalFile==directory.canonicalFile)
        val data=buildJsonObject {put("schema",1);put("filename",file.name);put("sha256",hash);put("size",file.length())}.toString().toByteArray(Charsets.UTF_8)
        val output=index.startWrite()
        try {output.write(data);index.finishWrite(output)}catch(error:Throwable){index.failWrite(output);throw error}
        publishReady(file,hash)
    }
    private fun publishReady(file:File,hash:String){
        verified=remember(file,hash)
        mutableState.value=ImageModelState(available=true,downloadedBytes=file.length(),totalBytes=file.length(),
            status=if(file.name==DEFAULT_FILENAME)"DreamShaper8 LCM · 검증 완료"else"가져온 모델 · 검증 완료 · 엔진 호환성 확인 필요",modelPath=file.path)
    }
    private fun publishError(error:Exception){
        val existing=verifiedModelFile()
        mutableState.value=mutableState.value.copy(available=existing!=null,downloading=false,modelPath=existing?.path,
            status=if(existing==null)"이미지 모델 준비 실패"else"기존 모델은 유지했습니다.",error=error.message?.take(300))
    }
    private fun restoreAfterCancellation(){
        val existing=verifiedModelFile()
        mutableState.value=mutableState.value.copy(available=existing!=null,downloading=false,modelPath=existing?.path,
            status="중단했습니다. 기본 모델 다운로드는 다음에 이어서 받습니다.",error=null)
    }
    private fun progress(bytes:Long,total:Long,message:String,force:Boolean=false){
        val now=SystemClock.elapsedRealtime()
        if(force||now-lastProgress>=150||bytes==total){lastProgress=now
            mutableState.value=mutableState.value.copy(downloading=true,downloadedBytes=bytes,totalBytes=total,status=message,error=null)}
    }
    private fun remember(file:File,hash:String):Verified=Verified(file.canonicalFile,hash,file.length(),file.lastModified()).also {cache[file.canonicalPath]=it}
    private suspend fun verifyFile(file:File,expected:String){
        require(file.isFile&&file.length() in 1..MAX_BYTES)
        val cached=cache[file.canonicalPath]
        if(cached!=null&&cached.hash==expected&&cached.size==file.length()&&cached.modified==file.lastModified())return
        val size=file.length();val modified=file.lastModified();val digest=MessageDigest.getInstance("SHA-256")
        progress(0,size,"SHA-256 무결성 확인 중",true)
        var bytes=0L
        file.inputStream().use {input->val buffer=ByteArray(BUFFER_BYTES)
            while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;if(count==0)continue
                bytes+=count;require(bytes<=size){"검증 중 모델 파일 크기가 바뀌었습니다."};digest.update(buffer,0,count);progress(bytes,size,"SHA-256 무결성 확인 중")}
        }
        require(bytes==size&&file.length()==size&&file.lastModified()==modified){"검증 중 모델 파일이 변경되었습니다."}
        val hash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        require(hash==expected){"이미지 모델 SHA-256이 배포자의 값과 일치하지 않습니다."}
        validateFormat(file);remember(file,hash)
    }

    /** Validate inert model containers before any native parser sees them. */
    private fun validateFormat(file:File):String=RandomAccessFile(file,"r").use {input->
        require(input.length()>=24){"모델 파일이 너무 짧습니다."}
        val magic=ByteArray(4);input.readFully(magic)
        if(magic.contentEquals(byteArrayOf(0x47,0x47,0x55,0x46))){
            val header=ByteArray(20);input.readFully(header);val fields=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val version=fields.int;val tensors=fields.long;val metadata=fields.long
            require(version in 2..3&&tensors in 1..100_000&&metadata in 0..100_000){"GGUF 모델 헤더가 유효하지 않습니다."}
            return@use "gguf"
        }
        input.seek(0);val sizeBytes=ByteArray(8);input.readFully(sizeBytes)
        val headerSize=ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).long
        require(headerSize in 2..MAX_HEADER_BYTES.toLong()&&headerSize<=input.length()-8){"Safetensors 헤더 크기가 유효하지 않습니다. pickle·실행 파일은 가져오지 않습니다."}
        val bytes=ByteArray(headerSize.toInt());input.readFully(bytes);val text=utf8(bytes)
        checkJsonShape(text)
        val header=Json.parseToJsonElement(text) as? JsonObject?:error("Safetensors 헤더는 JSON 객체여야 합니다.")
        val payloadSize=input.length()-8-headerSize
        val ranges=mutableListOf<Pair<Long,Long>>()
        for((name,value)in header){
            if(name=="__metadata__"){require(value is JsonObject&&value.values.all {it is JsonPrimitive&&it.isString});continue}
            require(name.isNotBlank()&&name.length<=1024&&value is JsonObject&&value.keys==setOf("dtype","shape","data_offsets")){"Safetensors 텐서 정의가 유효하지 않습니다."}
            val dtype=value["dtype"]?.jsonPrimitive?.content?:error("텐서 타입 누락")
            val unit=DTYPE_BYTES[dtype]?:error("지원하지 않는 Safetensors 타입입니다: $dtype")
            val shape=value["shape"] as? JsonArray?:error("텐서 모양 누락")
            require(shape.size<=16)
            var elements=1L
            for(dimension in shape){val dim=(dimension as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull?:error("텐서 차원 오류")
                require(dim in 0..MAX_BYTES&& (dim==0L||elements<=MAX_BYTES/dim)){"텐서 크기가 유효하지 않습니다."};elements*=dim}
            require(elements<=MAX_BYTES/unit)
            val offsets=value["data_offsets"] as? JsonArray?:error("텐서 범위 누락")
            require(offsets.size==2)
            val start=(offsets[0] as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull?:error("텐서 시작 범위 오류")
            val end=(offsets[1] as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull?:error("텐서 끝 범위 오류")
            require(start in 0..payloadSize&&end in start..payloadSize&&end-start==elements*unit){"텐서 범위가 파일 크기와 일치하지 않습니다."}
            ranges+=start to end
        }
        require(ranges.isNotEmpty()&&ranges.size<=20_000){"가중치 텐서가 없거나 너무 많습니다."}
        var lastEnd=0L
        ranges.sortedWith(compareBy<Pair<Long,Long>> {it.first}.thenBy {it.second}).forEach {(start,end)->require(start>=lastEnd){"텐서 데이터 범위가 겹칩니다."};lastEnd=end}
        require(lastEnd==payloadSize){"모델 뒤에 선언하지 않은 데이터가 있습니다."}
        "safetensors"
    }

    private fun managed(name:String):File {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,150}")))
        val file=File(directory,name)
        require(file.canonicalFile.parentFile==directory.canonicalFile&&file.canonicalFile.name==name){"앱 이미지 모델 폴더 밖의 경로는 사용할 수 없습니다."}
        return file
    }
    private fun validModelName(name:String)=name==DEFAULT_FILENAME||Regex("[a-f0-9-]{36}\\.(safetensors|gguf)").matches(name)
    private fun deleteOwned(file:File){val safe=managed(file.name);require(file.canonicalFile==safe.canonicalFile);check(!safe.exists()||(safe.isFile&&safe.delete())){"이미지 모델 파일을 지울 수 없습니다."};cache.remove(safe.canonicalPath)}
    private fun ensureSpace(bytes:Long){require(bytes>=0&&bytes<=MAX_BYTES&&StatFs(directory.path).availableBytes>=bytes+STORAGE_RESERVE_BYTES){"이미지 모델 저장공간이 부족합니다. 최소 512MiB 여유 공간이 필요합니다."}}
    private fun utf8(bytes:ByteArray)=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private suspend fun connect(url:String,offset:Long):HttpURLConnection {
        var next=URI(url)
        repeat(8){
            currentCoroutineContext().ensureActive()
            val host=next.host?.lowercase(Locale.ROOT).orEmpty()
            require(next.scheme=="https"&&next.port in setOf(-1,443)&&next.userInfo==null&&next.fragment==null&&
                (host=="huggingface.co"||host.endsWith(".huggingface.co")||host.endsWith(".hf.co"))){"HTTPS Hugging Face/CDN 다운로드 주소만 허용합니다."}
            val connection=next.toURL().openConnection() as HttpURLConnection;var returned=false
            try {
                connection.instanceFollowRedirects=false;connection.connectTimeout=15_000;connection.readTimeout=30_000
                connection.setRequestProperty("User-Agent","ZipHap.Agent/1.0");connection.setRequestProperty("Accept-Encoding","identity")
                if(offset>0)connection.setRequestProperty("Range","bytes=$offset-")
                when(val code=connection.responseCode){
                    301,302,303,307,308->next=next.resolve(connection.getHeaderField("Location")?:error("모델 다운로드 이동 주소 누락"))
                    200,206->{returned=true;return connection}
                    401,403->error("모델 저장소의 사용 승인 또는 로그인이 필요합니다.")
                    else->error("이미지 모델 다운로드 응답 $code")
                }
            }finally{if(!returned)connection.disconnect()}
        }
        error("이미지 모델 다운로드 이동 횟수를 초과했습니다.")
    }

    /** Bound nesting/node count and reject duplicate object keys before the JSON library parses. */
    private fun checkJsonShape(raw:String){
        var at=0;var nodes=0
        fun space(){while(at<raw.length&&raw[at].isWhitespace())at++}
        fun string():String {val start=at;require(raw.getOrNull(at)=='"');at++;var escaped=false
            while(at<raw.length){val character=raw[at++];if(character=='"'&&!escaped)return Json.decodeFromString<String>(raw.substring(start,at));if(character=='\\'&&!escaped)escaped=true else escaped=false};error("모델 JSON 문자열 오류")}
        lateinit var value:(Int)->Unit
        value={depth->require(depth<=20&&++nodes<=200_000){"모델 JSON 구조가 너무 복잡합니다."};space()
            when(raw.getOrNull(at)){
                '{'->{at++;space();val keys=mutableSetOf<String>();if(raw.getOrNull(at)=='}')at++ else while(true){space();val key=string();require(keys.add(key)){"모델 JSON에 중복 키가 있습니다."};space();require(raw.getOrNull(at++)==':');value(depth+1);space();val end=raw.getOrNull(at++);if(end=='}')break;require(end==',')}}
                '['->{at++;space();if(raw.getOrNull(at)==']')at++ else while(true){value(depth+1);space();val end=raw.getOrNull(at++);if(end==']')break;require(end==',')}}
                '"'->{string();Unit}
                else->{val start=at;while(at<raw.length&&!raw[at].isWhitespace()&&raw[at] !in ",]}")at++;require(at>start&&at-start<=100){"모델 JSON 값 오류"}}
            }
        };value(0);space();require(at==raw.length){"모델 JSON 뒤에 데이터가 있습니다."}
    }

    companion object {
        const val DEFAULT_REPOSITORY="Lykon/dreamshaper-8-lcm"
        const val DEFAULT_REVISION="4645d8bc6a8e6b106d21606d63e8460cdad4f1a6"
        const val DEFAULT_SHA256="a4f3e1526c5dc4fcbe342f5c410d83ae202c7a415fcefcbb92e0f93fcd0a87c3"
        const val DEFAULT_BYTES=2_133_804_992L
        const val DEFAULT_FILENAME="dreamshaper8-lcm-a4f3e1526c5d.safetensors"
        const val DEFAULT_URL="https://huggingface.co/$DEFAULT_REPOSITORY/resolve/$DEFAULT_REVISION/DreamShaper8_LCM.safetensors"
        const val LICENSE="CreativeML OpenRAIL-M"
        const val LICENSE_URL="https://huggingface.co/Lykon/dreamshaper-8-lcm"
        private const val MAX_BYTES=8L*1024*1024*1024
        private const val STORAGE_RESERVE_BYTES=512L*1024*1024
        private const val MAX_HEADER_BYTES=1024*1024
        private const val BUFFER_BYTES=128*1024
        private val SHA256=Regex("[a-f0-9]{64}")
        private val CONTENT_RANGE=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        private val DTYPE_BYTES=mapOf("BOOL" to 1L,"U8" to 1L,"I8" to 1L,"U16" to 2L,"I16" to 2L,"U32" to 4L,"I32" to 4L,"U64" to 8L,"I64" to 8L,"F16" to 2L,"BF16" to 2L,"F32" to 4L,"F64" to 8L,"F8_E4M3" to 1L,"F8_E4M3FN" to 1L,"F8_E5M2" to 1L,"F8_E4M3FNUZ" to 1L,"F8_E5M2FNUZ" to 1L)
    }
}
