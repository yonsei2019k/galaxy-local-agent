package com.user.localagent

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.data.*
import com.user.localagent.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Rendered activity facts and real isolated encrypted queue writes; no model quality claim. */
@RunWith(AndroidJUnit4::class)
class CodexProgressUiValidation {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun approved(){assumeTrue(InstrumentationRegistry.getArguments().getString("codexProgressUiApproved")=="true")}

    @Test fun liveActivityShowsNewChecksInExecutionOrderWithoutOpeningDetails(){
        val events=mutableStateOf(listOf(
            UiRunActivityEvent("plan",UiWorkEventKind.PLAN,"SYNTHETIC_PLAN"),
            UiRunActivityEvent("failed",UiWorkEventKind.TOOL,"SYNTHETIC_FAILED",evidence=UiWorkEvidence.FAILED)))
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
            InlineRunActivityCard(UiRunActivity("qa-live","qa-room","qa-message","running",events=events.value))
        }}}
        compose.onNodeWithTag("activity_event_plan").assertIsDisplayed()
        compose.runOnIdle {events.value=events.value+listOf(
            UiRunActivityEvent("replan",UiWorkEventKind.PLAN,"SYNTHETIC_FOLLOWUP"),
            UiRunActivityEvent("verified",UiWorkEventKind.VERIFICATION,"SYNTHETIC_VERIFIED",evidence=UiWorkEvidence.VERIFIED))}
        val failed=compose.onNodeWithTag("activity_event_failed").fetchSemanticsNode().boundsInRoot
        val replan=compose.onNodeWithTag("activity_event_replan").fetchSemanticsNode().boundsInRoot
        val verified=compose.onNodeWithTag("activity_event_verified").fetchSemanticsNode().boundsInRoot
        assertTrue(failed.bottom<=replan.top+1f&&replan.bottom<=verified.top+1f)

    }

    @Test fun queueEditorWritesExactRequestAndKeepsOriginalRestrictions(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="codex-progress-qa-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val repo=LocalRepository(db)
            val request=runBlocking(Dispatchers.IO){
                val room=repo.chat.createThread(title="SYNTHETIC_QUEUE_ROOM")
                var captured:QueuedChatRequest?=null
                repo.chat.enqueueUserRun(room.id,room.activeBranchId,"SYNTHETIC_OLD","auto",false){user,run->
                    QueuedChatRequest(run.id,room.id,room.activeBranchId,null,"SYNTHETIC_OLD","auto",false,emptyList(),user.id,allowTools=false,mode="review")
                        .also{captured=it}.toJson()
                }
                checkNotNull(captured)
            }
            val items=mutableStateOf(listOf(UiQueuedRun(request.runId,request.threadId,"SYNTHETIC_OLD",request.text)))
            compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
                RunQueueDialog(items.value,onDismiss={},onEdit={id,text->
                    runBlocking(Dispatchers.IO){repo.chat.editQueuedRun(id,text,request.copy(text=text).toJson())}
                    items.value=items.value.map{if(it.id==id)it.copy(text=text,title=text)else it}
                },onMove={_,_->},onCancel={id->
                    runBlocking(Dispatchers.IO){repo.chat.cancelQueuedRun(id)}
                    items.value=items.value.filterNot{it.id==id}
                })
            }}}
            compose.onNodeWithTag("queue_edit_${request.runId}").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("SYNTHETIC_EDITED")
            compose.onNodeWithText("수정 적용").performClick()
            runBlocking(Dispatchers.IO){
                val saved=QueuedChatRequest.fromJson(checkNotNull(repo.chat.run(request.runId)).contextJson)
                assertEquals("SYNTHETIC_EDITED",saved.text)
                assertFalse(saved.allowTools);assertEquals("review",saved.mode)
                assertEquals(request.runId,saved.runId);assertEquals(request.threadId,saved.threadId)
                assertEquals("SYNTHETIC_EDITED",repo.getMessage(request.userMessageId)?.text)
            }
            compose.onNodeWithTag("queue_cancel_${request.runId}").performClick()
            runBlocking(Dispatchers.IO){assertEquals("cancelled",repo.chat.run(request.runId)?.status)}
        } finally {db.close();key.fill(0);check(name.startsWith("codex-progress-qa-"));context.deleteDatabase(name)}
    }

    @Test fun cancelledImageCleanupPreservesNewerOwnerEdits(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="codex-progress-qa-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name)
            .openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {runBlocking(Dispatchers.IO){
            val repo=LocalRepository(db)
            val receipt=repo.saveNote("SYNTHETIC_IMAGE","SYNTHETIC_ORIGINAL","generated-images")
            val edited=repo.saveNote("SYNTHETIC_IMAGE","SYNTHETIC_OWNER_EDIT","generated-images",receipt.id)
            assertFalse(repo.deleteGeneratedImageIfUnchanged(receipt))
            assertEquals(edited,repo.getNote(receipt.id))
            assertTrue(repo.deleteGeneratedImageIfUnchanged(edited))
            assertNull(repo.getNote(receipt.id))
        }} finally {db.close();key.fill(0);check(name.startsWith("codex-progress-qa-"));context.deleteDatabase(name)}
    }

    @Test fun pcImageSelectorLetsOwnerChooseEitherInstalledModel(){
        val selected=mutableStateOf(com.user.localagent.core.PcImageModelCatalog.QUALITY_ID)
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
            PcImageModelSelector(selected.value,{selected.value=it})
        }}}
        compose.onNodeWithTag("pc_image_model_qwen").assertIsSelected()
        compose.onNodeWithTag("pc_image_model_sdxl").performClick()
        compose.onNodeWithTag("pc_image_model_sdxl").assertIsSelected()
        compose.onNodeWithTag("pc_image_model_qwen").performClick()
        compose.onNodeWithTag("pc_image_model_qwen").assertIsSelected()
    }

    @Test fun composerShowsPendingContentsAndQueueManagementEntry(){
        compose.runOnUiThread{compose.activity.setContent{MaterialTheme{
            ChatWorkspace(emptyList(),false,"SYNTHETIC_IDLE",emptyList(),onModelChange={},onSend={false},onStop={},onNavigate={},
                composerKey="qa-room",draftValue="",queuedCount=2,queuedPreview=listOf("SYNTHETIC_FIRST","SYNTHETIC_SECOND"),onShowQueue={})
        }}}
        compose.onNodeWithTag("queue_preview_0").assertTextContains("SYNTHETIC_FIRST",substring=true)
        compose.onNodeWithTag("queue_preview_1").assertTextContains("SYNTHETIC_SECOND",substring=true)
        compose.onNodeWithTag("manage_run_queue").assertIsDisplayed()
    }
}
