package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.SemanticAction
import com.user.localagent.inference.loadTarget
import com.user.localagent.models.*
import com.user.localagent.planning.LocalSemanticPlanner
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A-scope only: real empty phone ModelManager + production selector/planner + synthetic typed engine.
 * No AppContainer, owner PC/connection singleton, Room, real transport/model or full Coordinator is used. */
@RunWith(AndroidJUnit4::class)
class PairedPcEmptyModelsValidation {
    private suspend fun fixture(block:suspend(ModelManager,File)->Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pairedPcEmptyModelsApproved")=="true")
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="paired-empty-models-"+UUID.randomUUID()
        val parent=base.cacheDir.canonicalFile
        val root=File(parent,name).canonicalFile
        check(root.parentFile==parent&&!root.exists());check(root.mkdir())
        val prefs=linkedSetOf<String>()
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply{check(isDirectory||mkdirs())}
            override fun getCacheDir()=File(root,"cache").apply{check(isDirectory||mkdirs())}
            override fun getNoBackupFilesDir()=File(root,"no-backup").apply{check(isDirectory||mkdirs())}
            override fun getExternalFilesDir(type:String?):File {check(type==null);return File(root,"external").apply{check(isDirectory||mkdirs())}}
            override fun getSharedPreferences(key:String,mode:Int):SharedPreferences {
                check(key.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
                val scoped="$name-$key";prefs+=scoped
                return base.getSharedPreferences(scoped,mode)
            }
        }
        try {
            val models=ModelManager(context)
            models.refresh()
            assertTrue(models.models.value.isEmpty())
            block(models,File(context.filesDir,"models"))
            models.refresh()
            assertTrue(models.models.value.isEmpty())
            assertTrue(File(context.filesDir,"models").listFiles().orEmpty().isEmpty())
        }finally{
            prefs.forEach {check(it.startsWith("$name-"));check(base.deleteSharedPreferences(it))}
            check(root.parentFile==parent&&root.name==name)
            check(root.deleteRecursively());assertFalse(root.exists())
        }
    }
    @Test fun emptyPhoneAutoUsesProductionSelectorAndActualPlannerWithTypedRecorder()=runBlocking {
        fixture {models,modelDirectory->
            assertTrue(models.models.value.isEmpty());assertTrue(modelDirectory.listFiles().orEmpty().isEmpty())
            val chosen=checkNotNull(RequestInferenceSelection.pairedText("auto",7,false,7,true,false))
            assertTrue(chosen.model is PairedPcText)
            val engine=PairedPcTargetRecordingEngine()
            val result=LocalSemanticPlanner(engine).plan("짧게 인사해주세요.",emptyList(),chosen,availableTools=emptySet())
            assertEquals(SemanticAction.ANSWER,result.plan.action)
            assertEquals(0,engine.pathLoads);assertEquals(1,engine.typedLoads)
            assertEquals(1,engine.generations)
        }
    }
    @Test fun explicitPcIntentNeedsNoPhoneFileOrDownloadedFlag()=runBlocking {
        fixture {models,_->
            val chosen=checkNotNull(RequestInferenceSelection.pairedText(PairedPcTextProfile.SELECTION_ID,7,false,7,true,false))
            val engine=PairedPcTargetRecordingEngine()
            engine.loadTarget(chosen.model,chosen.options)
            assertTrue(models.models.value.isEmpty())
            assertEquals(PairedPcTextProfile.SELECTION_ID,engine.lastTarget?.id)
            assertEquals(0,engine.pathLoads)
        }
    }
    @Test fun emptyPhoneDoesNotUpgradeAnOldRequestToCurrentPcAuthority()=runBlocking {
        fixture {models,_->
            assertTrue(models.models.value.isEmpty())
            assertNull(RequestInferenceSelection.pairedText("auto",null,false,7,true,false))
            try {RequestInferenceSelection.pairedText("auto",6,false,7,true,false);fail("Expected authority mismatch")}
            catch(e:InferencePrerequisiteMissing){assertEquals(InferencePrerequisiteReason.PC_AUTHORITY_CHANGED,e.reason)}
        }
    }
}
