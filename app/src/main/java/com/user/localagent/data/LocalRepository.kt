package com.user.localagent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import com.user.localagent.core.CapabilityPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
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
@Entity(tableName="messages",indices=[Index(value=["threadId","branchId","createdAt"])]) data class MessageRecord(
    @PrimaryKey val id:String=UUID.randomUUID().toString(),val role:String,val text:String,val createdAt:Long=System.currentTimeMillis(),
    @ColumnInfo(defaultValue="'legacy'") val threadId:String=ChatStore.LEGACY_THREAD_ID,
    @ColumnInfo(defaultValue="'legacy-main'") val branchId:String=ChatStore.LEGACY_BRANCH_ID,
    val runId:String?=null,val originMessageId:String?=null)
data class MessageCursor(val createdAt:Long,val id:String)
data class MessagePage(val messages:List<MessageRecord>,val nextCursor:MessageCursor?)
/** Metadata-only history scan keeps large checkpoints out of the 200-row search buffer. */
data class MessageMetadataRecord(val id:String,val role:String,val createdAt:Long,val threadId:String,val branchId:String,
    val runId:String?,val originMessageId:String?) {
    fun withText(text:String)=MessageRecord(id,role,text,createdAt,threadId,branchId,runId,originMessageId)
}
data class ToolAuditMetadataRecord(val id:String,val name:String,val success:Boolean,val at:Long)
@Entity(tableName="tool_runs",indices=[Index("runId"),Index("threadId")]) data class ToolRunRecord(@PrimaryKey val id:String=UUID.randomUUID().toString(),val name:String,val success:Boolean,val summary:String,val at:Long=System.currentTimeMillis(),val runId:String?=null,val threadId:String?=null)
@Entity(tableName="grants",primaryKeys=["extensionId","codeHash","toolName","scope"]) data class GrantRecord(val extensionId:String,val codeHash:String,val toolName:String,val scope:String)
@Entity(tableName="summaries") data class SummaryRecord(@PrimaryKey val id:String,val text:String,val sourceMessageIds:String)

