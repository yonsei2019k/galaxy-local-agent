package com.user.localagent.analysis

import com.user.localagent.core.*
import com.user.localagent.data.NoteRecord
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/** Real journal, synthetic atomic storage. Actual SQLCipher callbacks require instrumentation. */
class RepositoryAnalysisJournalTest {
    private val scope="synthetic-journal-scope"
    private class Backend(private val scope:String) {
        val rows=linkedMapOf<String,NoteRecord>();val bindings=mutableMapOf<String,String>();val expectedHashes=mutableListOf<String?>()
        var beforeCommit:(()->Unit)?=null;var accessible=true
        private val mutex=Mutex()
        suspend fun read(id:String):NoteRecord? {
            check(accessible){"synthetic revoked scope"}
            if(id in rows)check(bindings[id]==scope){"synthetic different source binding"}
            return rows[id]
        }
        val commit=AnalysisCheckpointCommit {rootId,expected,rootBody,stepId,stepBody->mutex.withLock {
            check(accessible);beforeCommit?.also {beforeCommit=null;it()}
            val old=rows[rootId]
            if(old==null)require(expected==null)else require(bindings[rootId]==scope&&old.collection==RepositoryAnalysisJournal.COLLECTION&&expected==sha(old.body))
            require(rootBody.length in 1..12000&&(stepId==null)==(stepBody==null))
            val root=Json.parseToJsonElement(rootBody).jsonObject
            require(root.getValue("id").jsonPrimitive.content==rootId&&root.getValue("scopeId").jsonPrimitive.content==scope)
            if(stepId!=null){
                require(stepId !in rows&&stepBody!!.length in 1..12000)
                val step=Json.parseToJsonElement(stepBody).jsonObject
                require(step.getValue("analysisId").jsonPrimitive.content==rootId&&step.getValue("scopeId").jsonPrimitive.content==scope)
                require(root.getValue("lastId").jsonPrimitive.content==stepId&&root.getValue("lastSha256").jsonPrimitive.content==sha(stepBody))
            }
            // No mutation above this line: the fake has the callback's atomic all-or-nothing contract.
            expectedHashes+=expected
            if(stepId!=null){rows[stepId]=NoteRecord(stepId,"synthetic step",stepBody!!,RepositoryAnalysisJournal.COLLECTION,1);bindings[stepId]=scope}
            rows[rootId]=NoteRecord(rootId,"synthetic root",rootBody,RepositoryAnalysisJournal.COLLECTION,1);bindings[rootId]=scope
        }}
        fun journal()=RepositoryAnalysisJournal(::read,scope,commit)
        fun remove(id:String){rows.remove(id);bindings.remove(id)}
        fun rootJson(id:String)=Json.parseToJsonElement(rows.getValue(id).body).jsonObject
        fun changeRoot(id:String,change:(JsonObject)->JsonObject){rows[id]=rows.getValue(id).copy(body=change(rootJson(id)).toString())}
    }
    private fun source(text:String="x".repeat(9000))=PrivateAnalysisSource.create(scope,"synthetic-source",PrivateEvidenceKind.SMS,text,PrivateTextCompleteness.COMPLETE_SELECTED_TEXT)
    private fun checkpoint(source:PrivateAnalysisSource=source())=PrivateAnalysisCheckpoint(UUID.randomUUID().toString(),source.ref,
        buildJsonObject {put("kind","sms");put("snapshotId",UUID.randomUUID().toString())},"합성 질문","a".repeat(64),"b".repeat(64),processed=emptyList(),nextByte=0,sourceExhausted=false)
    private fun append(source:PrivateAnalysisSource,checkpoint:PrivateAnalysisCheckpoint):PrivateAnalysisCheckpoint {
        val cursor=checkpoint.nextByte?.takeIf {it>0}?.let {PrivateChunkCursor(source.ref,4096,256,it)}
        val page=PrivateAnalysisEvidence.page(source,4096,256,1,cursor)
        val chunk=page.chunks.single()
        val step=AnalysisChunkResult(chunk.id,chunk.startByte,chunk.endByte,emptyList(),listOf("synthetic limitation"))
        return checkpoint.copy(processed=checkpoint.processed+step,nextByte=page.next?.nextByte,sourceExhausted=page.sourceExhausted)
    }

