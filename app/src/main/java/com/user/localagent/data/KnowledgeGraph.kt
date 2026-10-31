package com.user.localagent.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

@Entity(tableName="knowledge_entities")
data class KnowledgeEntity(@PrimaryKey val subject:String,val updatedAt:Long)

@Entity(tableName="knowledge_claims",foreignKeys=[
    ForeignKey(entity=KnowledgeEntity::class,parentColumns=["subject"],childColumns=["subject"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=KnowledgeEntity::class,parentColumns=["subject"],childColumns=["objectSubject"],onDelete=ForeignKey.SET_NULL)
],indices=[Index(value=["identityKey"],unique=true),Index(value=["subject","predicate","status"]),Index(value=["status","updatedAt"]),Index(value=["sourceId"]),Index(value=["objectSubject"])])
data class KnowledgeClaim(
    @PrimaryKey val id:String=UUID.randomUUID().toString(),val subject:String="owner",val predicate:String,val value:String,
    val status:String="proposed",val confidence:Double=0.5,val sourceKind:String="inference",val sourceId:String="",
    val sourceExcerpt:String?=null,val updatedAt:Long=System.currentTimeMillis(),val tags:List<String> = emptyList(),
    val objectSubject:String?=null,val identityKey:String=""
)

@Entity(tableName="knowledge_evidence",foreignKeys=[ForeignKey(entity=KnowledgeClaim::class,parentColumns=["id"],childColumns=["claimId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["claimId"]),Index(value=["sourceId"]),Index(value=["sourceKind","sourceId"])])
data class KnowledgeEvidence(@PrimaryKey val id:String,val claimId:String,val sourceKind:String,val sourceId:String,
    val sourceExcerpt:String?,val confidence:Double,val valueDigest:String,val observedAt:Long)

@Entity(tableName="knowledge_terms",primaryKeys=["term","claimId"],foreignKeys=[ForeignKey(entity=KnowledgeClaim::class,parentColumns=["id"],childColumns=["claimId"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["claimId"])])
data class KnowledgeTerm(val term:String,val claimId:String)
@Entity(tableName="knowledge_tombstones",primaryKeys=["sourceKind","sourceId"])
data class KnowledgeTombstone(val sourceKind:String,val sourceId:String,val deletedAt:Long)
@Entity(tableName="knowledge_meta") data class KnowledgeMeta(@PrimaryKey val key:String,val value:Long)

class KnowledgeConverters {
    @TypeConverter fun tags(value:List<String>):String=Json.encodeToString(value)
    @TypeConverter fun tags(value:String):List<String> = Json.decodeFromString(value)
}

@Dao interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_claims ORDER BY CASE status WHEN 'confirmed' THEN 0 WHEN 'observed' THEN 1 ELSE 2 END,updatedAt DESC,id LIMIT 500") fun observe():Flow<List<KnowledgeClaim>>
    @Query("SELECT * FROM knowledge_claims WHERE id=:id") suspend fun byId(id:String):KnowledgeClaim?
    @Query("SELECT * FROM knowledge_claims WHERE identityKey=:key LIMIT 1") suspend fun byKey(key:String):KnowledgeClaim?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun entity(value:KnowledgeEntity)
    @Upsert suspend fun claim(value:KnowledgeClaim)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun evidence(value:KnowledgeEvidence)
    @Query("SELECT * FROM knowledge_evidence WHERE claimId=:id ORDER BY observedAt DESC LIMIT 100") suspend fun evidenceFor(id:String):List<KnowledgeEvidence>
    @Query("UPDATE knowledge_evidence SET claimId=:newId WHERE claimId=:oldId") suspend fun moveEvidence(oldId:String,newId:String)
    @Query("DELETE FROM knowledge_evidence WHERE claimId=:claimId AND sourceId=:sourceId") suspend fun removeSourceEvidence(claimId:String,sourceId:String)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun terms(value:List<KnowledgeTerm>)
    @Query("DELETE FROM knowledge_terms WHERE claimId=:id") suspend fun clearTerms(id:String)
    @Query("SELECT c.* FROM knowledge_claims c JOIN knowledge_terms t ON t.claimId=c.id WHERE t.term IN (:terms) AND c.status IN ('confirmed','observed') GROUP BY c.id ORDER BY COUNT(*) DESC,CASE c.status WHEN 'confirmed' THEN 0 ELSE 1 END,c.confidence DESC,c.updatedAt DESC LIMIT :limit")
    suspend fun matching(terms:List<String>,limit:Int):List<KnowledgeClaim>
    @Query("SELECT * FROM knowledge_claims WHERE status IN ('confirmed','observed') AND (subject IN (:subjects) OR objectSubject IN (:subjects)) ORDER BY CASE status WHEN 'confirmed' THEN 0 ELSE 1 END,confidence DESC,updatedAt DESC LIMIT :limit")
    suspend fun neighbors(subjects:List<String>,limit:Int):List<KnowledgeClaim>
    @Query("SELECT * FROM knowledge_claims WHERE status='confirmed' ORDER BY updatedAt DESC LIMIT 100") suspend fun profileCandidates():List<KnowledgeClaim>
    @Query("SELECT COUNT(*) FROM knowledge_tombstones WHERE sourceId=:id AND (sourceKind=:kind OR sourceKind='*')") suspend fun tombstoned(kind:String,id:String):Int
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun tombstone(value:KnowledgeTombstone)
    @Query("SELECT value FROM knowledge_meta WHERE `key`='epoch'") suspend fun epoch():Long?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun meta(value:KnowledgeMeta)
    @Query("INSERT OR REPLACE INTO knowledge_tombstones (sourceKind,sourceId,deletedAt) SELECT sourceKind,sourceId,:at FROM knowledge_evidence WHERE claimId=:id") suspend fun tombstoneEvidence(id:String,at:Long)
    @Query("INSERT OR REPLACE INTO knowledge_tombstones (sourceKind,sourceId,deletedAt) SELECT sourceKind,sourceId,:at FROM knowledge_claims WHERE id=:id") suspend fun tombstoneCurrent(id:String,at:Long)
    @Query("SELECT DISTINCT c.* FROM knowledge_claims c LEFT JOIN knowledge_evidence e ON e.claimId=c.id WHERE c.sourceId=:sourceId OR e.sourceId=:sourceId") suspend fun linkedToSource(sourceId:String):List<KnowledgeClaim>
    @Query("DELETE FROM knowledge_claims WHERE id=:id") suspend fun delete(id:String)
    @Query("DELETE FROM knowledge_entities WHERE NOT EXISTS (SELECT 1 FROM knowledge_claims c WHERE c.subject=knowledge_entities.subject OR c.objectSubject=knowledge_entities.subject)") suspend fun pruneEntities()
    @Query("SELECT COUNT(*) FROM knowledge_claims") suspend fun count():Int
    @Query("INSERT OR REPLACE INTO knowledge_tombstones (sourceKind,sourceId,deletedAt) SELECT '*',sourceId,:at FROM knowledge_evidence GROUP BY sourceId") suspend fun tombstoneAllEvidence(at:Long)
    @Query("INSERT OR REPLACE INTO knowledge_tombstones (sourceKind,sourceId,deletedAt) SELECT '*',sourceId,:at FROM knowledge_claims GROUP BY sourceId") suspend fun tombstoneAllCurrent(at:Long)
    @Query("DELETE FROM knowledge_claims") suspend fun deleteAll()
}

object KnowledgeMigrations {
    val MIGRATION_1_2=object:Migration(1,2){
        override fun migrate(db:SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `knowledge_entities` (`subject` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`subject`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `knowledge_claims` (`id` TEXT NOT NULL, `subject` TEXT NOT NULL, `predicate` TEXT NOT NULL, `value` TEXT NOT NULL, `status` TEXT NOT NULL, `confidence` REAL NOT NULL, `sourceKind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `sourceExcerpt` TEXT, `updatedAt` INTEGER NOT NULL, `tags` TEXT NOT NULL, `objectSubject` TEXT, `identityKey` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`subject`) REFERENCES `knowledge_entities`(`subject`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`objectSubject`) REFERENCES `knowledge_entities`(`subject`) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_claims_identityKey` ON `knowledge_claims` (`identityKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_claims_subject_predicate_status` ON `knowledge_claims` (`subject`,`predicate`,`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_claims_status_updatedAt` ON `knowledge_claims` (`status`,`updatedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_claims_sourceId` ON `knowledge_claims` (`sourceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_claims_objectSubject` ON `knowledge_claims` (`objectSubject`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `knowledge_evidence` (`id` TEXT NOT NULL, `claimId` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `sourceExcerpt` TEXT, `confidence` REAL NOT NULL, `valueDigest` TEXT NOT NULL, `observedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`claimId`) REFERENCES `knowledge_claims`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_evidence_claimId` ON `knowledge_evidence` (`claimId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_evidence_sourceId` ON `knowledge_evidence` (`sourceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_evidence_sourceKind_sourceId` ON `knowledge_evidence` (`sourceKind`,`sourceId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `knowledge_terms` (`term` TEXT NOT NULL, `claimId` TEXT NOT NULL, PRIMARY KEY(`term`,`claimId`), FOREIGN KEY(`claimId`) REFERENCES `knowledge_claims`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_terms_claimId` ON `knowledge_terms` (`claimId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `knowledge_tombstones` (`sourceKind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`sourceKind`,`sourceId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `knowledge_meta` (`key` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            db.execSQL("INSERT OR IGNORE INTO knowledge_meta (`key`,`value`) VALUES ('epoch',0)")
        }
    }
}

/** Normalized indexed lexical graph, not embeddings or model-weight training. The containing DB is SQLCipher. */
internal class KnowledgeStore(private val database:LocalDatabase) {
    private val dao=database.knowledgeDao()
    val knowledge=dao.observe()
    suspend fun epoch():Long=dao.epoch()?:0L
    suspend fun allowed(kind:String,id:String):Boolean=dao.tombstoned(kind.trim().lowercase(Locale.ROOT),id)==0
    suspend fun get(id:String)=dao.byId(id)
    suspend fun save(input:KnowledgeClaim,expectedEpoch:Long?=null):KnowledgeClaim=database.withTransaction {
        if(expectedEpoch!=null)check(epoch()==expectedEpoch){"개인 지식 삭제·초기화 후 시작된 작업만 저장할 수 있습니다."}
        val now=System.currentTimeMillis();val record=normalize(input,now)
        if(expectedEpoch!=null)check(allowed(record.sourceKind,record.sourceId)){"잊은 자료에서 개인 지식을 다시 만들지 않습니다."}
        val previous=dao.byKey(record.identityKey)
        val original=dao.byId(record.id)
        // The caller's stable ID cannot silently reassign an unrelated claim.
        require(original==null||original.subject==record.subject&&original.predicate==record.predicate){"지식 ID의 대상·항목이 일치하지 않습니다."}
        val stableId=previous?.id?:record.id
        var saved=record.copy(id=stableId)
        if(previous!=null&&previous.updatedAt>saved.updatedAt)saved=previous
        if(saved===previous){appendEvidence(stableId,record);return@withTransaction saved}
        dao.entity(KnowledgeEntity(saved.subject,now));saved.objectSubject?.let {dao.entity(KnowledgeEntity(it,now))}
        // Promotion may collapse a proposed claim into an existing confirmed owner field.
        if(original!=null&&original.id!=stableId){
            dao.moveEvidence(original.id,stableId);dao.delete(original.id)
        }
        dao.claim(saved);dao.clearTerms(stableId);dao.terms(terms(saved).map {KnowledgeTerm(it,stableId)})
        appendEvidence(stableId,record)
        saved
    }
    private suspend fun appendEvidence(id:String,record:KnowledgeClaim){
        val digest=hash(record.value)
        dao.evidence(KnowledgeEvidence(hash("$id|${record.sourceKind}|${record.sourceId}|$digest"),id,record.sourceKind,record.sourceId,record.sourceExcerpt,record.confidence,digest,record.updatedAt))
    }
    suspend fun retrieve(query:String,limit:Int):List<KnowledgeClaim> {
        require(query.length<=2000&&'\u0000' !in query);require(limit in 1..10)
        val tokens=queryTerms(query)
        if(tokens.isEmpty())return emptyList()
        val direct=dao.matching(tokens,40)
        val subjects=(direct.take(3).flatMap {listOfNotNull(it.objectSubject)}+listOf(key(query.trim()))).distinct().filter {it.isNotBlank()}.take(4)
        val expanded=if(subjects.isEmpty())emptyList()else dao.neighbors(subjects,12)
        return (direct+expanded).distinctBy {it.id}.take(limit)
    }
    suspend fun core():List<KnowledgeClaim> = dao.profileCandidates().filter {claim->
        "core" in claim.tags||claim.subject=="owner"&&(CORE_PREDICATES.any {claim.predicate==it||claim.predicate.startsWith("$it.")||claim.predicate.startsWith("${it}_")})
    }.sortedWith(compareBy<KnowledgeClaim>{corePriority(it.predicate)}.thenByDescending {"core" in it.tags}.thenByDescending {it.updatedAt}).take(16)
    suspend fun approve(id:String):KnowledgeClaim {
        val previous=dao.byId(id)?:error("개인 지식을 찾을 수 없습니다.")
        return save(previous.copy(status="confirmed",confidence=1.0,sourceKind="owner_approved",updatedAt=System.currentTimeMillis()))
    }
    suspend fun delete(id:String)=database.withTransaction {
        if(dao.byId(id)!=null){eraseClaim(id,System.currentTimeMillis());bumpEpoch();dao.pruneEntities()}
    }
    suspend fun clear():Int=database.withTransaction {
        val count=dao.count();val now=System.currentTimeMillis();dao.tombstoneAllEvidence(now);dao.tombstoneAllCurrent(now)
        dao.deleteAll();dao.pruneEntities();bumpEpoch();count
    }
    /** Call within the same Room transaction as source deletion. Wildcard tombstones also cover not-yet-ingested sources. */
    suspend fun eraseSource(sourceId:String) {
        val now=System.currentTimeMillis();dao.tombstone(KnowledgeTombstone("*",sourceId,now))
        dao.linkedToSource(sourceId).forEach {claim->
            if(claim.sourceId==sourceId)eraseClaim(claim.id,now)
            else dao.removeSourceEvidence(claim.id,sourceId)
        };dao.pruneEntities();bumpEpoch()
    }
    private suspend fun eraseClaim(id:String,at:Long){dao.tombstoneEvidence(id,at);dao.tombstoneCurrent(id,at);dao.delete(id)}
    private suspend fun bumpEpoch(){dao.meta(KnowledgeMeta("epoch",epoch()+1))}
    private fun normalize(input:KnowledgeClaim,now:Long):KnowledgeClaim {
        require(input.id.length in 1..128&&input.id.none {it.code<32})
        val subject=key(input.subject);val predicate=key(input.predicate);val sourceKind=key(input.sourceKind)
        require(subject.length in 1..160&&predicate.length in 1..120&&sourceKind.length in 1..80)
        require(input.value.isNotBlank()&&input.value.length<=4000&&'\u0000' !in input.value)
        require(input.status in setOf("confirmed","observed","proposed")&&input.confidence.isFinite()&&input.confidence in 0.0..1.0)
        val sourceId=input.sourceId.ifBlank {input.id};require(sourceId.length<=300&&sourceId.none {it.code<32})
        require(input.sourceExcerpt==null||input.sourceExcerpt.length<=1000&&'\u0000' !in input.sourceExcerpt)
        require(input.tags.size<=20&&input.tags.all {it.length in 1..80&&'\u0000' !in it})
        val objectSubject=input.objectSubject?.let(::key);require(objectSubject==null||objectSubject.length in 1..160)
        val status=when {sourceKind in setOf("inference","model","assistant","reflection")->"proposed";input.status=="confirmed"&&sourceKind !in CONFIRMED_SOURCES->"observed";else->input.status}
        val confidence=input.confidence.coerceAtMost(when(status){"confirmed"->1.0;"observed"->0.9;else->0.69})
        val identity=if(status=="confirmed")"$subject|$predicate|confirmed"else "$subject|$predicate|$sourceKind|$sourceId"
        return input.copy(subject=subject,predicate=predicate,value=input.value.trim(),status=status,confidence=confidence,sourceKind=sourceKind,sourceId=sourceId,
            updatedAt=input.updatedAt.coerceIn(0,now+300_000),tags=input.tags.map(::key).distinct(),objectSubject=objectSubject,identityKey=hash(identity))
    }
    private fun terms(claim:KnowledgeClaim)=queryTerms(listOf(claim.subject,claim.predicate,claim.value,claim.objectSubject.orEmpty(),claim.tags.joinToString(" ")).joinToString(" ")).take(160)
    private fun queryTerms(value:String):List<String> {
        val normalized=Normalizer.normalize(value.lowercase(Locale.ROOT),Normalizer.Form.NFKC).take(5000)
        val tokens=normalized.split(Regex("[^\\p{L}\\p{N}]+" )).filter {it.length in 1..80}.take(80)
        val compact=normalized.filter {it.isLetterOrDigit()}.take(600)
        val aliases=ALIASES.filterKeys {it in normalized}.values.flatten()
        return (tokens+aliases+compact.windowed(2).take(120)).distinct().take(160)
    }
    private fun key(value:String)=Normalizer.normalize(value.trim().lowercase(Locale.ROOT),Normalizer.Form.NFKC).replace(Regex("\\s+"),"_")
    private fun corePriority(predicate:String)=when {
        predicate in setOf("preferred_name","name","display_name")->0
        predicate in setOf("family.size","family_size","family.children","children_count","family.children_count","children.count")->1
        Regex("family|children|child|spouse|partner|son|daughter|가족|자녀|배우자").containsMatchIn(predicate)->2
        Regex("preference|response|language|communication|선호|응답").containsMatchIn(predicate)->3
        else->4
    }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    companion object {
        private val CONFIRMED_SOURCES=setOf("owner","owner_approved","owner_seed","owner_transfer","developer_seed","approved_memory","user_message","direct_user")
        private val CORE_PREDICATES=listOf("preferred_name","name","display_name","occupation","profession","job","workplace","organization","role","family","spouse","partner","children","children_count","child","son","daughter","response_preference","response_style","language","preference","communication_preference")
        private val ALIASES=mapOf("이름" to listOf("name","preferred_name"),"직업" to listOf("occupation","profession","job"),"직장" to listOf("workplace","organization"),"가족" to listOf("family","spouse","children"),"아이" to listOf("children"),"아들" to listOf("children","son"),"딸" to listOf("children","daughter"),"선호" to listOf("preference","response_preference"),"메일" to listOf("email","work_email"),"연락처" to listOf("phone","email"))
    }
}
