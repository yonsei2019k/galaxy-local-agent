package com.user.localagent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.video.LocalVideoTools
import com.user.localagent.video.VideoExport
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real hardware MP4 export with synthetic shapes only. Does not touch a user's media or Gallery. */
@RunWith(AndroidJUnit4::class)
class LocalVideoValidation {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun approved(){assumeTrue("Explicit videoSmokeApproved=true is required",InstrumentationRegistry.getArguments().getString("videoSmokeApproved")=="true")}

    @Test fun syntheticSlideshowAndTrimProduceVerifiedChangingFrames()=runBlocking<Unit> {
        approved()
        withFixtures {fixture->
            val tools=LocalVideoTools(context)
            val first=fixture.image("first.png",Color.RED,false)
            val second=fixture.image("second.png",Color.BLUE,true)
            val slideshow=withTimeout(180000){tools.slideshow(listOf(fixture.uri(first),fixture.uri(second)),1500)}
            fixture.export(slideshow)
            assertExport(slideshow,3000)
            assertDifferentFrames(slideshow.file,500000,2500000)
            assertEquals(1f,tools.progress.value.fraction!!,0f);assertFalse(tools.progress.value.running)
            // Only this owned synthetic video is shared back through the fixture FileProvider.
            val clipInput=fixture.file("source.mp4");slideshow.file.copyTo(clipInput)
            val trimmed=withTimeout(180000){tools.trim(fixture.uri(clipInput),500,2500)}
            fixture.export(trimmed)
            assertExport(trimmed,2000)
            assertDifferentFrames(trimmed.file,250000,1750000)
            assertTrue("Committed video must stay in the private output folder",trimmed.file.canonicalFile.parentFile==File(context.filesDir,"video-exports").canonicalFile)
            assertTrue("Owned staging must be removed",workDirectories()==fixture.initialWorkDirectories)
            assertTrue(context.getSharedPreferences("validated-tools",android.content.Context.MODE_PRIVATE).edit().putBoolean("video.edit_local",true).commit())
        }
    }

    @Test fun cancellationCleansItsOwnedStagingAndDoesNotPublishAnExport()=runBlocking<Unit> {
        approved()
        withFixtures {fixture->
            val tools=LocalVideoTools(context)
            val input=fixture.image("cancel.png",Color.GREEN,true)
            var returned:VideoExport?=null
            val task=launch(Dispatchers.Default){try {returned=tools.slideshow(List(10){fixture.uri(input)},5000)}catch(expected:CancellationException){throw expected}}
            try {
                withTimeout(10000){tools.progress.first {it.running}}
                tools.cancel();withTimeout(15000){task.join()}
                returned?.let(fixture::export)
                assertTrue("Explicit cancellation should cancel the export task",task.isCancelled)
                assertFalse(tools.progress.value.running)
                assertEquals(fixture.initialWorkDirectories,workDirectories())
            } finally {if(task.isActive){tools.cancel();task.cancelAndJoin()}}
        }
    }

    @Test fun invalidRangesAndNonSelectedUrisAreRejectedBeforeAnyExport()=runBlocking<Unit> {
        approved()
        val tools=LocalVideoTools(context)
        suspend fun rejected(block:suspend ()->Unit){try{block();fail("Invalid local media request accepted")}catch(expected:IllegalArgumentException){/*expected*/}}
        rejected {tools.trim(Uri.parse("https://example.invalid/video.mp4"),0,1000)}
        rejected {tools.trim(Uri.parse("content://synthetic/unused"),1000,1000)}
        rejected {tools.trim(Uri.parse("content://synthetic/unused"),0,60001)}
        rejected {tools.slideshow(emptyList())}
        rejected {tools.slideshow(List(21){Uri.parse("content://synthetic/unused")})}
        rejected {tools.slideshow(List(20){Uri.parse("content://synthetic/unused")},4000)}
        rejected {tools.slideshow(listOf(Uri.parse("file:///sdcard/not-user-selected.png")))}
    }