    @Test fun appendAndLoadPreserveExactHistoryPointersAndResumeStateWithoutSourceTextCopies()=runBlocking {
        val backend=Backend(scope);val journal=backend.journal();val source=source();val first=checkpoint(source)
        journal.save(first);val originalRoot=backend.rows.getValue(first.id).body
        val one=append(source,first);journal.save(one)
        assertEquals(sha(originalRoot),backend.expectedHashes[1])
        val two=append(source,one);journal.save(two)
        assertEquals(two,backend.journal().load(first.id))
        assertEquals(3,backend.rows.size)
        assertTrue(backend.rows.values.all {it.collection==RepositoryAnalysisJournal.COLLECTION&&it.body.length<=12000})
        assertTrue(backend.rows.values.none {it.body.contains("x".repeat(500))})
    }
    @Test fun concurrentChangeBetweenReadAndCommitFailsCasWithoutWritingAnOrphanStep()=runBlocking {
        val backend=Backend(scope);val journal=backend.journal();val source=source();val first=checkpoint(source)
        journal.save(first)
        backend.beforeCommit={backend.changeRoot(first.id){JsonObject(it+("question" to JsonPrimitive("concurrent changed question")))}}
        assertTrue(runCatching {journal.save(append(source,first))}.isFailure)
        assertEquals(1,backend.rows.size);assertEquals(0,backend.rootJson(first.id).getValue("stepCount").jsonPrimitive.int)
    }
    @Test fun staleLoadedHeadIsNotSilentlyRebasedWhenConcurrentWriterOnlyChangesStatus()=runBlocking {
        val backend=Backend(scope);val source=source("");val initial=checkpoint(source)
        val firstWriter=backend.journal();firstWriter.save(initial)
        val staleWriter=backend.journal();assertEquals(initial,staleWriter.load(initial.id))
        firstWriter.save(initial.copy(nextByte=null,sourceExhausted=true))
        assertTrue("stale head must require explicit reload",runCatching {staleWriter.save(initial)}.isFailure)
        assertTrue(backend.rootJson(initial.id).getValue("sourceExhausted").jsonPrimitive.boolean)
    }
    @Test fun deletedEmptyCheckpointCannotBeResurrectedByItsExistingWriter()=runBlocking {
        val backend=Backend(scope);val source=source("");val initial=checkpoint(source);val journal=backend.journal()
        journal.save(initial);backend.remove(initial.id)
        assertTrue("deleted checkpoint must remain absent",runCatching {journal.save(initial.copy(nextByte=null,sourceExhausted=true))}.isFailure)
        assertFalse(initial.id in backend.rows)
    }
    @Test fun switchingCachedAnalysisIdsDoesNotForgetADeletedPreviouslyKnownHead()=runBlocking {
        val backend=Backend(scope);val journal=backend.journal();val source=source("")
        val first=checkpoint(source);val second=checkpoint(source)
        journal.save(first);journal.save(second);backend.remove(first.id)
        assertTrue(runCatching {journal.save(first.copy(nextByte=null,sourceExhausted=true))}.isFailure)
        assertFalse(first.id in backend.rows);assertEquals(second,backend.journal().load(second.id))
    }
    @Test fun nonAppendHistoryRewriteAndSkippedHistoryAppendAreRejected()=runBlocking {
        val backend=Backend(scope);val source=source();val initial=checkpoint(source);val journal=backend.journal()
        journal.save(initial);val one=append(source,initial);journal.save(one)
        val rewritten=one.copy(processed=one.processed.map {it.copy(limitations=listOf("rewritten old history"))})
        assertTrue(runCatching {journal.save(rewritten)}.isFailure)
        assertTrue(runCatching {journal.save(initial)}.isFailure)
        val two=append(source,one);val three=append(source,two)
        assertTrue(runCatching {journal.save(three)}.isFailure)
        assertEquals(one,backend.journal().load(initial.id))
    }
    @Test fun changedSourceQuestionLocatorModelOrScopeCannotReuseOldCheckpoint()=runBlocking {
        val backend=Backend(scope);val initial=checkpoint();val journal=backend.journal();journal.save(initial)
        val variants=listOf(
            initial.copy(source=source("changed body").ref),
            initial.copy(sourceLocator=buildJsonObject {put("kind","sms");put("snapshotId","different")}),
            initial.copy(question="different question"),initial.copy(modelSha256="c".repeat(64)),initial.copy(skillSha256="d".repeat(64)),
            initial.copy(overlapBytes=128),initial.copy(source=initial.source.copy(scopeId="other-scope"))
        )
        variants.forEach {assertTrue(runCatching {journal.save(it)}.isFailure)}
        assertEquals(initial,backend.journal().load(initial.id))
    }
    @Test fun missingOrModifiedStepChainDoesNotReturnPartialHistoryAsComplete()=runBlocking {
        for(delete in listOf(false,true)){
            val backend=Backend(scope);val source=source();val initial=checkpoint(source);val journal=backend.journal()
            journal.save(initial);journal.save(append(source,initial))
            val last=backend.rootJson(initial.id).getValue("lastId").jsonPrimitive.content
            if(delete)backend.remove(last)else backend.rows[last]=backend.rows.getValue(last).copy(body=backend.rows.getValue(last).body+" ")
            assertTrue(runCatching {backend.journal().load(initial.id)}.isFailure)
        }
    }
    @Test fun wrongProtectedCollectionOrChangedScopeBindingIsRejectedOnRead()=runBlocking {
        val backend=Backend(scope);val initial=checkpoint();backend.journal().save(initial)
        val root=backend.rows.getValue(initial.id)
        backend.rows[initial.id]=root.copy(collection="ordinary-notes")
        assertTrue(runCatching {backend.journal().load(initial.id)}.isFailure)
        backend.rows[initial.id]=root;backend.bindings[initial.id]="other-scope"
        assertTrue(runCatching {backend.journal().load(initial.id)}.isFailure)
        backend.bindings[initial.id]=scope;backend.accessible=false
        assertTrue(runCatching {backend.journal().load(initial.id)}.isFailure)
    }
    @Test fun forgedStepScopeFailsEvenWhenRootHeadHashIsUpdated()=runBlocking {
        val backend=Backend(scope);val source=source();val initial=checkpoint(source);val journal=backend.journal()
        journal.save(initial);journal.save(append(source,initial))
        val head=backend.rootJson(initial.id).getValue("lastId").jsonPrimitive.content
        val old=backend.rows.getValue(head)
        val wrongScope=JsonObject(Json.parseToJsonElement(old.body).jsonObject+("scopeId" to JsonPrimitive("other-scope"))).toString()
        backend.rows[head]=old.copy(body=wrongScope)
        backend.changeRoot(initial.id){JsonObject(it+("lastSha256" to JsonPrimitive(sha(wrongScope))))}
        assertTrue(runCatching {backend.journal().load(initial.id)}.isFailure)
    }
    @Test fun oversizedRootOrStepBodiesAreRejectedBeforeStorageCommit()=runBlocking {
        val backend=Backend(scope);val initial=checkpoint();val journal=backend.journal()
        assertTrue(runCatching {journal.save(initial.copy(question="q".repeat(12001)))}.isFailure)
        assertTrue(backend.rows.isEmpty())
        journal.save(initial)
        val source=source();val one=append(source,initial)
        val oversized=one.copy(processed=one.processed.map {it.copy(limitations=listOf("q".repeat(12001)))})
        assertTrue(runCatching {journal.save(oversized)}.isFailure)
        assertEquals(1,backend.rows.size)
    }
    @Test fun storedQuoteHashMismatchIsRejectedBeforeResumeEvenWithARehashedRootHead()=runBlocking {
        val backend=Backend(scope);val source=source("synthetic unique quote")
        val workflow=PrivateRecordAnalysisWorkflow(PrivateAnalysisDecider {_,_,_->"""{"findings":[{"text":"synthetic finding","quote":"synthetic unique quote"}],"limitations":[]}"""},backend.journal())
        val result=workflow.run(source,buildJsonObject {put("kind","sms");put("snapshotId",UUID.randomUUID().toString())},"synthetic question","a".repeat(64),"b".repeat(64))
        val id=result.checkpoint.id;val head=backend.rootJson(id).getValue("lastId").jsonPrimitive.content
        val note=backend.rows.getValue(head)
        val entry=Json.parseToJsonElement(note.body).jsonObject
        val savedResult=entry.getValue("result").jsonObject
        val finding=savedResult.getValue("findings").jsonArray.single().jsonObject
        val badFinding=JsonObject(finding+("quoteSha256" to JsonPrimitive("0".repeat(64))))
        val badResult=JsonObject(savedResult+("findings" to JsonArray(listOf(badFinding))))
        val body=JsonObject(entry+("result" to badResult)).toString()
        backend.rows[head]=note.copy(body=body);backend.changeRoot(id){JsonObject(it+("lastSha256" to JsonPrimitive(sha(body))))}
        assertTrue(runCatching {backend.journal().load(id)}.isFailure)
    }
    companion object {
        private fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}
