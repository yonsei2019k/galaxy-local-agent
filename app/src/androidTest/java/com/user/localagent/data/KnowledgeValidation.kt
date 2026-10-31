package com.user.localagent.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/** Opt-in isolated SQLCipher databases, entirely synthetic identities/provenance. Never opens the owner DB. */
@RunWith(AndroidJUnit4::class)
class KnowledgeValidation {
    @Test fun encryptedVersionOneMigrationPreservesExistingTablesAndAddsKnowledge()=runBlocking(Dispatchers.IO) {
        fixture {repository,database->
            assertEquals("Legacy note",repository.getNote("qa-note")?.title)
            assertEquals("Synthetic legacy memory",repository.getMemory("qa-memory")?.text)
            assertEquals(1,repository.messageCount())
            assertEquals("qa-scope",repository.listGrants().single().scope)
            assertEquals("pending",repository.getReminder("qa-reminder")?.status)
            assertEquals(1,database.dao().toolAuditMetadata(0,Long.MAX_VALUE,10).size)
            val saved=repository.saveKnowledgeClaim(KnowledgeClaim(predicate="occupation",value="Synthetic tester",status="confirmed",confidence=1.0,sourceKind="owner_transfer",sourceId="qa-seed"))
            assertEquals("confirmed",saved.status)
            assertEquals(saved.id,repository.retrieveKnowledge("Synthetic tester").first().id)
            assertEquals(3,database.openHelper.readableDatabase.version)
            assertNotEquals("SQLite format 3",String(database.openHelper.readableDatabase.path.let {java.io.File(it)}.inputStream().use {input->ByteArray(15).also {input.read(it)}},Charsets.ISO_8859_1))
        }
    }

    @Test fun canonicalOwnerUpdatesDedupeAndRetainEvidenceWhilePhoneInferencesStayUnconfirmed()=runBlocking(Dispatchers.IO) {
        fixture {repository,_->
            val first=repository.saveKnowledgeClaim(KnowledgeClaim(id="qa-owner-job",predicate="occupation",value="QA old role",status="confirmed",confidence=1.0,sourceKind="owner_transfer",sourceId="qa-seed",updatedAt=1000))
            val changed=repository.saveKnowledgeClaim(KnowledgeClaim(predicate="occupation",value="QA corrected role",status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="qa-correction",updatedAt=2000))
            assertEquals(first.id,changed.id)
            assertEquals("QA corrected role",repository.getKnowledge(first.id)?.value)
            assertEquals(1,repository.knowledge.first().count {it.subject=="owner"&&it.predicate=="occupation"&&it.status=="confirmed"})
            assertEquals(2,repository.knowledgeEvidence(first.id).size)
            val observed=repository.saveKnowledgeClaim(KnowledgeClaim(predicate="visit",value="QA location",status="confirmed",confidence=1.0,sourceKind="phone_sms",sourceId="qa-sms"))
            assertEquals("observed",observed.status);assertTrue(observed.confidence<=0.9)
            val inferred=repository.saveKnowledgeClaim(KnowledgeClaim(predicate="unverified_trait",value="QA inferred trait",status="confirmed",confidence=1.0,sourceKind="inference",sourceId="qa-inference"))
            assertEquals("proposed",inferred.status);assertTrue(inferred.confidence<=0.69)
            assertFalse(repository.retrieveKnowledge("QA inferred trait",10).any {it.id==inferred.id})
        }
    }

    @Test fun subjectNeighborsCoreFamilyAndContactFilteringUseBoundedQuotedContext()=runBlocking(Dispatchers.IO) {
        fixture {repository,_->
            repository.saveKnowledgeClaim(KnowledgeClaim(predicate="children_count",value="2",status="confirmed",confidence=1.0,sourceKind="owner_transfer",sourceId="qa-family",tags=listOf("core")))
            repository.saveKnowledgeClaim(KnowledgeClaim(predicate="response_preference",value="Brief Korean replies",status="confirmed",confidence=1.0,sourceKind="owner_transfer",sourceId="qa-preference",tags=listOf("core")))
            repository.saveKnowledgeClaim(KnowledgeClaim(predicate="work_email",value="qa-owner@example.invalid",status="confirmed",confidence=1.0,sourceKind="owner_transfer",sourceId="qa-email",tags=listOf("core","sensitive","email")))
            repository.saveKnowledgeClaim(KnowledgeClaim(predicate="child",value="QZG77",status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="qa-edge",objectSubject="person.node-k"))
            val neighbor=repository.saveKnowledgeClaim(KnowledgeClaim(subject="person.node-k",predicate="school",value="Campus-Z9",status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="qa-neighbor"))
            assertTrue(repository.retrieveKnowledge("QZG77",5).any {it.id==neighbor.id})
            val context=repository.ownerProfileContext("오늘 할 일을 정리해줘")
            assertTrue(context.length<=2800);assertTrue(context.contains("children_count"));assertTrue(context.contains("Brief Korean replies"))
            assertFalse(context.contains("qa-owner@example.invalid"));assertFalse(context.contains("sourceExcerpt"))
            assertFalse(repository.ownerProfileContext("숙소 주소 알려줘").contains("qa-owner@example.invalid"))
            assertTrue(repository.ownerProfileContext("내 업무 이메일 계정").contains("qa-owner@example.invalid"))
            assertTrue(context.contains("\"untrustedData\":true"))
        }
    }

