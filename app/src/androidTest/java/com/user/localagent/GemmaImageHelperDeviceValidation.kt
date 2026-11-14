package com.user.localagent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.PcInferenceEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit synthetic data only; preserves the owner's model selections and image jobs. */
@RunWith(AndroidJUnit4::class)
class GemmaImageHelperDeviceValidation {
    @Test fun selectedGemmaUnderstandsImageAndWritesPrompt()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("gemmaPcApproved")=="true")
        val id=args.getString("gemmaModel")?:error("Model required")
        require(id in setOf("gemma-e2b","gemma-e4b"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        app.models.refresh()
        val selectedBefore=app.models.selectedModel.value?.id
        val mediaBefore=app.models.mediaSelection.value
        val model=app.models.models.value.single {it.id==id&&it.verified}
        assertTrue("Verified projector is required",app.models.mediaReady(model))
        val image=File(context.cacheDir,"gemma-synthetic-${UUID.randomUUID()}.png")
        val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {drawColor(Color.WHITE);drawCircle(128f,128f,72f,Paint().apply {color=Color.RED})}
        image.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        val engine=PcInferenceEngine(context,computeOwner=UUID.randomUUID().toString())
        try {
            withTimeout(650_000){
                engine.load(model.path,app.models.mediaOptions(model,audio=false))
                val result=StringBuilder()
                engine.describeImage(image,"Identify the main shape and its color. Answer in one brief English sentence.").collect {result.append(it)}
                assertTrue("Image color was not identified",result.toString().contains("red",ignoreCase=true))
                assertTrue("Image shape was not identified",Regex("circle|circular|disk|disc",RegexOption.IGNORE_CASE).containsMatchIn(result))
                engine.unload()
                engine.load(model.path,InferenceOptions(contextSize=4096,maxOutputTokens=128,imagePreparation=true))
                val prompt=StringBuilder()
                engine.generateChat("흰 테이블 위에 놓인 파란 컵, 자연광 사진",com.user.localagent.core.ClinicalImagePrompt.translationInstruction("흰 테이블 위에 놓인 파란 컵, 자연광 사진")).collect {prompt.append(it)}
                val translated=com.user.localagent.core.ClinicalImagePrompt.validateTranslation("흰 테이블 위에 놓인 파란 컵, 자연광 사진",prompt.toString())
                assertTrue(translated.contains("blue",ignoreCase=true))
                assertTrue(Regex("cup|mug",RegexOption.IGNORE_CASE).containsMatchIn(translated))
            }
        } finally {
            engine.unload();image.delete()
            assertEquals(selectedBefore,app.models.selectedModel.value?.id)
            assertEquals(mediaBefore,app.models.mediaSelection.value)
        }
    }
}
