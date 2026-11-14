package com.user.localagent

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.data.*
import com.user.localagent.imports.ConversationImport
import com.user.localagent.imports.PhoneRecords
import com.user.localagent.scheduling.ReminderScheduler
import com.user.localagent.tools.ToolExecutor
import com.user.localagent.workspace.ProjectReadDelivery
import com.user.localagent.workspace.ProjectWorkspace
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual phone tools/SQLCipher/private files/temporary scheduling; no model inference or owner DB.
 * Successful direct tool chains do not establish natural-language planning/coordinator success.
 * No account, provider content, external application, connected folder or real message is accessed.
 */
@RunWith(AndroidJUnit4::class)
class TeamProblemSolvingDeviceValidation {
    private data class Fixture(val context:Context,val database:LocalDatabase,val owner:LocalRepository,
        val a:LocalRepository,val b:LocalRepository,val workspace:ProjectWorkspace,val scheduler:ReminderScheduler,
        val tools:ToolExecutor)

    private fun call(name:String,arguments:JsonObjectBuilder.()->Unit={})=
        ToolCall(UUID.randomUUID().toString(),name,buildJsonObject(arguments))
    private fun verified(result:ToolResult):String {
        assertTrue(result.content,result.success);assertEquals(result.content,VerificationState.VERIFIED,result.verification)
        return result.content
    }
    private suspend fun readForEdit(f:Fixture,path:String):JsonObject {
        val result=f.tools.execute(call("read_project_file"){put("path",path)});verified(result)
        val item=buildJsonObject {put("tool","read_project_file");put("success",true);put("verification","VERIFIED")
            put("result",Json.parseToJsonElement(ProjectReadDelivery.modelResult(result.content)))}
        // Synthetic model-input delivery, using the actual host verifier; no model was invoked.
        f.tools.acceptProjectReadContext(listOf(item),"QA actual delivered input: "+JsonArray(listOf(item)))
        return Json.parseToJsonElement(result.content).jsonObject
    }

    @Test fun absentDeletionNeverClaimsAnActualChange()=runBlocking(Dispatchers.IO){fixture{f->
        val retained=f.a.saveNote("QA 남길 메모","원본 유지","qa-team")
        for(name in listOf("delete_note","delete_reminder")){
            val result=f.tools.execute(call(name){put("id",UUID.randomUUID().toString())})
            assertFalse(result.success);assertEquals(VerificationState.FAILED,result.verification)
            assertTrue(result.content.contains("없어"));assertFalse(result.content.contains("삭제·재조회 확인"))
        }
        assertEquals(retained,f.a.getNote(retained.id))
    }}

    @Test fun approvedRecordMustRemainTheSameUntilTheMutation()=runBlocking(Dispatchers.IO){fixture{f->
        val note=f.a.saveNote("QA 확인한 제목","승인 전 본문","qa-team")
        val edit=call("update_note"){put("id",note.id);put("scope","qa-team");put("content","실행되면 안 되는 본문")}
        assertTrue(f.tools.reviewForAction(edit).orEmpty().contains(note.title))
        val changed=f.a.saveNote("QA 다른 수정","새 버전 보존","qa-team",note.id,expectedUpdatedAt=note.updatedAt)
        assertFalse(f.tools.execute(edit).success);assertEquals(changed,f.a.getNote(note.id))
        val delete=call("delete_note"){put("id",note.id)}
        assertTrue(f.tools.reviewForAction(delete).orEmpty().contains(changed.title))
        val latest=f.a.saveNote(changed.title,"삭제 승인 뒤 정정","qa-team",note.id,expectedUpdatedAt=changed.updatedAt)
        assertFalse(f.tools.execute(delete).success);assertEquals(latest,f.a.getNote(note.id))
    }}

    @Test fun noteSearchCorrectionAndDeletionPreserveOtherProject()=runBlocking(Dispatchers.IO){fixture{f->
        val keep=f.b.saveNote("QA 보존 메모","다른 프로젝트 원문","qa-team")
        verified(f.tools.execute(call("save_note"){put("title","QA 팀 납기");put("content","납기 목요일 14시");put("scope","qa-team")}))
        val search=f.tools.execute(call("search_notes"){put("query","QA 팀 납기");put("scope","qa-team")});verified(search)
        val note=f.a.searchNotes("QA 팀 납기").single()
        assertTrue(search.content.contains(note.id))
        verified(f.tools.execute(call("update_note"){put("id",note.id);put("title",note.title);put("scope","qa-team");put("content","납기 금요일 16시")}))
        assertEquals("납기 금요일 16시",f.a.getNote(note.id)?.body)
        assertFalse(f.tools.execute(call("update_note"){put("id",keep.id);put("scope","qa-team");put("content","잘못된 대상")} ).success)
        assertEquals(keep,f.b.getNote(keep.id))
        verified(f.tools.execute(call("delete_note"){put("id",note.id)}))
        assertTrue(f.a.searchNotes("QA 팀 납기").isEmpty());assertEquals(keep,f.b.getNote(keep.id))
    }}

