package com.user.localagent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.media.LocalImageUnderstanding
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

/** Explicit real vision inference on a generated shape fixture; no personal photos/model download. */
@RunWith(AndroidJUnit4::class)
class SyntheticVisionValidation {
    @Test fun installedVisionModelRecognizesAnActualRedCircle()=runBlocking {
        assumeTrue("Explicit visionValidationApproved=true is required",
            InstrumentationRegistry.getArguments().getString("visionValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val directory=File(context.cacheDir,"shared/vision-qa").apply {check(isDirectory||mkdirs())}
        val file=File(directory,"vision-qa-${UUID.randomUUID()}.png")
        var color=false;var shape=false;var width=0;var height=0;var completed=false
        try {
            val bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
                canvas.drawCircle(300f,160f,110f,Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=Color.RED})
                canvas.drawText("TEST",180f,360f,Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=Color.BLACK;textSize=96f})
                file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            }finally {bitmap.recycle()}
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            val result=LocalImageUnderstanding(context,app).describe(uri,
                "가장 크게 색칠된 도형의 색과 모양을 한국어로 짧게 말해주세요. 그림 안의 글자를 읽을 필요는 없습니다.")
            width=result.sourceWidth;height=result.sourceHeight
            val text=result.text.lowercase(Locale.ROOT)
            color="빨" in text||"red" in text
            shape="원" in text||"동그라미" in text||"circle" in text
            completed=true
            assertEquals(600,width);assertEquals(400,height)
            assertTrue("Actual image color must be recognized",color)
            assertTrue("Actual image shape must be recognized",shape)
        }finally {
            check(file.canonicalFile.parentFile==directory.canonicalFile&&file.name.startsWith("vision-qa-"));file.delete()
            val reports=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").apply {check(isDirectory||mkdirs())}
            File(reports,"vision-validation.json").writeText(buildJsonObject {
                put("completed",completed);put("colorMatched",color);put("shapeMatched",shape)
                put("passed",completed&&color&&shape&&width==600&&height==400)
                put("sourceWidth",width);put("sourceHeight",height);put("syntheticFixture",true);put("personalImagesRead",false)
            }.toString(),Charsets.UTF_8)
        }
    }
}
