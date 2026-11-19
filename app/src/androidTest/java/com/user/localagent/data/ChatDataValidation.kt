package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID

/** Explicitly gated isolated synthetic databases; never opens local-agent.db. */
@RunWith(AndroidJUnit4::class)
class ChatDataValidation {
    @Test fun shortReplyToAPrivateQuestionStaysInItsBranch()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val chat=repo.chat
        val thread=chat.createThread()
        val other=chat.createThread()
        val branch=thread.activeBranchId
        val run=chat.createRun(thread.id,branch,modelId=com.user.localagent.core.ConversationPrivacy.LOCAL_SELECTION)
        chat.startRun(run.id)
        assertFalse(chat.awaitingPrivateReply(thread.id,branch))
        chat.updateRun(run.id,"paused",errorCode=com.user.localagent.core.NextActionQuestion.PAUSE_CODE)
        assertTrue(chat.awaitingPrivateReply(thread.id,branch))
        assertFalse(chat.awaitingPrivateReply(other.id,other.activeBranchId))
        chat.createRun(thread.id,branch)
        assertFalse(chat.awaitingPrivateReply(thread.id,branch))
    }}

    @Test fun scopedHistoryUsesCurrentBranchAndFullLiveChunksWithoutCrossProjectLeakage()=runBlocking(Dispatchers.IO){fixture{repo,db,_->
        val a=repo.chat.createProject("Synthetic history A");val b=repo.chat.createProject("Synthetic history B")
        val ta=repo.chat.createThread(a.id);val tb=repo.chat.createThread(b.id)
        val same="Synthetic equal source text QA-775"
        val ua=repo.chat.addMessage(ta.id,ta.activeBranchId,"user",same)
        val ub=repo.chat.addMessage(tb.id,tb.activeBranchId,"user",same)
        val run=repo.chat.createRun(ta.id,ta.activeBranchId,ua.id);repo.chat.startRun(run.id)
        val assistant=repo.chat.beginAssistant(run.id);val segment=repo.chat.beginSegment(run.id,assistant.id,1)
        val head="x".repeat(24000)+" CROSS";val tail="BOUNDARY ScopedAOnlyTail927"
        repo.chat.appendChunk(run.id,assistant.id,segment.id,0,head);repo.chat.appendChunk(run.id,assistant.id,segment.id,1,tail)
        val full=head+tail
        assertEquals("",db.dao().messageById(assistant.id)?.text)
        val scopedA=repo.forThread(ta.id);val scopedB=repo.forThread(tb.id)
        assertEquals(1L,scopedA.messageCount(same));assertEquals(1L,scopedB.messageCount(same));assertEquals(2L,repo.messageCount(same))
        assertEquals(2L,scopedA.messageCount());assertEquals(1L,scopedB.messageCount())
        assertEquals(full,scopedA.messagePage(query="CROSSBOUNDARY").messages.single().text)
        assertEquals(1L,scopedA.messageCount("ScopedAOnlyTail927"));assertEquals(0L,scopedB.messageCount("ScopedAOnlyTail927"))
        assertTrue(scopedB.messagePage(query="ScopedAOnlyTail927").messages.isEmpty())
        val first=scopedA.messagePage(limit=1);assertEquals(assistant.id,first.messages.single().id)
        assertEquals(full,first.messages.single().text)
        val older=scopedA.messagePage(before=first.nextCursor,limit=1);assertEquals(ua.id,older.messages.single().id);assertNull(older.nextCursor)
        assertTrue(runCatching {scopedA.messagePage(MessageCursor(ub.createdAt,ub.id))}.isFailure)
        // Force the admin query past a metadata-only 200-row scan page.
        db.withTransaction {repeat(205){index->
            val noise=repo.chat.addMessage(tb.id,tb.activeBranchId,"user","Synthetic history noise $index")
            db.openHelper.writableDatabase.execSQL("UPDATE messages SET createdAt=? WHERE id=?",arrayOf<Any?>(assistant.createdAt+1000+index,noise.id))
        }}
        assertEquals(full,repo.messagePage(query="CROSSBOUNDARY",limit=1).messages.single().text)
        assertEquals(1L,repo.messageCount("CROSSBOUNDARY"))
        assertEquals(full,repo.messagePage(limit=200,before=MessageCursor(assistant.createdAt+1,"zzzz")).messages.first {it.id==assistant.id}.text)
        val exported=ByteArrayOutputStream();repo.chat.exportText(ta.id,output=exported)
        assertTrue(exported.toString("UTF-8").contains(full))
        repo.chat.forkBranch(ta.id,ua.id)
        assertTrue(runCatching {scopedA.messagePage()}.isFailure);assertTrue(runCatching {scopedA.messageCount()}.isFailure)
        val current=repo.forThread(ta.id)
        assertEquals(1L,current.messageCount());assertTrue(current.messagePage(query="ScopedAOnlyTail927").messages.isEmpty())
        assertTrue(runCatching {current.messagePage(MessageCursor(assistant.createdAt,assistant.id))}.isFailure)
    }}

    @Test fun ingestionReceivesFullSourceTextAndKeepsProjectClaimsOutOfOwnerContext()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val project=repo.chat.createProject("Synthetic ingestion boundary");val thread=repo.chat.createThread(project.id)
        val other=repo.chat.createThread();val longSource="Synthetic source "+"y".repeat(24000)+" KnowledgeSourceTailA940"
        val source=repo.chat.addMessage(thread.id,thread.activeBranchId,"user",longSource)
        val unrelated=repo.chat.addMessage(other.id,other.activeBranchId,"user","Synthetic unrelated source")
        val scoped=repo.forThread(thread.id)
        val page=scoped.knowledgeMessagePage(limit=200)
        assertEquals(listOf(source.id),page.messages.map {it.id});assertEquals(longSource,page.messages.single().text)
        val global=repo.knowledgeMessagePage(limit=200)
        assertEquals(longSource,global.messages.first {it.id==source.id}.text);assertTrue(global.messages.any {it.id==unrelated.id})
        val claim=repo.saveKnowledgeClaim(KnowledgeClaim(predicate="personal_fact.qa_ingestion",value="ProjectOnlyClaimTailA940",status="confirmed",confidence=1.0,sourceKind="user_message",sourceId=source.id))
        assertNull(repo.getKnowledge(claim.id));assertEquals(claim.id,scoped.getKnowledge(claim.id)?.id)
        assertFalse(repo.ownerProfileContext("ProjectOnlyClaimTailA940").contains("ProjectOnlyClaimTailA940"))
        assertTrue(scoped.ownerProfileContext("ProjectOnlyClaimTailA940").contains("ProjectOnlyClaimTailA940"))
    }}

    @Test fun versionTwoMigrationPreservesIdsKeysAndLegacyHistory()=runBlocking(Dispatchers.IO){fixture(versionTwo=true){repo,db,_->
        assertEquals(3,db.openHelper.readableDatabase.version)
        assertEquals("qa-note",repo.getNote("qa-note")?.id)
        assertEquals("qa-memory",repo.getMemory("qa-memory")?.id)
        assertEquals("qa-scope",repo.listGrants().single().scope)
        val legacy=repo.chat.thread(ChatStore.LEGACY_THREAD_ID)!!
        assertEquals("default",repo.chat.project(legacy.projectId!!)?.workspaceKey)
        assertEquals("qa-message",repo.chat.messages(legacy.id).first().single().id)
        assertEquals("Synthetic existing conversation",repo.chat.fullMessageText("qa-message"))
    }}

    @Test fun draftsFullTextBranchesAndCopiesSurviveWithoutChangingOriginalIds()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val thread=repo.chat.createThread();val longText="synthetic full text ".repeat(3000)
        val original=repo.chat.addMessage(thread.id,thread.activeBranchId,"user",longText)
        assertEquals(longText,repo.chat.fullMessageText(original.id))
        repo.chat.saveDraft(thread.id,"unsent synthetic draft","qa-model",true,original.id,12)
        assertEquals("qa-model",repo.chat.readDraft(thread.id)?.modelId)
        val branch=repo.chat.forkBranch(thread.id,original.id,includeMessage=false)
        assertTrue(repo.chat.messages(thread.id,branch.id).first().isEmpty())
        assertEquals(original.id,repo.chat.messages(thread.id,thread.activeBranchId).first().single().id)
        repo.chat.selectBranch(thread.id,thread.activeBranchId)
        val copy=repo.chat.duplicateThread(thread.id)
        val copied=repo.chat.messages(copy.id).first().single()
        assertNotEquals(original.id,copied.id);assertEquals(original.id,copied.originMessageId);assertEquals(longText,copied.text)
        val out=ByteArrayOutputStream();assertEquals(1,repo.chat.exportText(thread.id,output=out));assertTrue(out.toString("UTF-8").contains(longText))
    }}

    @Test fun chunksAreDurableIdempotentAndRecoveredWithoutReplay()=runBlocking(Dispatchers.IO){fixture{repo,_,reopen->
        val thread=repo.chat.createThread();val user=repo.chat.addMessage(thread.id,thread.activeBranchId,"user","synthetic task")
        val run=repo.chat.createRun(thread.id,thread.activeBranchId,user.id,contextJson="{\"qa\":true}")
        repo.chat.startRun(run.id);val message=repo.chat.beginAssistant(run.id);val segment=repo.chat.beginSegment(run.id,message.id,1)
        assertTrue(repo.chat.appendChunk(run.id,message.id,segment.id,0,"Part one. ").inserted)
        assertFalse(repo.chat.appendChunk(run.id,message.id,segment.id,0,"Part one. ").inserted)
        assertTrue(runCatching {repo.chat.appendChunk(run.id,message.id,segment.id,0,"different")}.isFailure)
        repo.chat.appendChunk(run.id,message.id,segment.id,1,"Durable text.")
        assertEquals("Part one. Durable text.",repo.chat.messages(thread.id).first().last().text)
        val reopened=reopen();assertEquals(1,reopened.chat.recoverInterruptedRuns())
        assertEquals("Part one. Durable text.",reopened.chat.fullMessageText(message.id))
        assertEquals("interrupted",reopened.chat.run(run.id)?.status);assertEquals("{\"qa\":true}",reopened.chat.run(run.id)?.contextJson)
        assertTrue(runCatching {reopened.chat.appendChunk(run.id,message.id,segment.id,2,"late")}.isFailure)
    }}

    @Test fun scrollOnlyWritesPreserveComposerAndRejectOtherRoomOldBranchAndDeletedScope()=runBlocking(Dispatchers.IO){fixture{repo,_,reopen->
        val project=repo.chat.createProject("Synthetic viewport",ProjectSettings(modelId="qa-project-model",thinking=true))
        val a=repo.chat.createThread(project.id);val b=repo.chat.createThread()
        val anchorA=repo.chat.addMessage(a.id,a.activeBranchId,"user","Synthetic viewport A")
        val anchorB=repo.chat.addMessage(b.id,b.activeBranchId,"user","Synthetic viewport B")
        val first=repo.chat.saveScroll(a.id,anchorA.id,17,a.activeBranchId)
        assertEquals("qa-project-model",first.modelId);assertTrue(first.thinking)
        val draft=repo.chat.saveDraft(a.id,"SYNTHETIC_TYPED_DRAFT","qa-user-model",false)
        assertEquals(anchorA.id,draft.scrollMessageId);assertEquals(17,draft.scrollOffset)
        val other=repo.chat.saveDraft(b.id,"SYNTHETIC_OTHER_DRAFT","qa-other-model",true)
        val scrolled=repo.chat.saveScroll(a.id,anchorA.id,41,a.activeBranchId)
        assertEquals(draft.copy(scrollOffset=41),scrolled)
        assertEquals(other,repo.chat.readDraft(b.id))
        val packed=com.user.localagent.core.ChatViewportAnchorCodec.encode(anchorA.id,19)
        assertEquals(scrolled.copy(scrollMessageId=packed),repo.chat.saveScroll(a.id,packed,41,a.activeBranchId))
        assertTrue(runCatching{repo.chat.saveScroll(a.id,com.user.localagent.core.ChatViewportAnchorCodec.encode(anchorB.id,2),1,a.activeBranchId)}.isFailure)
        repo.chat.saveScroll(a.id,anchorA.id,41,a.activeBranchId)
        assertTrue(runCatching{repo.chat.saveScroll(a.id,anchorB.id,2,a.activeBranchId)}.isFailure)
        assertTrue(runCatching{repo.chat.saveScroll(b.id,anchorA.id,2,b.activeBranchId)}.isFailure)
        assertTrue(runCatching{repo.chat.saveScroll(a.id,null,1,a.activeBranchId)}.isFailure)
        val branch=repo.chat.forkBranch(a.id,anchorA.id,includeMessage=false)
        assertTrue(runCatching{repo.chat.saveScroll(a.id,anchorA.id,4,a.activeBranchId)}.isFailure)
        assertEquals(scrolled,repo.chat.readDraft(a.id))
        val bottom=repo.chat.saveScroll(a.id,null,0,branch.id)
        assertEquals(scrolled.copy(scrollMessageId=null,scrollOffset=0),bottom)
        val reopened=reopen();assertEquals(bottom,reopened.chat.readDraft(a.id));assertEquals(other,reopened.chat.readDraft(b.id))
        reopened.chat.clearThread(a.id)
        assertTrue(runCatching{reopened.chat.saveScroll(a.id,null,0,branch.id)}.isFailure)
        assertNull(reopened.chat.readDraft(a.id))
        reopened.chat.deleteThread(b.id)
        assertTrue(runCatching{reopened.chat.saveScroll(b.id,null,0,b.activeBranchId)}.isFailure)
        // The helper owns a reopened DB now; a rejected late scroll cannot recreate its row.
        assertTrue(runCatching{reopened.chat.readDraft(b.id)}.isFailure)
    }}

    @Test fun clearIsExactAndRejectsLateCallbacksWithoutErasingIndependentOwnerFacts()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val a=repo.chat.createThread();val b=repo.chat.createThread()
        val source=repo.chat.addMessage(a.id,a.activeBranchId,"user","identical synthetic phrase")
        val independent=repo.chat.addMessage(b.id,b.activeBranchId,"user","identical synthetic phrase")
        val earlier=repo.saveKnowledgeClaim(KnowledgeClaim(predicate="qa.role",value="old",status="confirmed",confidence=1.0,sourceKind="user_message",sourceId=source.id,updatedAt=1000))
        repo.saveKnowledgeClaim(KnowledgeClaim(predicate="qa.role",value="independent current",status="confirmed",confidence=1.0,sourceKind="owner_transfer",sourceId="qa-independent",updatedAt=2000))
        val run=repo.chat.createRun(a.id,a.activeBranchId,source.id);repo.chat.startRun(run.id)
        val answer=repo.chat.beginAssistant(run.id);val segment=repo.chat.beginSegment(run.id,answer.id,1)
        repo.chat.clearThread(a.id,deleteDerivedMemory=true)
        assertEquals("independent current",repo.getKnowledge(earlier.id)?.value)
        assertEquals(independent.id,repo.chat.messages(b.id).first().single().id)
        assertTrue(runCatching {repo.chat.appendChunk(run.id,answer.id,segment.id,0,"late")}.isFailure)
        assertFalse(repo.knowledgeSourceAllowed("user_message",source.id))
    }}

    @Test fun projectNotesAndDerivedFactsCannotLeakIntoGlobalOrAnotherProject()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        repo.saveKnowledgeClaim(KnowledgeClaim(predicate="response_preference",value="Synthetic global preference",status="confirmed",confidence=1.0,sourceKind="owner_approved",tags=listOf("core")))
        val a=repo.chat.createProject("Synthetic project A");val b=repo.chat.createProject("Synthetic project B")
        val ta=repo.chat.createThread(a.id);val tb=repo.chat.createThread(b.id)
        val scoped=repo.forThread(ta.id);val other=repo.forThread(tb.id)
        val note=scoped.saveNote("QA private project note","ProjectOnlyToken643","default")
        assertTrue(repo.searchNotes("ProjectOnlyToken643").isEmpty());assertTrue(other.searchNotes("ProjectOnlyToken643").isEmpty())
        assertEquals(note.id,scoped.searchNotes("ProjectOnlyToken643").single().id)
        val message=repo.chat.addMessage(ta.id,ta.activeBranchId,"user","Synthetic project statement")
        val fact=repo.saveKnowledgeClaim(KnowledgeClaim(predicate="response_preference",value="ProjectOnlyToken643",status="confirmed",confidence=1.0,sourceKind="user_message",sourceId=message.id,tags=listOf("core")))
        assertFalse(repo.ownerProfileContext("ProjectOnlyToken643").contains("ProjectOnlyToken643"))
        assertFalse(repo.ownerProfileContext(b.id,"ProjectOnlyToken643").contains("ProjectOnlyToken643"))
        assertTrue(repo.ownerProfileContext(a.id,"ProjectOnlyToken643").contains("ProjectOnlyToken643"))
        assertTrue(repo.ownerProfileContext("response preference").contains("Synthetic global preference"))
        assertNull(repo.getKnowledge(fact.id));assertEquals(fact.id,scoped.getKnowledge(fact.id)?.id)
        repo.chat.updateProject(a.id,settings=a.settings.copy(useOwnerMemory=false))
        assertFalse(repo.ownerProfileContext(a.id,"response preference").contains("Synthetic global preference"))
    }}

    @Test fun deletedProjectErasesAllItsRoomsAndLeavesOtherProjectsIntact()=runBlocking(Dispatchers.IO){fixture{repo,db,_->
        val doomed=repo.chat.createProject("Synthetic delete project");val kept=repo.chat.createProject("Synthetic kept project")
        val a=repo.chat.createThread(doomed.id);val b=repo.chat.createThread(doomed.id);val other=repo.chat.createThread(kept.id)
        repo.chat.archiveThread(b.id)
        val m=repo.chat.addMessage(a.id,a.activeBranchId,"user","Synthetic project delete text QA-911")
        repo.chat.addMessage(other.id,other.activeBranchId,"user","Synthetic kept text QA-912")
        val run=repo.chat.createRun(a.id,a.activeBranchId,m.id);repo.chat.saveDraft(b.id,"Synthetic draft")
        val result=repo.chat.deleteProject(doomed.id)
        assertEquals(2,result.threadsDeleted);assertEquals(1,result.messagesDeleted)
        assertNull(repo.chat.project(doomed.id))
        assertNull(repo.chat.thread(a.id));assertNull(repo.chat.thread(b.id))
        assertNull(db.dao().messageById(m.id));assertNull(db.chatDao().draft(b.id))
        assertTrue(repo.chat.run(run.id)?.status in ChatStore.TERMINAL)
        assertTrue(repo.chat.projects.first().none{it.id==doomed.id})
        assertNotNull(repo.chat.project(kept.id));assertNotNull(repo.chat.thread(other.id))
        assertEquals(1L,repo.forThread(other.id).messageCount())
        assertTrue(runCatching{repo.chat.createThread(doomed.id)}.isFailure)
        assertTrue(runCatching{repo.chat.deleteProject(doomed.id)}.isFailure)
    }}

    @Test fun deletedRoomCannotReceiveDraftMessageOrRun()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val t=repo.chat.createThread();repo.chat.deleteThread(t.id)
        assertNull(repo.chat.thread(t.id))
        assertTrue(runCatching {repo.chat.saveDraft(t.id,"late draft")}.isFailure)
        assertTrue(runCatching {repo.chat.addMessage(t.id,t.activeBranchId,"user","late text")}.isFailure)
        assertTrue(runCatching {repo.chat.createRun(t.id,t.activeBranchId)}.isFailure)
    }}

    @Test fun ownerForgetErasesOwnerLiteralCopiesButPreservesSameTextInPrivateProject()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val text="Synthetic shared phrase QA-9726"
        val ownerSource=repo.addMessage("user",text)
        val ownerCopy=repo.addMessage("user","Owner duplicate: $text")
        val ownerMemory=repo.saveMemory(text,"fact",ownerSource)
        val project=repo.chat.createProject("Synthetic private erasure boundary")
        val thread=repo.chat.createThread(project.id);val scoped=repo.forThread(thread.id)
        val privateSource=repo.chat.addMessage(thread.id,thread.activeBranchId,"user",text)
        val privateMemory=scoped.saveMemory(text,"fact",privateSource.id)
        val note=scoped.saveNote("Synthetic private note",text)
        val run=repo.chat.createRun(thread.id,thread.activeBranchId,privateSource.id);repo.chat.startRun(run.id)
        val answer=repo.chat.beginAssistant(run.id);val segment=repo.chat.beginSegment(run.id,answer.id,1)
        repo.chat.appendChunk(run.id,answer.id,segment.id,0,text);repo.chat.finishSegment(segment.id)
        repo.chat.updateRun(run.id,"completed")

        repo.forgetMemory(ownerMemory.id)

        assertNull(repo.getMessage(ownerSource));assertNull(repo.getMessage(ownerCopy));assertNull(repo.getMemory(ownerMemory.id))
        assertEquals(text,repo.getMessage(privateSource.id)?.text)
        assertEquals(text,repo.chat.fullMessageText(answer.id));assertEquals(text,repo.chat.segmentText(segment.id))
        assertEquals(privateMemory.id,scoped.getMemory(privateMemory.id)?.id)
        assertEquals(privateMemory.text,scoped.getKnowledge("memory.${privateMemory.id}")?.value)
        assertEquals(text,scoped.getNote(note.id)?.body)
        assertTrue(repo.knowledgeSourceAllowed("user_message",privateSource.id))
        assertFalse(repo.knowledgeSourceAllowed("user_message",ownerSource))
    }}

    @Test fun explicitTextResumeRebindsReturnedBranchButNeverDeletedTextOrChangedProject()=runBlocking(Dispatchers.IO){fixture{repo,_,_->
        val project=repo.chat.createProject("Synthetic resume project")
        suspend fun pausedThread():Triple<ChatThreadRecord,ChatRunRecord,MessageRecord>{
            val thread=repo.chat.createThread(project.id);val user=repo.chat.addMessage(thread.id,thread.activeBranchId,"user","Synthetic resume request")
            val run=repo.chat.createRun(thread.id,thread.activeBranchId,user.id);repo.chat.startRun(run.id)
            val message=repo.chat.beginAssistant(run.id)
            repo.chat.updateRunContext(run.id,buildJsonObject {put("allowTools",true);put("continuation",buildJsonObject {put("messageId",message.id)})}.toString())
            repo.chat.updateRun(run.id,"paused")
            return Triple(thread,run,message)
        }
        val (thread,run,message)=pausedThread()
        repo.chat.resumeRun(run.id)
        val oldSegment=repo.chat.beginSegment(run.id,message.id,1)
        repo.chat.appendChunk(run.id,message.id,oldSegment.id,0,"Synthetic durable first part")
        repo.chat.updateRun(run.id,"paused")
        repo.chat.forkBranch(thread.id,message.id,"Synthetic saved branch")
        assertTrue(runCatching {repo.chat.resumeRun(run.id)}.isFailure)
        repo.chat.selectBranch(thread.id,thread.activeBranchId)
        val resumed=repo.chat.resumeRun(run.id)
        assertEquals(repo.chat.thread(thread.id)?.revision,resumed.threadRevision)
        assertEquals("running",resumed.status)
        assertEquals(false,Json.parseToJsonElement(resumed.contextJson).jsonObject.getValue("allowTools").jsonPrimitive.boolean)
        assertTrue(runCatching {repo.chat.appendChunk(run.id,message.id,oldSegment.id,1,"late old callback")}.isFailure)
        val next=repo.chat.beginSegment(run.id,message.id,2)
        repo.chat.appendChunk(run.id,message.id,next.id,0," plus resumed text")
        repo.chat.finishSegment(next.id);repo.chat.updateRun(run.id,"paused")
        assertEquals("Synthetic durable first part plus resumed text",repo.chat.fullMessageText(message.id))
        repo.chat.updateProject(project.id,settings=project.settings.copy(instructions="Synthetic changed instruction"))
        assertTrue(runCatching {repo.chat.resumeRun(run.id)}.isFailure)

        val (cleared,clearRun,_)=pausedThread();repo.chat.clearThread(cleared.id)
        assertTrue(runCatching {repo.chat.resumeRun(clearRun.id)}.isFailure)
        val (deleted,deleteRun,_)=pausedThread();repo.chat.deleteThread(deleted.id)
        assertTrue(runCatching {repo.chat.resumeRun(deleteRun.id)}.isFailure)
    }}

    private suspend fun fixture(versionTwo:Boolean=false,block:suspend(LocalRepository,LocalDatabase,suspend()->LocalRepository)->Unit){
        assumeTrue("chatDataValidationApproved=true required",InstrumentationRegistry.getArguments().getString("chatDataValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="chat-data-qa-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)};System.loadLibrary("sqlcipher");var database:LocalDatabase?=null
        fun open():LocalDatabase=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .addMigrations(KnowledgeMigrations.MIGRATION_1_2,ChatMigrations.MIGRATION_2_3).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            if(versionTwo){
                val helper=SupportOpenHelperFactory(key.clone()).create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(2){
                    override fun onCreate(db:SupportSQLiteDatabase){
                        listOf(
                            "CREATE TABLE notes(id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,collection TEXT NOT NULL,updatedAt INTEGER NOT NULL)",
                            "CREATE TABLE memories(id TEXT NOT NULL PRIMARY KEY,text TEXT NOT NULL,kind TEXT NOT NULL,sourceMessageId TEXT,updatedAt INTEGER NOT NULL)",
                            "CREATE TABLE reminders(id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,atEpochMillis INTEGER NOT NULL,reminder_group TEXT NOT NULL,status TEXT NOT NULL,updatedAt INTEGER NOT NULL)",
                            "CREATE TABLE messages(id TEXT NOT NULL PRIMARY KEY,role TEXT NOT NULL,text TEXT NOT NULL,createdAt INTEGER NOT NULL)",
                            "CREATE TABLE tool_runs(id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,success INTEGER NOT NULL,summary TEXT NOT NULL,at INTEGER NOT NULL)",
                            "CREATE TABLE grants(extensionId TEXT NOT NULL,codeHash TEXT NOT NULL,toolName TEXT NOT NULL,scope TEXT NOT NULL,PRIMARY KEY(extensionId,codeHash,toolName,scope))",
                            "CREATE TABLE summaries(id TEXT NOT NULL PRIMARY KEY,text TEXT NOT NULL,sourceMessageIds TEXT NOT NULL)"
                        ).forEach(db::execSQL)
                        KnowledgeMigrations.MIGRATION_1_2.migrate(db)
                        db.execSQL("INSERT INTO notes VALUES ('qa-note','Existing note','Synthetic existing note','default',1)")
                        db.execSQL("INSERT INTO memories VALUES ('qa-memory','Synthetic existing memory','fact',NULL,1)")
                        db.execSQL("INSERT INTO messages VALUES ('qa-message','user','Synthetic existing conversation',1)")
                        db.execSQL("INSERT INTO grants VALUES ('qa-extension','qa-hash','qa-tool','qa-scope')")
                    }
                    override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Room owns upgrade")
                }).build());helper.writableDatabase;helper.close()
            }
            val opened=open();database=opened;opened.openHelper.writableDatabase
            block(LocalRepository(opened),opened){database?.close();val reopened=open();database=reopened;reopened.openHelper.writableDatabase;LocalRepository(reopened)}
        }finally {database?.close();key.fill(0);check(name.startsWith("chat-data-qa-"));context.deleteDatabase(name)}
    }
}
