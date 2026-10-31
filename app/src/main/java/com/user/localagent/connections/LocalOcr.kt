package com.user.localagent.connections

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

data class OcrText(val text:String,val blockCount:Int,val width:Int,val height:Int,val truncated:Boolean)

object LocalOcr {
    suspend fun read(context:Context,uri:Uri,korean:Boolean=true):OcrText=withContext(Dispatchers.IO) {
        require(uri.scheme=="content"){"선택한 사진 URI만 사용할 수 있습니다."}
        require(context.contentResolver.getType(uri)?.startsWith("image/")==true){"사진 파일을 선택해주세요."}
        val bytes=context.contentResolver.openInputStream(uri)?.use {input->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=20*1024*1024){"사진은20MiB이하여야 합니다."};output.write(buffer,0,n)};output.toByteArray()
        }?:error("사진을 읽을 수 없습니다.")
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0)
        var sample=1;while(bounds.outWidth/sample>2048||bounds.outHeight/sample>2048)sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})?:error("사진을 디코딩하지 못했습니다.")
        val recognizer=if(korean)TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val modules=ModuleInstall.getClient(context)
            if(!modules.areModulesAvailable(recognizer).await().areModulesAvailable()){
                modules.installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build()).await()
                withTimeout(90_000){while(!modules.areModulesAvailable(recognizer).await().areModulesAvailable())delay(500)}
            }
            val orientation=runCatching {android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
            val rotation=when(orientation){3->180;6->90;8->270;else->0}
            val result=recognizer.process(InputImage.fromBitmap(bitmap,rotation)).await()
            OcrText(result.text.take(50_000),result.textBlocks.size,bitmap.width,bitmap.height,result.text.length>50_000)
        }
        finally {recognizer.close();bitmap.recycle()}
    }
}
