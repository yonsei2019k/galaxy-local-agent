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
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual isolated Workspace/ToolExecutor with synthetic model-input delivery.
 * No actual model, owner provider/account or approval UI is used. Direct tool calls are fixture-authorized only. */
@RunWith(AndroidJUnit4::class)
class ProjectWriteBindingValidation {
    private data class Space(val workspace:ProjectWorkspace,val root:File)
    private class Fixture(val context:Context,val repository:LocalRepository) {
        val spaces=mutableListOf<Space>();val executors=mutableListOf<ToolExecutor>()
        fun freshSpace():Space {
            val namespace="schema-read-"+UUID.randomUUID().toString()
            val parent=File(context.filesDir,"projects").canonicalFile;val root=File(parent,namespace).canonicalFile
            check(root.parentFile==parent&&root.name==namespace&&!root.exists())
            return Space(ProjectWorkspace(context,namespace),root).also{spaces+=it}
        }
        var current=freshSpace()
        val workspace get()=current.workspace
        fun tools()=ToolExecutor(context,repository,ReminderScheduler(context),ConversationImport(context,repository),PhoneRecords(context),
            projectWorkspaceProvider={workspace}).also{it.bindAvailableTools(setOf("read_project_file","write_project_file"));executors+=it}
    }
    private suspend fun fixture(block:suspend(Fixture)->Unit){
        assumeTrue(InstrumentationRegistry.getArguments().getString("projectWriteBindingApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="project-read-binding-qa-"+UUID.randomUUID()+".db";val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .addMigrations(KnowledgeMigrations.MIGRATION_1_2,ChatMigrations.MIGRATION_2_3).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        val f=Fixture(context,LocalRepository(db))
        try{db.openHelper.writableDatabase;block(f)}finally{withContext(NonCancellable){
            try{
                f.executors.forEach{it.releaseProjectReadContext()}
                f.spaces.forEach{space->
                    check(space.root.parentFile==File(context.filesDir,"projects").canonicalFile&&space.root.name.startsWith("schema-read-"))
                    space.workspace.discard();assertFalse(space.root.exists())
                }
            }finally{db.close();key.fill(0);check(name.startsWith("project-read-binding-qa-"));check(context.deleteDatabase(name)){"Fixture database deletion failed"}}
        }}
    }
    private fun call(name:String,path:String,content:String?=null,hash:String?=null)=ToolCall("qa-"+UUID.randomUUID(),name,buildJsonObject{
        put("path",path);content?.let{put("content",it)};hash?.let{put("expectedSha256",it)}
    })
    private suspend fun read(tools:ToolExecutor,path:String):ObservedToolOutcome {
        val c=call("read_project_file",path);return ObservedToolOutcome(c,tools.execute(c))
    }
    private fun evidence(row:ObservedToolOutcome)=buildJsonObject{
        put("tool",row.call.name);put("success",row.result.success);put("verification",row.result.verification.name)
        put("result",Json.parseToJsonElement(ProjectReadDelivery.modelResult(row.result.content)))
    }
    private fun query(rows:List<ObservedToolOutcome>)=JsonArray(rows.map(::evidence)).toString()
    private suspend fun accept(tools:ToolExecutor,rows:List<ObservedToolOutcome>,actualQuery:String=query(rows))=
        tools.acceptProjectReadContext(rows.map(::evidence),actualQuery,rows)
    private fun hash(row:ObservedToolOutcome)=Json.parseToJsonElement(row.result.content).jsonObject.getValue("sha256").jsonPrimitive.content
    private fun pairs(hint:ProjectWriteSchemaConstraints):Map<String,String> {
        val schema=Json.parseToJsonElement(ToolCallSchema.forTools(setOf("write_project_file"),projectWrites=hint)).jsonObject
        val arguments=schema.getValue("oneOf").jsonArray.single().jsonObject.getValue("properties").jsonObject.getValue("arguments").jsonObject
        val variants=(arguments["oneOf"] as? JsonArray)?.map{it.jsonObject}?:listOf(arguments)
        return variants.filter{"expectedSha256" in it.getValue("properties").jsonObject}.associate{
            val p=it.getValue("properties").jsonObject
            p.getValue("path").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content to
                p.getValue("expectedSha256").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content
        }
    }

    @Test fun completeDeliveredSourceExportsItsOwnPairAndExistingCasStillRejectsWrongHash()=runBlocking(Dispatchers.IO){fixture{f->
        f.workspace.write("source.txt","original\r\n두  칸 😀");f.workspace.write("keep.txt","keep unchanged")
        val tools=f.tools();val r=read(tools,"source.txt");assertTrue(r.result.success)
        assertEquals(mapOf("source.txt" to hash(r)),pairs(accept(tools,listOf(r))))
        val bad=call("write_project_file","source.txt","replacement","0".repeat(64))
        assertFalse(tools.execute(bad).success);assertEquals("0".repeat(64),bad.arguments.getValue("expectedSha256").jsonPrimitive.content)
        assertEquals("original\r\n두  칸 😀",f.workspace.read("source.txt"))
        val good=tools.execute(call("write_project_file","source.txt","replacement",hash(r)))
        assertTrue(good.content,good.success);assertEquals("replacement",f.workspace.read("source.txt"));assertEquals("keep unchanged",f.workspace.read("keep.txt"))
    }}

    @Test fun permissionRevokedAtCommitKeepsTheOriginalFile()=runBlocking(Dispatchers.IO){fixture{f->
        f.workspace.write("source.txt","original")
        val tools=f.tools()
        val observed=read(tools,"source.txt")
        accept(tools,listOf(observed))
        val expectedBinding=f.workspace.bindingFingerprint()
        val reachedCommit=CompletableDeferred<String>()
        val permission=CompletableDeferred<Boolean>()
        val write=async {
            tools.execute(call("write_project_file","source.txt","replacement",hash(observed)),verifyProjectWrite={binding->
                reachedCommit.complete(binding)
                check(permission.await())
            })
        }
        assertEquals(expectedBinding,withTimeout(5_000){reachedCommit.await()})
        permission.complete(false)
        val refused=withTimeout(5_000){write.await()}
        assertFalse(refused.success)
        assertTrue(refused.effectNotApplied)
        assertEquals("original",f.workspace.read("source.txt"))
    }}
    @Test fun matchingSchemaHashCannotAuthorizeAnOmittedOrTruncatedActualBody()=runBlocking(Dispatchers.IO){fixture{f->
        f.workspace.write("source.txt","actual complete source")
        val tools=f.tools();val r=read(tools,"source.txt")
        assertEquals(hash(r),pairs(accept(tools,listOf(r)))["source.txt"])
        assertTrue(pairs(accept(tools,listOf(r),"query omitted host source array")).isEmpty())
        val fabricatedHint=ProjectWriteSchemaConstraints.fromHostReadSnapshot("synthetic schema only",listOf(ProjectWriteReadBinding("source.txt",hash(r))))
        assertEquals(hash(r),pairs(fabricatedHint)["source.txt"])
        assertFalse(tools.execute(call("write_project_file","source.txt","should not apply",hash(r))).success)
        assertTrue(pairs(accept(tools,listOf(r),query(listOf(r)).take(80))).isEmpty())
        assertEquals("actual complete source",f.workspace.read("source.txt"))
    }}
    @Test fun latestPartialAndActualFailedReadCannotReviveOlderGoodDelivery()=runBlocking(Dispatchers.IO){fixture{f->
        f.workspace.write("source.txt","original");val tools=f.tools();val good=read(tools,"source.txt")
        assertFalse(pairs(accept(tools,listOf(good))).isEmpty())
        f.workspace.write("source.txt","x".repeat(9000),hash(good))
        val partial=read(tools,"source.txt");assertTrue(partial.result.success)
        assertTrue(pairs(accept(tools,listOf(good,partial))).isEmpty())
        assertFalse(tools.execute(call("write_project_file","source.txt","must not overwrite excerpt",hash(partial))).success)
        // Restore fixture bytes, then make a real same-path read fail. Old issued evidence must remain unusable after restoration.
        f.workspace.write("source.txt","original",hash(partial));val restored=read(tools,"source.txt");accept(tools,listOf(restored))
        val target=File(f.current.root,"source.txt").canonicalFile;check(target.parentFile==f.current.root.canonicalFile);assertTrue(target.delete())
        val failed=read(tools,"source.txt");assertFalse(failed.result.success);f.workspace.write("source.txt","original")
        assertTrue(pairs(accept(tools,listOf(restored,failed))).isEmpty())
        assertFalse(tools.execute(call("write_project_file","source.txt","must not apply old proof",hash(restored))).success)
    }}
    @Test fun otherRequestReaderReleasedAuthorityAndDifferentWorkspaceCannotBorrowTheSnapshot()=runBlocking(Dispatchers.IO){fixture{f->
        f.workspace.write("source.txt","same bytes");val a=f.tools();val b=f.tools();val r=read(a,"source.txt")
        assertFalse(pairs(accept(a,listOf(r))).isEmpty());assertTrue(pairs(accept(b,listOf(r))).isEmpty())
        assertFalse(b.execute(call("write_project_file","source.txt","wrong request",hash(r))).success)
        a.releaseProjectReadContext();assertTrue(pairs(accept(a,listOf(r))).isEmpty())
        val again=read(a,"source.txt");accept(a,listOf(again));f.current=f.freshSpace();f.workspace.write("source.txt","same bytes")
        assertTrue(pairs(accept(a,listOf(again))).isEmpty())
        assertFalse(a.execute(call("write_project_file","source.txt","wrong workspace",hash(again))).success)
    }}
    @Test fun staleExternalWriteFailsCasAndSuccessfulToolWriteRequiresANewReadToken()=runBlocking(Dispatchers.IO){fixture{f->
        f.workspace.write("source.txt","original");val tools=f.tools();val old=read(tools,"source.txt");accept(tools,listOf(old))
        f.workspace.write("source.txt","external newer bytes",hash(old))
        assertFalse(tools.execute(call("write_project_file","source.txt","must not replace newer bytes",hash(old))).success)
        assertEquals("external newer bytes",f.workspace.read("source.txt"))
        val fresh=read(tools,"source.txt");assertEquals(mapOf("source.txt" to hash(fresh)),pairs(accept(tools,listOf(old,fresh))))
        val write=tools.execute(call("write_project_file","source.txt","model selected new body",hash(fresh)));assertTrue(write.content,write.success)
        assertTrue(pairs(accept(tools,listOf(fresh))).isEmpty())
        val latest=read(tools,"source.txt");assertFalse(pairs(accept(tools,listOf(latest))).isEmpty())
    }}
    @Test fun pathIdentityAndHashAbsentCreationBranchDoNotEnableExistingFileOverwrite()=runBlocking(Dispatchers.IO){fixture{f->
        val tools=f.tools();val paths=listOf("A.txt","a.txt","two  spaces.txt","e\u0301.txt","é.txt")
        paths.forEach{f.workspace.write(it,"Synthetic "+it)}
        val rows=paths.map{read(tools,it)};val actual=pairs(accept(tools,rows));assertEquals(paths.toSet(),actual.keys)
        paths.forEach{assertEquals(hash(rows.single{r->r.call.arguments.getValue("path").jsonPrimitive.content==it}),actual[it])}
        assertTrue(tools.execute(call("write_project_file","new.txt","new content")).success)
        assertFalse(tools.execute(call("write_project_file","new.txt","overwrite without hash")).success)
        assertFalse(tools.execute(call("write_project_file","new.txt","wrong hash via free JSON","f".repeat(64))).success)
        assertEquals("new content",f.workspace.read("new.txt"))
        for(path in listOf("../outside.txt","/outside.txt","C:/outside.txt","dir\\outside.txt","dir//outside.txt"))
            assertFalse(tools.execute(call("write_project_file",path,"must not escape")).success)
    }}
}
