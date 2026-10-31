package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.compactJsonData
import com.user.localagent.workspace.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual synthetic private files only; never reads user folders, model history or providers. */
@RunWith(AndroidJUnit4::class)
class ProjectReadProofValidation {
    private fun raw(read:ProjectToolRead)=buildJsonObject {
        put("path",read.path);put("text",read.text.take(8000));put("truncated",read.text.length>8000)
        put("sha256",read.sha256);put("readToken",read.token);put("untrustedSourceData",true)
    }.toString()
    private fun evidence(wrapped:String)=listOf(buildJsonObject{
        put("tool","read_project_file");put("success",true);put("verification","VERIFIED");put("result",Json.parseToJsonElement(wrapped))
    })
    private suspend fun deliver(workspace:ProjectWorkspace,reader:String,rows:List<JsonObject>,query:String=JsonArray(rows).toString()){
        workspace.acceptToolReadContext(reader,ProjectReadDelivery.delivered(rows,query))
    }
    @Test fun compactedTailCannotAuthorizeButWhole301Through8000CharacterSourcesCan()=runBlocking(Dispatchers.IO){fixture{workspace->
        for(length in listOf(301,8000)){
            val path="file-$length.txt";val tail="TAIL_UNMODIFIED!";val original="x".repeat(length-tail.length)+tail
            assertEquals(length,original.length);workspace.write(path,original)
            val read=workspace.readForTool(path,"run-a")
            assertTrue(runCatching{workspace.writeForTool(path,"partial",read.sha256,"run-a")}.isFailure)
            deliver(workspace,"run-a",evidence(compactJsonData(raw(read),1100)))
            assertTrue(runCatching{workspace.writeForTool(path,"partial",read.sha256,"run-a")}.isFailure)
            assertEquals(original,workspace.read(path))
            val full=ProjectReadDelivery.modelResult(raw(read));val fullData=Json.parseToJsonElement(full).jsonObject.getValue("data").jsonObject
            assertEquals(original,fullData.getValue("text").jsonPrimitive.content)
            deliver(workspace,"run-a",evidence(full))
            workspace.writeForTool(path,"Synthetic revised whole file",read.sha256,"run-a")
            assertEquals("Synthetic revised whole file",workspace.read(path))
        }
    }}
    @Test fun partialFlagsMalformedWrapperAndHashMismatchNeverPromoteReadProof()=runBlocking(Dispatchers.IO){fixture{workspace->
        val original="q".repeat(8001);workspace.write("large.txt",original)
        val read=workspace.readForTool("large.txt","run-a")
        deliver(workspace,"run-a",evidence(ProjectReadDelivery.modelResult(raw(read))))
        assertTrue(runCatching{workspace.writeForTool("large.txt","short",read.sha256,"run-a")}.isFailure)
        assertEquals(original,workspace.read("large.txt"))
        workspace.write("small.txt","Synthetic whole file");val small=workspace.readForTool("small.txt","run-a")
        val good=Json.parseToJsonElement(ProjectReadDelivery.modelResult(raw(small))).jsonObject
        val badViews=listOf(
            JsonObject(good+ ("truncated" to JsonPrimitive(true))),
            JsonObject(good+ ("sourceScopePartial" to JsonPrimitive(true))),
            JsonObject(good+ ("data" to JsonObject(good.getValue("data").jsonObject+("truncated" to JsonPrimitive(true))))),
            JsonObject(good+ ("data" to JsonObject(good.getValue("data").jsonObject+("text" to JsonPrimitive("Unseen replacement")))))
        )
        for(view in badViews){deliver(workspace,"run-a",evidence(view.toString()));assertTrue(runCatching{workspace.writeForTool("small.txt","overwrite",small.sha256,"run-a")}.isFailure)}
        deliver(workspace,"run-a",evidence(ProjectReadDelivery.modelResult(raw(small)+"\nAUDIT_SAVE_UNCONFIRMED")))
        assertTrue(runCatching{workspace.writeForTool("small.txt","overwrite",small.sha256,"run-a")}.isFailure)
        assertEquals(small.text,workspace.read("small.txt"))
    }}
    @Test fun uiAndOtherRequestProofsCannotAuthorizeAndPrunedContextRevokesCurrentRequest()=runBlocking(Dispatchers.IO){fixture{workspace->
        workspace.write("file.txt","Synthetic initial source");workspace.read("file.txt")
        val read=workspace.readForTool("file.txt","request-a");val rows=evidence(ProjectReadDelivery.modelResult(raw(read)))
        deliver(workspace,"request-a",rows)
        assertTrue(runCatching{workspace.writeForTool("file.txt","wrong request",read.sha256,"request-b")}.isFailure)
        workspace.read("file.txt") // Full UI read still cannot grant another request.
        assertTrue(runCatching{workspace.writeForTool("file.txt","wrong request",read.sha256,"request-b")}.isFailure)
        deliver(workspace,"request-a",rows,"The host evidence was pruned from this actual query.")
        assertTrue(runCatching{workspace.writeForTool("file.txt","lost tail",read.sha256,"request-a")}.isFailure)
        assertEquals(read.text,workspace.read("file.txt"))
        deliver(workspace,"request-a",rows);workspace.releaseToolReadContext("request-a")
        assertTrue(runCatching{workspace.writeForTool("file.txt","released request",read.sha256,"request-a")}.isFailure)
    }}
    @Test fun explicitFolderResetInvalidatesOldTokensWhileUiFullReadStillEnablesUiEditing()=runBlocking(Dispatchers.IO){fixture{workspace->
        val original="Synthetic owner editable text";workspace.write("file.txt",original)
        val read=workspace.readForTool("file.txt","run-a");val rows=evidence(ProjectReadDelivery.modelResult(raw(read)))
        deliver(workspace,"run-a",rows);workspace.usePrivateProject();deliver(workspace,"run-a",rows)
        assertTrue(runCatching{workspace.writeForTool("file.txt","stale folder token",read.sha256,"run-a")}.isFailure)
        assertTrue(runCatching{workspace.write("file.txt","stale UI proof",read.sha256)}.isFailure)
        assertEquals(original,workspace.read("file.txt"));workspace.write("file.txt","Synthetic actual UI edit",read.sha256)
        assertEquals("Synthetic actual UI edit",workspace.read("file.txt"))
    }}
    private suspend fun fixture(block:suspend(ProjectWorkspace)->Unit){
        assumeTrue("projectReadProofValidationApproved=true required",InstrumentationRegistry.getArguments().getString("projectReadProofValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val namespace="readproof-${UUID.randomUUID()}"
        val workspace=ProjectWorkspace(context,namespace);val root=File(context.filesDir,"projects/$namespace").canonicalFile
        try {block(workspace)}finally{
            check(root.parentFile==File(context.filesDir,"projects").canonicalFile&&root.name==namespace)
            root.listFiles().orEmpty().forEach{check(it.isFile&&it.canonicalFile.parentFile==root);it.delete()};root.delete()
            context.getSharedPreferences("project-workspace-v1-$namespace",0).edit().clear().commit()
        }
    }
}
