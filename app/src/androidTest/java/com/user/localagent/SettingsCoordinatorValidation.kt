package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.data.ChatThreadRecord
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual app coordinator, synthetic empty rooms only. Never sends a request or calls a model. */
@RunWith(AndroidJUnit4::class)
class SettingsCoordinatorValidation {
    @Test fun thinkingPersistsPerRoomAndLateCallbackCannotChangeTheNextRoom()=runBlocking<Unit>(Dispatchers.Default){
        assumeTrue("settingsCoordinatorApproved=true required",InstrumentationRegistry.getArguments().getString("settingsCoordinatorApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val runs=app.runs
        fun requireIdle(){
            check(!RuntimeGate.foreground.get()&&!RuntimeGate.inference.get()&&!RuntimeGate.benchmark.get()){"An owner or benchmark task is active"}
            check(!runs.busy.value&&runs.activeRunId==null&&runs.queued.value.isEmpty()){"An owner chat is active or queued"}
            check(!app.imageStudio.state.value.running){"An image task is active"}
        }
        suspend fun await(label:String,condition:suspend()->Boolean){
            try{withTimeout(15000){while(!condition()){currentCoroutineContext().ensureActive();delay(25)}}}
            catch(error:TimeoutCancellationException){throw AssertionError("Timed out waiting for $label",error)}
            requireIdle()
        }
        suspend fun select(id:String){
            requireIdle()
            withContext(Dispatchers.Main.immediate){runs.selectThread(id)}
            await("Prepared synthetic room"){runs.selectionReady.value&&runs.selectedThreadId.value==id}
        }
        requireIdle()
        await("Existing room ready"){runs.selectionReady.value&&runs.selectedThreadId.value.isNotBlank()}
        val originalId=runs.selectedThreadId.value
        val originalDraft=app.repository.chat.readDraft(originalId)
        val originalThinking=runs.modelOptions.value.thinking
        val token=UUID.randomUUID().toString()
        val created=mutableListOf<ChatThreadRecord>()
        val texts=mutableMapOf<String,String>()
        var failure:Throwable?=null
        try{
            val a=app.repository.chat.createThread(title="Synthetic settings A $token").also{created+=it}
            val b=app.repository.chat.createThread(title="Synthetic settings B $token").also{created+=it}
            val aText="Synthetic unsent A $token";val bText="Synthetic unsent B $token"
            texts[a.id]=aText;texts[b.id]=bText
            app.repository.chat.saveDraft(a.id,aText,"auto",false)
            app.repository.chat.saveDraft(b.id,bText,"auto",true)

            select(a.id)
            assertFalse(runs.modelOptions.value.thinking);assertEquals(aText,runs.draft.value)
            withContext(Dispatchers.Main.immediate){runs.setThinking(true,a.id)}
            await("A encrypted thinking draft"){app.repository.chat.readDraft(a.id)?.let{it.thinking&&it.text==aText&&it.modelId=="auto"}==true}

            select(b.id)
            assertTrue(runs.modelOptions.value.thinking);assertEquals(bText,runs.draft.value)
            withContext(Dispatchers.Main.immediate){runs.setThinking(false,b.id)}
            await("B encrypted thinking draft"){app.repository.chat.readDraft(b.id)?.let{!it.thinking&&it.text==bText&&it.modelId=="auto"}==true}
            val bSaved=checkNotNull(app.repository.chat.readDraft(b.id))

            select(a.id)
            assertTrue(runs.modelOptions.value.thinking);assertEquals(aText,runs.draft.value)
            select(b.id)
            assertFalse(runs.modelOptions.value.thinking)
            // A callback captured before the switch must neither mutate B nor enqueue a draft save.
            withContext(Dispatchers.Main.immediate){runs.setThinking(true,a.id)}
            assertFalse(runs.modelOptions.value.thinking)
            assertEquals(bSaved,app.repository.chat.readDraft(b.id))
            assertTrue(checkNotNull(app.repository.chat.readDraft(a.id)).thinking)
            created.forEach{thread->assertTrue(app.repository.chat.runsForThread(thread.id).isEmpty())}
            assertEquals(originalDraft,app.repository.chat.readDraft(originalId))
            select(originalId)
            assertEquals(originalThinking,runs.modelOptions.value.thinking)
            assertEquals(originalDraft,app.repository.chat.readDraft(originalId))
        }catch(error:Throwable){failure=error;throw error}
        finally{withContext(NonCancellable){
            var cleanupError:Throwable?=null
            try{
                val current=runs.selectedThreadId.value
                if(current==originalId||created.any{it.id==current}){
                    requireIdle()
                    select(originalId)
                    check(app.repository.chat.readDraft(originalId)==originalDraft){"Original owner draft changed during the synthetic test"}
                }
            }catch(error:Throwable){cleanupError=error}
            for(thread in created){
                try{
                    val stillThere=app.repository.chat.thread(thread.id)?:continue
                    val draft=app.repository.chat.readDraft(thread.id)
                    // Only exact empty synthetic rooms owned by this fixture may be removed.
                    check(stillThere.title==thread.title&&app.repository.chat.runsForThread(thread.id).isEmpty())
                    check(draft==null||draft.text==texts[thread.id])
                    check(runs.selectedThreadId.value!=thread.id&&runs.activeThreadId!=thread.id)
                    app.repository.chat.deleteThread(thread.id,deleteDerivedMemory=false)
                    check(app.repository.chat.thread(thread.id)==null)
                }catch(error:Throwable){cleanupError?.addSuppressed(error)?:run{cleanupError=error}}
            }
            cleanupError?.let{error->failure?.addSuppressed(error)?:throw error}
        }}
    }
}
