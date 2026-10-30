package com.user.localagent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import com.user.localagent.core.CapabilityPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName="notes") data class NoteRecord(@PrimaryKey val id:String=UUID.randomUUID().toString(),val title:String,val body:String,val collection:String="default",val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="memories") data class MemoryRecord(@PrimaryKey val id:String=UUID.randomUUID().toString(),val text:String,val kind:String="fact",val sourceMessageId:String?=null,val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="reminders") data class ReminderRecord(@PrimaryKey val id:String=UUID.randomUUID().toString(),val title:String,val atEpochMillis:Long,@ColumnInfo(name="reminder_group") val group:String="default",val status:String="pending",val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="messages") data class MessageRecord(@PrimaryKey val id:String=UUID.randomUUID().toString(),val role:String,val text:String,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="tool_runs") data class ToolRunRecord(@PrimaryKey val id:String=UUID.randomUUID().toString(),val name:String,val success:Boolean,val summary:String,val at:Long=System.currentTimeMillis())
@Entity(tableName="grants",primaryKeys=["extensionId","codeHash","toolName","scope"]) data class GrantRecord(val extensionId:String,val codeHash:String,val toolName:String,val scope:String)
@Entity(tableName="summaries") data class SummaryRecord(@PrimaryKey val id:String,val text:String,val sourceMessageIds:String)

@Dao interface LocalDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun observeNotes():Flow<List<NoteRecord>>
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") fun observeMemories():Flow<List<MemoryRecord>>
    @Query("SELECT * FROM reminders ORDER BY atEpochMillis") fun observeReminders():Flow<List<ReminderRecord>>
    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT 200") fun observeMessages():Flow<List<MessageRecord>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun note(value:NoteRecord)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun memory(value:MemoryRecord)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun reminder(value:ReminderRecord)
    @Insert suspend fun message(value:MessageRecord)
    @Query("DELETE FROM messages WHERE id=:id") suspend fun deleteMessage(id:String)
    @Query("DELETE FROM memories WHERE sourceMessageId=:id") suspend fun deleteMessageMemories(id:String)
    @Insert suspend fun toolRun(value:ToolRunRecord)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun grant(value:GrantRecord)
    @Query("SELECT * FROM grants") suspend fun grants():List<GrantRecord>
    @Query("SELECT * FROM grants WHERE extensionId='builtin' AND codeHash=:oldCodeHash AND toolName IN ('save_note','update_note','create_reminder','update_reminder')")
    suspend fun carryableBuiltinGrants(oldCodeHash:String):List<GrantRecord>
    @Query("DELETE FROM grants WHERE extensionId=:extensionId") suspend fun revoke(extensionId:String)
    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' ESCAPE '!' OR body LIKE '%' || :query || '%' ESCAPE '!' ORDER BY updatedAt DESC LIMIT 50") suspend fun searchNotes(query:String):List<NoteRecord>
    @Query("SELECT * FROM memories WHERE text LIKE '%' || :query || '%' ESCAPE '!' ORDER BY updatedAt DESC LIMIT :limit") suspend fun searchMemories(query:String,limit:Int):List<MemoryRecord>
    @Query("SELECT * FROM memories WHERE text=:text LIMIT 1") suspend fun exactMemory(text:String):MemoryRecord?
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT 500") suspend fun recentMemories():List<MemoryRecord>
    @Query("SELECT * FROM messages WHERE role='user' AND instr(lower(text),lower(:query))>0 AND NOT EXISTS (SELECT 1 FROM memories WHERE memories.sourceMessageId=messages.id) ORDER BY createdAt DESC LIMIT :limit") suspend fun sourceMessages(query:String,limit:Int):List<MessageRecord>
    @Query("DELETE FROM memories WHERE id=:id OR text=:text OR sourceMessageId IN (SELECT id FROM messages WHERE id=:sourceId OR instr(text,:text)>0)") suspend fun removeLinkedMemories(id:String,sourceId:String,text:String)
    @Query("SELECT * FROM memories WHERE id=:id") suspend fun memoryById(id:String):MemoryRecord?
    @Query("SELECT * FROM notes WHERE id=:id") suspend fun noteById(id:String):NoteRecord?
    @Query("SELECT * FROM reminders WHERE id=:id") suspend fun reminderById(id:String):ReminderRecord?
    @Query("SELECT * FROM reminders WHERE status='pending'") suspend fun pendingReminders():List<ReminderRecord>
    @Query("UPDATE reminders SET status=:status WHERE id=:id") suspend fun reminderStatus(id:String,status:String)
    @Query("UPDATE reminders SET status='delivering' WHERE id=:id AND status='pending'") suspend fun claimReminder(id:String):Int
    @Query("UPDATE reminders SET status='pending', updatedAt=CASE WHEN updatedAt>=:now THEN updatedAt+1 ELSE :now END WHERE status='delivering'") suspend fun recoverDelivering(now:Long):Int
    @Query("UPDATE reminders SET status='delivering' WHERE id=:id AND status='pending' AND atEpochMillis=:at AND updatedAt=:revision AND atEpochMillis<=:now") suspend fun claimReminderVersion(id:String,at:Long,revision:Long,now:Long):Int
    @Query("UPDATE reminders SET status=:status WHERE id=:id AND status='delivering' AND atEpochMillis=:at AND updatedAt=:revision") suspend fun finishReminderVersion(id:String,at:Long,revision:Long,status:String):Int
    @Query("DELETE FROM memories WHERE id=:id") suspend fun deleteMemory(id:String)
    @Query("DELETE FROM memories WHERE id=:id AND kind='proposed'") suspend fun discardProposed(id:String):Int
    @Query("DELETE FROM memories WHERE kind='proposed'") suspend fun discardAllProposed():Int
    @Query("DELETE FROM notes WHERE id=:id") suspend fun deleteNote(id:String)
    @Query("DELETE FROM reminders WHERE id=:id") suspend fun deleteReminder(id:String)
    @Query("DELETE FROM messages WHERE id=:id OR instr(text,:memoryText)>0") suspend fun removeMemorySource(id:String,memoryText:String)
    @Query("DELETE FROM summaries") suspend fun invalidateSummaries()
}
@Database(entities=[NoteRecord::class,MemoryRecord::class,ReminderRecord::class,MessageRecord::class,ToolRunRecord::class,GrantRecord::class,SummaryRecord::class],version=1,exportSchema=true)
abstract class LocalDatabase:RoomDatabase(){abstract fun dao():LocalDao}

