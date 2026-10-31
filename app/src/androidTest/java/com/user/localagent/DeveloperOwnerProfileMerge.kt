package com.user.localagent

import android.os.Bundle
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.data.KnowledgeClaim
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.LocalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Explicit owner fixture only. No profile facts, query strings or model calls are compiled in. */
@RunWith(AndroidJUnit4::class)
class DeveloperOwnerProfileMerge {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val repository get()=(context.applicationContext as LocalAgentApplication).container.repository
    private fun authorized(){
        assumeTrue("ownerProfileMergeApproved=true required",InstrumentationRegistry.getArguments().getString("ownerProfileMergeApproved")=="true")
    }
    private fun expectedHash():String=checkNotNull(InstrumentationRegistry.getArguments().getString("profileSourceSha256")).also{require(it.matches(Regex("[a-f0-9]{64}")))}
    private fun rawId(hash:String)="owner.profile.raw.${hash.take(24)}"
    private fun receiptId(hash:String)="owner.profile.receipt.${hash.take(24)}"

    @Test fun mergeOwnerApprovedProfileWithoutInference()=runBlocking(Dispatchers.IO){
        authorized()
        val expected=expectedHash()
        val fixture=File(context.getExternalFilesDir(null),"diagnostics/owner-profile-merge.json")
        var saved=0;var reused=0;var skippedDeleted=0;var contextsChecked=0;var failure:String?=null
        try{
            require(fixture.isFile&&fixture.length() in 1..256_000)
            val input=Json.parseToJsonElement(fixture.readText(Charsets.UTF_8)).jsonObject
            require(input.getValue("version").jsonPrimitive.int==1&&input.getValue("approvedByOwner").jsonPrimitive.boolean)
            val raw=input.getValue("rawText").jsonPrimitive.content
            require(raw.isNotBlank()&&raw.length<=12000&&'\u0000' !in raw)
            check(profileDigest(raw)==expected&&input.getValue("sourceSha256").jsonPrimitive.content==expected)
            check(raw.length==input.getValue("sourceChars").jsonPrimitive.int)
            check(raw.toByteArray(Charsets.UTF_8).size==input.getValue("sourceUtf8Bytes").jsonPrimitive.int)
            val sourceId=rawId(expected)
            // A forgotten source is not re-imported simply because its plaintext fixture was pushed again.
            check(repository.knowledgeSourceAllowed("note",sourceId)&&repository.knowledgeSourceAllowed("owner_transfer",sourceId)){"Profile source was previously forgotten"}
            val epoch=repository.knowledgeEpoch()
            val oldReceipt=repository.getNote(receiptId(expected))?.let{Json.parseToJsonElement(it.body).jsonObject}
            val priorClaimIds=oldReceipt?.get("claims")?.jsonArray.orEmpty().map{it.jsonObject.getValue("id").jsonPrimitive.content}.toSet()
            val existing=repository.getNote(sourceId)
            if(existing==null)repository.saveNote(input.getValue("title").jsonPrimitive.content,raw,"owner-profile",sourceId)
            else check(existing.collection=="owner-profile"&&profileDigest(existing.body)==expected){"Existing profile note differs"}
            check(repository.getNote(sourceId)?.body==raw){"Full original profile read-back differs"}
            val facts=input.getValue("facts").jsonArray
            require(facts.size in 1..64)
            val keys=mutableSetOf<String>()
            val ledger=mutableListOf<JsonObject>()
            val importedPredicates=mutableSetOf<String>()
            for(value in facts){
                val fact=value.jsonObject
                val key=fact.getValue("key").jsonPrimitive.content
                require(key.matches(Regex("[a-z0-9_.-]{1,80}"))&&keys.add(key))
                val predicate=fact.getValue("predicate").jsonPrimitive.content
                val text=fact.getValue("value").jsonPrimitive.content
                val status=fact.getValue("status").jsonPrimitive.content
                val confidence=fact.getValue("confidence").jsonPrimitive.double
                require(status in setOf("confirmed","observed")&&confidence in 0.0..1.0&&text.isNotBlank()&&text.length<=4000)
                val excerpt=fact["sourceExcerpt"]?.jsonPrimitive?.content
                require(excerpt==null||excerpt.length<=1000&&raw.contains(excerpt))
                val id="profile.${expected.take(16)}.$key"
                if(!profileSourcesAllowed(repository,sourceId,fact["priorSources"]?.jsonArray?:JsonArray(emptyList()))){skippedDeleted++;continue}
                // The graph merges a confirmed subject/predicate into its canonical ID and
                // preserves source evidence. Observations retain their lower-confidence status.
                val claim=repository.saveKnowledgeClaim(KnowledgeClaim(
                    id=id,subject="owner",predicate=predicate,value=text,status=status,confidence=confidence,
                    sourceKind="owner_transfer",sourceId=sourceId,sourceExcerpt=excerpt,
                    tags=fact["tags"]?.jsonArray?.map{it.jsonPrimitive.content}.orEmpty()),expectedEpoch=epoch)
                val stored=checkNotNull(repository.getKnowledge(claim.id))
                check(stored.value==text.trim()&&stored.status==status&&stored.sourceId==sourceId){"Profile fact read-back differs"}
                if(claim.id in priorClaimIds)reused++ else saved++
                ledger+=buildJsonObject{put("id",claim.id);put("digest",profileDigest(stored.value));put("status",stored.status)}
                importedPredicates+=predicate
            }
            // These queries are private fixture data, not executable instructions. Verify that
            // normal related-memory context can retrieve the imported domains without an LLM.
            for(item in input["contextChecks"]?.jsonArray.orEmpty()){
                val spec=item.jsonObject
                val query=spec.getValue("query").jsonPrimitive.content
                val expectedPredicates=spec.getValue("predicatesAny").jsonArray.map{it.jsonPrimitive.content}.filter{it in importedPredicates}.toSet()
                if(expectedPredicates.isEmpty())continue
                val result=Json.parseToJsonElement(repository.ownerProfileContext(query)).jsonObject
                check(result.getValue("untrustedData").jsonPrimitive.boolean)
                val actual=(result["coreProfile"]?.jsonArray.orEmpty()+result["relevantKnowledge"]?.jsonArray.orEmpty()).map{it.jsonObject.getValue("predicate").jsonPrimitive.content}.toSet()
                check(actual.any{it in expectedPredicates}){"Related profile context did not retrieve the expected domain"}
                contextsChecked++
            }
            val receipt=buildJsonObject{
                put("version",1);put("sourceSha256",expected);put("rawNoteId",sourceId);put("rawChars",raw.length);put("rawUtf8Bytes",raw.toByteArray(Charsets.UTF_8).size)
                put("claims",JsonArray(ledger));put("skippedDeleted",skippedDeleted);put("contextsChecked",contextsChecked)
                put("modelInference",false);put("broadSourceScan",false)
            }.toString()
            repository.saveNote("프로필 병합 검증 기록",receipt,"owner-profile-receipts",receiptId(expected))
            check(repository.getNote(receiptId(expected))?.body==receipt)
        }catch(error:Throwable){failure=error.javaClass.simpleName;throw error}
        finally{
            withContext(NonCancellable+Dispatchers.IO){
                fixture.delete()
                val directory=File(context.getExternalFilesDir(null),"benchmarks").apply{check(isDirectory||mkdirs())}
                File(directory,"owner-profile-merge.json").writeText(buildJsonObject{
                    put("sourceSha256",expected);put("newClaims",saved);put("reusedClaims",reused);put("skippedDeleted",skippedDeleted);put("contextsChecked",contextsChecked)
                    put("fixtureDeleted",!fixture.exists());put("modelInference",false);put("broadSourceScan",false);put("failureClass",failure)
                }.toString())
            }
            instrumentation.sendStatus(0,Bundle().apply{putString("stream","Owner profile merge: new=$saved reused=$reused deleted-skipped=$skippedDeleted context-checks=$contextsChecked; no profile text exported.\n")})
        }
    }