    @Test fun deletionTombstonesAndEpochPreventReingestionAndMemoryEditsStaySynchronized()=runBlocking(Dispatchers.IO) {
        fixture {repository,_->
            val epoch=repository.knowledgeEpoch()
            val original=KnowledgeClaim(predicate="preference",value="QA forget me",status="confirmed",confidence=1.0,sourceKind="user_message",sourceId="qa-source")
            val saved=repository.saveKnowledgeClaim(original,expectedEpoch=epoch)
            repository.deleteKnowledge(saved.id)
            assertNull(repository.getKnowledge(saved.id))
            assertFalse(repository.knowledgeSourceAllowed("user_message","qa-source",Long.MAX_VALUE))
            assertTrue(repository.retrieveKnowledge("QA forget me").none {it.id==saved.id})
            assertTrue(runCatching {repository.saveKnowledgeClaim(original.copy(sourceId="qa-new-source"),expectedEpoch=epoch)}.isFailure)
            assertTrue(runCatching {repository.saveKnowledgeClaim(original,expectedEpoch=repository.knowledgeEpoch())}.isFailure)
            val message=repository.addMessage("user","Synthetic linked source")
            val memory=repository.saveMemory("QA first memory","fact",message)
            val linkedId="memory.${memory.id}"
            assertEquals("QA first memory",repository.getKnowledge(linkedId)?.value)
            repository.editMemory(memory.id,"QA corrected memory","correction")
            assertEquals("QA corrected memory",repository.getKnowledge(linkedId)?.value)
            repository.forgetMemory(memory.id)
            assertNull(repository.getKnowledge(linkedId));assertNull(repository.getMemory(memory.id))
            assertFalse(repository.knowledgeSourceAllowed("user_message",message))
        }
    }

    @Test fun credentialAssignmentsAreOmittedButNonCredentialPolicyTextRemains()=runBlocking(Dispatchers.IO) {
        fixture {repository,_->
            repository.saveKnowledgeClaim(KnowledgeClaim(predicate="personal_fact.qa_one",value="비밀번호: QA_SYNTHETIC_PASSWORD_829",status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="qa-secret-password",tags=listOf("core")))
            repository.saveKnowledgeClaim(KnowledgeClaim(predicate="personal_fact.qa_two",value="API key=QA_SYNTHETIC_APIKEY_391",status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="qa-secret-key",tags=listOf("core")))
            repository.saveKnowledgeClaim(KnowledgeClaim(predicate="personal_fact.qa_policy",value="비밀번호를 요구하지 않음",status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="qa-policy",tags=listOf("core")))
            for(query in listOf("오늘 할 일","비밀번호와 API key 계정")){
                val context=repository.ownerProfileContext(query)
                assertFalse(context.contains("QA_SYNTHETIC_PASSWORD_829"));assertFalse(context.contains("QA_SYNTHETIC_APIKEY_391"))
                assertTrue(context.contains("비밀번호를 요구하지 않음"))
            }
        }
    }

    @Test fun deletingOldEvidencePreservesCurrentIndependentOwnerClaim()=runBlocking(Dispatchers.IO) {
        fixture {repository,_->
            val messageId=repository.addMessage("user","Synthetic earlier role")
            val earlier=repository.saveKnowledgeClaim(KnowledgeClaim(predicate="occupation",value="QA earlier role",status="confirmed",confidence=1.0,sourceKind="user_message",sourceId=messageId,updatedAt=1000))
            val current=repository.saveKnowledgeClaim(KnowledgeClaim(predicate="occupation",value="QA owner transferred role",status="confirmed",confidence=1.0,sourceKind="owner_transfer",sourceId="qa-independent-owner",updatedAt=2000))
            assertEquals(earlier.id,current.id)
            repository.deleteMessage(messageId)
            assertEquals("QA owner transferred role",repository.getKnowledge(current.id)?.value)
            assertEquals("owner_transfer",repository.getKnowledge(current.id)?.sourceKind)
            assertTrue(repository.knowledgeEvidence(current.id).none {it.sourceId==messageId})
            assertTrue(repository.knowledgeEvidence(current.id).any {it.sourceId=="qa-independent-owner"})
            assertFalse(repository.knowledgeSourceAllowed("user_message",messageId))
            assertTrue(repository.knowledgeSourceAllowed("owner_transfer","qa-independent-owner"))
            repository.deleteKnowledge(current.id)
            assertNull(repository.getKnowledge(current.id))
            assertFalse(repository.knowledgeSourceAllowed("owner_transfer","qa-independent-owner"))
        }
    }