class LocalRepository(context:Context) {
    private val database=DatabaseProvider.get(context.applicationContext)
    private val dao=database.dao()
    val notes=dao.observeNotes();val memories=dao.observeMemories();val reminders=dao.observeReminders();val messages=dao.observeMessages()
    suspend fun addMessage(role:String,text:String):String {require(role in setOf("user","assistant"));val record=MessageRecord(role=role,text=text.take(20000));dao.message(record);return record.id}
    suspend fun deleteMessage(id:String)=database.withTransaction {dao.deleteMessageMemories(id);dao.deleteMessage(id);dao.invalidateSummaries()}
    suspend fun saveNote(title:String,body:String,collection:String="default",id:String?=null):NoteRecord {require(body.isNotBlank()&&body.length<=12000);val record=NoteRecord(id?:UUID.randomUUID().toString(),title.take(300),body,collection.take(80));dao.note(record);return record}
    suspend fun searchNotes(query:String)=dao.searchNotes(escapeLike(query.take(500)))
    suspend fun saveMemory(text:String,kind:String="fact",sourceMessageId:String?=null):MemoryRecord {
        require(text.isNotBlank()&&text.length<=4000);require(kind.isNotBlank()&&kind.length<=80)
        return database.withTransaction {
            dao.exactMemory(text) ?: MemoryRecord(text=text,kind=kind,sourceMessageId=sourceMessageId).also {dao.memory(it)}
        }
    }
    /** An explicit user correction; preserve provenance, replace the approved text, invalidate derived summaries. */
    suspend fun editMemory(id:String,text:String,kind:String="correction"):MemoryRecord = database.withTransaction {
        require(text.isNotBlank()&&text.length<=4000);require(kind.isNotBlank()&&kind.length<=80)
        val previous=dao.memoryById(id)?:error("기억을 찾을 수 없습니다.")
        val corrected=previous.copy(text=text,kind=kind,updatedAt=System.currentTimeMillis())
        dao.memory(corrected);dao.invalidateSummaries();corrected
    }
    /** Literal stored user text only. Source-linked messages are excluded in favor of their current approved memory. */
    suspend fun sourceMessages(query:String,limit:Int=3):List<MessageRecord> {
        val literal=query.trim().take(500)
        return if(literal.isBlank())emptyList() else dao.sourceMessages(literal,limit.coerceIn(1,5))
    }
    suspend fun discardProposedMemory(id:String):Boolean=database.withTransaction {
        val removed=dao.discardProposed(id)>0
        if(removed)dao.invalidateSummaries()
        removed
    }
    suspend fun discardProposedMemories():Int=database.withTransaction {
        dao.discardAllProposed().also {if(it>0)dao.invalidateSummaries()}
    }
    /** Bounded lexical retrieval, not embeddings or generated semantic search. */
    suspend fun searchMemories(query:String,limit:Int=5):List<MemoryRecord> = withContext(Dispatchers.Default) {
        val literal=query.trim().take(500)
        if(literal.isBlank())return@withContext emptyList()
        val maximum=limit.coerceIn(1,5)
        val exact=dao.searchMemories(escapeLike(literal),maximum).filter {it.kind.lowercase(java.util.Locale.ROOT) !in setOf("proposed","draft","rejected")}
        if(exact.isNotEmpty())return@withContext exact
        val queryTokens=lexicalTokens(literal)
        val queryPairs=characterPairs(literal)
        dao.recentMemories().asSequence().filter {approvedMemoryKind(it.kind)}.mapNotNull {memory ->
            val tokens=lexicalTokens(memory.text)
            val pairs=characterPairs(memory.text)
            val tokenOverlap=queryTokens.intersect(tokens).size
            val pairOverlap=queryPairs.intersect(pairs).size
            val tokenScore=if(queryTokens.isEmpty())0.0 else tokenOverlap.toDouble()/queryTokens.size
            val pairScore=if(queryPairs.isEmpty())0.0 else pairOverlap.toDouble()/queryPairs.size
            // Require shared words or at least two adjacent character pairs. No invented matches.
            if(tokenOverlap==0&&(pairOverlap<2||pairScore<0.15))null
            else memory to (tokenScore*3+pairScore)
        }.sortedWith(compareByDescending<Pair<MemoryRecord,Double>> {it.second}.thenByDescending {it.first.updatedAt})
            .take(maximum).map {it.first}.toList()
    }
    suspend fun forgetMemory(id:String)=database.withTransaction {
        dao.memoryById(id)?.let {memory ->
            // Remove all sibling memories derived from any source copy before erasing those messages.
            dao.removeLinkedMemories(id,memory.sourceMessageId?:"",memory.text)
            dao.removeMemorySource(memory.sourceMessageId?:"",memory.text)
            dao.invalidateSummaries()
        }
    }
    suspend fun saveReminder(title:String,atEpochMillis:Long,group:String="default",id:String?=null):ReminderRecord = database.withTransaction {
        require(title.isNotBlank())
        val previous=id?.let {dao.reminderById(it)}
        val revision=maxOf(System.currentTimeMillis(),(previous?.updatedAt?:0)+1)
        val record=ReminderRecord(id?:UUID.randomUUID().toString(),title.take(300),atEpochMillis,group.take(80),updatedAt=revision)
        dao.reminder(record);record
    }
    suspend fun setReminderStatus(id:String,status:String)=dao.reminderStatus(id,status)
    suspend fun claimReminder(id:String)=dao.claimReminder(id)==1
    suspend fun claimReminder(record:ReminderRecord)=dao.claimReminderVersion(record.id,record.atEpochMillis,record.updatedAt,System.currentTimeMillis())==1
    suspend fun finishReminder(record:ReminderRecord,status:String)=dao.finishReminderVersion(record.id,record.atEpochMillis,record.updatedAt,status)==1
    suspend fun recoverInterruptedReminders()=dao.recoverDelivering(System.currentTimeMillis())
    suspend fun pendingReminders()=dao.pendingReminders()
    suspend fun getReminder(id:String)=dao.reminderById(id)
    suspend fun getNote(id:String)=dao.noteById(id)
    suspend fun deleteNote(id:String)=dao.deleteNote(id)
    suspend fun deleteReminder(id:String)=dao.deleteReminder(id)
    suspend fun recordToolRun(name:String,success:Boolean,summary:String)=dao.toolRun(ToolRunRecord(name=name,success=success,summary=summary.take(200)))
    suspend fun addGrant(grant:GrantRecord)=dao.grant(grant)
    /**
     * Carry only existing scoped builtin note/reminder approvals across a trusted app update.
     * Caller must finish APK signature/hash/package/capability review before calling this API.
     * SQLite serializes this read+copy transaction with revokeGrant's deletion, so revocation
     * either removes the old source before selection or deletes both hashes after the copy.
     */
    suspend fun carryBuiltinGrants(oldCodeHash:String,newCodeHash:String):Int {
        val sha256=Regex("[a-fA-F0-9]{64}")
        require(sha256.matches(oldCodeHash)&&sha256.matches(newCodeHash)){"Both code hashes must be 64-digit SHA-256 hex strings"}
        if(oldCodeHash==newCodeHash)return 0
        return database.withTransaction {
            val existing=dao.carryableBuiltinGrants(oldCodeHash).filter {CapabilityPolicy.canPreapprove(it.toolName)}
            existing.forEach {dao.grant(it.copy(codeHash=newCodeHash))}
            existing.size
        }
    }
    suspend fun revokeGrant(extensionId:String)=dao.revoke(extensionId)
    suspend fun listGrants()=dao.grants()
    private fun escapeLike(value:String)=value.replace("!","!!").replace("%","!%").replace("_","!_")
    private fun approvedMemoryKind(kind:String)=kind.lowercase(java.util.Locale.ROOT) in setOf("fact","preference","correction","approved","approved_fact","approved_preference","approved_correction")
    private fun lexicalTokens(value:String)=value.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
        .filter {it.length>=2&&it !in setOf("what","when","where","which","that","this","with","have","does","remember","please","내가","나는","저는","기억","알려줘","무엇","뭐야")}.toSet()
    private fun characterPairs(value:String):Set<String> {
        val normalized=value.lowercase(java.util.Locale.ROOT).filter {it.isLetterOrDigit()}.take(4000)
        return normalized.windowed(2).toSet()
    }
}
private object DatabaseProvider {
    @Volatile private var instance:LocalDatabase?=null
    fun get(context:Context):LocalDatabase=instance?:synchronized(this){instance?:run {
        System.loadLibrary("sqlcipher")
        Room.databaseBuilder(context,LocalDatabase::class.java,"local-agent.db")
            .openHelperFactory(SupportOpenHelperFactory(databaseKey(context)))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build().also {instance=it}
    }}
    private fun databaseKey(context:Context):ByteArray {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val alias="local-agent-database-wrap-v1"
        if(!store.containsAlias(alias))KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
        val key=store.getKey(alias,null) as SecretKey
        val preferences=context.getSharedPreferences("database-key",Context.MODE_PRIVATE)
        val wrapped=preferences.getString("wrapped",null)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        if(wrapped!=null){val iv=Base64.decode(preferences.getString("iv",null)?:error("DB 키 정보가 없습니다."),Base64.NO_WRAP);cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv));return cipher.doFinal(Base64.decode(wrapped,Base64.NO_WRAP))}
        check(!context.getDatabasePath("local-agent.db").exists()){ "저장된 DB의 암호화 키가 없습니다. 기존 데이터를 자동으로 지우지 않습니다." }
        val raw=ByteArray(32).also {SecureRandom().nextBytes(it)}
        cipher.init(Cipher.ENCRYPT_MODE,key)
        check(preferences.edit().putString("wrapped",Base64.encodeToString(cipher.doFinal(raw),Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit())
        return raw
    }
}





