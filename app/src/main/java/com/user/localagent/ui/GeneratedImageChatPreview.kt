package com.user.localagent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun GeneratedImageChatPreview(text:String) {
    val names=remember(text){chatImageNames(text)}
    if(names.isEmpty())return
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(names.size>1)Text("이미지 결과 · ${names.size}개")
        names.forEach{name->key(name){GeneratedImagePreview(name)}}
        if(names.size==16)Text("한 응답에서 이미지 미리보기는 최대 16개까지 표시합니다.")
    }
}

@Composable private fun GeneratedImagePreview(name:String) {
    val context=LocalContext.current
    var bitmap by remember(name){mutableStateOf<ImageBitmap?>(null)}
    var loaded by remember(name){mutableStateOf(false)}
    LaunchedEffect(name){bitmap=withContext(Dispatchers.IO){
        val directory=File(context.filesDir,"generated-images").canonicalFile
        val file=File(directory,name).canonicalFile
        if(file.parentFile!=directory||!file.isFile)null else {
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
            if(bounds.outWidth !in 1..1024||bounds.outHeight !in 1..1024)null else BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }
    };loaded=true}
    bitmap?.let {Image(it,"생성 이미지",Modifier.fillMaxWidth().heightIn(max=360.dp),contentScale=ContentScale.Fit)}
    if(!loaded)Text("이미지 미리보기 준비 중…")
    if(loaded&&bitmap==null)Text("이 이미지의 미리보기를 열지 못했습니다. 파일이 없거나 지원하는 크기를 넘었을 수 있습니다.")
}