    @Test fun genericPromptKeepsFamilyAggregatesBeforeNewerBirthdayDetails()=runBlocking(Dispatchers.IO) {
        fixture {repository,_->
            for((predicate,value)in listOf("family.size" to "4","children_count" to "2","family.children" to "Two synthetic children")){
                repository.saveKnowledgeClaim(KnowledgeClaim(predicate=predicate,value=value,status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="qa-aggregate-$predicate",updatedAt=1000,tags=listOf("core")))
            }
            repeat(7){index->repository.saveKnowledgeClaim(KnowledgeClaim(predicate="family.child_${index}_birthday",value="Synthetic detail $index",status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="qa-birthday-$index",updatedAt=2000L+index,tags=listOf("core")))}
            repository.saveKnowledgeClaim(KnowledgeClaim(predicate="response_preference",value="Brief replies",status="confirmed",confidence=1.0,sourceKind="owner_approved",sourceId="qa-response",tags=listOf("core")))
            val context=repository.ownerProfileContext("오늘 할 일을 정리해줘")
            assertTrue(context.contains("family.size"));assertTrue(context.contains("children_count"));assertTrue(context.contains("Two synthetic children"))
            assertTrue(context.contains("Brief replies"));assertTrue(context.length<=2800)
        }
    }

    private suspend fun fixture(block:suspend(LocalRepository,LocalDatabase)->Unit) {
        assumeTrue("Explicit knowledgeValidationApproved=true is required",InstrumentationRegistry.getArguments().getString("knowledgeValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="knowledge-qa-${UUID.randomUUID()}.db";val key=ByteArray(32).also {SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        var database:LocalDatabase?=null
        try {
            val helper=SupportOpenHelperFactory(key.clone()).create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(1){
                override fun onCreate(db:SupportSQLiteDatabase){
                    LEGACY_TABLES.forEach(db::execSQL)
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES (42,'bd8f267ee8fa8a914bd477a5ee362e85')")
                    db.execSQL("INSERT INTO notes VALUES ('qa-note','Legacy note','Synthetic legacy note','default',1)")
                    db.execSQL("INSERT INTO memories VALUES ('qa-memory','Synthetic legacy memory','fact',NULL,1)")
                    db.execSQL("INSERT INTO messages VALUES ('qa-message','user','Synthetic legacy conversation',1)")
                    db.execSQL("INSERT INTO reminders VALUES ('qa-reminder','Synthetic reminder',2000000000000,'qa','pending',1)")
                    db.execSQL("INSERT INTO grants VALUES ('qa-extension','qa-hash','qa-tool','qa-scope')")
                    db.execSQL("INSERT INTO tool_runs VALUES ('qa-run','qa-tool',1,'Synthetic metadata',1)")
                    db.execSQL("INSERT INTO summaries VALUES ('qa-summary','Synthetic summary','qa-message')")
                }
                override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Only Room performs this test migration")
            }).build())
            helper.writableDatabase;helper.close()
            val opened=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
                .addMigrations(KnowledgeMigrations.MIGRATION_1_2,ChatMigrations.MIGRATION_2_3).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
            database=opened;opened.openHelper.writableDatabase
            block(LocalRepository(opened),opened)
        }finally {database?.close();key.fill(0);check(name.startsWith("knowledge-qa-"));context.deleteDatabase(name)}
    }
    companion object {
        private val LEGACY_TABLES=listOf(
            "CREATE TABLE notes (id TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,collection TEXT NOT NULL,updatedAt INTEGER NOT NULL,PRIMARY KEY(id))",
            "CREATE TABLE memories (id TEXT NOT NULL,text TEXT NOT NULL,kind TEXT NOT NULL,sourceMessageId TEXT,updatedAt INTEGER NOT NULL,PRIMARY KEY(id))",
            "CREATE TABLE reminders (id TEXT NOT NULL,title TEXT NOT NULL,atEpochMillis INTEGER NOT NULL,reminder_group TEXT NOT NULL,status TEXT NOT NULL,updatedAt INTEGER NOT NULL,PRIMARY KEY(id))",
            "CREATE TABLE messages (id TEXT NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL,createdAt INTEGER NOT NULL,PRIMARY KEY(id))",
            "CREATE TABLE tool_runs (id TEXT NOT NULL,name TEXT NOT NULL,success INTEGER NOT NULL,summary TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(id))",
            "CREATE TABLE grants (extensionId TEXT NOT NULL,codeHash TEXT NOT NULL,toolName TEXT NOT NULL,scope TEXT NOT NULL,PRIMARY KEY(extensionId,codeHash,toolName,scope))",
            "CREATE TABLE summaries (id TEXT NOT NULL,text TEXT NOT NULL,sourceMessageIds TEXT NOT NULL,PRIMARY KEY(id))")
    }
}