    @Test fun verifiedCalculationIsSavedAsExactSearchableNote()=runBlocking(Dispatchers.IO){fixture{f->
        val calculation=f.tools.execute(call("calculate"){put("expression","12000 * 3 + 4500");put("decimalPlaces",0)})
        val value=Json.parseToJsonElement(verified(calculation)).jsonObject.getValue("value").jsonPrimitive.content
        assertEquals("40500",value)
        val body="팀 발주 합계: "+value+"원\n수량 3개와 배송비 포함"
        verified(f.tools.execute(call("save_note"){put("title","QA 팀 발주 금액");put("content",body);put("scope","qa-team")}))
        val found=f.tools.execute(call("search_notes"){put("query","40500");put("scope","qa-team")});verified(found)
        val note=f.a.searchNotes("40500").single();assertEquals(body,note.body);assertTrue(found.content.contains(note.id))
        assertTrue(f.b.searchNotes("40500").isEmpty())
    }}

    @Test fun projectFileCreateReadAndExactEditPreserveSibling()=runBlocking(Dispatchers.IO){fixture{f->
        val before="담당: 미정\n기한: 금요일\n두  칸 😀"
        verified(f.tools.execute(call("write_project_file"){put("path","handoff.txt");put("content",before)}))
        verified(f.tools.execute(call("write_project_file"){put("path","keep.txt");put("content","보존 원문")}))
        val listed=f.tools.execute(call("list_project_files"));verified(listed);assertTrue(listed.content.contains("handoff.txt"))
        val read=readForEdit(f,"handoff.txt");assertEquals(before,read.getValue("text").jsonPrimitive.content)
        val after="담당: 운영팀\n기한: 금요일\n두  칸 😀"
        verified(f.tools.execute(call("write_project_file"){put("path","handoff.txt");put("content",after);put("expectedSha256",read.getValue("sha256"))}))
        assertEquals(after,f.workspace.read("handoff.txt"));assertEquals("보존 원문",f.workspace.read("keep.txt"))
    }}

    @Test fun staleFileHashCannotClobberAndFreshReadRepairsTheEdit()=runBlocking(Dispatchers.IO){fixture{f->
        val before="담당: 미정\n기한: 금요일"
        verified(f.tools.execute(call("write_project_file"){put("path","handoff.txt");put("content",before)}))
        val first=readForEdit(f,"handoff.txt")
        assertEquals(before,f.workspace.read("handoff.txt"))
        f.workspace.write("handoff.txt","담당: 미정\n기한: 다음 월요일",first.getValue("sha256").jsonPrimitive.content)
        val stale=f.tools.execute(call("write_project_file"){put("path","handoff.txt");put("content","담당: 운영팀\n기한: 금요일");put("expectedSha256",first.getValue("sha256"))})
        assertFalse(stale.success);assertEquals(VerificationState.FAILED,stale.verification)
        assertEquals("담당: 미정\n기한: 다음 월요일",f.workspace.read("handoff.txt"))
        val fresh=readForEdit(f,"handoff.txt")
        verified(f.tools.execute(call("write_project_file"){put("path","handoff.txt");put("content","담당: 운영팀\n기한: 다음 월요일");put("expectedSha256",fresh.getValue("sha256"))}))
        assertEquals("담당: 운영팀\n기한: 다음 월요일",f.workspace.read("handoff.txt"))
    }}

    @Test fun reminderCreateListCorrectAndDeleteLeaveNoQueue()=runBlocking(Dispatchers.IO){fixture{f->
        val at=OffsetDateTime.now().plusDays(7).withNano(0)
        verified(f.tools.execute(call("create_reminder"){put("title","QA 팀 회수");put("datetime",at.toString());put("scope","qa-team")}))
        val original=f.database.dao().observeReminders().first().single()
        assertEquals("QA 팀 회수",original.title);assertTrue(f.scheduler.verifyScheduled(original))
        val listed=f.tools.execute(call("list_reminders"){put("scope","qa-team");put("limit",10)});verified(listed)
        assertTrue(listed.content.contains(original.id));assertTrue(listed.content.contains(original.title))
        verified(f.tools.execute(call("update_reminder"){put("id",original.id);put("scope","qa-team");put("title","QA 최종 팀 회수");put("datetime",at.plusHours(1).toString())}))
        val edited=checkNotNull(f.a.getReminder(original.id));assertEquals(original.id,edited.id)
        assertEquals("QA 최종 팀 회수",edited.title);assertEquals(at.plusHours(1).toInstant().toEpochMilli(),edited.atEpochMillis)
        assertTrue(f.scheduler.verifyScheduled(edited))
        val again=f.tools.execute(call("list_reminders"){put("scope","qa-team")});verified(again);assertTrue(again.content.contains(edited.title))
        verified(f.tools.execute(call("delete_reminder"){put("id",original.id)}))
        assertTrue(f.database.dao().observeReminders().first().isEmpty())
        withTimeout(15_000){while(f.scheduler.verifyScheduled(edited))delay(25)}
    }}

