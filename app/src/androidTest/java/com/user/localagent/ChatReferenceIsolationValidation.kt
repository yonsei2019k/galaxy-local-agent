package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.inference.*
import com.user.localagent.workspace.ChatAttachments
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two invented coloured photos only; inference is a rejecting fake and original app data is never requested. */
@RunWith(AndroidJUnit4::class)
class ChatReferenceIsolationValidation {
    @Test fun anotherChatPrepareReleaseAndClearCannotRetargetTheOriginalReference()=runBlocking(Dispatchers.IO) {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val shared=File(target.cacheDir,"shared").canonicalFile
        assertEquals(target.cacheDir.canonicalFile,shared.parentFile);check(shared.isDirectory||shared.mkdir())
        val root=File(shared,"chat-reference-isolation-${UUID.randomUUID()}").canonicalFile
        assertEquals(shared,root.parentFile);assertTrue(root.mkdir())
        val isolated=object:ContextWrapper(target){
            override fun getFilesDir()=root
            override fun getApplicationContext():Context=this
        }
        val app=(target.applicationContext as LocalAgentApplication).container
        val manager=ChatAttachments(isolated,app)
        val engine=RejectInference();val suffix=UUID.randomUUID().toString()
        val a="reference-a-$suffix";val b="reference-b-$suffix"
        val runA="reference-run-a-$suffix";val runB="reference-run-b-$suffix"
        try{
            fun photo(name:String,color:Int):android.net.Uri {
                val file=File(root,name);val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
                try{bitmap.eraseColor(color);file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))}}
                finally{bitmap.recycle()}
                return FileProvider.getUriForFile(target,"${target.packageName}.files",file)
            }
            manager.selectThread(a);manager.add(listOf(photo("public-a.jpg",Color.BLUE)),a)
            val itemsA=manager.state.value.toList();manager.captureForRequest(runA,a,itemsA)
            manager.selectThread(b);manager.add(listOf(photo("public-b.jpg",Color.GREEN)),b)
            val itemsB=manager.state.value.toList();manager.captureForRequest(runB,b,itemsB)
            manager.prepareInCurrentRequest("이 사진을 수채화로 바꿔줘",itemsA,runA,requestEngine=engine)
            val original=checkNotNull(manager.currentRequestReferenceUri(runA))
            manager.prepareInCurrentRequest("이 사진을 수채화로 바꿔줘",itemsB,runB,requestEngine=engine)
            val other=checkNotNull(manager.currentRequestReferenceUri(runB))
            assertNotEquals(original,other);assertEquals(original,manager.currentRequestReferenceUri(runA))
            assertNull(manager.currentRequestReferenceUri()) // UI/default access cannot borrow a run's image.
            manager.releaseRequest(runA);assertNull(manager.currentRequestReferenceUri(runA))
            assertEquals(other,manager.currentRequestReferenceUri(runB))
            manager.clearThread(a);assertEquals(other,manager.currentRequestReferenceUri(runB))
            manager.clearThread(b);assertNull(manager.currentRequestReferenceUri(runB))
            assertEquals(0,engine.loads)
        }finally{
            withContext(NonCancellable){runCatching{manager.releaseRequest(runA)};runCatching{manager.releaseRequest(runB)}}
            val entries=root.walkBottomUp().toList()
            assertTrue(entries.size<=100)
            entries.forEach{entry->assertTrue(entry.canonicalFile==root||entry.canonicalPath.startsWith(root.path+File.separator))}
            entries.forEach{entry->assertTrue(entry.delete())}
        }
    }
    private class RejectInference:InferenceEngine {
        var loads=0
        override val state=MutableStateFlow("no inference permitted")
        override val lastMetrics=MutableStateFlow<InferenceMetrics?>(null)
        override val residentModelPath:String?=null
        override suspend fun load(path:String,options:InferenceOptions){loads++;error("This source-isolation fixture must not load a model")}
        override fun generate(prompt:String):Flow<String> = flow{error("No inference in this fixture")}
        override suspend fun unload(){}
        override fun cancel(){}
    }
}