@Dao interface LocalDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun observeNotes():Flow<List<NoteRecord>>
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") fun observeMemories():Flow<List<MemoryRecord>>
    @Query("SELECT * FROM reminders ORDER BY atEpochMillis") fun observeReminders():Flow<List<ReminderRecord>>
    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT 200") fun observeMessages():Flow<List<MessageRecord>>
    @Query("SELECT * FROM messages WHERE (:beforeAt IS NULL OR createdAt<:beforeAt OR (createdAt=:beforeAt AND id<:beforeId)) AND (:query='' OR instr(lower(text),lower(:query))>0) ORDER BY createdAt DESC,id DESC LIMIT :limit")
    suspend fun messageHistory(beforeAt:Long?,beforeId:String?,query:String,limit:Int):List<MessageRecord>
    @Query("SELECT id,role,createdAt,threadId,branchId,runId,originMessageId FROM messages WHERE (:beforeAt IS NULL OR createdAt<:beforeAt OR (createdAt=:beforeAt AND id<:beforeId)) ORDER BY createdAt DESC,id DESC LIMIT :limit")
    suspend fun messageMetadataHistory(beforeAt:Long?,beforeId:String?,limit:Int):List<MessageMetadataRecord>
    @Query("SELECT m.id,m.role,m.createdAt,m.threadId,m.branchId,m.runId,m.originMessageId FROM messages m JOIN chat_branch_messages b ON b.messageId=m.id WHERE m.threadId=:threadId AND b.branchId=:branchId AND (:beforePosition IS NULL OR b.position<:beforePosition) ORDER BY b.position DESC LIMIT :limit")
    suspend fun scopedMessageMetadataHistory(threadId:String,branchId:String,beforePosition:Long?,limit:Int):List<MessageMetadataRecord>
    @Query("SELECT id,role,createdAt,threadId,branchId,runId,originMessageId FROM messages WHERE id=:id") suspend fun messageMetadataById(id:String):MessageMetadataRecord?
    @Query("SELECT COUNT(*) FROM messages m JOIN chat_branch_messages b ON b.messageId=m.id WHERE m.threadId=:threadId AND b.branchId=:branchId") suspend fun scopedMessageCount(threadId:String,branchId:String):Long
    @Query("SELECT * FROM messages WHERE role='user' AND originMessageId IS NULL AND (:afterAt IS NULL OR createdAt>:afterAt OR (createdAt=:afterAt AND id>:afterId)) ORDER BY createdAt ASC,id ASC LIMIT :limit")
    suspend fun knowledgeMessages(afterAt:Long?,afterId:String?,limit:Int):List<MessageRecord>
    @Query("SELECT m.* FROM messages m JOIN chat_branch_messages b ON b.messageId=m.id WHERE m.threadId=:threadId AND b.branchId=:branchId AND m.role='user' AND m.originMessageId IS NULL AND (:afterAt IS NULL OR m.createdAt>:afterAt OR (m.createdAt=:afterAt AND m.id>:afterId)) ORDER BY m.createdAt ASC,m.id ASC LIMIT :limit")
    suspend fun scopedKnowledgeMessages(threadId:String,branchId:String,afterAt:Long?,afterId:String?,limit:Int):List<MessageRecord>
    @Query("SELECT * FROM notes WHERE collection IN ('imported-chats','sms-schedule-candidates','audio-transcripts','native-sms') AND (updatedAt>:afterAt OR (updatedAt=:afterAt AND id>:afterId)) ORDER BY updatedAt ASC,id ASC LIMIT :limit")
    suspend fun knowledgeNotes(afterAt:Long,afterId:String,limit:Int):List<NoteRecord>
    @Query("SELECT COUNT(*) FROM messages WHERE :query='' OR instr(lower(text),lower(:query))>0")
    suspend fun messageCount(query:String):Long
    @Query("SELECT * FROM messages WHERE id=:id LIMIT 1") suspend fun messageById(id:String):MessageRecord?
    @Query("SELECT id,name,success,at FROM tool_runs WHERE name NOT IN ('work_trace_v1','work_receipt_v1') AND at>=:start AND at<=:end ORDER BY at DESC,id DESC LIMIT :limit")
    suspend fun toolAuditMetadata(start:Long,end:Long,limit:Int):List<ToolAuditMetadataRecord>
    @Query("SELECT * FROM tool_runs WHERE id=:id") suspend fun toolRunById(id:String):ToolRunRecord?
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
    @Query("SELECT * FROM notes WHERE collection NOT IN ('imported-chats','native-sms','audio-transcripts','rawaudio-transcripts','calendar-event-links','sms-schedule-candidates','generated-images','web-research','planning-feedback') AND (:scope='' OR collection=:scope) AND (title LIKE '%' || :query || '%' ESCAPE '!' OR body LIKE '%' || :query || '%' ESCAPE '!') ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun searchUserNotes(query:String,scope:String,limit:Int):List<NoteRecord>
    @Query("SELECT * FROM memories WHERE text LIKE '%' || :query || '%' ESCAPE '!' ORDER BY updatedAt DESC LIMIT :limit") suspend fun searchMemories(query:String,limit:Int):List<MemoryRecord>
    @Query("SELECT * FROM memories WHERE text=:text LIMIT 1") suspend fun exactMemory(text:String):MemoryRecord?
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT 500") suspend fun recentMemories():List<MemoryRecord>
    @Query("SELECT * FROM messages WHERE role='user' AND instr(lower(text),lower(:query))>0 AND NOT EXISTS (SELECT 1 FROM memories WHERE memories.sourceMessageId=messages.id) ORDER BY createdAt DESC LIMIT :limit") suspend fun sourceMessages(query:String,limit:Int):List<MessageRecord>
    @Query("DELETE FROM memories WHERE id=:id OR text=:text OR sourceMessageId IN (SELECT id FROM messages WHERE id=:sourceId OR instr(text,:text)>0)") suspend fun removeLinkedMemories(id:String,sourceId:String,text:String)
    @Query("SELECT * FROM memories WHERE id=:id") suspend fun memoryById(id:String):MemoryRecord?
    @Query("SELECT * FROM memories WHERE sourceMessageId=:id") suspend fun memoriesForMessage(id:String):List<MemoryRecord>
    @Query("SELECT * FROM memories WHERE kind='proposed'") suspend fun proposedMemories():List<MemoryRecord>
    @Query("SELECT * FROM memories WHERE id=:id OR text=:text OR sourceMessageId IN (SELECT id FROM messages WHERE id=:sourceId OR instr(text,:text)>0)") suspend fun memoriesForErasure(id:String,sourceId:String,text:String):List<MemoryRecord>
    @Query("SELECT id FROM messages WHERE id=:id OR instr(text,:text)>0") suspend fun messageIdsForErasure(id:String,text:String):List<String>
    @Query("SELECT * FROM notes WHERE id=:id") suspend fun noteById(id:String):NoteRecord?
    @Query("SELECT * FROM notes WHERE collection=:collection ORDER BY updatedAt DESC, id DESC LIMIT 1") suspend fun latestNote(collection:String):NoteRecord?
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
@Database(entities=[NoteRecord::class,MemoryRecord::class,ReminderRecord::class,MessageRecord::class,ToolRunRecord::class,GrantRecord::class,SummaryRecord::class,
    KnowledgeEntity::class,KnowledgeClaim::class,KnowledgeEvidence::class,KnowledgeTerm::class,KnowledgeTombstone::class,KnowledgeMeta::class,
    ProjectRecord::class,ChatThreadRecord::class,ChatBranchRecord::class,BranchMessageRef::class,ChatRunRecord::class,
    GenerationSegmentRecord::class,MessageChunkRecord::class,ChatDraftRecord::class,SourceScopeRecord::class,KnowledgeScopeRecord::class],version=3,exportSchema=true)
@TypeConverters(KnowledgeConverters::class,ChatConverters::class)
abstract class LocalDatabase:RoomDatabase(){abstract fun dao():LocalDao;abstract fun knowledgeDao():KnowledgeDao;abstract fun chatDao():ChatDao}

class LocalRepository internal constructor(private val database:LocalDatabase,private val access:ChatAccessScope?=null) {
    constructor(context:Context):this(DatabaseProvider.get(context.applicationContext))
    private val dao=database.dao()
    private val graph=KnowledgeStore(database)
    val chat=ChatStore(database)
    val workTrace by lazy {WorkTraceStore(WorkTraceRoomStorage(database))}
    private val chatDao=database.chatDao()
    private val scopeKey get()=access?.scopeKey?:"owner"
    suspend fun forThread(threadId:String):LocalRepository=LocalRepository(database,chat.accessScope(threadId))
    /** Atomically move only this chat's workflow goal; project notes and memories stay in place. */
    internal suspend fun moveThreadWithGoal(
        threadId:String,targetProjectId:String?,expectedThreadRevision:Long,expectedProjectId:String?,
        expectedGoalBody:String?,replacementGoalBody:String?,
    ):ChatThreadRecord=database.withTransaction {
        val before=chat.thread(threadId)?:error("대화가 삭제되었거나 존재하지 않습니다.")
        check(before.revision==expectedThreadRevision&&before.projectId==expectedProjectId){"이동 중 대화·프로젝트 상태가 변경되었습니다."}
        val id="chat-goal:$threadId"
        val note=dao.noteById(id)
        check(note?.body==expectedGoalBody){"이동할 목표 내용이 변경되었습니다. 다시 불러와주세요."}
        if(note!=null){
            check(note.collection=="chat-goals"&&replacementGoalBody!=null&&replacementGoalBody.length<=12000){"이동할 목표 저장 형식이 올바르지 않습니다."}
            val source=chatDao.sourceScope("note",id)?:error("목표의 저장 범위를 확인하지 못했습니다.")
            val originalScope=chat.accessScope(threadId)
            check(source.scopeKey==originalScope.scopeKey&&source.threadId==threadId){"다른 대화·프로젝트의 목표는 이동하지 않습니다."}
            val old=com.user.localagent.core.GoalDefinition.parse(note.body)
            val updated=com.user.localagent.core.GoalDefinition.parse(replacementGoalBody)
            val expectedCriteria=old.criteria.map{criterion->criterion.copy(
                status=if(criterion.status in setOf(com.user.localagent.core.GoalCriterionStatus.VERIFIED,com.user.localagent.core.GoalCriterionStatus.USER_CONFIRMED))com.user.localagent.core.GoalCriterionStatus.PENDING else criterion.status,
                evidence=emptyList())}
            check(old.threadId==threadId&&old.projectId==before.projectId&&updated==old.copy(
                projectId=targetProjectId,criteria=expectedCriteria,status=com.user.localagent.core.GoalStatus.DRAFT,linkedRunIds=emptyList(),updatedAt=updated.updatedAt)
                &&updated.updatedAt>old.updatedAt){"목표 이동은 원문·조건·식별자를 보존하고 기존 검증 연결만 초기화해야 합니다."}
        }else check(replacementGoalBody==null){"원래 목표가 없는데 새 목표 본문이 전달되었습니다."}
        val moved=chat.moveThread(threadId,targetProjectId,moveDerivedMemory=false)
        if(note!=null){
            val body=checkNotNull(replacementGoalBody)
            val targetScope=chat.accessScope(threadId)
            dao.note(note.copy(body=body,updatedAt=System.currentTimeMillis()))
            chatDao.sourceScope(SourceScopeRecord("note",id,targetScope.scopeKey,threadId))
            check(dao.noteById(id)?.body==body&&chatDao.sourceScope("note",id)?.scopeKey==targetScope.scopeKey){"이동한 목표를 다시 읽어 확인하지 못했습니다."}
        }
        check(chat.thread(threadId)?.projectId==targetProjectId){"대화 이동을 확인하지 못했습니다."}
        moved
    }
    private val credentialAssignment=Regex("(?:비밀번호|패스워드|api[ _-]?key|password|passcode|access[ _-]?token|refresh[ _-]?token|client[ _-]?secret|secret[ _-]?key|주민(?:등록)?번호|카드번호)[\\s\"']*[:：=]\\s*\\S",RegexOption.IGNORE_CASE)
    val notes=chatDao.notes(scopeKey);val memories=chatDao.memories(scopeKey);val reminders=chatDao.reminders(scopeKey)
    /** The explicit owner management screen may aggregate live projects; chat tools use scoped listReminders. */
    internal val remindersForOwnerManagement:Flow<List<ReminderRecord>> by lazy {check(access==null);chatDao.remindersForOwnerManagement()}
    val messages=if(access==null)dao.observeMessages()else chat.messages(access.threadId)
    val knowledge:Flow<List<KnowledgeClaim>> = chatDao.knowledge(scopeKey,access?.useOwnerMemory?:true).map {items->items.map(::displayKnowledge)}
    suspend fun saveKnowledgeClaim(record:KnowledgeClaim,expectedEpoch:Long?=null):KnowledgeClaim=database.withTransaction {
        verifyScopeLive();check(access?.memoryEnabled!=false){"이 프로젝트의 기억 저장이 꺼져 있습니다."}
        val resolved=resolveKnowledgeScope(record.sourceId)
        val prepared=if(resolved=="owner")record else record.copy(subject="$resolved|${record.subject}",objectSubject=record.objectSubject?.let {"$resolved|$it"})
        val result=graph.save(prepared,expectedEpoch)
        if(resolved!="owner")chatDao.knowledgeScope(KnowledgeScopeRecord(result.id,resolved))
        displayKnowledge(result)
    }
    suspend fun getKnowledge(id:String):KnowledgeClaim?=graph.get(id)?.takeIf {knowledgeAccessible(it.id)}?.let(::displayKnowledge)
    suspend fun getMessage(id:String):MessageRecord? {require(id.length in 1..100);return dao.messageById(id)?.takeIf {access==null||it.threadId==access.threadId}?.let {it.copy(text=chat.fullMessageText(it.id))}}
    suspend fun retrieveKnowledge(query:String,limit:Int=5):List<KnowledgeClaim> = scopedKnowledge(query,scopeKey,access?.useOwnerMemory?:true,limit)
    suspend fun approveKnowledge(id:String):KnowledgeClaim {check(knowledgeAccessible(id));return displayKnowledge(graph.approve(id))}
    suspend fun deleteKnowledge(id:String)=database.withTransaction {
        check(knowledgeAccessible(id)){"현재 범위의 기억이 아닙니다."}
        if(id.startsWith("memory.")){val memoryId=id.removePrefix("memory.");if(dao.memoryById(memoryId)!=null)forgetMemory(memoryId)}
        graph.delete(id);chatDao.deleteKnowledgeScope(id)
    }
    suspend fun clearKnowledge():Int=if(access==null)graph.clear()else database.withTransaction {chatDao.scopeClaims(scopeKey).also {items->items.forEach {graph.delete(it.id);chatDao.deleteKnowledgeScope(it.id)}}.size}
    suspend fun knowledgeEpoch():Long=graph.epoch()
    suspend fun knowledgeSourceAllowed(sourceKind:String,sourceId:String,@Suppress("UNUSED_PARAMETER") sourceUpdatedAt:Long=0):Boolean=graph.allowed(sourceKind,sourceId)
    suspend fun knowledgeEvidence(id:String):List<KnowledgeEvidence> = database.knowledgeDao().evidenceFor(id)
    suspend fun knowledgeMessagePage(after:MessageCursor?=null,limit:Int=100):MessagePage {
        require(limit in 1..200);require(after==null||after.id.length in 1..100)
        val binding=historyBinding()
        val found=if(binding==null)dao.knowledgeMessages(after?.createdAt,after?.id,limit+1)
            else dao.scopedKnowledgeMessages(binding.first,binding.second,after?.createdAt,after?.id,limit+1)
        val page=found.take(limit).map {it.copy(text=chat.fullMessageText(it.id))};verifyScopeLive()
        return MessagePage(page,if(found.size>limit)page.last().let {MessageCursor(it.createdAt,it.id)}else null)
    }
    suspend fun knowledgeNotePage(afterUpdatedAt:Long=0,afterId:String="",limit:Int=100):List<NoteRecord> {
        require(afterUpdatedAt>=0&&afterId.length<=100&&limit in 1..200);return dao.knowledgeNotes(afterUpdatedAt,afterId,limit)
    }
    /** Small quoted context, never a tool grant. Core approved identity/family/preferences precede relevant observations. */
    suspend fun ownerProfileContext(projectId:String?,query:String):String {
        if(projectId==null)return LocalRepository(database).ownerProfileContext(query)
        val project=chat.project(projectId)?:error("프로젝트를 찾을 수 없습니다.")
        if(project.settings.memoryMode=="off")return buildJsonObject {put("type","local_owner_knowledge_context");put("untrustedData",true);put("coreProfile",buildJsonArray{});put("relevantKnowledge",buildJsonArray{})}.toString()
        return LocalRepository(database,ChatAccessScope("",projectId,"project:$projectId",project.settings.useOwnerMemory)).ownerProfileContext(query)
    }
    suspend fun ownerProfileContext(query:String,includeCore:Boolean=true):String {
        require(query.length<=6000&&'\u0000' !in query)
        if(access?.memoryEnabled==false)return "{\"type\":\"local_owner_knowledge_context\",\"untrustedData\":true,\"coreProfile\":[],\"relevantKnowledge\":[]}"
        val emailQuery=Regex("(?i)메일|이메일|email|계정|\\baccount\\b").containsMatchIn(query)
        val phoneQuery=Regex("(?i)연락처|전화번호|휴대폰번호|\\bphone\\b|\\bcontact\\b").containsMatchIn(query)
        val addressQuery=Regex("(?i)(?:내|나의|우리|본인)\\s*(?:(?:집|자택|거주지|직장|회사|업무|사무실)\\s*)?주소|(?:집|자택|직장|회사|업무|사무실)\\s*주소|\\b(?:my|home|work|owner)\\s+address\\b").containsMatchIn(query)
        fun permitted(predicate:String,value:String):Boolean {
            if(Regex("(?i)password|비밀번호|api.?key|secret|access.?token|주민번호|card_number").containsMatchIn(predicate))return false
            if(credentialAssignment.containsMatchIn(value))return false
            val email=Regex("(?i)email|account|메일|계정").containsMatchIn(predicate)||Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|(?i)(?:이메일|메일|email)\\s*[:：=]").containsMatchIn(value)
            val phone=Regex("(?i)phone|mobile|전화|휴대폰").containsMatchIn(predicate)||Regex("(?:01[016789]|0\\d{1,2})[- ]?\\d{3,4}[- ]?\\d{4}|(?i)(?:전화|휴대폰|phone)\\s*[:：=]").containsMatchIn(value)
            val address=Regex("(?i)address|주소").containsMatchIn(predicate)||Regex("(?i)(?:주소|address)\\s*[:：=]").containsMatchIn(value)
            val genericContact=Regex("(?i)contact|연락").containsMatchIn(predicate)
            return (!email||emailQuery)&&(!phone||phoneQuery)&&(!address||addressQuery)&&(!genericContact||emailQuery||phoneQuery||addressQuery)
        }
        val candidates=if(!includeCore)emptyList()else chatDao.accessibleKnowledge(scopeKey,access?.useOwnerMemory?:true).map(::displayKnowledge).filter {claim->
            claim.status=="confirmed"&&("core" in claim.tags||claim.subject=="owner"&&Regex("^(?:preferred_name|name|display_name|occupation|profession|job|workplace|organization|role|family|spouse|partner|children|children_count|child|son|daughter|response_preference|response_style|language|preference|communication_preference)(?:[._].*)?$").matches(claim.predicate))&&permitted(claim.predicate,claim.value)
        }.sortedWith(compareBy<KnowledgeClaim> {when {it.predicate in setOf("preferred_name","name","display_name")->0;it.predicate in setOf("family.size","family_size","family.children","children_count","family.children_count","children.count")->1;Regex("family|children|child|spouse|partner|son|daughter|가족|자녀|배우자").containsMatchIn(it.predicate)->2;else->3}}.thenByDescending {it.updatedAt}).take(16)
        val family=candidates.filter {Regex("family|children|child|spouse|partner|son|daughter|가족|자녀|배우자").containsMatchIn(it.predicate)}.take(4)
        val preference=candidates.filter {Regex("preference|response|language|communication|선호|응답").containsMatchIn(it.predicate)}.take(2)
        val core=(family+preference+candidates).distinctBy {it.id}.take(8).toMutableList()
        val relevant=retrieveKnowledge(query.take(2000),5).filter {item->core.none {it.id==item.id}&&permitted(item.predicate,item.value)}.take(4).toMutableList()
        val existingValues=(core+relevant).map {it.value}.toSet()
        val legacy=if(!includeCore)mutableListOf()else searchMemories(query,3).filter {it.text !in existingValues&&permitted("legacy_memory",it.text)&&graph.allowed("approved_memory",it.id)}.take(2).toMutableList()
        fun claim(item:KnowledgeClaim)=buildJsonObject {
            put("subject",item.subject.take(48));item.objectSubject?.let {put("relatedSubject",it.take(48))};put("predicate",item.predicate.take(64));put("value",item.value.take(140))
            put("status",item.status);put("confidence",item.confidence);put("sourceKind",item.sourceKind.take(32))
        }
        fun render()=buildJsonObject {
            put("type","local_owner_knowledge_context");put("untrustedData",true);put("modelWeightsLearned",false)
            put("coreProfile",buildJsonArray {core.forEach {add(claim(it))}})
            put("relevantKnowledge",buildJsonArray {relevant.forEach {add(claim(it))}})
            put("legacyMemories",buildJsonArray {legacy.forEach {add(buildJsonObject {put("id",it.id.take(80));put("text",it.text.take(180));put("kind",it.kind.take(40))})}})
        }.toString()
        var result=render()
        while(result.length>2800&&(legacy.isNotEmpty()||relevant.isNotEmpty()||core.size>1)){
            when {legacy.isNotEmpty()->legacy.removeAt(legacy.lastIndex);relevant.isNotEmpty()->relevant.removeAt(relevant.lastIndex);else->core.removeAt(core.lastIndex)}
            result=render()
        }
        return result
    }
    /** Scoped views read only their captured live branch. The base repository is an explicit admin view. */
    suspend fun messagePage(before:MessageCursor?=null,limit:Int=100,query:String=""):MessagePage=withContext(Dispatchers.IO) {
        require(limit in 1..200);require(query.length<=500 && '\u0000' !in query)
        require(before==null || before.id.length in 1..100)
        val binding=historyBinding()
        if(query.isEmpty()){
            val found=rawHistoryPage(binding,before,limit+1)
            val page=found.take(limit).map {it.withText(chat.fullMessageText(it.id))};verifyScopeLive()
            return@withContext MessagePage(page,if(found.size>limit)page.last().let {MessageCursor(it.createdAt,it.id)}else null)
        }
        val matched=mutableListOf<MessageRecord>();var scan=before
        while(matched.size<=limit){
            verifyScopeLive();val metadata=rawHistoryPage(binding,scan,200)
            if(metadata.isEmpty())break
            for(record in metadata){
                val fullText=chat.fullMessageText(record.id)
                if(fullText.contains(query,ignoreCase=true))matched+=record.withText(fullText)
                if(matched.size>limit)break
            }
            if(matched.size>limit||metadata.size<200)break
            scan=metadata.last().let {MessageCursor(it.createdAt,it.id)}
        }
        verifyScopeLive();val page=matched.take(limit)
        MessagePage(page,if(matched.size>limit)page.last().let {MessageCursor(it.createdAt,it.id)}else null)
    }
    suspend fun messageCount(query:String=""):Long=withContext(Dispatchers.IO) {
        require(query.length<=500 && '\u0000' !in query);val binding=historyBinding()
        if(query.isEmpty()){
            val count=if(binding==null)dao.messageCount("")else dao.scopedMessageCount(binding.first,binding.second)
            verifyScopeLive();return@withContext count
        }
        var count=0L;var scan:MessageCursor?=null
        while(true){
            verifyScopeLive();val metadata=rawHistoryPage(binding,scan,200)
            if(metadata.isEmpty())break
            for(record in metadata)if(chat.fullMessageText(record.id).contains(query,ignoreCase=true))count++
            if(metadata.size<200)break
            scan=metadata.last().let {MessageCursor(it.createdAt,it.id)}
        }
        verifyScopeLive();count
    }
    private suspend fun historyBinding():Pair<String,String>? {
        verifyScopeLive();val scope=access?:return null
        require(scope.threadId.isNotBlank()){"대화 기록에는 명시적인 대화방 연결이 필요합니다."}
        val thread=chat.thread(scope.threadId)?:error("대화방을 찾을 수 없습니다.")
        return thread.id to thread.activeBranchId
    }
    private suspend fun rawHistoryPage(binding:Pair<String,String>?,before:MessageCursor?,limit:Int):List<MessageMetadataRecord> {
        if(binding==null)return dao.messageMetadataHistory(before?.createdAt,before?.id,limit)
        val position=before?.let {cursor->
            val source=dao.messageMetadataById(cursor.id)?:error("기록 커서의 원문이 삭제되었습니다.")
            check(source.threadId==binding.first&&source.createdAt==cursor.createdAt){"현재 대화방의 기록 커서가 아닙니다."}
            chatDao.position(binding.second,cursor.id)?:error("현재 대화 분기의 기록 커서가 아닙니다.")
        }
        return dao.scopedMessageMetadataHistory(binding.first,binding.second,position,limit)
    }
    /** Metadata projection deliberately excludes tool summaries and arguments. */
    suspend fun toolAuditMetadata(start:Long,end:Long,limit:Int=1000):List<ToolAuditMetadataRecord> {
        require(end>=start && limit in 1..1001);return dao.toolAuditMetadata(start,end,limit)
    }
    suspend fun addMessage(role:String,text:String):String {
        val target=access?.let {chat.thread(it.threadId)?:error("대화방을 찾을 수 없습니다.")}?:chat.ensureLegacyThread()
        return chat.addMessage(target.id,target.activeBranchId,role,text).id
    }
    suspend fun deletePrivateCallFollowUpReportIfUnchanged(threadId:String,publicationId:String,expectedBodySha256:String):Boolean=database.withTransaction {
        verifyScopeLive();check(access==null||access.threadId==threadId)
        require(expectedBodySha256.matches(Regex("[a-f0-9]{64}")))
        if(database.chatDao().metaValue("call-followup.publication.$publicationId")!=1L)return@withTransaction false
        val message=database.dao().messageById(publicationId)?:return@withTransaction false
        if(message.threadId!=threadId||message.role!="assistant"||message.runId!=null)return@withTransaction false
        val body=chat.fullMessageText(publicationId)
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        if(digest!=expectedBodySha256)return@withTransaction false
        deleteMessage(publicationId);true
    }
    /** Only a just-inserted, unchanged host publication can become replayable after cancellation. */
    suspend fun rollbackPrivateCallFollowUpReportIfUnchanged(threadId:String,publicationId:String,expectedBodySha256:String):Boolean=database.withTransaction {
        verifyScopeLive();check(access==null||access.threadId==threadId)
        require(expectedBodySha256.matches(Regex("[a-f0-9]{64}")))
        val marker="call-followup.publication.$publicationId"
        if(chatDao.metaValue(marker)!=1L)return@withTransaction false
        val message=dao.messageById(publicationId)?:return@withTransaction false
        if(message.threadId!=threadId||message.role!="assistant"||message.runId!=null)return@withTransaction false
        val body=chat.fullMessageText(publicationId)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        if(hash!=expectedBodySha256)return@withTransaction false
        deleteMessage(publicationId)
        database.knowledgeDao().meta(KnowledgeMeta("call-followup.rollback.$publicationId.$threadId.$hash",1))
        database.knowledgeDao().meta(KnowledgeMeta("call-followup.publication-scope.$publicationId.$threadId",1))
        database.knowledgeDao().meta(KnowledgeMeta(marker,2));true
    }
    suspend fun deleteMessage(id:String)=database.withTransaction {
        verifyScopeLive()
        val existing=dao.messageById(id)
        check(access==null||existing?.threadId==access.threadId||existing==null&&
            chatDao.metaValue("call-followup.thread.${access.threadId}")==1L&&
            chatDao.metaValue("call-followup.publication-scope.$id.${access.threadId}")==1L)
        if(chatDao.metaValue("call-followup.publication.$id")!=null)
            database.knowledgeDao().meta(KnowledgeMeta("call-followup.publication.$id",3))
        dao.memoriesForMessage(id).forEach {graph.eraseSource(it.id)};graph.eraseSource(id);dao.deleteMessageMemories(id)
        chatDao.deleteChunks(id);chatDao.deleteSegments(id);chatDao.deleteRefs(id);chatDao.deleteSourceScope("message",id);dao.deleteMessage(id);dao.invalidateSummaries()
    }
    /** Host-only adapter: same scoped Room transaction owns CAS, actual readback and durable receipt. */
    internal fun notePatchTransactions(binding:com.user.localagent.core.NoteReadBinding):com.user.localagent.notes.NotePatchTransactions=
        object:com.user.localagent.notes.NotePatchTransactions {
            override suspend fun <T> transaction(requireRunning:Boolean,block:suspend com.user.localagent.notes.NotePatchTransaction.()->T):T=database.withTransaction {
                check(access!=null&&access.threadId==binding.threadId&&scopeKey==binding.scopeKey)
                verifyScopeLive();check(chat.accessScope(binding.threadId)==access){"NOTE_ACCESS_CHANGED"}
                val run=checkNotNull(chat.run(binding.runId))
                check(run.threadId==binding.threadId&&run.branchId==binding.branchId&&run.projectId==access.projectId){"NOTE_RUN_BINDING_CHANGED"}
                if(requireRunning)check(run.status=="running"){"NOTE_RUN_STOPPED"}
                fun source(n:NoteRecord)=com.user.localagent.core.NoteSource(n.id,scopeKey,n.collection,n.title,n.body,n.updatedAt)
                block(object:com.user.localagent.notes.NotePatchTransaction {
                    override suspend fun note(id:String):com.user.localagent.core.NoteSource? {
                        val n=dao.noteById(id)?:return null
                        checkResource("note",id);check(n.collection !in com.user.localagent.core.InternalNoteCollections.protected)
                        return source(n)
                    }
                    override suspend fun putPreservingMetadata(before:com.user.localagent.core.NoteSource,body:String):com.user.localagent.core.NoteSource {
                        val n=checkNotNull(dao.noteById(before.id));check(source(n)==before){"NOTE_CAS_CONFLICT"}
                        checkResource("note",n.id);check(n.collection !in com.user.localagent.core.InternalNoteCollections.protected)
                        val updated=n.copy(body=body,updatedAt=nextResourceRevision(n.updatedAt));dao.note(updated);return source(updated)
                    }
                    override suspend fun receipt(operationId:String):String? {
                        val stored=dao.toolRunById(operationId)?:return null
                        check(stored.name==com.user.localagent.notes.NOTE_PATCH_RECEIPT&&stored.success&&stored.runId==binding.runId&&stored.threadId==binding.threadId){"NOTE_RECEIPT_BINDING_CHANGED"}
                        return stored.summary
                    }
                    override suspend fun putReceipt(operationId:String,json:String) {
                        check(operationId.matches(Regex("note-patch-[a-f0-9]{64}"))&&dao.toolRunById(operationId)==null)
                        com.user.localagent.notes.NotePatchReceipt.parse(json)
                        dao.toolRun(ToolRunRecord(id=operationId,name=com.user.localagent.notes.NOTE_PATCH_RECEIPT,success=true,summary=json,runId=binding.runId,threadId=binding.threadId))
                    }
                })
            }
        }

    suspend fun saveNote(title:String,body:String,collection:String="default",id:String?=null,expectedUpdatedAt:Long?=null):NoteRecord=database.withTransaction {
        verifyScopeLive();require(body.isNotBlank()&&body.length<=12000)
        val previous=id?.let {dao.noteById(it)}
        if(previous!=null)checkResource("note",previous.id)
        if(expectedUpdatedAt!=null)check(previous?.updatedAt==expectedUpdatedAt){"확인한 메모가 변경되었거나 삭제되어 덮어쓰지 않았습니다."}
        val revision=nextResourceRevision(previous?.updatedAt)
        val record=NoteRecord(id?:UUID.randomUUID().toString(),title.take(300),body,collection.take(80),revision);dao.note(record)
        bindResource("note",record.id);record
    }
    suspend fun searchNotes(query:String)=chatDao.searchNotes(scopeKey,query.take(500),50)
    suspend fun searchUserNotes(query:String,scope:String="",limit:Int=5)=chatDao.searchUserNotes(scopeKey,query.take(500),scope.takeIf {it.isNotBlank()}.orEmpty(),com.user.localagent.core.InternalNoteCollections.protected.toList(),limit.coerceIn(1,5))
    suspend fun latestNote(collection:String):NoteRecord? {require(collection.length<=80);return chatDao.searchNotes(scopeKey,"",2000).firstOrNull {it.collection==collection}}
    internal suspend fun saveApprovedMemory(text:String,kind:String):MemoryRecord {
        require(approvedMemoryKind(kind)){"기억 유형은 사실, 선호 또는 정정이어야 합니다."}
        return saveMemory(text,kind)
    }
    suspend fun saveMemory(text:String,kind:String="fact",sourceMessageId:String?=null):MemoryRecord {
        require(text.isNotBlank()&&text.length<=4000);require(kind.isNotBlank()&&kind.length<=80)
        return database.withTransaction {
            check(memoryAccessEnabled()){"이 대화에서는 기억 저장이 꺼져 있습니다."}
            val previous=chatDao.recentMemories(scopeKey).firstOrNull {it.text==text}
            val record=when {
                previous==null->MemoryRecord(text=text,kind=kind,sourceMessageId=sourceMessageId).also {dao.memory(it)}
                !approvedMemoryKind(previous.kind)&&approvedMemoryKind(kind)->previous.copy(kind=kind,sourceMessageId=sourceMessageId?:previous.sourceMessageId,updatedAt=System.currentTimeMillis()).also {dao.memory(it)}
                else->previous
            }
            bindResource("memory",record.id,sourceMessageId);syncMemoryKnowledge(record);record
        }
    }
    /** An explicit user correction; preserve provenance, replace the approved text, invalidate derived summaries. */
    suspend fun editMemory(id:String,text:String,kind:String="correction"):MemoryRecord = database.withTransaction {
        require(text.isNotBlank()&&text.length<=4000);require(kind.isNotBlank()&&kind.length<=80)
        val previous=dao.memoryById(id)?:error("기억을 찾을 수 없습니다.")
        checkResource("memory",id)
        val corrected=previous.copy(text=text,kind=kind,updatedAt=System.currentTimeMillis())
        dao.memory(corrected);syncMemoryKnowledge(corrected);dao.invalidateSummaries();corrected
    }
    /** Literal stored user text only. Source-linked messages are excluded in favor of their current approved memory. */
    suspend fun sourceMessages(query:String,limit:Int=3):List<MessageRecord> {
        val literal=query.trim().take(500)
        return if(literal.isBlank())emptyList() else dao.sourceMessages(literal,50).filter {message->
            val source=chatDao.sourceScope("message",message.id)?.scopeKey?:"owner"
            if(access==null)source=="owner"else message.threadId==access.threadId
        }.take(limit.coerceIn(1,5))
    }
    suspend fun discardProposedMemory(id:String):Boolean=database.withTransaction {
        val removed=dao.discardProposed(id)>0
        if(removed){graph.eraseSource(id);dao.invalidateSummaries()}
        removed
    }
    suspend fun discardProposedMemories():Int=database.withTransaction {
        val proposed=dao.proposedMemories();dao.discardAllProposed().also {if(it>0){proposed.forEach {memory->graph.eraseSource(memory.id)};dao.invalidateSummaries()}}
    }
    /** Bounded lexical retrieval, not embeddings or generated semantic search. */
    suspend fun searchMemories(query:String,limit:Int=5):List<MemoryRecord> = withContext(Dispatchers.Default) {
        val literal=query.trim().take(500)
        val maximum=limit.coerceIn(1,5)
        val accessible=database.withTransaction {
            if(!memoryAccessEnabled())return@withTransaction emptyList()
            val own=chatDao.recentMemories(scopeKey)
            if(scopeKey!="owner"&&access?.useOwnerMemory==true)
                (own+chatDao.recentMemories("owner")).sortedByDescending {it.updatedAt}
            else own
        }
        val approved=accessible.filter {approvedMemoryKind(it.kind)}
        if(literal.isBlank())return@withContext approved.take(maximum)
        val exact=approved.filter {it.text.contains(literal,ignoreCase=true)}.take(maximum)
        if(exact.isNotEmpty())return@withContext exact
        val queryTokens=lexicalTokens(literal)
        val queryPairs=characterPairs(literal)
        approved.asSequence().mapNotNull {memory ->
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
        checkResource("memory",id)
        dao.memoryById(id)?.let {memory ->
            if(access!=null){graph.eraseSource(id);dao.deleteMemory(id);chatDao.deleteSourceScope("memory",id);dao.invalidateSummaries();return@withTransaction}
            // Legacy owner-wide forgetting may remove literal owner-source copies, but never
            // crosses an explicit project/thread source boundary merely because text matches.
            val ownerMessages=dao.messageIdsForErasure(memory.sourceMessageId?:"",memory.text).filter {messageId->
                (chatDao.sourceScope("message",messageId)?.scopeKey?:"owner")=="owner"
            }.toSet()
            val ownerMemories=dao.memoriesForErasure(id,memory.sourceMessageId?:"",memory.text).filter {candidate->
                (chatDao.sourceScope("memory",candidate.id)?.scopeKey?:"owner")=="owner"&&
                    (candidate.id==id||candidate.text==memory.text||candidate.sourceMessageId in ownerMessages)
            }
            ownerMemories.forEach {candidate->graph.eraseSource(candidate.id);dao.deleteMemory(candidate.id);chatDao.deleteSourceScope("memory",candidate.id)}
            ownerMessages.mapNotNull {dao.messageById(it)?.threadId}.distinct().forEach {chat.cancelRuns(it,"owner_memory_erased")}
            ownerMessages.forEach {messageId->
                graph.eraseSource(messageId)
                chatDao.deleteChunks(messageId);chatDao.deleteSegments(messageId);chatDao.deleteRefs(messageId)
                chatDao.deleteSourceScope("message",messageId);dao.deleteMessage(messageId)
            }
            dao.invalidateSummaries()
        }
    }
    suspend fun saveReminder(title:String,atEpochMillis:Long,group:String="default",id:String?=null,expectedUpdatedAt:Long?=null):ReminderRecord = database.withTransaction {
        verifyScopeLive();require(title.isNotBlank())
        val previous=id?.let {dao.reminderById(it)}
        if(previous!=null)checkResource("reminder",previous.id)
        if(expectedUpdatedAt!=null)check(previous?.updatedAt==expectedUpdatedAt){"확인한 리마인더가 변경되었거나 삭제되어 덮어쓰지 않았습니다."}
        val revision=nextResourceRevision(previous?.updatedAt)
        val record=ReminderRecord(id?:UUID.randomUUID().toString(),title.take(300),atEpochMillis,group.take(80),updatedAt=revision)
        dao.reminder(record);chatDao.sourceScope(SourceScopeRecord("reminder",record.id,scopeKey,access?.threadId));record
    }
    suspend fun listReminders(scope:String="",limit:Int=20,includeCompleted:Boolean=false):List<ReminderRecord> = database.withTransaction {
        verifyScopeLive();require(scope.length<=80&&'\u0000' !in scope);require(limit in 1..50)
        chatDao.listReminders(scopeKey,scope,limit,includeCompleted)
    }
    suspend fun setReminderStatus(id:String,status:String)=database.withTransaction {if(dao.reminderById(id)!=null){checkResource("reminder",id);dao.reminderStatus(id,status)}}
    suspend fun claimReminder(id:String)=database.withTransaction {if(dao.reminderById(id)==null)false else {checkResource("reminder",id);dao.claimReminder(id)==1}}
    suspend fun claimReminder(record:ReminderRecord)=database.withTransaction {if(dao.reminderById(record.id)==null)false else {checkResource("reminder",record.id);dao.claimReminderVersion(record.id,record.atEpochMillis,record.updatedAt,System.currentTimeMillis())==1}}
    suspend fun finishReminder(record:ReminderRecord,status:String)=database.withTransaction {if(dao.reminderById(record.id)==null)false else {checkResource("reminder",record.id);dao.finishReminderVersion(record.id,record.atEpochMillis,record.updatedAt,status)==1}}
    internal suspend fun recoverInterruptedReminders():Int {check(access==null);return dao.recoverDelivering(System.currentTimeMillis())}
    suspend fun pendingReminders():List<ReminderRecord> = database.withTransaction {verifyScopeLive();chatDao.pendingReminders(scopeKey)}
    suspend fun getReminder(id:String):ReminderRecord? = database.withTransaction {val value=dao.reminderById(id)?:return@withTransaction null;checkResource("reminder",id);value}
    suspend fun getNote(id:String):NoteRecord? = database.withTransaction {val value=dao.noteById(id)?:return@withTransaction null;checkResource("note",id);value}
    suspend fun getMemory(id:String):MemoryRecord? = database.withTransaction {val value=dao.memoryById(id)?:return@withTransaction null;checkResource("memory",id);value}
    internal suspend fun getReminderForDelivery(id:String):ReminderRecord? = database.withTransaction {
        check(access==null)
        val value=dao.reminderById(id)?:return@withTransaction null
        val stored=chatDao.sourceScope("reminder",id)?.scopeKey?:"owner"
        val live=when {
            stored=="owner"->true
            stored.startsWith("project:")->chatDao.project(stored.removePrefix("project:"))!=null
            stored.startsWith("thread:")->chatDao.thread(stored.removePrefix("thread:"))?.let {it.deletedAt==null}==true
            else->false
        }
        value.takeIf {live}
    }
    internal suspend fun pendingRemindersForDelivery():List<ReminderRecord> {check(access==null);return chatDao.pendingRemindersForDelivery()}
    internal suspend fun claimReminderForDelivery(record:ReminderRecord):Boolean=database.withTransaction {
        check(access==null)
        if(getReminderForDelivery(record.id)==null)false else dao.claimReminderVersion(record.id,record.atEpochMillis,record.updatedAt,System.currentTimeMillis())==1
    }
    internal suspend fun finishReminderForDelivery(record:ReminderRecord,status:String):Boolean=database.withTransaction {
        check(access==null)
        if(getReminderForDelivery(record.id)==null)false else dao.finishReminderVersion(record.id,record.atEpochMillis,record.updatedAt,status)==1
    }
    internal suspend fun reminderIdsForProjectRemoval(projectId:String):List<String> = database.withTransaction {
        check(access==null);chatDao.reminderIdsInScopes(chatDao.projectThreadScopeKeys(projectId)+"project:$projectId")
    }
    internal suspend fun reminderIdsForThreadRemoval(threadId:String):List<String> {check(access==null);return chatDao.reminderIdsInScopes(listOf("thread:$threadId"))}
    /** Rollback only the exact image metadata receipt created by this execution. */
    suspend fun deleteGeneratedImageIfUnchanged(expected:NoteRecord):Boolean=database.withTransaction {
        require(expected.collection=="generated-images")
        checkResource("note",expected.id)
        val current=dao.noteById(expected.id)?:return@withTransaction true
        if(current!=expected)return@withTransaction false
        graph.eraseSource(expected.id);dao.deleteNote(expected.id);chatDao.deleteSourceScope("note",expected.id)
        true
    }
    suspend fun deleteNote(id:String,expectedUpdatedAt:Long?=null)=database.withTransaction {
        val previous=dao.noteById(id)
        if(previous!=null)checkResource("note",id)
        if(expectedUpdatedAt!=null)check(previous?.updatedAt==expectedUpdatedAt){"확인한 메모가 변경되었거나 삭제되어 지우지 않았습니다."}
        if(previous!=null){if(previous.collection!="planning-feedback")graph.eraseSource(id);dao.deleteNote(id);chatDao.deleteSourceScope("note",id)}
    }
    suspend fun deleteReminder(id:String,expectedUpdatedAt:Long?=null)=database.withTransaction {
        val previous=dao.reminderById(id)
        if(previous!=null)checkResource("reminder",id)
        if(expectedUpdatedAt!=null)check(previous?.updatedAt==expectedUpdatedAt){"확인한 리마인더가 변경되었거나 삭제되어 지우지 않았습니다."}
        if(previous!=null){dao.deleteReminder(id);chatDao.deleteSourceScope("reminder",id)}
    }
    suspend fun recordToolRun(name:String,success:Boolean,summary:String,runId:String?=null,threadId:String?=null){
        recordToolRunEvidence(name,success,summary,runId,threadId)
    }
    suspend fun recordToolRunEvidence(name:String,success:Boolean,summary:String,runId:String?=null,threadId:String?=null):ToolRunRecord=database.withTransaction {
        require(name !in setOf(WorkTraceStore.ROW_NAME,WorkTraceReceiptCodec.ROW_NAME)){"진행 기록과 실행 영수증은 전용 저장 경로를 사용해야 합니다."}
        if(runId!=null){val run=chat.run(runId)?:error("감사 기록의 작업을 찾을 수 없습니다.");check(threadId==run.threadId){"감사 기록의 대화방이 일치하지 않습니다."}}
        val record=ToolRunRecord(name=name,success=success,summary=WorkTraceCodec.clip(summary,200),runId=runId,threadId=threadId?:access?.threadId)
        dao.toolRun(record)
        check(dao.toolRunById(record.id)==record){"감사 기록 저장을 다시 확인하지 못했습니다."}
        record
    }
    /** Exact lookup has no timeline window and never treats progress as tool-completion proof. */
    suspend fun toolRunEvidence(id:String):ToolRunRecord? {
        require(id.isNotBlank()&&id.length<=128&&id.none{it.code<32}){"검증 근거 식별자가 올바르지 않습니다."}
        verifyScopeLive()
        val record=dao.toolRunById(id)?:return null
        check(record.name !in setOf("work_trace_v1","work_receipt_v1",com.user.localagent.notes.NOTE_PATCH_RECEIPT)){"진행 기록은 작업 완료의 검증 근거가 아닙니다."}
        check(access==null||record.threadId==access.threadId){"다른 대화방의 실행 근거입니다."}
        return record
    }
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
    private suspend fun syncMemoryKnowledge(memory:MemoryRecord) {
        val approved=approvedMemoryKind(memory.kind)
        saveKnowledgeClaim(KnowledgeClaim(id="memory.${memory.id}",predicate="memory.${memory.id}",value=memory.text,
            status=if(approved)"confirmed"else"proposed",confidence=if(approved)1.0 else 0.5,
            sourceKind=if(approved)"approved_memory"else"memory",sourceId=memory.id,sourceExcerpt=memory.text.take(1000),updatedAt=memory.updatedAt,tags=listOf("legacy_memory",memory.kind)))
    }
    /** Host-only append and compare-and-set pointer. No model-selected collection or raw SQL. */
    suspend fun commitPrivateAnalysisCheckpoint(rootId:String,expectedRootSha256:String?,rootBody:String,stepId:String?,stepBody:String?)=database.withTransaction {
        verifyScopeLive()
        val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        require(uuid.matches(rootId)&&rootBody.length in 1..12000&&(stepId==null)==(stepBody==null))
        fun digest(body:String)=java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
        val root=dao.noteById(rootId)
        if(root!=null){checkResource("note",rootId);require(root.collection=="private-analysis"&&expectedRootSha256!=null&&digest(root.body)==expectedRootSha256)}
        else require(expectedRootSha256==null)
        val meta=kotlinx.serialization.json.Json.parseToJsonElement(rootBody).jsonObject
        require(meta["kind"]?.jsonPrimitive?.content=="private_analysis_checkpoint_v1"&&meta["id"]?.jsonPrimitive?.content==rootId&&meta["scopeId"]?.jsonPrimitive?.content==scopeKey)
        val previous=root?.body?.let {kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject}
        if(previous!=null)listOf("kind","id","scopeId","source","sourceLocator","question","modelSha256","skillSha256","maxChunkBytes","overlapBytes").forEach {key->require(meta[key]==previous[key])}
        val previousCount=previous?.get("stepCount")?.jsonPrimitive?.int?:0
        if(stepId!=null){
            require(uuid.matches(stepId)&&stepId!=rootId&&stepBody!!.length in 1..12000&&dao.noteById(stepId)==null)
            val step=kotlinx.serialization.json.Json.parseToJsonElement(stepBody).jsonObject
            require(step["kind"]?.jsonPrimitive?.content=="private_analysis_step_v1"&&step["analysisId"]?.jsonPrimitive?.content==rootId&&step["scopeId"]?.jsonPrimitive?.content==scopeKey)
            require(meta["lastId"]?.jsonPrimitive?.content==stepId&&meta["lastSha256"]?.jsonPrimitive?.content==digest(stepBody))
            require(meta["stepCount"]?.jsonPrimitive?.int==previousCount+1&&step["index"]?.jsonPrimitive?.int==previousCount)
            require(step["previousId"]==(previous?.get("lastId")?:kotlinx.serialization.json.JsonNull))
            require(step["previousSha256"]==(previous?.get("lastSha256")?:kotlinx.serialization.json.JsonNull))
            dao.note(NoteRecord(stepId,"분석 근거 조각",stepBody,"private-analysis"));bindResource("note",stepId)
        }else require(meta["stepCount"]?.jsonPrimitive?.int==previousCount&&meta["lastId"]==(previous?.get("lastId")?:kotlinx.serialization.json.JsonNull)&&meta["lastSha256"]==(previous?.get("lastSha256")?:kotlinx.serialization.json.JsonNull))
        dao.note(NoteRecord(rootId,"개인 자료 분석 진행",rootBody,"private-analysis"));bindResource("note",rootId)
        check(dao.noteById(rootId)?.body==rootBody)
    }

    /** Host-only rollback of exact unchanged outputs from one unpublished SMS capture. */
    internal suspend fun discardIncompletePrivateSourceNotes(
        snapshotId:String,expected:List<com.user.localagent.analysis.IncompletePrivateSourceNote>,
    ):com.user.localagent.analysis.PrivateSourceCleanupResult=database.withTransaction {
        val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        require(snapshotId.matches(uuid)&&expected.size<=4000&&expected.map {it.noteId}.distinct().size==expected.size)
        expected.forEach {require(it.noteId.matches(uuid)&&it.bodySha256.matches(Regex("[a-f0-9]{64}")))}
        var result=com.user.localagent.analysis.PrivateSourceCleanupResult()
        for(item in expected){
            val note=dao.noteById(item.noteId)
            if(note==null){result+=com.user.localagent.analysis.PrivateSourceCleanupResult(missing=1);continue}
            val source=chatDao.sourceScope("note",item.noteId)
            if((source?.scopeKey?:"owner")!=scopeKey||(source!=null&&access!=null&&source.threadId!=access.threadId)){
                result+=com.user.localagent.analysis.PrivateSourceCleanupResult(retainedUnverified=1);continue
            }
            val header=note.body.substringBefore('\n')
            val owned=note.collection==com.user.localagent.analysis.LocalPrivateAnalysisSources.SOURCE_COLLECTION&&runCatching {
                val body=Json.parseToJsonElement(header).jsonObject
                body["schemaVersion"]?.jsonPrimitive?.intOrNull==1&&body["snapshotId"]?.jsonPrimitive?.contentOrNull==snapshotId&&
                    body["kind"]?.jsonPrimitive?.contentOrNull in setOf("sms_snapshot","sms_snapshot_page","sms_snapshot_chunk")
            }.getOrDefault(false)
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(note.body.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
            if(!owned||digest!=item.bodySha256){result+=com.user.localagent.analysis.PrivateSourceCleanupResult(retainedChanged=1);continue}
            graph.eraseSource(item.noteId);dao.deleteNote(item.noteId);chatDao.deleteSourceScope("note",item.noteId)
            check(dao.noteById(item.noteId)==null){"미완성 문자 분석 자료 삭제를 확인하지 못했습니다."}
            result+=com.user.localagent.analysis.PrivateSourceCleanupResult(deleted=1)
        }
        result
    }

    /** Host-only rollback of this transcription invocation's exact unchanged outputs.
     * Uses the repository's captured scope even after a thread revision/move invalidates ordinary
     * access. No model tool exposes this method. Callers pass only IDs+hashes computed before save.
     */
    internal suspend fun discardIncompleteAudioNotes(
        transcriptId:String,
        expected:List<com.user.localagent.audio.IncompleteAudioNote>,
    ):com.user.localagent.audio.AudioCleanupResult=database.withTransaction {
        val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        require(transcriptId.matches(uuid)&&expected.size<=512&&expected.map {it.noteId}.distinct().size==expected.size)
        expected.forEach {require(it.noteId.matches(uuid)&&it.bodySha256.matches(Regex("[a-f0-9]{64}")))}
        var result=com.user.localagent.audio.AudioCleanupResult()
        for(item in expected) {
            val note=dao.noteById(item.noteId)
            if(note==null){result+=com.user.localagent.audio.AudioCleanupResult(missing=1);continue}
            val source=chatDao.sourceScope("note",item.noteId)
            if((source?.scopeKey?:"owner")!=scopeKey ||
                (source!=null&&access!=null&&source.threadId!=access.threadId)) {
                result+=com.user.localagent.audio.AudioCleanupResult(retainedUnverified=1);continue
            }
            val owned=when(note.collection) {
                "audio-transcripts"->note.body.startsWith("전사 자료 $transcriptId · ")
                com.user.localagent.audio.RecordingTranscriptManifest.COLLECTION->runCatching {
                    val body=Json.parseToJsonElement(note.body).jsonObject
                    body["schemaVersion"]?.jsonPrimitive?.intOrNull==1&&
                        body["kind"]?.jsonPrimitive?.contentOrNull in setOf("recording_transcript","recording_transcript_page")&&
                        body["transcriptId"]?.jsonPrimitive?.contentOrNull==transcriptId
                }.getOrDefault(false)
                else->false
            }
            if(!owned||com.user.localagent.audio.RecordingTranscriptManifest.sha256(note.body)!=item.bodySha256) {
                result+=com.user.localagent.audio.AudioCleanupResult(retainedChanged=1);continue
            }
            graph.eraseSource(item.noteId)
            dao.deleteNote(item.noteId);chatDao.deleteSourceScope("note",item.noteId)
            check(dao.noteById(item.noteId)==null){"미완성 전사 자료 삭제를 확인하지 못했습니다."}
            result+=com.user.localagent.audio.AudioCleanupResult(deleted=1)
        }
        result
    }

    /** Host-only rollback of exact outputs captured before an interrupted scoped file import.
     * Uses the repository's captured scope/thread even after its ordinary revision check fails.
     * No tool or selected file supplies these expected IDs or hashes.
     */
    internal suspend fun discardIncompleteConversationNotes(
        importRun:String,
        expected:List<com.user.localagent.imports.IncompleteConversationNote>,
    ):com.user.localagent.imports.ConversationCleanupResult=database.withTransaction {
        com.user.localagent.imports.ConversationImportCleanup.validateExpected(importRun,expected)
        var result=com.user.localagent.imports.ConversationCleanupResult()
        for(item in expected) {
            val note=dao.noteById(item.noteId)
            if(note==null){result+=com.user.localagent.imports.ConversationCleanupResult(missing=1);continue}
            val source=chatDao.sourceScope("note",item.noteId)
            if((source?.scopeKey?:"owner")!=scopeKey ||
                (access!=null&&(source==null||source.threadId!=access.threadId))) {
                result+=com.user.localagent.imports.ConversationCleanupResult(retainedUnverified=1);continue
            }
            if(!com.user.localagent.imports.ConversationImportCleanup.owns(note.collection,note.body,importRun,item)) {
                result+=com.user.localagent.imports.ConversationCleanupResult(retainedChanged=1);continue
            }
            graph.eraseSource(item.noteId)
            dao.deleteNote(item.noteId);chatDao.deleteSourceScope("note",item.noteId)
            check(dao.noteById(item.noteId)==null&&chatDao.sourceScope("note",item.noteId)==null){"미완성 대화 자료 삭제를 확인하지 못했습니다."}
            result+=com.user.localagent.imports.ConversationCleanupResult(deleted=1)
        }
        result
    }

    private suspend fun checkResource(kind:String,id:String){
        val stored=chatDao.sourceScope(kind,id)?.scopeKey?:"owner"
        check(stored==scopeKey){"다른 대화방·프로젝트 범위의 자료입니다."}
        verifyScopeLive()
    }
    private fun nextResourceRevision(previous:Long?):Long {
        check(previous!=Long.MAX_VALUE){"자료 버전 한도에 도달해 변경하지 않았습니다."}
        return maxOf(System.currentTimeMillis(),(previous?:0)+1)
    }
    private suspend fun verifyScopeLive(){access?.takeIf {it.threadId.isNotBlank()}?.let {scope->
        val thread=chat.thread(scope.threadId)?:error("삭제된 대화방에는 자료를 저장하지 않습니다.")
        check(thread.projectId==scope.projectId&&thread.revision==scope.threadRevision){"대화방·프로젝트가 변경되었습니다. 요청을 다시 시작해 주세요."}
    }}
    private suspend fun memoryAccessEnabled():Boolean {
        access?.takeIf{it.threadId.isNotBlank()}?.let{scope->
            check(chat.accessScope(scope.threadId)==scope){"기억 사용 범위가 변경되었습니다. 요청을 다시 시작해 주세요."}
        }
        return access?.memoryEnabled!=false
    }
    private suspend fun bindResource(kind:String,id:String,sourceMessageId:String?=null){
        val resolved=sourceMessageId?.let {resolveKnowledgeScope(it)}?:scopeKey
        if(resolved!="owner")chatDao.sourceScope(SourceScopeRecord(kind,id,resolved,access?.threadId))
    }
    private suspend fun resolveKnowledgeScope(sourceId:String):String {
        val message=dao.messageById(sourceId)
        if(message!=null){
            val thread=chat.thread(message.threadId)?:error("삭제된 대화에서 기억을 생성하지 않습니다.")
            if(message.originMessageId!=null)error("복제한 대화에서는 같은 기억을 다시 학습하지 않습니다.")
            val captured=chatDao.sourceScope("message",sourceId)?.scopeKey
            // A local source cannot become owner knowledge because learning was later enabled.
            if(captured!=null&&captured!="owner")return captured
            // Disabling owner learning also fences pending sources from older requests.
            // Previously approved rows keep their binding; only this new derivation is routed.
            return chat.learningScopeForNewSource(thread.id)
        }
        chatDao.sourceScope("note",sourceId)?.let {return it.scopeKey}
        chatDao.sourceScope("memory",sourceId)?.let {return it.scopeKey}
        return scopeKey
    }
    private suspend fun knowledgeAccessible(id:String):Boolean {
        val key=chatDao.knowledgeScope(id)?.scopeKey?:"owner"
        return key==scopeKey||key=="owner"&&(access?.useOwnerMemory?:true)
    }
    private fun displayKnowledge(record:KnowledgeClaim):KnowledgeClaim {
        fun original(subject:String)=if(subject.startsWith("project:")||subject.startsWith("thread:"))subject.substringAfter('|',subject)else subject
        return record.copy(subject=original(record.subject),objectSubject=record.objectSubject?.let(::original))
    }
    private suspend fun scopedKnowledge(query:String,key:String,includeOwner:Boolean,limit:Int):List<KnowledgeClaim> {
        require(query.length<=2000&&limit in 1..10&&'\u0000' !in query)
        val normalized=query.lowercase(java.util.Locale.ROOT)
        val aliases=mapOf("가족" to "family spouse children","아이" to "children","직업" to "occupation profession job","이름" to "name preferred_name","메일" to "email work_email","선호" to "preference response_preference")
        val expanded=normalized+" "+aliases.filterKeys {it in normalized}.values.joinToString(" ")
        val tokens=lexicalTokens(expanded);val pairs=characterPairs(normalized)
        val indexedTerms=graph.queryTerms(expanded)
        if(indexedTerms.isEmpty())return emptyList()
        val matches=chatDao.matchingKnowledge(indexedTerms,key,includeOwner)
        val scored=matches.mapNotNull {claim->
            val readable=displayKnowledge(claim)
            val text="${readable.subject} ${readable.predicate} ${readable.value} ${readable.tags.joinToString(" ")}"
            val overlap=tokens.intersect(lexicalTokens(text)).size
            val paired=pairs.intersect(characterPairs(text)).size
            if(overlap==0&&paired<2)null else claim to (overlap*5+paired)
        }.sortedWith(compareByDescending<Pair<KnowledgeClaim,Int>> {it.second}
            .thenByDescending {it.first.status=="confirmed"}.thenByDescending {it.first.updatedAt}).map {it.first}
        val subjects=scored.take(3).mapNotNull {it.objectSubject}.distinct()
        val related=if(subjects.isEmpty())emptyList()else chatDao.relatedKnowledge(subjects,key,includeOwner,limit)
        return (scored+related).distinctBy {it.id}.take(limit).map(::displayKnowledge)
    }
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
            .addMigrations(KnowledgeMigrations.MIGRATION_1_2,ChatMigrations.MIGRATION_2_3)
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