    @Test fun sameGroupForeignReminderCannotBeListedEditedOrDeleted()=runBlocking(Dispatchers.IO){fixture{f->
        val other=f.b.saveReminder("QA 다른 프로젝트 알림",System.currentTimeMillis()+7L*24*60*60*1000,"qa-team")
        val listed=f.tools.execute(call("list_reminders"){put("scope","qa-team")});verified(listed)
        assertFalse(listed.content.contains(other.id));assertFalse(listed.content.contains(other.title))
        assertFalse(f.tools.execute(call("update_reminder"){put("id",other.id);put("scope","qa-team");put("title","잘못된 대상")}).success)
        assertFalse(f.tools.execute(call("delete_reminder"){put("id",other.id)}).success)
        assertEquals(other,f.b.getReminder(other.id))
    }}

    @Test fun malformedUnknownAndEscapingCallsLeaveFixtureUnchanged()=runBlocking(Dispatchers.IO){fixture{f->
        val attempts=listOf(call("delete_phone_files"),call("calculate"){put("expression","1 / 0")},
            call("save_note"){put("content","")},call("write_project_file"){put("path","../outside.txt");put("content","escape")})
        attempts.forEach{assertFalse(f.tools.execute(it).success)}
        assertTrue(f.a.notes.first().isEmpty());assertTrue(f.database.dao().observeReminders().first().isEmpty());assertTrue(f.workspace.list().isEmpty())
    }}

    @Test fun missingFileReadRecoversWithObservedCorrectedPath()=runBlocking(Dispatchers.IO){fixture{f->
        verified(f.tools.execute(call("write_project_file"){put("path","report-final.txt");put("content","최종 검수 완료")}))
        val missing=f.tools.execute(call("read_project_file"){put("path","report.txt")})
        assertFalse(missing.success);assertEquals(VerificationState.FAILED,missing.verification)
        val listed=f.tools.execute(call("list_project_files"));verified(listed);assertTrue(listed.content.contains("report-final.txt"))
        val found=f.tools.execute(call("read_project_file"){put("path","report-final.txt")})
        assertEquals("최종 검수 완료",Json.parseToJsonElement(verified(found)).jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("최종 검수 완료",f.workspace.read("report-final.txt"))
    }}

    private suspend fun fixture(block:suspend(Fixture)->Unit){
        assumeTrue("teamProblemSolvingApproved=true required",InstrumentationRegistry.getArguments().getString("teamProblemSolvingApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="team-problem-qa-"+UUID.randomUUID()+".db"
        val key=ByteArray(32).also{SecureRandom().nextBytes(it)};System.loadLibrary("sqlcipher")
        val database=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .addMigrations(KnowledgeMigrations.MIGRATION_1_2,ChatMigrations.MIGRATION_2_3).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        var workspace:ProjectWorkspace?=null;val scheduler=ReminderScheduler(context)
        try {
            database.openHelper.writableDatabase
            val owner=LocalRepository(database)
            val pa=owner.chat.createProject("QA 문제 해결 A",ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false))
            val pb=owner.chat.createProject("QA 문제 해결 B",ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false))
            val a=owner.forThread(owner.chat.createThread(pa.id,"QA A").id);val b=owner.forThread(owner.chat.createThread(pb.id,"QA B").id)
            val privateWorkspace=ProjectWorkspace(context,"qaTeam-"+UUID.randomUUID()).also{workspace=it}
            assertFalse(privateWorkspace.state.value.external)
            val tools=ToolExecutor(context,a,scheduler,ConversationImport(context,a),PhoneRecords(context),
                projectWorkspaceProvider={privateWorkspace},ownerRequest="QA 격리 자료의 도구 동작 검증")
            block(Fixture(context,database,owner,a,b,privateWorkspace,scheduler,tools))
        }finally {
            // Only the isolated database's UUID records can own these temporary scheduled jobs.
            try {database.dao().observeReminders().first().forEach{record->scheduler.cancel(record.id);database.dao().deleteReminder(record.id)}}
            finally {try{workspace?.discard()}finally{database.close();key.fill(0);check(name.startsWith("team-problem-qa-"));context.deleteDatabase(name)}}
        }
    }
}
