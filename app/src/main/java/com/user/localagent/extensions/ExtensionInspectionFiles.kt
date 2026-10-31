package com.user.localagent.extensions

import com.user.localagent.core.ExtensionInspection
import com.user.localagent.core.ExtensionSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Explicit owner-selected streams only. No download, extraction, installation, or execution. */
internal object ExtensionInspectionFiles {
    const val MAX_MANIFEST_BYTES=16*1024
    suspend fun inspect(
        openBundle:()->InputStream,openManifest:()->InputStream,
        expectedSha256:String,expectedCommit:String,
    ):ExtensionInspection=withContext(Dispatchers.IO) {
        // Validate independent anchors before opening either untrusted file.
        require(Regex("[a-fA-F0-9]{64}").matches(expectedSha256)){"별도로 확인한 SHA-256 64자리가 필요합니다."}
        require(Regex("[a-fA-F0-9]{40}").matches(expectedCommit)){"별도로 확인한 고정 commit 40자리가 필요합니다."}
        val bundle=openBundle().use{readBounded(it,ExtensionSecurity.MAX_COMPRESSED_BYTES)}
        require(bundle.size>=22){"ZIP 파일이 너무 작습니다."}
        val metadata=openManifest().use{readBounded(it,MAX_MANIFEST_BYTES)}
        val manifest=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(metadata)).toString()
        currentCoroutineContext().ensureActive()
        ExtensionSecurity.inspect(bundle,manifest,expectedSha256,expectedCommit).also{currentCoroutineContext().ensureActive()}
    }
    private suspend fun readBounded(input:InputStream,maximum:Int):ByteArray {
        val bytes=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){
            currentCoroutineContext().ensureActive()
            val count=input.read(buffer,0,minOf(buffer.size,maximum-bytes.size()+1))
            if(count<0)break
            require(count>0){"파일 제공자가 읽기 진행 상태를 반환하지 않았습니다."}
            require(bytes.size().toLong()+count<=maximum){"선택한 파일이 허용 크기 ${maximum}바이트를 넘습니다."}
            bytes.write(buffer,0,count)
        }
        return bytes.toByteArray()
    }
}