    private fun assertExport(value:VideoExport,expectedDuration:Long){
        assertTrue(value.file.isFile);assertTrue(value.sizeBytes>1024);assertEquals(value.file.length(),value.sizeBytes)
        assertEquals("video/mp4",value.mimeType);assertEquals(1280,value.width);assertEquals(720,value.height)
        assertTrue("Actual duration must match selected edit",abs(value.durationMs-expectedDuration)<=100)
        assertTrue(value.durationMs<=LocalVideoTools.MAX_DURATION_MS)
    }
    private fun assertDifferentFrames(file:File,firstUs:Long,lastUs:Long){
        MediaMetadataRetriever().use {retriever->
            retriever.setDataSource(file.absolutePath)
            val first=retriever.getFrameAtTime(firstUs,MediaMetadataRetriever.OPTION_CLOSEST)?:error("First exported frame missing")
            val last=retriever.getFrameAtTime(lastUs,MediaMetadataRetriever.OPTION_CLOSEST)?:error("Last exported frame missing")
            try {
                val a=average(first);val b=average(last)
                assertTrue("First synthetic frame should have a red background",a[0]>a[2]+50)
                assertTrue("Last synthetic frame should have a blue background",b[2]>b[0]+50)
                assertTrue("Export must actually contain both different photos",a.indices.sumOf {abs(a[it]-b[it])}>100)
            } finally {first.recycle();last.recycle()}
        }
    }
    private fun average(bitmap:Bitmap):List<Int>{
        val sums=IntArray(3);var count=0
        for(y in 0 until bitmap.height step 32)for(x in 0 until bitmap.width step 32){val pixel=bitmap.getPixel(x,y);sums[0]+=Color.red(pixel);sums[1]+=Color.green(pixel);sums[2]+=Color.blue(pixel);count++}
        return sums.map {it/count}
    }
    private fun workDirectories()=File(context.filesDir,"video-exports").listFiles()?.filter {it.isDirectory&&it.name.startsWith(".work-") }?.map {it.name}?.toSet()?:emptySet()

    private inner class Fixtures(val directory:File){
        val initialWorkDirectories=workDirectories()
        val owned=mutableListOf<File>()
        val exports=mutableListOf<File>()
        fun file(name:String):File {require(Regex("[a-z]+\\.(png|mp4)").matches(name));return File(directory,name).canonicalFile.also {check(it.parentFile==directory&&!it.exists());owned+=it}}
        fun image(name:String,background:Int,rectangle:Boolean):File {
            val file=file(name);val bitmap=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888)
            try {val canvas=Canvas(bitmap);canvas.drawColor(background);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE};if(rectangle)canvas.drawRect(430f,100f,540f,230f,paint)else canvas.drawCircle(140f,170f,60f,paint);file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
            finally {bitmap.recycle()};return file
        }
        fun uri(file:File):Uri {check(file in owned&&file.canonicalFile.parentFile==directory);return FileProvider.getUriForFile(context,"${context.packageName}.files",file)}
        fun export(value:VideoExport){check(value.file.canonicalFile.parentFile==File(context.filesDir,"video-exports").canonicalFile&&value.file.name.startsWith("video-"));exports+=value.file}
        fun cleanup(){
            exports.asReversed().forEach {file->check(file.canonicalFile.parentFile==File(context.filesDir,"video-exports").canonicalFile);check(!file.exists()||file.delete())}
            owned.asReversed().forEach {file->check(file.canonicalFile.parentFile==directory);check(!file.exists()||file.delete())}
            check(directory.canonicalFile.parentFile==File(context.cacheDir,"shared").canonicalFile&&directory.name.startsWith("qa-video-"));check(directory.listFiles()?.isEmpty()==true&&directory.delete())
        }
    }
    private suspend fun withFixtures(block:suspend (Fixtures)->Unit){
        val app=(context.applicationContext as LocalAgentApplication).container
        var benchmark=false;var inference=false;var busy=false;var fixtures:Fixtures?=null
        try {
            benchmark=RuntimeGate.benchmark.compareAndSet(false,true);check(benchmark){"Another benchmark is active"}
            inference=RuntimeGate.inference.compareAndSet(false,true);check(inference){"Another inference is active"}
            app.updates.setBusy(true);busy=true;app.engine.unload()
            withContext(Dispatchers.IO){val shared=File(context.cacheDir,"shared").canonicalFile;check(shared.parentFile==context.cacheDir.canonicalFile);check(shared.isDirectory||shared.mkdir());val dir=File(shared,"qa-video-${UUID.randomUUID()}").canonicalFile;check(dir.parentFile==shared&&dir.mkdir());fixtures=Fixtures(dir)}
            block(checkNotNull(fixtures))
        } finally {
            try {withContext(NonCancellable+Dispatchers.IO){fixtures?.cleanup()}}
            finally {if(busy)app.updates.setBusy(false);if(inference)RuntimeGate.inference.set(false);if(benchmark)RuntimeGate.benchmark.set(false)}
        }
    }
}
