package com.user.localagent

import android.app.ActivityManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.LlamaCppEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Cold-load timing of an installed phone GGUF model with memory snapshots. No prompt, no personal data. */
@RunWith(AndroidJUnit4::class)
class PhoneModelLoadTimingDiagnostic {
    @Test fun coldLoadTiming()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("phoneModelLoadTimingApproved")=="true")
        val modelId=args.getString("modelId")?:"gemma-e4b"
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        app.models.refresh()
        val model=app.models.models.value.singleOrNull{it.id==modelId&&it.verified}
            ?:error("installed: "+app.models.models.value.joinToString{it.id+":"+it.verified+":"+it.runtime})
        val am=context.getSystemService(ActivityManager::class.java)
        fun availMb()=ActivityManager.MemoryInfo().also{am.getMemoryInfo(it)}.availMem/(1024L*1024)
        check(RuntimeGate.inference.compareAndSet(false,true)){"다른 추론이 실행 중입니다."}
        val engine=LlamaCppEngine(context)
        val before=availMb();val started=SystemClock.elapsedRealtime()
        var error:String?=null
        val finished=try {
            withTimeoutOrNull(300_000){engine.load(model.path,InferenceOptions(contextSize=4096,thinking=false,backend="CPU",maxOutputTokens=384));true}?:false
        }catch(t:Throwable){error=t.javaClass.simpleName;false}
        val loadMs=SystemClock.elapsedRealtime()-started;val after=availMb()
        runCatching{engine.unload()}
        RuntimeGate.inference.set(false)
        File(checkNotNull(context.getExternalFilesDir("diagnostics")),"phone-model-load-timing.json").writeText(buildJsonObject {
            put("modelId",modelId);put("fileMb",File(model.path).length()/(1024L*1024))
            put("availBeforeMb",before);put("availAfterLoadMb",after);put("loadMs",loadMs)
            put("finished",finished);put("error",error);put("planningLoadBudgetMs",90_000)
        }.toString())
    }
}
