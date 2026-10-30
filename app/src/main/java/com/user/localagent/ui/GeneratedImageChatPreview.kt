package com.user.localagent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
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
    val name=Regex("\\[\\[image:(image-[a-f0-9-]{36}\\.png)]]").find(text)?.groupValues?.get(1)?:return
    val context=LocalContext.current
    var bitmap by remember(name){mutableStateOf<ImageBitmap?>(null)}
    LaunchedEffect(name){bitmap=withContext(Dispatchers.IO){
        val directory=File(context.filesDir,"generated-images").canonicalFile
        val file=File(directory,name).canonicalFile
        if(file.parentFile!=directory||!file.isFile)null else {
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
            if(bounds.outWidth !in 1..1024||bounds.outHeight !in 1..1024)null else BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }
    }}
    bitmap?.let {Image(it,"기기에서 생성한 이미지",Modifier.fillMaxWidth().heightIn(max=360.dp),contentScale=ContentScale.Fit)}
}
