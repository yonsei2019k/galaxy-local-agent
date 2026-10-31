package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.user.localagent.analysis.*
import com.user.localagent.benchmark.RuntimeGate
import com.user.localagent.core.*
import com.user.localagent.imports.ConversationImport
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.models.ChosenInference
import com.user.localagent.data.LocalDatabase
import com.user.localagent.data.LocalRepository
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real phone model and scoped DB; public synthetic text only. No live SMS/accounts/recordings. */
@RunWith(AndroidJUnit4::class)
class PrivateAnalysisSemanticValidation {
    @Test fun realModelReadsCorrectionAcrossBatchesAndRetainsAllExactEvidence()=runBlocking<Unit>(Dispatchers.Default) {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("privateAnalysisSemanticApproved=true required",arguments.getString("privateAnalysisSemanticApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        check(!app.runs.busy.value&&app.runs.queued.value.isEmpty()){"Wait for the owner's current task"}
        val ownsBenchmark=RuntimeGate.benchmark.compareAndSet(false,true);check(ownsBenchmark)
        var ownsInference=false;var threadId:String?=null
        val databaseName="private-analysis-semantic-qa-${UUID.randomUUID()}.db"
        val databaseKey=ByteArray(32).also {java.security.SecureRandom().nextBytes(it)}
        var database:LocalDatabase?=null;var testRepository:LocalRepository?=null
        val ownedNotes=mutableMapOf<String,MutableSet<String>>()
        fun ownNote(id:String,body:String){ownedNotes.getOrPut(id){mutableSetOf()}+=java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}}
        val artifacts=mutableSetOf<File>();val reviews=mutableListOf<JsonObject>()
        val reportDirectory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").apply {mkdirs()}
        val report=File(reportDirectory,"private-analysis-semantic.json")
        var failureClass:String?=null
        try {
            ownsInference=RuntimeGate.inference.compareAndSet(false,true);check(ownsInference)
            app.cancelIdleUnload();app.updates.setBusy(true);app.models.refresh()
            val requestedModel=arguments.getString("privateAnalysisModelId")?:"gemma-e4b"
            val model=app.models.models.value.single {it.id==requestedModel&&it.verified&&it.runtime in setOf("gguf","litertlm")}
            val chosen=ChosenInference(model,InferenceOptions(contextSize=4096,backend="CPU",thinking=false,maxOutputTokens=1024,deviceOnly=true),"합성 개인자료 실기 검사")
            System.loadLibrary("sqlcipher")
            database=Room.databaseBuilder(context,LocalDatabase::class.java,databaseName).openHelperFactory(SupportOpenHelperFactory(databaseKey.clone())).build()
            val isolated=LocalRepository(checkNotNull(database));testRepository=isolated
            val thread=isolated.chat.createThread(title="QA private analysis ${UUID.randomUUID()}");threadId=thread.id
            val repository=isolated.forThread(thread.id)
            val scope=isolated.chat.accessScope(thread.id)
            val importer=ConversationImport(context,repository,repository::discardIncompleteConversationNotes)
            val fixture=buildString {
                appendLine("공개 합성 대화. 실제 연락처·업무·개인 기록이 아닙니다.")
                appendLine("2026-10-30 09:00 합성A: 시사회는 2026-11-05 09:00에 하자는 제안입니다. 아직 확정하지 않았습니다.")
                repeat(110){appendLine("공개 합성 배경 $it: 복도 조명 상태를 관찰했습니다. 시사회 일정과 관계없는 기록입니다.")}
                appendLine("2026-10-31 10:00 합성B: 2026-11-05 09:00 제안은 취소합니다. 최종 확정 시사회는 2026-11-06 11:00입니다.")
                appendLine("2026-10-31 10:05 합성A: 합성C가 최종 일정으로 초대장을 고쳐야 합니다. 초대장 수정 완료 여부는 아직 모릅니다.")
            }
            val imported=importer.importText(fixture)
            imported.noteIds.forEach {id->repository.getNote(id)?.let {ownNote(id,it.body)}}
            val sources=LocalPrivateAnalysisSources(context,repository,scope.scopeKey,cleanupIncomplete=repository::discardIncompletePrivateSourceNotes)
            val journal=RepositoryAnalysisJournal(repository,scope.scopeKey,AnalysisCheckpointCommit {rootId,expected,rootBody,stepId,stepBody->
                ownNote(rootId,rootBody);if(stepId!=null&&stepBody!=null)ownNote(stepId,stepBody)
                repository.commitPrivateAnalysisCheckpoint(rootId,expected,rootBody,stepId,stepBody)
            })
            val executor=PrivateAnalysisExecutor(context,app,repository,scope.scopeKey,sources,journal,{chosen})
            val questions=listOf(
                "시사회에 관해 오간 말을 끝까지 살펴서 기존 제안과 최종 확정 일시, 누가 아직 무엇을 해야 하는지 근거를 붙여 정리해줘.",
                "처음 얘기한 날짜로 초대장을 보내면 곤란할까? 변경된 약속과 아직 끝났다고 볼 수 없는 일을 원문에서 찾아줘.",
                "조명 얘기는 빼고, 시사회 약속이 뒤에서 어떻게 바뀌었는지와 후속 조치의 상태를 같이 확인해줘.")
            val selected=if(arguments.getString("privateAnalysisSemanticVariants")=="all")questions else questions.take(1)
            for((questionIndex,question) in selected.withIndex())withTimeout(30*60_000L) {
                var analysisId:String?=null;var version=0;var last:ToolResult?=null
                var batch=0
                while(batch++<16) {
                    val args=buildJsonObject {
                        if(analysisId==null){put("question",question);put("contentSha256",imported.contentSha256.uppercase())}
                        else {put("analysisId",analysisId);put("expectedProcessedChunks",version)}
                        put("maxChunks",1)
                    }
                    val result=executor.execute(ToolCall(UUID.randomUUID().toString(),"analyze_conversation_import",args))
                    assertTrue(result.success)
                    val payload=Json.parseToJsonElement(result.content).jsonObject
                    analysisId=payload.getValue("analysisId").jsonPrimitive.content
                    val checkpoint=checkNotNull(journal.load(analysisId!!));version=checkpoint.processed.size
                    payload["artifact"]?.jsonObject?.get("fileName")?.jsonPrimitive?.content?.let {name->artifacts+=File(context.filesDir,"exports/$name")}
                    last=result
                    if(payload.getValue("analysisCompleteWithinSelectedText").jsonPrimitive.boolean)break
                    assertEquals(VerificationState.UNVERIFIED,result.verification)
                    assertTrue(payload.getValue("canResumeStoredText").jsonPrimitive.boolean)
                }
                val payload=Json.parseToJsonElement(checkNotNull(last).content).jsonObject
                val checkpoint=checkNotNull(journal.load(checkNotNull(analysisId)))
                val findings=checkpoint.processed.flatMap {it.findings}
                val quotations=findings.joinToString("\n"){it.citation.quote}
                val recall=mapOf("initial_proposal" to quotations.contains("2026-11-05 09:00"),
                    "final_time" to quotations.contains("2026-11-06 11:00"),"withdrawal" to quotations.contains("취소"),
                    "unfinished_followup" to quotations.contains("초대장"))
                reviews+=buildJsonObject {
                    put("syntheticQuestion",question);put("variant",questionIndex+1);put("modelId",model.id)
                    put("modelSha256",model.sha256);put("sourceBytes",fixture.toByteArray(Charsets.UTF_8).size)
                    put("processedChunks",version);put("batches",batch);put("coverage",payload.getValue("coverage"))
                    put("requiredFactQuotationRecall",JsonObject(recall.mapValues {JsonPrimitive(it.value)}))
                    put("findings",buildJsonArray {findings.forEach {finding->add(buildJsonObject {put("finding",finding.text);put("quote",finding.citation.quote);put("evidenceId",finding.id)})}})
                    put("interpretationQualityNeedsHumanReview",true);put("chatIntentRoutingTested",false)
                }
                report.writeText(JsonObject(mapOf("syntheticOnly" to JsonPrimitive(true),"reviews" to JsonArray(reviews))).toString())
                assertEquals(VerificationState.VERIFIED,last!!.verification)
                assertTrue(payload.getValue("analysisCompleteWithinSelectedText").jsonPrimitive.boolean)
                assertTrue("Required synthetic evidence missed; inspect the synthetic review artifact",recall.values.all {it})
                assertTrue(version>1)
            }
        }catch(error:Throwable){failureClass=error.javaClass.simpleName;throw error}
        finally {
            withContext(NonCancellable) {
                try {
                    // The isolated DB is never observed by the owner's automatic memory worker.
                    testRepository?.let {isolated->
                        ownedNotes.forEach {(id,versions)->isolated.getNote(id)?.let {note->
                            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(note.body.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
                            if(hash in versions)isolated.deleteNote(id)
                        }}
                        threadId?.let {isolated.chat.deleteThread(it,deleteDerivedMemory=true)}
                    }
                    artifacts.forEach {file->if(file.canonicalFile.parentFile==File(context.filesDir,"exports").canonicalFile)file.delete()}
                }finally {
                    try {if(ownsInference)app.engine.unload()}
                    finally {
                        if(ownsInference){RuntimeGate.inference.set(false);app.updates.setBusy(false);app.scheduleIdleUnload()}
                        if(ownsBenchmark)RuntimeGate.benchmark.set(false)
                        database?.close();databaseKey.fill(0)
                        check(databaseName.startsWith("private-analysis-semantic-qa-"));context.deleteDatabase(databaseName)
                        report.writeText(buildJsonObject {put("syntheticOnly",true);put("ownerDatabaseUsedForSyntheticData",false);put("failureClass",failureClass);put("reviews",JsonArray(reviews));put("interpretationQualityNeedsHumanReview",true);put("chatIntentRoutingTested",false)}.toString())
                    }
                }
            }
        }
    }
}