    @Test fun verifyOwnerProfilePersistedWithoutPlaintextFixture()=runBlocking(Dispatchers.IO){
        authorized()
        val hash=expectedHash()
        val receipt=Json.parseToJsonElement(checkNotNull(repository.getNote(receiptId(hash))).body).jsonObject
        val raw=checkNotNull(repository.getNote(rawId(hash))).body
        check(profileDigest(raw)==hash&&raw.length==receipt.getValue("rawChars").jsonPrimitive.int)
        check(raw.toByteArray(Charsets.UTF_8).size==receipt.getValue("rawUtf8Bytes").jsonPrimitive.int)
        for(item in receipt.getValue("claims").jsonArray){val entry=item.jsonObject
            val claim=checkNotNull(repository.getKnowledge(entry.getValue("id").jsonPrimitive.content))
            check(profileDigest(claim.value)==entry.getValue("digest").jsonPrimitive.content&&claim.status==entry.getValue("status").jsonPrimitive.content)
        }
        instrumentation.sendStatus(0,Bundle().apply{putString("stream","Owner profile raw note and merged facts persisted; no plaintext fixture/model inference used.\n")})
    }

    @Test fun syntheticSourceDeletionAndCanonicalDuplicateRulesArePreserved()=runBlocking(Dispatchers.IO){
        assumeTrue("ownerProfileMergeSyntheticApproved=true required",InstrumentationRegistry.getArguments().getString("ownerProfileMergeSyntheticApproved")=="true")
        val name="profile-merge-qa-${UUID.randomUUID()}.db"
        val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
        System.loadLibrary("sqlcipher")
        val database=Room.databaseBuilder(context,LocalDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.clone()))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try{
            val repo=LocalRepository(database)
            val source="profile-qa-${UUID.randomUUID()}"
            repo.saveNote("Synthetic original profile","Synthetic complete raw profile\n한글🙂","owner-profile",source)
            val first=repo.saveKnowledgeClaim(KnowledgeClaim(id="profile.qa.first",predicate="synthetic.profile.preference",value="Synthetic preference",status="confirmed",confidence=1.0,sourceKind="owner_transfer",sourceId=source))
            val repeated=repo.saveKnowledgeClaim(KnowledgeClaim(id="profile.qa.repeat",predicate=first.predicate,value=first.value,status="confirmed",confidence=1.0,sourceKind="owner_transfer",sourceId=source))
            assertEquals(first.id,repeated.id)
            assertEquals(1,repo.knowledge.first().count{it.predicate==first.predicate})
            assertTrue(profileSourcesAllowed(repo,source,JsonArray(emptyList())))
            repo.deleteKnowledge(first.id)
            assertFalse(profileSourcesAllowed(repo,source,JsonArray(emptyList())))
            assertNull(repo.getKnowledge(first.id))

            val nextSource="profile-qa-next-${UUID.randomUUID()}"
            repo.saveNote("Synthetic next source","Synthetic next complete profile","owner-profile",nextSource)
            val priors=buildJsonArray{add(buildJsonObject{put("kind","owner_transfer");put("id",source)})}
            assertFalse("New source must respect a known forgotten prior source",profileSourcesAllowed(repo,nextSource,priors))
            repo.deleteNote(nextSource)
            assertFalse(profileSourcesAllowed(repo,nextSource,JsonArray(emptyList())))
        }finally{database.close();context.deleteDatabase(name);key.fill(0)}
    }
}

private fun profileDigest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
private suspend fun profileSourcesAllowed(repository:LocalRepository,sourceId:String,priorSources:JsonArray):Boolean {
    if(!repository.knowledgeSourceAllowed("note",sourceId)||!repository.knowledgeSourceAllowed("owner_transfer",sourceId))return false
    for(item in priorSources){val prior=item.jsonObject
        if(!repository.knowledgeSourceAllowed(prior.getValue("kind").jsonPrimitive.content,prior.getValue("id").jsonPrimitive.content))return false
    }
    return true
}
