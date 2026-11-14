package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.compute.PcTextAuthority
import com.user.localagent.compute.PcUnavailable
import com.user.localagent.inference.AutomaticComputeEngine
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.inference.PcRequired
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** No PC generation/tokenization/API call and no mutation of the owner's local/cloud setting. */
class PcTextAuthorityDeviceValidation {
    private class IsolatedContext(base:Context):ContextWrapper(base){
        val prefix="qa-text-authority-"+UUID.randomUUID()
        val directory=File(base.cacheDir,prefix).apply{check(mkdir())}
        val preferences=mutableSetOf<String>()
        override fun getApplicationContext():Context=this
        override fun getFilesDir():File=directory
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            val scoped="$prefix-$name";preferences+=scoped
            return baseContext.getSharedPreferences(scoped,mode)
        }
        fun cleanup(){
            preferences.forEach{baseContext.deleteSharedPreferences(it)}
            check(directory.listFiles().orEmpty().isEmpty()){"Unexpected isolated fixture file; preserving it"}
            check(directory.delete())
        }
    }
    @Test fun engineChoiceChangesRevokeCapturedAuthorityAndNoOpPreservesIt()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pcTextAuthorityValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value&&app.runs.queued.value.isEmpty()&&!app.imageStudio.state.value.running&&!RuntimeGate.benchmark.get()&&!RuntimeGate.inference.get()&&!RuntimeGate.pc.get()){"Owner work has priority"}
        val owner=PcComputeSettings.get(context)
        check(owner.state.value.paired){"Existing authorized pairing is required; this test does not pair a PC"}
        val ownerRevision=owner.authorizationRevision
        val ownerChoice=owner.state.value.claudeText
        val isolated=IsolatedContext(context)
        try {
            val settings=PcComputeSettings.isolatedForValidation(isolated)
            val before=settings.authorizationRevision
            assertFalse(settings.state.value.claudeText)
            settings.claudeText(false);assertEquals(before,settings.authorizationRevision)
            settings.claudeText(true);assertEquals(Math.addExact(before,1L),settings.authorizationRevision)
            assertEquals(PcTextAuthority(Math.addExact(before,1L),true),settings.captureTextAuthority())
            settings.claudeText(true);assertEquals(Math.addExact(before,1L),settings.authorizationRevision)
            assertNull(settings.updateTextEngineIfUnchanged(before,false))
            assertTrue(settings.state.value.claudeText)
            assertEquals(Math.addExact(before,2L),settings.updateTextEngineIfUnchanged(Math.addExact(before,1L),false))
            assertFalse(settings.state.value.claudeText)
            assertEquals(PcTextAuthority(Math.addExact(before,2L),false),settings.captureTextAuthority())
            // An already-mismatched durable queue is refused before preflight or external work.
            val staleRevision=if(ownerRevision>0)ownerRevision-1 else Long.MAX_VALUE
            val stale=AutomaticComputeEngine(context,UUID.randomUUID().toString(),capturedPcRevision=staleRevision,allowCloudText=false)
            try {
                try {stale.load("/no-model-is-loaded",InferenceOptions());fail("Old authority must be refused before PC preflight")}
                catch(_:PcRequired){/* eligibleForPc fails before any transport operation */}
                var invoked=false
                try {stale.withPcResource({}){invoked=true};fail("Stale external authority must be refused")}
                catch(_:PcUnavailable){/* refused before the external block */}
                assertFalse(invoked)
            }finally{withContext(NonCancellable){stale.unload()}}
            assertEquals(ownerRevision,owner.authorizationRevision)
            assertEquals(ownerChoice,owner.state.value.claudeText)
        }finally{isolated.cleanup()}
    }
}
