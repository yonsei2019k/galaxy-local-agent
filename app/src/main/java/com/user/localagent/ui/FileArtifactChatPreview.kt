package com.user.localagent.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Only a named, already-created private artifact; external opening/export follows a user click. */
@Composable fun FileArtifactChatPreview(text:String) {
    val name=Regex("\\[\\[file:(artifact-[a-f0-9-]{36}\\.(?:docx|xlsx|pdf|txt|csv))]]").find(text)?.groupValues?.get(1)?:return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val directory=remember(context){File(context.filesDir,"exports").canonicalFile}
    val file=remember(name){File(directory,name).canonicalFile}
    if(file.parentFile!=directory||!file.isFile)return
    val mime=when(file.extension){"docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";"xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";"pdf"->"application/pdf";"csv"->"text/csv";else->"text/plain"}
    var state by remember(name){mutableStateOf("")};var working by remember(name){mutableStateOf(false)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)){uri->if(uri!=null)scope.launch {
        working=true
        try {withContext(Dispatchers.IO){
            val expected=file.inputStream().use(::artifactDigest)
            context.contentResolver.openOutputStream(uri,"wt")?.use {out->file.inputStream().use {it.copyTo(out)}}?:error("저장 위치를 열지 못했습니다.")
            val actual=context.contentResolver.openInputStream(uri)?.use(::artifactDigest)?:error("저장한 파일을 다시 열지 못했습니다.")
            check(expected.contentEquals(actual)){"저장한 파일의 내용 확인이 일치하지 않습니다."}
        };state="저장한 파일을 다시 읽어 확인했습니다."}
        catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){state=error.message?.take(180)?:"파일 저장을 확인하지 못했습니다."}
        finally{working=false}
    }}
    Surface(color=ZipHapColors.NavySurface,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Text("${file.extension.uppercase()} · ${file.length()/1024+1}KiB",style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TextButton(enabled=!working,onClick={save.launch("ZipHap-document.${file.extension}")}){Text("파일 저장")}
            TextButton(enabled=!working,onClick={
                try {val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"파일 열기·공유"))
                }catch(error:Exception){state=error.message?.take(150)?:"파일을 열 앱을 확인해주세요."}
            }){Text("열기·공유")}
        }
        if(state.isNotBlank())Text(state,style=MaterialTheme.typography.bodySmall)
    }}
}
private fun artifactDigest(input:InputStream):ByteArray {
    val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(32*1024);var total=0L
    while(true){val count=input.read(bytes);if(count<0)break;total+=count;require(total<=32L*1024*1024){"파일 검증 용량을 초과했습니다."};digest.update(bytes,0,count)}
    return digest.digest()
}
