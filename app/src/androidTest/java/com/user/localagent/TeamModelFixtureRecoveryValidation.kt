package com.user.localagent

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Recovery of the single storage round whose report escaped in1067, never a general project deleter. */
class TeamModelFixtureRecoveryValidation {
    private fun png(color:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        return try {bitmap.eraseColor(color);ByteArrayOutputStream().use {out->
            check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray()
        }}finally{bitmap.recycle()}
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun writeReport(file:File,report:JsonObject) {
        check(file.parentFile?.isDirectory==true||file.parentFile?.mkdirs()==true)
        val atomic=AtomicFile(file);val out=atomic.startWrite()
        try{out.write(report.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(out)}catch(error:Throwable){
            try{atomic.failWrite(out)}catch(cleanup:Throwable){if(cleanup!==error)error.addSuppressed(cleanup)};throw error
        }
    }
    @Test fun recoverExactlyBoundInterruptedStorageFixture()=runBlocking(Dispatchers.Default) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("teamModelFixtureRecoveryApproved")=="true")
        val token=checkNotNull(args.getString("teamModelFixtureRecoveryBatchToken"))
        require(UUID.fromString(token).toString()==token)
        val expectedRevision=checkNotNull(args.getString("teamModelFixtureRecoveryPcRevision")?.toLongOrNull())
        val expectedModel=checkNotNull(args.getString("teamModelFixtureRecoveryModelLabel"))
        require(expectedModel.isNotBlank()&&expectedModel.length<=100)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val expectedVersion=checkNotNull(args.getString("teamModelFixtureRecoveryExpectedVersion")?.toLongOrNull())
        check(context.packageManager.getPackageInfo(context.packageName,0).longVersionCode==expectedVersion)
        val app=(context.applicationContext as LocalAgentApplication).container
        val chat=app.repository.chat
        fun quiet(){check(!app.runs.hasActiveRuns.value&&!app.runs.busy.value){"Owner work active; no recovery"}}
        quiet();check(chat.queuedRuns().isEmpty())
        val title="qa-team-model-$token-1-storage_report"
        val noteTitle=title+"-result"
        val expectedOwner="현재 프로젝트 저장소를 실제 점검하고 파일의 바이트와 SHA-256을 비교해 정확히 같은 중복 파일을 찾아줘. 파일은 삭제하거나 수정하지 마. 제목 '"+noteTitle+"' 메모에 실제 관찰값으로 보고서를 저장해. 본문은 다음 네 줄 형식만 사용해: '파일 수=숫자', '중복 그룹 수=숫자', '중복 경로=가나다순 경로를 쉼표와 한 칸으로 연결', '추가 사본 바이트=숫자'."
        val allowed=setOf("inspect_project_storage","find_project_duplicates","save_note")
        val project=chat.projects.first().single{it.title==title}
        require(UUID.fromString(project.id).toString()==project.id&&project.workspaceKey==project.id)
        require(project.revision==0L&&!project.archived)
        require(project.settings==com.user.localagent.data.ProjectSettings(useOwnerMemory=false,learnOwnerFacts=false,
            disabledTools=ToolRegistry.definitions.keys-allowed))
        val room=chat.threads.first().filter{it.projectId==project.id}.single()
        require(room.title==title&&room.revision==0L&&!room.archived&&room.deletedAt==null)
        val branches=chat.branches(room.id)
        require(branches.map{it.id}==listOf(room.activeBranchId))
        val run=chat.runsForThread(room.id).single()
        require(run.projectId==project.id&&run.threadId==room.id&&run.branchId==room.activeBranchId&&run.modelId==expectedModel)
        require(run.status !in setOf("queued","running","waiting_approval"))
        val request=QueuedChatRequest.fromJson(run.contextJson)
        require(request.runId==run.id&&request.threadId==room.id&&request.branchId==room.activeBranchId&&request.projectId==project.id)
        require(request.text==expectedOwner&&request.modelId==expectedModel&&!request.thinking&&request.mode=="act")
        require(request.allowTools&&!request.allowCloudText&&request.capturedPcRevision==expectedRevision&&request.attachments.isEmpty()&&request.goal==null)
        require(request.previousSteps.isNotEmpty()&&request.previousSteps.all{it.tool in allowed&&it.evidence.state==VerificationState.VERIFIED})
        val saves=request.previousSteps.filter{it.tool=="save_note"}
        require(saves.size==1&&saves.single().arguments["title"]?.jsonPrimitive?.content==noteTitle)
        val messages=chat.messages(room.id).first()
        require(messages.single{it.role=="user"}.let{it.id==run.userMessageId&&it.id==request.userMessageId&&chat.fullMessageText(it.id)==expectedOwner})
        require(messages.all{it.role=="user"||it.role=="assistant"&&it.runId==run.id})
        val repository=app.repository.forThread(room.id)
        val access=chat.accessScope(room.id)
        require(access.projectId==project.id&&access.scopeKey=="project:"+project.id&&!access.useOwnerMemory)
        require(app.repository.reminderIdsForProjectRemoval(project.id).isEmpty())
        val workspace=app.workspaceFor(project.id)
        require(!workspace.state.value.external&&workspace.bindingFingerprint()==request.workspaceBinding)
        val root=File(context.filesDir,"projects/"+project.id).canonicalFile
        require(root.parentFile==File(context.filesDir,"projects").canonicalFile&&root.name==project.id)
        val expectedFiles=mapOf("a.png" to png(Color.CYAN),"b.png" to png(Color.CYAN),"c.png" to png(Color.MAGENTA))
        require(root.listFiles()?.map{it.name}?.toSet()==expectedFiles.keys)
        expectedFiles.forEach{(name,bytes)->require(File(root,name).isFile&&File(root,name).readBytes().contentEquals(bytes))}
        val expectedBody="파일 수=3\n중복 그룹 수=1\n중복 경로=a.png, b.png\n추가 사본 바이트="+expectedFiles.getValue("a.png").size
        val notes=repository.notes.first()
        require(notes.size==1)
        val saved=notes.single()
        val collection=saves.single().arguments["scope"]?.jsonPrimitive?.content?:"default"
        require(saved.title==noteTitle&&saved.body==expectedBody&&saved.collection==collection)
        require(saved.updatedAt>=run.createdAt&&saved.updatedAt<=run.updatedAt)
        require(saves.single().arguments["content"]?.jsonPrimitive?.content==expectedBody)
        val report=File(checkNotNull(context.getExternalFilesDir(null)),"diagnostics/team-model-interrupted-recovery.json")
        fun evidence(recovered:Boolean)=buildJsonObject {
            put("versionCode",expectedVersion);put("originalVersionCode",1067);put("kind","storage_report");put("round",1)
            put("scopeBindingVerified",true);put("originalRunStatus",run.status);put("noteCount",notes.size)
            put("exactSingleArtifactObserved",true);put("fixtureFilesByteIdentical",true);put("noteBodySha256",sha(saved.body.toByteArray(Charsets.UTF_8)))
            put("stepTools",JsonArray(request.previousSteps.map{JsonPrimitive(it.tool)}))
            put("originalStrictVerdictChanged",false);put("recoveredOwnedScope",recovered)
        }
        writeReport(report,evidence(false))
        withContext(NonCancellable) {
            quiet();check(chat.queuedRuns().isEmpty());require(chat.project(project.id)==project&&chat.thread(room.id)==room&&chat.run(run.id)==run)
            require(chat.threads.first().filter{it.projectId==project.id}==listOf(room))
            require(chat.branches(room.id)==branches&&chat.runsForThread(room.id)==listOf(run))
            require(chat.messages(room.id).first()==messages)
            require(repository.notes.first()==notes)
            require(root.listFiles()?.map{it.name}?.toSet()==expectedFiles.keys)
            expectedFiles.forEach{(name,bytes)->require(File(root,name).readBytes().contentEquals(bytes))}
            repository.deleteNote(saved.id,expectedUpdatedAt=saved.updatedAt);assertNull(repository.getNote(saved.id))
            chat.deleteProject(project.id,deleteDerivedMemory=true);workspace.discard()
            assertNull(chat.project(project.id));assertNull(chat.thread(room.id));assertFalse(root.exists())
        }
        writeReport(report,evidence(true))
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putInt("exactOwnedStorageFixturesRecovered",1)})
    }
}
